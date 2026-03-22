(ns frontend.components.agenda
  "日程页面 – 任务日历 + 看板，原生集成 Logseq DB。
   设计灵感来自 logseq-plugin-agenda (haydenull)，按 DB 版规范重写。"
  (:require [clojure.string :as string]
            [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.handler.db-based.property :as db-property-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.route :as route-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [logseq.shui.ui :as shui]
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

(defn- start-of-week-ms [ymd]
  (ymd->day-ms (first (week-days ymd))))

(defn- end-of-week-ms [ymd]
  (+ (ymd->day-ms (last (week-days ymd))) 86399999))

;; ── 数据查询 ──────────────────────────────────────────────────────────────────

(def ^:private task-pull-spec
  '[* {:logseq.property/status [:db/ident :block/title]
       :block/tags              [:db/id :block/title]
       :block/page              [:db/id :block/title :block/uuid :block/journal-day]}])

(defn- <load-tasks
  "从 DB Worker 加载所有带状态的块，返回 promise<seq>"
  [repo]
  (db-async/<q repo {:transact-db? true}
               '[:find [(pull ?block ?pull-spec) ...]
                 :in $ ?pull-spec
                 :where
                 [?block :logseq.property/status _]]
               task-pull-spec))

(defn- task-status-ident [task]
  (get-in task [:logseq.property/status :db/ident]))

(defn- task-active?
  "非已完成、非已取消"
  [task]
  (let [ident (task-status-ident task)]
    (not (contains? #{:logseq.property/status.done
                      :logseq.property/status.canceled} ident))))

(defn- journal-day->ms
  "将 YYYYMMDD 整数（如 20240322）转换为当天零点的本地毫秒时间戳"
  [jd]
  (let [s     (str jd)
        year  (js/parseInt (.substring s 0 4))
        month (dec (js/parseInt (.substring s 4 6)))
        day   (js/parseInt (.substring s 6 8))]
    (.getTime (js/Date. year month day))))

(defn- task-date-info
  "返回 {:ms 毫秒时间戳 :source :scheduled|:deadline|:journal|:created}。
   优先级：scheduled > deadline > journal-day > created-at。
   所有任务都有 created-at，因此始终返回非 nil。"
  [task]
  (cond
    (:logseq.property/scheduled task)
    {:ms (:logseq.property/scheduled task) :source :scheduled}

    (:logseq.property/deadline task)
    {:ms (:logseq.property/deadline task) :source :deadline}

    (get-in task [:block/page :block/journal-day])
    {:ms (journal-day->ms (get-in task [:block/page :block/journal-day]))
     :source :journal}

    :else
    {:ms (or (:block/created-at task) 0) :source :created}))

(defn- task-date-ms
  "返回任务的有效日期毫秒，无日期返回 nil"
  [task]
  (:ms (task-date-info task)))

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

(defn- classify-task
  "将任务分类到 :overdue / :today / :this-week / :later / :no-date"
  [task today-ymd week-end-ms]
  (if-let [ms (task-date-ms task)]
    (let [task-ymd    (ms->ymd ms)
          today-ms    (ymd->day-ms today-ymd)
          task-day-ms (ymd->day-ms task-ymd)]
      (cond
        (same-day? task-ymd today-ymd) :today
        (< task-day-ms today-ms)       :overdue
        (<= task-day-ms week-end-ms)   :this-week
        :else                          :later))
    :no-date))

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

;; ── 任务卡片 ──────────────────────────────────────────────────────────────────

(def ^:private date-source-label
  {:scheduled "📅" :deadline "⏰" :journal "📓" :created "🕐"})

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
        label   (get status-label ident "未知")
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

     (when p-title
       [:div {:style {:fontSize "11px" :opacity "0.5" :marginTop "2px"}} p-title])

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
            (task-card t))
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
     (for [t tasks] (task-card t))
     [:div {:style {:fontSize "12px" :opacity "0.35" :textAlign "center"
                    :paddingTop "12px"}} "暂无"])])

(defn- classify-kanban
  "看板分类逻辑（5 列）：
   :today     → scheduled=今天 或 deadline=今天 或 (无显式日期 且 created=今天)
   :scheduled → 有 scheduled（已开始或未来，持续显示直到完成）
   :deadline  → 有 deadline 且 deadline > 今天结束（未到期）
   :overdue   → deadline 已过期 或 (无显式日期 且 created < 今天)
   done 单独过滤，不走本函数"
  [task today-ms today-end]
  (let [s (:logseq.property/scheduled task)
        d (:logseq.property/deadline task)
        c (:block/created-at task)]
    (cond
      ;; 今天：显式日期=今天，或无显式日期但今天创建
      (or (and s (>= s today-ms) (<= s today-end))
          (and d (>= d today-ms) (<= d today-end))
          (and (nil? s) (nil? d) c (>= c today-ms) (<= c today-end)))
      :today

      ;; 有 scheduled：不论过去还是未来，持续在"计划中"
      s :scheduled

      ;; 有 deadline 且未过期
      (and d (> d today-end)) :deadline-future

      ;; 逾期：deadline 已过 或 无显式日期且创建时间比今天早
      :else :overdue)))

