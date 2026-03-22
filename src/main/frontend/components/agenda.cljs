(ns frontend.components.agenda
  "日程页面 – 任务日历 + 看板，原生集成 Logseq DB。
   设计灵感来自 logseq-plugin-agenda (haydenull)，按 DB 版规范重写。"
  (:require [clojure.string :as string]
            [frontend.db :as db]
            [frontend.db.async :as db-async]
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
  (db-async/<q repo {:transact-db? false}
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
  "返回 {:ms 毫秒时间戳 :source :scheduled|:deadline|:journal}，
   优先级：scheduled > deadline > journal-day（所在日记页）。
   均无则返回 nil（真正未计划）。"
  [task]
  (cond
    (:logseq.property/scheduled task)
    {:ms (:logseq.property/scheduled task) :source :scheduled}

    (:logseq.property/deadline task)
    {:ms (:logseq.property/deadline task) :source :deadline}

    (get-in task [:block/page :block/journal-day])
    {:ms (journal-day->ms (get-in task [:block/page :block/journal-day]))
     :source :journal}

    :else nil))

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

;; ── 任务卡片 ──────────────────────────────────────────────────────────────────

(def ^:private date-source-label
  {:scheduled "📅" :deadline "⏰" :journal "📓"})

(rum/defc task-card
  [{:keys [block/title block/uuid block/page] :as task}]
  (let [ident   (task-status-ident task)
        color   (get status-color ident "#94a3b8")
        label   (get status-label ident "未知")
        p-title (:block/title page)
        dinfo   (task-date-info task)
        src-ico (when dinfo (get date-source-label (:source dinfo)))]
    [:div.agenda-task-card
     {:key   (str uuid)
      :style {:background    "var(--lx-gray-01, #fff)"
              :border        "1px solid var(--lx-gray-05, #e5e7eb)"
              :borderRadius  "8px"
              :padding       "8px 10px"
              :marginBottom  "6px"
              :cursor        "pointer"}
      :on-click (fn []
                  (when uuid
                    (route-handler/redirect-to-page! (str uuid))))}
     [:div {:style {:display "flex" :alignItems "center" :gap "6px" :marginBottom "3px"}}
      [:span {:style {:width  "8px" :height "8px" :borderRadius "50%"
                      :background color :flexShrink "0"}}]
      [:span {:style {:fontSize "12px" :color color :opacity "0.85"}} label]
      (when src-ico
        [:span {:style {:fontSize "11px" :opacity "0.45" :marginLeft "auto"
                        :title (case (:source dinfo)
                                 :scheduled "来自计划日期"
                                 :deadline  "来自截止日期"
                                 :journal   "来自日记页日期"
                                 "")}}
         src-ico])]
     [:div {:style {:fontSize "13px" :fontWeight "500"
                    :overflow "hidden" :textOverflow "ellipsis"
                    :whiteSpace "nowrap" :lineHeight "1.4"}}
      (or title "(无标题)")]
     (when p-title
       [:div {:style {:fontSize "11px" :opacity "0.5" :marginTop "2px"}} p-title])]))

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

(rum/defc kanban-view
  [all-tasks]
  (let [today-ymd  (today-ymd)
        active     (filter task-active? all-tasks)
        done       (filter #(contains? #{:logseq.property/status.done
                                         :logseq.property/status.canceled}
                                       (task-status-ident %))
                           all-tasks)
        week-end   (end-of-week-ms today-ymd)
        groups     (group-by #(classify-task % today-ymd week-end) active)
        overdue    (sort-by task-date-ms (get groups :overdue []))
        today-t    (sort-by task-date-ms (get groups :today []))
        this-week  (sort-by task-date-ms (get groups :this-week []))
        later      (sort-by task-date-ms (get groups :later []))
        no-date    (get groups :no-date [])]
    [:div.agenda-kanban {:style {:display "flex" :gap "10px" :height "100%"
                                 :overflowX "auto"}}
     (kanban-column "逾期"   "#ef4444" overdue)
     (kanban-column "今天"   "#f59e0b" today-t)
     (kanban-column "本周"   "#6366f1" this-week)
     (kanban-column "之后"   "#22c55e" later)
     (kanban-column "待安排" "#94a3b8" no-date)
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
  "scope = :personal  → 无项目标签的任务
   scope = string     → 有该项目标签的任务"
  [tasks scope]
  (if (= scope :personal)
    (filter #(nil? (task-project-tags %)) tasks)
    (filter #(some (fn [tag] (= (:block/title tag) scope))
                   (:block/tags %))
            tasks)))

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

(defn- scope-tab [label is-project? active? on-click]
  [:button
   {:on-click on-click
    :style {:padding      "4px 12px"
            :borderRadius "6px"
            :border       (if active?
                            "1.5px solid var(--lx-accent-07,#6366f1)"
                            "1px solid var(--lx-gray-05,#e5e7eb)")
            :background   (if active? "var(--lx-accent-03,#eef2ff)" "transparent")
            :color        (if active?
                            "var(--lx-accent-09,#4f46e5)"
                            "var(--lx-gray-11,#374151)")
            :fontSize     "13px"
            :fontWeight   (if active? "600" "400")
            :cursor       "pointer"
            :whiteSpace   "nowrap"
            :display      "flex" :alignItems "center" :gap "4px"}}
   (when is-project? [:span {:style {:opacity "0.6" :fontSize "11px"}} "#"])
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
           [y m _] td]
       (reset! *month [y m])
       (reset! *week  td)
       (reset! *sel   td)
       (p/let [tasks (some-> (state/get-current-repo)
                             (<load-tasks))]
         (reset! *tasks (or tasks [])))
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

      ;; ── 范围 tabs（个人 + 各项目）─────────────────────────────────────────
      [:div {:style {:display "flex" :gap "4px" :overflowX "auto"
                     :flex "1" :padding "0 4px"
                     :borderLeft "1px solid var(--lx-gray-05,#e5e7eb)"
                     :marginLeft "4px"}}
       (scope-tab "个人" false (= scope :personal) #(reset! *scope :personal))
       (for [p projects]
         [:span {:key p}
          (scope-tab p true (= scope p) #(reset! *scope p))])]

      ;; view switcher
      [:div {:style {:display "flex" :gap "4px" :flexShrink "0"}}
       (toolbar-btn "月"   (= view :month)  #(reset! *view :month))
       (toolbar-btn "周"   (= view :week)   #(reset! *view :week))
       (toolbar-btn "看板" (= view :kanban) #(reset! *view :kanban))]

      ;; 刷新按钮
      [:button {:on-click (fn []
                            (reset! *tasks nil)
                            (p/let [ts (some-> (state/get-current-repo) (<load-tasks))]
                              (reset! *tasks (or ts []))))
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
