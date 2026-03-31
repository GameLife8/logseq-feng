(ns frontend.components.agenda
  "日程页面 – 任务日历 + 看板，原生集成 Logseq DB。
   设计灵感来自 logseq-plugin-agenda (haydenull)，按 DB 版规范重写。"
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [frontend.components.agenda-data :as agenda-data]
            [frontend.date :as date]
            [frontend.db :as db]
            [frontend.db.model :as db-model]
            [frontend.handler.db-based.property :as db-property-handler]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.page :as page-handler]
            [frontend.handler.route :as route-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [promesa.core :as p]
            [rum.core :as rum]))

;; ── 常量 ──────────────────────────────────────────────────────────────────────

(def ^:private weekday-names ["一" "二" "三" "四" "五" "六" "日"])
(def ^:private month-names   ["1月" "2月" "3月" "4月" "5月" "6月"
                               "7月" "8月" "9月" "10月" "11月" "12月"])

(def ^:private status-label
  {:logseq.property/status.todo      "待办"
   :logseq.property/status.doing     "进行中"
   :logseq.property/status.done      "完成"
   :logseq.property/status.canceled  "已取消"
   :logseq.property/status.backlog   "待办池"
   :logseq.property/status.in-review "审核中"
   :logseq.property/status.waiting   "等待中"})

(def ^:private status-color
  {:logseq.property/status.todo      "#6366f1"
   :logseq.property/status.doing     "#f59e0b"
   :logseq.property/status.done      "#22c55e"
   :logseq.property/status.canceled  "#9ca3af"
   :logseq.property/status.backlog   "#94a3b8"
   :logseq.property/status.in-review "#a855f7"
   :logseq.property/status.waiting   "#64748b"})

;; Closed-value API names used by batch-set-property-closed-value!
(def ^:private status-api-name
  {:logseq.property/status.backlog   "Backlog"
   :logseq.property/status.todo      "Todo"
   :logseq.property/status.doing     "Doing"
   :logseq.property/status.in-review "In Review"
   :logseq.property/status.done      "Done"
   :logseq.property/status.canceled  "Canceled"})

;; All editable statuses in display order
(def ^:private all-statuses
  [[:logseq.property/status.backlog   "待办池"]
   [:logseq.property/status.todo      "待办"]
   [:logseq.property/status.doing     "进行中"]
   [:logseq.property/status.in-review "审核中"]
   [:logseq.property/status.done      "完成"]
   [:logseq.property/status.canceled  "已取消"]])

;; Module-level atom so task-card can trigger a reload without threading
(defonce ^:private *global-reload! (atom nil))

;; ── 日期工具 ──────────────────────────────────────────────────────────────────

(defn- today [] (js/Date.))

(defn- date->ymd
  "返回 [year month(0-indexed) day] 三元组"
  [^js d]
  [(.getFullYear d) (.getMonth d) (.getDate d)])

(defn- ymd->date
  [[year month day]]
  (js/Date. year month day))

(defn- ymd->day-ms
  "一天开始（本地零点）的毫秒时间戳"
  [[year month day]]
  (.getTime (js/Date. year month day)))

(defn- ms->ymd
  "UTC 毫秒 → 本地 [year month day]"
  [ms]
  (date->ymd (js/Date. ms)))

(defn- same-day?
  "判断两个 [y m d] 三元组是否同一天"
  [ymd1 ymd2]
  (= ymd1 ymd2))

(defn- today-ymd [] (date->ymd (today)))

(defn- days-in-month [year month]
  (.getDate (js/Date. year (inc month) 0)))

(defn- first-weekday-of-month
  "该月 1 日是周几（0=周日…6=周六），转换为以周一为起始（0=周一）"
  [year month]
  (let [raw (.getDay (js/Date. year month 1))]
    ;; Sunday=0 → 6, Monday=1 → 0, …
    (mod (dec raw) 7)))

(defn- month-grid-days
  "生成月历格子：包含前一月补位、本月、后一月补位，共 6×7=42 格。
   每格：{:ymd [y m d] :current? bool}"
  [year month]
  (let [first-wd  (first-weekday-of-month year month)
        total     (days-in-month year month)
        ;; prev month fill
        [py pm]   (if (zero? month) [(dec year) 11] [year (dec month)])
        prev-days (days-in-month py pm)
        prev-fill (mapv (fn [d] {:ymd [py pm d] :current? false})
                        (range (- prev-days first-wd -1) (inc prev-days)))
        ;; current month
        cur-days  (mapv (fn [d] {:ymd [year month d] :current? true})
                        (range 1 (inc total)))
        ;; next month fill
        [ny nm]   (if (= 11 month) [(inc year) 0] [year (inc month)])
        need      (- 42 (count prev-fill) (count cur-days))
        next-fill (mapv (fn [d] {:ymd [ny nm d] :current? false})
                        (range 1 (inc need)))]
    (into [] (concat prev-fill cur-days next-fill))))

(defn- week-days
  "以包含 date-ymd 的那一周（周一开始）的所有 7 天"
  [ymd]
  (let [d     (ymd->date ymd)
        raw-wd (.getDay d)
        ;; Monday=0
        wd    (mod (dec raw-wd) 7)
        mon   (js/Date. (.getTime d) )
        _     (.setDate mon (- (.getDate d) wd))]
    (mapv (fn [i]
            (let [di (js/Date. (.getTime mon))]
              (.setDate di (+ (.getDate mon) i))
              (date->ymd di)))
          (range 7))))

;; ── 数据查询 ──────────────────────────────────────────────────────────────────

;; 不使用 [*] 通配符——DataScript 在将 pull-spec 作为 :in 变量传入时
;; 不会展开 * 通配符，导致 :logseq.property/scheduled 等标量属性返回 nil。
;; 改为显式列出所有需要的属性。
;; Share task/date helpers with the Today page so both views normalize dates the same way.
(def ^:private <load-tasks agenda-data/<load-tasks)
(def ^:private task-status-ident agenda-data/task-status-ident)
(def ^:private task-active? agenda-data/task-active?)
(def ^:private prop->ms agenda-data/prop->ms)
(def ^:private task-date-info agenda-data/task-date-info)
(def ^:private task-date-ms agenda-data/task-date-ms)

(defn- group-tasks-by-day
  "tasks → {[y m d] [tasks...]}"
  [tasks]
  (reduce (fn [acc task]
            (if-let [ms (task-date-ms task)]
              (let [ymd (ms->ymd ms)]
                (update acc ymd (fnil conj []) task))
              acc))
          {}
          tasks))

;; ── 项目视图数据 ──────────────────────────────────────────────────────────────