(rum/defc kanban-view
  [all-tasks]
  (let [today-ymd  (today-ymd)
        today-ms   (ymd->day-ms today-ymd)
        today-end  (+ today-ms 86399999)
        active     (filter task-active? all-tasks)
        done       (filter #(contains? #{:logseq.property/status.done
                                         :logseq.property/status.canceled}
                                       (task-status-ident %))
                           all-tasks)
        groups     (group-by #(classify-kanban % today-ms today-end) active)
        today-t    (sort-by #(or (:logseq.property/scheduled %)
                                 (:logseq.property/deadline %)
                                 (:block/created-at %))
                            (get groups :today []))
        scheduled  (sort-by :logseq.property/scheduled (get groups :scheduled []))
        deadline   (sort-by :logseq.property/deadline  (get groups :deadline-future []))
        overdue    (sort-by #(or (:logseq.property/deadline %)
                                 (:block/created-at %))
                            (get groups :overdue []))]
    [:div.agenda-kanban {:style {:display "flex" :gap "10px" :height "100%"
                                 :overflowX "auto"}}
     (kanban-column "今天"   "#f59e0b" today-t)
     (kanban-column "计划中" "#6366f1" scheduled)
     (kanban-column "截止日" "#ef4444" deadline)
     (kanban-column "逾期"   "#dc2626" overdue)
     (kanban-column "已完成" "#10b981" done)]))

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
  "scope = :personal  → 全部任务（所有项目汇总）
   scope = string     → 只显示该项目标签的任务"
  [tasks scope]
  (if (= scope :personal)
    tasks
    (filter #(some (fn [tag] (= (:block/title tag) scope))
                   (:block/tags %))
            tasks)))

(defn- notify-on-load!
  "加载任务后检查 Scheduled/Deadline 并发出通知。
   - Scheduled 今日开始：提示用户今天有计划任务
   - Deadline 已逾期（deadline < 当前时刻）：警告"
  [tasks]
  (let [today-ms  (ymd->day-ms (today-ymd))
        today-end (+ today-ms 86399999)
        now-ms    (.getTime (js/Date.))
        active    (filter task-active? tasks)
        ;; 今天开始的 scheduled 任务
        starting  (filter #(let [s (:logseq.property/scheduled %)]
                              (and s (>= s today-ms) (<= s today-end)))
                          active)
        ;; deadline 已逾期（deadline < 现在）
        overdue   (filter #(let [d (:logseq.property/deadline %)]
                              (and d (< d now-ms)))
                          active)]
    (when (seq starting)
      (notification/show!
       [:div
        [:div {:style {:font-weight "600" :margin-bottom "6px"}}
         (str "📅 今日有 " (count starting) " 个计划任务开始")]
        (for [t (take 3 starting)]
          [:div {:key   (str (:block/uuid t))
                 :style {:font-size "12px" :opacity "0.8" :margin-bottom "2px"}}
           (str "· " (or (:block/title t) "(无标题)"))])
        (when (> (count starting) 3)
          [:div {:style {:font-size "11px" :opacity "0.5" :margin-top "2px"}}
           (str "还有 " (- (count starting) 3) " 个…")])]
       :info false nil nil nil))
    (when (seq overdue)
      (notification/show!
       [:div
        [:div {:style {:font-weight "600" :margin-bottom "6px"}}
         (str "⏰ " (count overdue) " 个任务已超过 Deadline")]
        (for [t (take 3 overdue)]
          [:div {:key   (str (:block/uuid t))
                 :style {:font-size "12px" :opacity "0.8" :margin-bottom "2px"}}
           (str "· " (or (:block/title t) "(无标题)"))])
        (when (> (count overdue) 3)
          [:div {:style {:font-size "11px" :opacity "0.5" :margin-top "2px"}}
           (str "还有 " (- (count overdue) 3) " 个…")])]
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
       (for [t tasks] (task-card t))
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
  (rum/local :personal   ::scope)         ;; :personal | "项目名"
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
           do-load! (fn []
                      (p/let [tasks (some-> (state/get-current-repo) (<load-tasks))]
                        (reset! *tasks (or tasks []))
                        (notify-on-load! (or tasks []))))]
       (reset! *month [y m])
       (reset! *week  td)
       (reset! *sel   td)
       ;; 注册全局刷新函数，供 task-card 状态修改后调用
       (reset! *global-reload! do-load!)
       (do-load!)
       (p/catch (p/let [_ (p/delay 0)] nil)
                (fn [err]
                  (reset! *err (str err)))))
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
        week-days  (week-days week-ymd)
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
          (let [[y m d] (first week-days)
                [_ m2 d2] (last week-days)]
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

      ;; ── 范围下拉（个人 / 各项目）──────────────────────────────────────────
      [:div {:style {:display "flex" :alignItems "center" :gap "6px"
                     :borderLeft "1px solid var(--lx-gray-05,#e5e7eb)"
                     :paddingLeft "12px" :marginLeft "4px" :flex "1"}}
       (ui/icon "folder" {:size 14 :class "opacity-40"})
       [:select
        {:value     (if (= scope :personal) "personal" (str scope))
         :on-change (fn [e]
                      (let [v (.. e -target -value)]
                        (reset! *scope (if (= v "personal") :personal v))))
         :style {:padding      "4px 8px"
                 :borderRadius "6px"
                 :border       "1px solid var(--lx-gray-05,#e5e7eb)"
                 :background   "var(--lx-gray-02,#f9fafb)"
                 :fontSize     "13px"
                 :cursor       "pointer"
                 :outline      "none"
                 :maxWidth     "200px"}}
        [:option {:value "personal"} "个人（全部）"]
        (for [p projects]
          [:option {:key p :value p} (str "# " p)])]]

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
           :week   (week-view tasks-by-day week-days)
           :kanban (kanban-view tasks))]

        ;; 月视图右侧日任务面板
        (when (= view :month)
          (day-panel sel-ymd tasks-by-day))])]))