(defn- task-project-tags
  "返回任务上所有以'项目'结尾的用户标签，
   例如 #容器化项目 → [{:block/title '容器化项目' ...}]"
  [task]
  (->> (:block/tags task)
       (filter #(string/ends-with? (or (:block/title %) "") "项目"))
       seq))

(defn- group-tasks-by-project
  "tasks → {project-title [task...]}，无项目标签的归 nil 键"
  [tasks]
  (reduce (fn [acc task]
            (if-let [ptags (task-project-tags task)]
              (reduce (fn [a tag]
                        (update a (:block/title tag) (fnil conj []) task))
                      acc ptags)
              (update acc nil (fnil conj []) task)))
          {} tasks))

;; ── 状态 & 导航辅助 ───────────────────────────────────────────────────────────

(defn- update-task-status!
  "将任务状态写入 DB，然后触发全局刷新。"
  [block-uuid new-ident]
  (when-let [api-name (get status-api-name new-ident)]
    (db-property-handler/batch-set-property-closed-value!
     [block-uuid] :logseq.property/status api-name)
    (when-let [reload! @*global-reload!]
      (js/setTimeout reload! 300))))

(defn- open-in-sidebar!
  "在右侧边栏打开 block。"
  [db-id]
  (when db-id
    (state/sidebar-add-block!
     (state/get-current-repo) db-id :block)))

;; ── 任务创建 ──────────────────────────────────────────────────────────────────

(defn- <create-task!
  "在指定日期的日记页新建任务块，设置状态、可选日期属性（精确到分钟）和项目标签。
   type              : :todo | :scheduled | :deadline
   date-ms           : UTC 毫秒（nil = 今天）
   selected-projects : 项目名字符串集合（nil = 无归属项目）"
  [title type date-ms selected-projects]
  (when-not (string/blank? title)
    (p/let [page-name (if date-ms
                        (date/js-date->journal-title (js/Date. date-ms))
                        (date/today))
            _         (when-not (db-model/get-journal-page page-name)
                        (page-handler/<create! page-name {:redirect? false}))
            target    (db-model/get-journal-page page-name)]
      (when target
        (p/let [block (editor-handler/api-insert-new-block!
                       title {:page (:block/uuid target) :edit-block? false})]
          (when block
            (let [uuid (:block/uuid block)]
              ;; 1. 设置状态（自动加 :logseq.class/Task 标签）
              (db-property-handler/batch-set-property-closed-value!
               [uuid] :logseq.property/status "Todo")
              ;; 2. 设置日期属性
              (when (and date-ms (#{:scheduled :deadline} type))
                (db-property-handler/batch-set-property!
                 [uuid]
                 (if (= type :scheduled)
                   :logseq.property/scheduled
                   :logseq.property/deadline)
                 date-ms {}))
              ;; 3. 追加项目标签（cardinality-many，逐个 add，不覆盖 Task 类标签）
              (doseq [pname selected-projects]
                (when-let [entity (db/get-page pname)]
                  (db-property-handler/batch-set-property!
                   [uuid] :block/tags (:db/id entity) {:entity-id? true})))
              block)))))))

;; ── 小型日历选择器 ─────────────────────────────────────────────────────────────

(rum/defcs mini-date-picker
  "内联迷你日历，全中文展示。
   on-select: (fn [utc-ms]) 选中日期的 UTC 毫秒时间戳
   selected-ms: 当前已选的 UTC 毫秒（nil = 无选中）"
  < rum/reactive
  (rum/local nil ::dp-month)
  [state on-select selected-ms]
  (let [*month  (::dp-month state)
        now     (js/Date.)
        init-ym [(if selected-ms
                   (.getFullYear (js/Date. selected-ms))
                   (.getFullYear now))
                 (if selected-ms
                   (.getMonth (js/Date. selected-ms))
                   (.getMonth now))]
        _       (when (nil? @*month) (reset! *month init-ym))
        [cy cm] (or @*month init-ym)
        today   (today-ymd)
        sel-ymd (when selected-ms (ms->ymd selected-ms))
        grid    (month-grid-days cy cm)]
    [:div {:style {:userSelect "none" :minWidth "220px"}}
     ;; 月份导航
     [:div {:style {:display "flex" :alignItems "center" :justifyContent "space-between"
                    :marginBottom "8px"}}
      [:button {:on-click (fn [e]
                            (.stopPropagation e)
                            (let [[y m] @*month
                                  [ny nm] (if (zero? m) [(dec y) 11] [y (dec m)])]
                              (reset! *month [ny nm])))
                :style {:background "none" :border "none" :cursor "pointer"
                        :fontSize "18px" :padding "0 6px" :lineHeight "1.2"
                        :color "var(--lx-gray-11,#374151)"}} "‹"]
      [:span {:style {:fontSize "13px" :fontWeight "600"}}
       (str cy "年" (inc cm) "月")]
      [:button {:on-click (fn [e]
                            (.stopPropagation e)
                            (let [[y m] @*month
                                  [ny nm] (if (= 11 m) [(inc y) 0] [y (inc m)])]
                              (reset! *month [ny nm])))
                :style {:background "none" :border "none" :cursor "pointer"
                        :fontSize "18px" :padding "0 6px" :lineHeight "1.2"
                        :color "var(--lx-gray-11,#374151)"}} "›"]]
     ;; 星期标题
     [:div {:style {:display "grid" :gridTemplateColumns "repeat(7,1fr)"
                    :marginBottom "4px"}}
      (for [wd weekday-names]
        [:div {:key wd
               :style {:textAlign "center" :fontSize "11px" :fontWeight "600"
                       :opacity "0.4" :padding "3px 0"}}
         wd])]
     ;; 日格
     (for [week (partition 7 grid)
           :let [wk (str (-> week first :ymd))]]
       [:div {:key wk :style {:display "grid" :gridTemplateColumns "repeat(7,1fr)"}}
        (for [{:keys [ymd current?]} week
              :let [is-today    (same-day? ymd today)
                    is-sel      (= ymd sel-ymd)
                    [_ _ d]     ymd]]
          [:div {:key      (str ymd)
                 :on-click (fn [e]
                              (.stopPropagation e)
                              (when current?
                                ;; 保留已选时间（小时:分钟），只改日期部分
                                (let [base (ymd->date ymd)
                                      d    (if selected-ms
                                             (doto base
                                               (.setHours (.getHours (js/Date. selected-ms))
                                                          (.getMinutes (js/Date. selected-ms))
                                                          0 0))
                                             base)]
                                  (on-select (.getTime d)))))
                 :style {:textAlign "center" :fontSize "12px"
                         :padding "4px 2px" :borderRadius "4px"
                         :cursor (if current? "pointer" "default")
                         :fontWeight (if is-today "700" "400")
                         :opacity (if current? 1 0.25)
                         :background (cond is-sel   "var(--lx-accent-09,#4f46e5)"
                                          is-today "var(--lx-gray-04,#f1f5f9)"
                                          :else    "transparent")
                         :color (if is-sel "#fff" "inherit")}}
           d])])]))

;; ── 新建任务弹窗 ───────────────────────────────────────────────────────────────

(rum/defcs new-task-dialog
  "新建任务弹窗：支持 待办 / 计划 / 截止 三种形式，可选项目归属。
   on-close : 关闭回调
   projects : 可选项目名字符串列表（来自当前任务的标签统计）"
  < rum/reactive
  (rum/local ""        ::nt-title)
  (rum/local :todo     ::nt-type)
  (rum/local nil       ::nt-date-ms)
  (rum/local false     ::nt-saving?)
  (rum/local #{}       ::nt-projects)  ;; 已选项目名集合
  [state on-close projects]
  (let [*title    (::nt-title state)
        *type     (::nt-type state)
        *date-ms  (::nt-date-ms state)
        *saving?  (::nt-saving? state)
        *projects (::nt-projects state)
        sel-proj  (rum/react *projects)
        title    (rum/react *title)
        type     (rum/react *type)
        date-ms  (rum/react *date-ms)
        saving?  (rum/react *saving?)
        ;; 计划/截止必须选日期；待办可选（nil = 今天）
        can-save (and (not (string/blank? title))
                      (or (= type :todo) (some? date-ms)))
        ;; 当前时分（用于时间选择器回显）
        cur-h    (if date-ms (.getHours   (js/Date. date-ms)) 9)
        cur-m    (if date-ms (.getMinutes (js/Date. date-ms)) 0)
        ;; 更新 date-ms 的时分，保留日期
        set-time (fn [new-h new-m]
                   (let [base (if date-ms
                                (js/Date. date-ms)
                                (doto (js/Date.) (.setHours 0 0 0 0)))]
                     (.setHours base new-h new-m 0 0)
                     (reset! *date-ms (.getTime base))))
        do-save! (fn []
                   (when can-save
                     (reset! *saving? true)
                     (p/let [_ (<create-task! title type date-ms (seq sel-proj))]
                       (reset! *saving? false)
                       (on-close)
                       (js/setTimeout #(when-let [r! @*global-reload!] (r!)) 300))))
        btn-base {:padding "5px 14px" :borderRadius "6px" :fontSize "12px"
                  :cursor "pointer" :fontWeight "500"}
        sel-sty  {:padding "4px 6px" :borderRadius "6px" :fontSize "13px"
                  :border "1px solid var(--lx-gray-05,#e5e7eb)"
                  :background "var(--lx-gray-01,#fff)"
                  :cursor "pointer" :outline "none"}]
    [:div {:on-click #(.stopPropagation %)
           :style {:background "#fff"
                   :border "1px solid var(--lx-gray-05,#e5e7eb)"
                   :borderRadius "12px" :padding "16px"
                   :boxShadow "0 8px 32px rgba(0,0,0,0.15)"
                   :width "300px"}}
     [:div {:style {:fontSize "14px" :fontWeight "700" :marginBottom "12px"
                    :color "var(--lx-gray-12,#111)"}} "新建任务"]

     ;; 标题输入
     [:input {:value       title
              :placeholder "任务标题…"
              :auto-focus  true
              :on-change   #(reset! *title (.. % -target -value))
              :on-key-down (fn [e]
                             (when (= "Enter" (.-key e))
                               (.preventDefault e)
                               (do-save!)))
              :style {:width "100%" :fontSize "13px"
                      :padding "8px 10px" :borderRadius "7px"
                      :border "1px solid var(--lx-gray-05,#e5e7eb)"
                      :outline "none" :boxSizing "border-box"
                      :marginBottom "10px"
                      :fontFamily "inherit"}}]

     ;; 类型选择
     [:div {:style {:display "flex" :gap "4px" :marginBottom "12px"}}
      (for [[t lbl ico] [[:todo "待办" "☑"] [:scheduled "计划" "📅"] [:deadline "截止" "⏰"]]]
        [:button {:key      (name t)
                  :on-click #(reset! *type t)
                  :style {:flex "1" :padding "6px 4px" :borderRadius "6px"
                          :fontSize "12px" :cursor "pointer"
                          :border (if (= t type)
                                    "1.5px solid var(--lx-accent-07,#6366f1)"
                                    "1px solid var(--lx-gray-05,#e5e7eb)")
                          :background (if (= t type)
                                        "var(--lx-accent-03,#ede9fe)"
                                        "var(--lx-gray-02,#f9fafb)")
                          :color (if (= t type)
                                   "var(--lx-accent-11,#4338ca)"
                                   "var(--lx-gray-10,#6b7280)")
                          :fontWeight (if (= t type) "600" "400")}}
         (str ico " " lbl)])]

     ;; ── 项目归属（可多选）──────────────────────────────────────────────────────
     (when (seq projects)
       [:div {:style {:marginBottom "10px"}}
        [:div {:style {:fontSize "11px" :fontWeight "600" :opacity "0.5" :marginBottom "5px"}}
         "# 归属项目（可多选）"]
        [:div {:style {:display "flex" :flexWrap "wrap" :gap "4px"}}
         (for [p projects]
           [:button {:key      p
                     :on-click #(swap! *projects (fn [ps] (if (ps p) (disj ps p) (conj ps p))))
                     :style {:padding "3px 9px" :borderRadius "20px" :fontSize "12px"
                             :cursor "pointer"
                             :border (if (sel-proj p)
                                       "1.5px solid var(--lx-accent-07,#6366f1)"
                                       "1px solid var(--lx-gray-05,#e5e7eb)")
                             :background (if (sel-proj p)
                                           "var(--lx-accent-03,#ede9fe)"
                                           "transparent")
                             :color (if (sel-proj p)
                                      "var(--lx-accent-11,#4338ca)"
                                      "var(--lx-gray-10,#6b7280)")
                             :fontWeight (if (sel-proj p) "600" "400")}}
            (str "# " p)])]])

     ;; ── 日期 + 时间选择器 ──────────────────────────────────────────────────────
     [:div {:style {:marginBottom "12px" :padding "10px"
                    :background "var(--lx-gray-02,#f9fafb)"
                    :borderRadius "8px"
                    :border "1px solid var(--lx-gray-04,#e8eaed)"}}
      [:div {:style {:fontSize "11px" :fontWeight "600" :opacity "0.55" :marginBottom "8px"}}
       (case type
         :todo      "📌 选择归属日期（空 = 今天）"
         :scheduled "📅 选择计划日期（必填）"
                    "⏰ 选择截止日期（必填）")]
      ;; 日历
      (rum/with-key
        (mini-date-picker #(reset! *date-ms %) date-ms)
        (str "dp-" (name type)))
      ;; 时分（计划 / 截止）
      (when (#{:scheduled :deadline} type)
        [:div {:style {:display "flex" :alignItems "center" :gap "6px"
                       :marginTop "10px" :paddingTop "8px"
                       :borderTop "1px solid var(--lx-gray-04,#e8eaed)"}}
         [:span {:style {:fontSize "12px" :opacity "0.6" :whiteSpace "nowrap"}} "⏱ 时间："]
         [:select {:value     cur-h
                   :on-change (fn [e] (set-time (js/parseInt (.. e -target -value)) cur-m))
                   :style sel-sty}
          (for [h (range 24)]
            [:option {:key h :value h} (util/zero-pad h)])]
         [:span {:style {:fontWeight "700" :fontSize "14px"}} "："]
         [:select {:value     cur-m
                   :on-change (fn [e] (set-time cur-h (js/parseInt (.. e -target -value))))
                   :style sel-sty}
          (for [m (range 60)]
            [:option {:key m :value m} (util/zero-pad m)])]])
      ;; 确认提示
      (when date-ms
        (let [[y mo d] (ms->ymd date-ms)]
          [:div {:style {:fontSize "11px" :marginTop "6px" :textAlign "center"
                         :color "var(--lx-accent-09,#4f46e5)" :fontWeight "600"}}
           (str "✓ " y "年" (inc mo) "月" d "日"
                (when (#{:scheduled :deadline} type)
                  (str " " (util/zero-pad cur-h) ":" (util/zero-pad cur-m))))]))]

     ;; 操作按钮
     [:div {:style {:display "flex" :gap "6px" :justifyContent "flex-end"}}
      [:button {:on-click on-close
                :style (merge btn-base
                               {:border "1px solid var(--lx-gray-05,#e5e7eb)"
                                :background "var(--lx-gray-02,#f9fafb)"
                                :color "var(--lx-gray-10,#6b7280)"})}
       "取消"]
      [:button {:on-click do-save!
                :disabled (or saving? (not can-save))
                :style (merge btn-base
                               {:border "none"
                                :background (if can-save
                                              "var(--lx-accent-09,#4f46e5)"
                                              "var(--lx-gray-05,#e5e7eb)")
                                :color (if can-save "#fff" "var(--lx-gray-08,#9ca3af)")
                                :cursor (if can-save "pointer" "not-allowed")})}
       (if saving? "创建中…" "创建")]]]))

;; ── 新建任务按钮（工具栏用）────────────────────────────────────────────────────

(rum/defcs new-task-btn
  < rum/reactive
  (rum/local false ::ntb-open?)
  [state projects]
  (let [*open (::ntb-open? state)
        open? (rum/react *open)]
    [:div {:style {:position "relative" :flexShrink "0"}}
     (when open?
       [:div {:on-click #(reset! *open false)
              :style {:position "fixed" :inset "0" :zIndex "498"}}])
     [:button {:on-click (fn [e]
                           (.stopPropagation e)
                           (swap! *open not))
               :style {:display "flex" :alignItems "center" :gap "5px"
                       :padding "4px 11px" :borderRadius "6px"
                       :border (if open?
                                 "1.5px solid var(--lx-accent-07,#6366f1)"
                                 "1px solid var(--lx-accent-07,#6366f1)")
                       :background (if open?
                                     "var(--lx-accent-09,#4f46e5)"
                                     "var(--lx-accent-03,#ede9fe)")
                       :color (if open? "#fff" "var(--lx-accent-11,#4338ca)")
                       :fontSize "13px" :fontWeight "600" :cursor "pointer"
                       :flexShrink "0"}}
      [:span {:style {:fontSize "15px" :lineHeight "1"}} "＋"]
      "新建"]
     (when open?
       [:div {:style {:position "absolute" :top "calc(100% + 6px)" :right "0"
                      :zIndex "499"}}
        (new-task-dialog #(reset! *open false) projects)])]))

;; ── 范围下拉（自定义按钮样式）──────────────────────────────────────────────────

(rum/defcs scope-select
  "自定义项目范围下拉，样式与工具栏按钮一致。
   scope: :personal 或项目名字符串
   on-change: (fn [new-scope]) 回调"
  < rum/reactive
  (rum/local false ::ss-open?)
  [state scope projects on-change]
  (let [*open (::ss-open? state)
        open? (rum/react *open)
        label (cond (= scope :personal) "个人"
                    (= scope :all)      "全部"
                    :else               (str "# " scope))]
    [:div {:style {:position "relative" :flexShrink "0"}}
     (when open?
       [:div {:on-click #(reset! *open false)
              :style {:position "fixed" :inset "0" :zIndex "398"}}])
     [:button {:on-click (fn [e]
                           (.stopPropagation e)
                           (swap! *open not))
               :style {:display "flex" :alignItems "center" :gap "5px"
                       :padding "4px 10px" :borderRadius "6px"
                       :border "1px solid var(--lx-gray-06,#e5e7eb)"
                       :background (if open?
                                     "var(--lx-gray-04,#f1f5f9)"
                                     "var(--lx-gray-03,#f3f4f6)")
                       :color "var(--lx-gray-11,#374151)"
                       :fontSize "13px" :cursor "pointer"}}
      (ui/icon "folder" {:size 13 :class "opacity-50"})
      [:span label]
      [:span {:style {:fontSize "9px" :opacity "0.45" :marginLeft "2px"}} "▾"]]
     (when open?
       [:div {:on-click #(.stopPropagation %)
              :style {:position "absolute" :top "calc(100% + 4px)" :left "0"
                      :zIndex "399" :background "#fff"
                      :border "1px solid var(--lx-gray-05,#e5e7eb)"
                      :borderRadius "8px" :padding "4px"
                      :boxShadow "0 6px 20px rgba(0,0,0,0.12)"
                      :minWidth "160px"}}
        (for [[v lbl] (into [[:personal "个人"] [:all "全部"]]
                            (map (fn [p] [p (str "# " p)]) projects))]
          [:div {:key      (str v)
                 :on-click (fn [e]
                              (.stopPropagation e)
                              (reset! *open false)
                              (on-change v))
                 :style {:display "flex" :alignItems "center" :gap "6px"
                         :padding "6px 10px" :borderRadius "5px" :cursor "pointer"
                         :fontSize "13px"
                         :fontWeight (if (= v scope) "600" "400")
                         :background (if (= v scope)
                                       "var(--lx-gray-03,#f3f4f6)"
                                       "transparent")}}
           (when (= v scope)
             [:span {:style {:fontSize "10px" :color "var(--lx-accent-09,#4f46e5)"}} "✓"])
           lbl])])]))

;; ── 任务卡片 ──────────────────────────────────────────────────────────────────

(def ^:private date-source-label
  {:scheduled "📅" :deadline "⏰" :journal "📓" :created "🕐"})

(defn- ms->date-str
  "将 UTC 毫秒格式化为日期字符串；若非整点（小时:分钟 ≠ 0:0）则附加时间。"
  [ms]
  (let [d  (js/Date. ms)
        h  (.getHours d)
        mi (.getMinutes d)
        ds (date/js-date->journal-title d)]
    (if (= 0 h mi)
      ds
      (str ds " " (util/zero-pad h) ":" (util/zero-pad mi)))))

(rum/defcs task-card
  "任务卡片：
   - 点击状态圆点/标签 → 展开状态选择器
   - 点击标题/卡片体  → 展开导航弹窗（侧边栏 or 跳转）"
  < rum/reactive
  (rum/local false ::nav-open?)
  (rum/local false ::status-open?)
  [state {:keys [block/title block/uuid block/page db/id] :as task}]
  (let [*nav    (::nav-open? state)
        *status (::status-open? state)
        nav?    (rum/react *nav)
        status? (rum/react *status)
        ident   (task-status-ident task)
        color   (get status-color ident "#94a3b8")
        label   (get status-label ident "无状态")
        p-title (:block/title page)
        dinfo   (task-date-info task)
        src-tip (case (:source dinfo)
                  :scheduled "计划日期" :deadline "截止日期"
                  :journal "日记页日期" :created "创建时间" "")]
    [:div.agenda-task-card
     {:key   (str uuid)
      :style {:background   "var(--lx-gray-01, #fff)"
              :border       "1px solid var(--lx-gray-05, #e5e7eb)"
              :borderRadius "8px"
              :padding      "8px 10px"
              :marginBottom "6px"
              :position     "relative"}}

     ;; ── 状态行 ────────────────────────────────────────────────────────────
     [:div {:style {:display "flex" :alignItems "center" :gap "6px" :marginBottom "4px"}}

      ;; 状态 pill（点击展开状态菜单）
      [:div {:on-click (fn [e]
                         (.stopPropagation e)
                         (swap! *status not)
                         (reset! *nav false))
             :title "点击修改状态"
             :style {:display "flex" :alignItems "center" :gap "5px"
                     :cursor "pointer" :borderRadius "5px" :padding "1px 5px"
                     :background (if status? "var(--lx-gray-04,#f1f5f9)" "transparent")
                     :border (if status? "1px solid var(--lx-gray-05,#e5e7eb)" "1px solid transparent")}}
       [:span {:style {:width "7px" :height "7px" :borderRadius "50%"
                       :background color :flexShrink "0"}}]
       [:span {:style {:fontSize "12px" :color color}} label]
       [:span {:style {:fontSize "9px" :opacity "0.4"}} "▾"]]

      ;; 日期来源图标
      (when dinfo
        [:span {:title src-tip
                :style {:fontSize "11px" :opacity "0.4" :marginLeft "auto"
                        :cursor "default"}}
         (get date-source-label (:source dinfo))])]

     ;; ── 标题（点击展开导航弹窗）─────────────────────────────────────────
     [:div {:on-click (fn [e]
                        (.stopPropagation e)
                        (swap! *nav not)
                        (reset! *status false))
            :title "点击选择打开方式"
            :style {:fontSize "13px" :fontWeight "500"
                    :overflow "hidden" :textOverflow "ellipsis"
                    :whiteSpace "nowrap" :lineHeight "1.4"
                    :cursor "pointer"}}
      (or title "(无标题)")]

     ;; 日期行：scheduled/deadline 显示属性日期+时间，todo 显示日记页日期
     [:div {:style {:fontSize "11px" :opacity "0.5" :marginTop "2px"}}
      (case (:source dinfo)
        :scheduled (str "📅 " (ms->date-str (:ms dinfo)))
        :deadline  (str "⏰ " (ms->date-str (:ms dinfo)))
        (or p-title ""))]

     ;; ── 状态选择下拉 ──────────────────────────────────────────────────────
     (when status?
       [:div {:style {:position "absolute" :top "calc(100% + 2px)" :left "0"
                      :zIndex "300" :background "#fff"
                      :border "1px solid var(--lx-gray-05,#e5e7eb)"
                      :borderRadius "8px" :padding "4px"
                      :boxShadow "0 6px 20px rgba(0,0,0,0.12)"
                      :minWidth "140px"}}
        (for [[s-ident s-label] all-statuses
              :let [active? (= s-ident ident)
                    c       (get status-color s-ident "#94a3b8")]]
          [:div {:key      (str s-ident)
                 :on-click (fn [e]
                              (.stopPropagation e)
                              (reset! *status false)
                              (update-task-status! uuid s-ident))
                 :style {:display "flex" :alignItems "center" :gap "7px"
                         :padding "5px 8px" :borderRadius "5px" :cursor "pointer"
                         :fontWeight (if active? "600" "400")
                         :background (if active? "var(--lx-gray-03,#f3f4f6)" "transparent")}}
           [:span {:style {:width "7px" :height "7px" :borderRadius "50%"
                           :background c :flexShrink "0"}}]
           [:span {:style {:fontSize "12px"}} s-label]])])

     ;; ── 导航弹窗 ─────────────────────────────────────────────────────────
     (when nav?
       [:div {:style {:position "absolute" :top "calc(100% + 2px)" :left "0" :right "0"
                      :zIndex "300" :background "#fff"
                      :border "1px solid var(--lx-gray-05,#e5e7eb)"
                      :borderRadius "8px" :padding "8px"
                      :boxShadow "0 6px 20px rgba(0,0,0,0.12)"}}
        [:div {:style {:fontSize "11px" :opacity "0.45" :marginBottom "6px"
                       :overflow "hidden" :textOverflow "ellipsis" :whiteSpace "nowrap"}}
         (or title "(无标题)")]
        [:div {:style {:display "flex" :flexDirection "column" :gap "4px"}}
         [:button {:on-click (fn [e]
                               (.stopPropagation e)
                               (reset! *nav false)
                               (open-in-sidebar! id))
                   :style {:padding "6px 10px" :borderRadius "6px" :textAlign "left"
                           :border "1px solid var(--lx-gray-05,#e5e7eb)"
                           :background "var(--lx-gray-02,#f9fafb)"
                           :fontSize "12px" :cursor "pointer" :width "100%"}}
          "📌 在侧边栏打开"]
         [:button {:on-click (fn [e]
                               (.stopPropagation e)
                               (reset! *nav false)
                               (when uuid (route-handler/redirect-to-page! (str uuid))))
                   :style {:padding "6px 10px" :borderRadius "6px" :textAlign "left"
                           :border "1px solid var(--lx-gray-05,#e5e7eb)"
                           :background "var(--lx-gray-02,#f9fafb)"
                           :fontSize "12px" :cursor "pointer" :width "100%"}}
          "→ 跳转到页面"]
         [:button {:on-click (fn [e]
                               (.stopPropagation e)
                               (reset! *nav false))
                   :style {:padding "4px 10px" :borderRadius "6px" :textAlign "left"
                           :border "none" :background "transparent"
                           :fontSize "11px" :cursor "pointer" :opacity "0.4" :width "100%"}}
          "取消"]]])]))

;; ── 月历视图 ──────────────────────────────────────────────────────────────────

(rum/defc month-view
  [tasks-by-day cur-year cur-month selected-ymd on-select-day]
  (let [grid       (month-grid-days cur-year cur-month)
        today      (today-ymd)
        weeks      (partition 7 grid)]
    [:div.agenda-month-view {:style {:display "flex" :flexDirection "column" :gap "2px"}}
     ;; weekday headers
     [:div {:style {:display "grid" :gridTemplateColumns "repeat(7, 1fr)"
                    :gap "2px" :marginBottom "4px"}}
      (for [wd weekday-names]
        [:div {:key wd
               :style {:textAlign "center" :fontSize "12px" :fontWeight "600"
                       :opacity "0.5" :padding "4px 0"}}
         wd])]
     ;; day cells
     (for [week weeks
           :let [week-key (str (-> week first :ymd))]]
       [:div {:key   week-key
              :style {:display "grid" :gridTemplateColumns "repeat(7, 1fr)"
                      :gap "2px"}}
        (for [{:keys [ymd current?]} week
              :let [tasks     (get tasks-by-day ymd [])
                    is-today  (same-day? ymd today)
                    selected  (same-day? ymd selected-ymd)
                    n-tasks   (count tasks)]]
          [:div.agenda-day-cell
           {:key      (str ymd)
            :on-click #(on-select-day ymd)
            :style    {:minHeight  "72px"
                       :padding    "4px 6px"
                       :borderRadius "6px"
                       :background (cond selected "var(--lx-accent-04, #e0e7ff)"
                                         is-today "var(--lx-gray-04, #f1f5f9)"
                                         :else    "var(--lx-gray-02, #f9fafb)")
                       :border     (if selected
                                     "1px solid var(--lx-accent-07, #6366f1)"
                                     "1px solid transparent")
                       :cursor     "pointer"
                       :opacity    (if current? 1 0.45)
                       :transition "background 0.1s"}}
           [:div {:style {:display "flex" :justifyContent "space-between"
                          :alignItems "center" :marginBottom "3px"}}
            [:span {:style {:fontSize  "13px"
                            :fontWeight (if is-today "700" "500")
                            :color      (if is-today "var(--lx-accent-09, #4f46e5)" "inherit")}}
             (last ymd)]
            (when (pos? n-tasks)
              [:span {:style {:fontSize "11px" :background "#6366f1" :color "#fff"
                              :borderRadius "10px" :padding "0 5px" :lineHeight "16px"}}
               n-tasks])]
           ;; show up to 2 task dots
           (for [t (take 2 tasks)
                 :let [color (get status-color (task-status-ident t) "#94a3b8")]]
             [:div {:key   (str (:block/uuid t))
                    :style {:fontSize "11px" :overflow "hidden"
                            :textOverflow "ellipsis" :whiteSpace "nowrap"
                            :color color :lineHeight "1.4"}}
              (str "• " (or (:block/title t) "(无标题)"))])
           (when (> n-tasks 2)
             [:div {:style {:fontSize "10px" :opacity "0.5"}}
              (str "+" (- n-tasks 2) " 更多")])])])]))

;; ── 周视图 ────────────────────────────────────────────────────────────────────

(rum/defc week-view
  [tasks-by-day week-ymds]
  (let [today (today-ymd)]
    [:div.agenda-week-view {:style {:display "flex" :gap "8px" :height "100%"}}
     (for [ymd week-ymds
           :let [tasks    (get tasks-by-day ymd [])
                 is-today (same-day? ymd today)
                 [y m d]  ymd]]
       [:div {:key   (str ymd)
              :style {:flex "1" :minWidth "0"
                      :background "var(--lx-gray-02, #f9fafb)"
                      :borderRadius "8px"
                      :border (if is-today
                                "1.5px solid var(--lx-accent-07, #6366f1)"
                                "1px solid var(--lx-gray-05, #e5e7eb)")
                      :padding "8px 8px 6px"
                      :overflowY "auto"}}
        [:div {:style {:fontSize "12px" :fontWeight "700" :marginBottom "6px"
                       :color (if is-today "var(--lx-accent-09, #4f46e5)" "inherit")}}
         (str (nth weekday-names (mod (dec (.getDay (ymd->date ymd))) 7)) " "
              (inc m) "/" d)]
        (if (seq tasks)
          (for [t tasks]
            (rum/with-key (task-card t) (str (:block/uuid t))))
          [:div {:style {:fontSize "12px" :opacity "0.35" :textAlign "center"
                         :paddingTop "12px"}} "暂无任务"])])]))

;; ── 看板视图 ──────────────────────────────────────────────────────────────────

(rum/defc kanban-column
  [title color tasks]
  [:div {:style {:flex "1" :minWidth "180px"
                 :background "var(--lx-gray-02, #f9fafb)"
                 :borderRadius "10px"
                 :border     "1px solid var(--lx-gray-05, #e5e7eb)"
                 :padding    "10px 10px 8px"
                 :overflowY  "auto"}}
   [:div {:style {:display "flex" :alignItems "center" :gap "6px" :marginBottom "10px"}}
    [:span {:style {:width "10px" :height "10px" :borderRadius "3px"
                    :background color :flexShrink "0"}}]
    [:span {:style {:fontSize "13px" :fontWeight "600"}} title]
    [:span {:style {:fontSize "12px" :opacity "0.5" :marginLeft "auto"}}
     (count tasks)]]
   (if (seq tasks)
     (for [t tasks] (rum/with-key (task-card t) (str (:block/uuid t))))
     [:div {:style {:fontSize "12px" :opacity "0.35" :textAlign "center"
                    :paddingTop "12px"}} "暂无"])])

(defn- classify-kanban
  "看板分类逻辑（4 列，去掉今天列，deadline 优先）：
   :deadline  → 有 deadline 且未过期（deadline 优先于 scheduled）
   :overdue   → 有 deadline 但已过期
   :scheduled → 有 scheduled（无 deadline）
   :no-date   → 无显式日期
   done/canceled/nil-status 单独过滤，不走本函数"
  [task today-end]
  (let [s-ms (prop->ms (:logseq.property/scheduled task))
        d-ms (prop->ms (:logseq.property/deadline task))]
    (cond
      ;; deadline 优先：未过期 → 截止日列
      (and d-ms (> d-ms today-end)) :deadline

      ;; deadline 已过期 → 逾期列
      d-ms :overdue

      ;; 仅有 scheduled → 计划中列
      s-ms :scheduled

      ;; 无显式日期 → 逾期（兜底）
      :else :no-date)))

(rum/defc kanban-view
  [all-tasks]
  (let [today-ymd  (today-ymd)
        today-ms   (ymd->day-ms today-ymd)
        today-end  (+ today-ms 86399999)
        ;; 无状态（nil status）→ 单独放"已取消"列
        no-status  (filter #(nil? (task-status-ident %)) all-tasks)
        ;; 有明确状态的任务
        has-status (filter #(some? (task-status-ident %)) all-tasks)
        done       (filter #(= :logseq.property/status.done (task-status-ident %)) has-status)
        canceled   (filter #(= :logseq.property/status.canceled (task-status-ident %)) has-status)
        ;; 活跃任务（非 done/canceled，且有明确状态）
        active     (filter task-active? has-status)
        groups     (group-by #(classify-kanban % today-end) active)
        scheduled  (sort-by #(prop->ms (:logseq.property/scheduled %))
                            (get groups :scheduled []))
        deadline   (sort-by #(prop->ms (:logseq.property/deadline %))
                            (get groups :deadline []))
        overdue    (sort-by #(or (prop->ms (:logseq.property/deadline %))
                                 (:block/created-at %))
                            (concat (get groups :overdue []) (get groups :no-date [])))]
    [:div.agenda-kanban {:style {:display "flex" :gap "10px" :height "100%"
                                 :overflowX "auto"}}
     (kanban-column "计划中" "#6366f1" scheduled)
     (kanban-column "截止日" "#ef4444" deadline)
     (kanban-column "逾期"   "#dc2626" overdue)
     (kanban-column "已完成" "#10b981" (concat done canceled))
     ;; 已取消列专放无状态（nil status）任务，方便识别未分类项
     (kanban-column "已取消" "#94a3b8" (sort-by :block/created-at no-status))]))

;; ── 范围过滤辅助 ─────────────────────────────────────────────────────────────

(defn- all-projects
  "从所有任务中提取去重、排序后的项目标签名列表（以'项目'结尾的标签）"
  [tasks]
  (->> tasks
       (mapcat task-project-tags)
       (map :block/title)
       distinct
       sort))

(defn- filter-tasks-by-scope
  "scope = :all      → 全部任务（个人 + 所有项目）
   scope = :personal → 仅无项目标签的个人任务
   scope = string    → 只显示该项目标签的任务"
  [tasks scope]
  (cond
    (= scope :all)      tasks
    (= scope :personal) (filter #(nil? (task-project-tags %)) tasks)
    :else               (filter #(some (fn [tag] (= (:block/title tag) scope))
                                       (:block/tags %))
                                tasks)))

(defn- notification-task-list
  "构建通知弹窗的任务列表 DOM，最多显示 3 条，超出显示省略。"
  [header tasks]
  [:div
   [:div {:style {:font-weight "600" :margin-bottom "6px"}} header]
   (for [t (take 3 tasks)]
     [:div {:key   (str (:block/uuid t))
            :style {:font-size "12px" :opacity "0.8" :margin-bottom "2px"}}
      (str "· " (or (:block/title t) "(无标题)"))])
   (when (> (count tasks) 3)
     [:div {:style {:font-size "11px" :opacity "0.5" :margin-top "2px"}}
      (str "还有 " (- (count tasks) 3) " 个…")])])

(defn- notify-on-load!
  "加载任务后检查 Scheduled/Deadline 并发出通知。"
  [tasks]
  (let [today-ms  (ymd->day-ms (today-ymd))
        today-end (+ today-ms 86399999)
        now-ms    (.getTime (js/Date.))
        active    (filter task-active? tasks)
        starting  (filter #(let [scheduled-ms (prop->ms (:logseq.property/scheduled %))]
                              (and scheduled-ms
                                   (>= scheduled-ms today-ms)
                                   (<= scheduled-ms today-end)))
                          active)
        overdue   (filter #(let [deadline-ms (prop->ms (:logseq.property/deadline %))]
                              (and deadline-ms
                                   (< deadline-ms now-ms)))
                          active)]
    (when (seq starting)
      (notification/show!
       (notification-task-list
        (str "📅 今日有 " (count starting) " 个计划任务开始")
        starting)
       :info false nil nil nil))
    (when (seq overdue)
      (notification/show!
       (notification-task-list
        (str "⏰ " (count overdue) " 个任务已超过 Deadline")
        overdue)
       :warning false nil nil nil))))

;; ── 日任务面板（月视图侧边栏）────────────────────────────────────────────────

(rum/defc day-panel
  [selected-ymd tasks-by-day]
  (let [[y m d]  selected-ymd
        tasks    (get tasks-by-day selected-ymd [])]
    [:div.agenda-day-panel
     {:style {:width         "280px"
              :flexShrink    "0"
              :background    "var(--lx-gray-01, #fff)"
              :borderLeft    "1px solid var(--lx-gray-05, #e5e7eb)"
              :padding       "14px 14px 10px"
              :overflowY     "auto"}}
     [:div {:style {:fontSize "14px" :fontWeight "700" :marginBottom "12px"}}
      (str (inc m) "月" d "日")]
     (if (seq tasks)
       (for [t tasks] (rum/with-key (task-card t) (str (:block/uuid t))))
       [:div {:style {:fontSize "13px" :opacity "0.4" :textAlign "center"
                      :paddingTop "24px"}}
        "当日无任务"])]))

;; ── 顶部工具栏 ────────────────────────────────────────────────────────────────

(defn- toolbar-btn [label active? on-click]
  [:button
   {:on-click on-click
    :style {:padding       "4px 12px"
            :borderRadius  "6px"
            :border        "1px solid var(--lx-gray-06, #e5e7eb)"
            :background    (if active? "var(--lx-gray-12, #111)" "var(--lx-gray-03, #f3f4f6)")
            :color         (if active? "#fff" "var(--lx-gray-11, #374151)")
            :fontSize      "13px"
            :fontWeight    (if active? "600" "400")
            :cursor        "pointer"
            :transition    "all 0.12s"}}
   label])


(defn- nav-btn [label on-click]
  [:button
   {:on-click on-click
    :style {:padding      "3px 10px"
            :borderRadius "6px"
            :border       "1px solid var(--lx-gray-06, #e5e7eb)"
            :background   "var(--lx-gray-03, #f3f4f6)"
            :color        "var(--lx-gray-11, #374151)"
            :fontSize     "13px"
            :cursor       "pointer"}}
   label])

;; ── 主页面 ────────────────────────────────────────────────────────────────────

(rum/defcs agenda-page
  "全屏日程页面：月历 / 周历 / 看板，范围可切换为个人或各项目。"
  < rum/reactive
  (rum/local :month      ::view)          ;; :month | :week | :kanban
  (rum/local :all        ::scope)         ;; :personal | :all | "项目名"
  (rum/local nil         ::tasks)         ;; loaded tasks vec
  (rum/local nil         ::loading-err)
  (rum/local nil         ::selected-ymd)  ;; [y m d] for month day-panel
  (rum/local nil         ::cur-month)     ;; [year month(0-indexed)]
  (rum/local nil         ::cur-week-ymd)  ;; base ymd for week view
  {:did-mount
   (fn [state]
     (let [*tasks  (::tasks state)
           *err    (::loading-err state)
           *month  (::cur-month state)
           *week   (::cur-week-ymd state)
           *sel    (::selected-ymd state)
           td      (today-ymd)
           [y m _] td
           *timer   (volatile! nil)
           ;; silent? = true 时只刷新任务列表，不触发弹窗（用于轮询/监听器）
           do-load! (fn [& [silent?]]
                      (-> (p/let [tasks (some-> (state/get-current-repo) (<load-tasks))]
                            (reset! *err nil)
                            (reset! *tasks (or tasks []))
                            (when-not silent?
                              (notify-on-load! (or tasks []))))
                          (p/catch (fn [e]
                                     ;; DB Worker 尚未初始化（应用启动期间），忽略
                                     (reset! *err (.-message e))))))
           ;; 监听的属性：新建任务/状态变更/日期变更 都会触发刷新
           watch-attrs #{:logseq.property/status
                         :logseq.property/scheduled
                         :logseq.property/deadline}]
       (reset! *month [y m])
       (reset! *week  td)
       (reset! *sel   td)
       ;; 注册全局刷新函数，供 task-card 状态修改后调用
       ;; 全局刷新始终静默（状态修改、新建任务等触发），
      ;; 只有页面初次进入时的 do-load! 才触发通知弹窗
      (reset! *global-reload! #(do-load! true))
       ;; app-ready-promise 兑现后 DB Worker 及本地 conn 均已就绪：
       ;; 1. 注册 d/listen! 监听属性变更 → 触发静默刷新
       ;; 2. 执行初始加载
       (-> state/app-ready-promise
           (p/then
            (fn [_]
              (when-let [conn (db/get-db (state/get-current-repo) false)]
                (d/listen! conn ::agenda-auto-refresh
                           (fn [{:keys [tx-data]}]
                             (let [matched (filter #(contains? watch-attrs (:a %)) tx-data)]
                               (when (seq matched)
                                 (js/clearTimeout @*timer)
                                 (vreset! *timer (js/setTimeout #(do-load! true) 400)))))))
              (do-load!)))
           (p/catch (fn [_] nil))))
     state)
   :will-unmount
   (fn [state]
     (when-let [conn (db/get-db (state/get-current-repo) false)]
       (d/unlisten! conn ::agenda-auto-refresh))
     state)}
  [state]
  (let [*view      (::view state)
        *scope     (::scope state)
        *tasks     (::tasks state)
        *err       (::loading-err state)
        *sel       (::selected-ymd state)
        *cur-month (::cur-month state)
        *cur-week  (::cur-week-ymd state)
        view       (rum/react *view)
        scope      (rum/react *scope)
        all-tasks  (rum/react *tasks)
        err        (rum/react *err)
        sel-ymd    (rum/react *sel)
        [cy cm]    (or (rum/react *cur-month) [(first (today-ymd)) (second (today-ymd))])
        week-ymd   (or (rum/react *cur-week) (today-ymd))
        week-ymds  (week-days week-ymd)
        ;; 从全量任务提取项目列表，用于渲染 scope tabs
        projects   (all-projects (or all-tasks []))
        ;; 按当前 scope 过滤
        tasks      (filter-tasks-by-scope (or all-tasks []) scope)
        tasks-by-day (group-tasks-by-day tasks)]

    [:div.agenda-page
     {:style {:display       "flex"
              :flexDirection "column"
              :height        "100%"
              :background    "var(--lx-gray-01, #fff)"
              :overflow      "hidden"}}

     ;; ── 顶部工具栏 ────────────────────────────────────────────────────────────
     [:div.agenda-toolbar
      {:style {:display        "flex"
               :alignItems     "center"
               :gap            "8px"
               :padding        "10px 16px 10px"
               :borderBottom   "1px solid var(--lx-gray-05, #e5e7eb)"
               :flexShrink     "0"}}

      ;; title
      [:div {:style {:display "flex" :alignItems "center" :gap "6px" :marginRight "8px" :flexShrink "0"}}
       (ui/icon "calendar-time" {:size 18 :class "opacity-70"})
       [:h1 {:style {:fontSize "16px" :fontWeight "700" :margin 0}} "日程"]]

      ;; 月/周导航
      (when (= view :month)
        [:<>
         (nav-btn "‹" #(let [[y m] @*cur-month
                               [ny nm] (if (zero? m) [(dec y) 11] [y (dec m)])]
                          (reset! *cur-month [ny nm])))
         [:span {:style {:fontSize "14px" :fontWeight "600" :minWidth "72px"
                         :textAlign "center" :flexShrink "0"}}
          (str cy "年" (nth month-names cm))]
         (nav-btn "›" #(let [[y m] @*cur-month
                               [ny nm] (if (= 11 m) [(inc y) 0] [y (inc m)])]
                          (reset! *cur-month [ny nm])))])

      (when (= view :week)
        [:<>
         (nav-btn "‹" #(reset! *cur-week (first (week-days
                                                  (let [[y m d] @*cur-week]
                                                    [y m (- d 7)])))))
         [:span {:style {:fontSize "14px" :fontWeight "600" :minWidth "120px"
                         :textAlign "center" :flexShrink "0"}}
          (let [[y m d] (first week-ymds)
                [_ m2 d2] (last week-ymds)]
            (str (inc m) "/" d " – " (inc m2) "/" d2))]
         (nav-btn "›" #(reset! *cur-week (first (week-days
                                                  (let [[y m d] @*cur-week]
                                                    [y m (+ d 7)])))))])

      ;; 回到今天
      [:button {:on-click (fn []
                            (let [td (today-ymd)
                                  [y m] td]
                              (reset! *sel td)
                              (reset! *cur-month [y m])
                              (reset! *cur-week td)))
                :style {:padding "3px 10px" :borderRadius "6px"
                        :border "1px solid var(--lx-gray-06,#e5e7eb)"
                        :background "var(--lx-gray-03,#f3f4f6)"
                        :fontSize "13px" :cursor "pointer" :flexShrink "0"}}
       "今天"]

      ;; ── 范围下拉 + 新建任务 ───────────────────────────────────────────────
      [:div {:style {:display "flex" :alignItems "center" :gap "6px"
                     :borderLeft "1px solid var(--lx-gray-05,#e5e7eb)"
                     :paddingLeft "12px" :marginLeft "4px" :flex "1"}}
       (scope-select scope projects #(reset! *scope %))
       (new-task-btn projects)]

      ;; view switcher
      [:div {:style {:display "flex" :gap "4px" :flexShrink "0"}}
       (toolbar-btn "月"   (= view :month)  #(reset! *view :month))
       (toolbar-btn "周"   (= view :week)   #(reset! *view :week))
       (toolbar-btn "看板" (= view :kanban) #(reset! *view :kanban))]

      ;; 刷新按钮
      [:button {:on-click (fn []
                            (reset! *tasks nil)
                            (when-let [reload! @*global-reload!] (reload!)))
                :title "刷新任务"
                :style {:background "none" :border "none" :cursor "pointer"
                        :opacity "0.5" :padding "3px 6px" :flexShrink "0"}}
       (ui/icon "refresh" {:size 16})]]

     ;; ── 内容区域 ──────────────────────────────────────────────────────────────
     (cond
       err
       [:div.flex.items-center.justify-center.h-full
        [:div.text-sm.opacity-60 (str "加载失败：" err)]]

       (nil? tasks)
       [:div.flex.items-center.justify-center.h-full
        [:div.text-sm.opacity-60 "加载任务中…"]]

       :else
       [:div {:style {:flex "1" :display "flex" :overflow "hidden"}}

        ;; main content
        [:div {:style {:flex "1" :padding "12px 16px" :overflowY "auto"}}
         (case view
           :month  (month-view tasks-by-day cy cm sel-ymd
                               (fn [ymd] (reset! *sel ymd)))
           :week   (week-view tasks-by-day week-ymds)
           :kanban (kanban-view tasks))]

        ;; 月视图右侧日任务面板
        (when (= view :month)
          (day-panel sel-ymd tasks-by-day))])]))
