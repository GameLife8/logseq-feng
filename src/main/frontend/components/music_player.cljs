(ns frontend.components.music-player
  "本地音乐播放器 — MusicBee 布局风格 + Logseq 主题配色。
   三栏布局：左栏（文件夹/艺术家）+ 中栏（曲目列表）+ 底部播放控制栏"
  (:require [clojure.string :as string]
            [frontend.handler.music-player-config :as mp-cfg]
            [frontend.util :as util]
            [promesa.core :as p]
            [rum.core :as rum]))

;; ── Electron IPC ────────────────────────────────────────────────────────────

(defn- mpv-invoke! [action & [args]]
  (js/console.log "[music-player] mpv-invoke!" action
                  "electron?" (util/electron?)
                  "window.apis?" (boolean (.-apis js/window))
                  "invoke?" (boolean (and (.-apis js/window) (.-invoke (.-apis js/window)))))
  (let [apis (.-apis js/window)]
    (when (and apis (.-invoke apis))
      (-> (.invoke apis "mpv-control"
                   (clj->js (merge {:action action} args)))
          (.then (fn [r] (js/console.log "[music-player] mpv reply" action r) r))
          (.catch (fn [e] (js/console.error "[music-player] mpv error" action e) (js/Promise.reject e)))))))

;; ── 全局状态（defonce 保证跨路由不丢失）────────────────────────────────────────

(defonce *player-state
  (atom {:status     :stopped   ; :stopped | :playing | :paused
         :current    nil        ; {:path "..." :name "..." :folder "..."}
         :playlist   []         ; 全部曲目
         :position   0
         :duration   0
         :volume     80
         :mpv-ready? false
         :error      nil}))

(defonce *poll-timer (atom nil))
(defonce *selected-folder (atom nil))   ; 左栏当前选中文件夹

;; ── 工具函数 ──────────────────────────────────────────────────────────────────

(defn- fmt-time [secs]
  (let [s (max 0 (int (or secs 0)))
        m (quot s 60) r (mod s 60)]
    (str m ":" (when (< r 10) "0") r)))

(defn- file-name [path]
  (last (string/split path #"[/\\]")))

(defn- parent-folder [path]
  (let [sep   (if (string/includes? path "\\") "\\" "/")
        parts (string/split path (re-pattern (str "\\" sep)))]
    (last (butlast parts))))

(defn- strip-ext [name]
  (if-let [i (string/last-index-of name ".")]
    (subs name 0 i)
    name))

;; 根据字符串生成 HSL 颜色（用于专辑封面占位符）
(defn- str->hue [s]
  (mod (reduce + 0 (map int s)) 360))

(defn- folder-color [folder]
  (let [h (str->hue (or folder ""))
        s 55 l 42]
    (str "hsl(" h "," s "%," l "%)")))

;; 按父文件夹分组，返回 [{:folder "..." :tracks [...]} ...]
(defn- group-by-folder [playlist]
  (->> playlist
       (group-by :folder)
       (sort-by first)
       (mapv (fn [[folder tracks]]
               {:folder folder :tracks (vec (sort-by :name tracks))}))))

;; ── mpv 控制 ─────────────────────────────────────────────────────────────────

(defn- start-poll! []
  (when-not @*poll-timer
    (reset! *poll-timer
            (js/setInterval
             (fn []
               (when-let [p (mpv-invoke! "status")]
                 (.then p (fn [s]
                            (when s
                              (let [s (js->clj s :keywordize-keys true)]
                                (swap! *player-state merge
                                       {:status   (cond (:playing? s) :playing
                                                        (:paused? s)  :paused
                                                        :else         :stopped)
                                        :position (:position s 0)
                                        :duration (:duration s 0)
                                        :volume   (:volume s (:volume @*player-state))})))))))
             1000))))

(defn- stop-poll! []
  (when-let [t @*poll-timer] (js/clearInterval t) (reset! *poll-timer nil)))

(defn- ensure-mpv! [cfg]
  (if (:mpv-ready? @*player-state)
    (p/resolved true)
    (p/let [reply (mpv-invoke! "start"
                               {:mpv-path (:mpv-path cfg)
                                :volume   (:volume cfg 80)})]
      (if (and reply (.-ok ^js reply))
        (do (swap! *player-state assoc :mpv-ready? true :error nil)
            (start-poll!)
            true)
        (do (swap! *player-state assoc
                   :error (str "启动 mpv 失败，请检查路径是否正确"))
            false)))))

(defn play-track! [track]
  (p/let [cfg (mp-cfg/<get-config)]
    (when (not (string/blank? (:mpv-path cfg)))
      (p/do! (ensure-mpv! cfg)
             (mpv-invoke! "play" {:path (:path track)})
             (swap! *player-state assoc
                    :current  track
                    :status   :playing
                    :position 0)))))

(defn play-pause! []
  (if (= :playing (:status @*player-state))
    (p/do! (mpv-invoke! "pause")
           (swap! *player-state assoc :status :paused))
    (p/do! (mpv-invoke! "resume")
           (swap! *player-state assoc :status :playing))))

(defn- adjacent-track [dir]
  (let [{:keys [playlist current]} @*player-state
        n (count playlist)]
    (when (and current (pos? n))
      (let [idx (first (keep-indexed #(when (= (:path %2) (:path current)) %1) playlist))]
        (when idx (get playlist (mod (+ idx dir) n)))))))

(defn next-track! [] (when-let [t (adjacent-track 1)] (play-track! t)))
(defn prev-track! []
  (if (> (:position @*player-state) 3)
    (mpv-invoke! "seek" {:pos 0})
    (when-let [t (adjacent-track -1)] (play-track! t))))

(defn set-volume! [v]
  (swap! *player-state assoc :volume v)
  (mpv-invoke! "set-volume" {:value v}))

(defn seek! [pos]
  (mpv-invoke! "seek" {:pos pos}))

(defn load-folder! [folder]
  (js/console.log "[music-player] load-folder! called, folder=" folder)
  (p/let [reply (mpv-invoke! "list-music" {:folder folder})]
    (js/console.log "[music-player] list-music reply:" reply)
    (when reply
      (let [files (js->clj reply :keywordize-keys true)
            _     (js/console.log "[music-player] files count:" (count files) "sample:" (clj->js (take 3 files)))
            tracks (mapv (fn [f]
                           {:path   (:path f)
                            :name   (strip-ext (file-name (:path f)))
                            :folder (parent-folder (:path f))})
                         files)]
        (js/console.log "[music-player] tracks count:" (count tracks))
        (swap! *player-state assoc :playlist tracks)
        tracks))))

;; ── UI ── 颜色变量（Logseq CSS 变量）────────────────────────────────────────────

(def ^:private $bg      "var(--ls-primary-background-color,#fff)")
(def ^:private $bg2     "var(--ls-secondary-background-color,#f3f4f6)")
(def ^:private $bg3     "var(--lx-gray-03,#e9eaec)")
(def ^:private $border  "var(--ls-border-color,#e5e7eb)")
(def ^:private $text    "var(--ls-primary-text-color,#1f2937)")
(def ^:private $text2   "var(--ls-secondary-text-color,#6b7280)")
(def ^:private $accent  "var(--rx-accent-09,#6366f1)")
(def ^:private $accent2 "var(--rx-accent-04,#c7d2fe)")

;; ── 底部播放控制栏 ─────────────────────────────────────────────────────────────

(rum/defc bottom-bar < rum/reactive []
  (let [{:keys [status current position duration volume]} (rum/react *player-state)
        playing? (= status :playing)
        pct      (if (> duration 0) (* 100 (/ position duration)) 0)]
    [:div.mp-bottom-bar
     {:style {:background $bg2
              :border-top (str "1px solid " $border)
              :display "grid"
              :grid-template-columns "1fr auto 1fr"
              :align-items "center"
              :padding "0 16px"
              :height "64px"
              :flex-shrink 0
              :gap "16px"
              :user-select "none"}}

     ;; 左：当前曲目信息
     [:div {:style {:display "flex" :flex-direction "column" :min-width 0}}
      [:span {:style {:font-size "13px" :font-weight "600"
                      :white-space "nowrap" :overflow "hidden"
                      :text-overflow "ellipsis" :color $text}}
       (if current (:name current) "未在播放")]
      (when current
        [:span {:style {:font-size "11px" :color $text2 :margin-top "1px"}}
         (:folder current)])]

     ;; 中：控制按钮 + 进度条
     [:div {:style {:display "flex" :flex-direction "column"
                    :align-items "center" :gap "4px" :width "340px"}}
      ;; 控制按钮
      [:div {:style {:display "flex" :align-items "center" :gap "4px"}}
       (for [[icon handler title size]
             [["⏮" prev-track! "上一曲" "14px"]
              [(if playing? "⏸" "▶") play-pause! (if playing? "暂停" "播放") "18px"]
              ["⏭" next-track! "下一曲" "14px"]]]
         [:button
          {:key title :on-click handler :title title
           :style {:background (if (= title (if playing? "暂停" "播放"))
                                 $accent "transparent")
                   :border "none"
                   :color (if (= title (if playing? "暂停" "播放")) "#fff" $text)
                   :width (if (= size "18px") "34px" "28px")
                   :height (if (= size "18px") "34px" "28px")
                   :border-radius "50%"
                   :cursor "pointer"
                   :font-size size
                   :display "flex" :align-items "center" :justify-content "center"
                   :transition "background 0.15s"}}
          icon])]
      ;; 进度条
      [:div {:style {:display "flex" :align-items "center" :gap "6px" :width "100%"}}
       [:span {:style {:font-size "10px" :color $text2 :min-width "32px" :text-align "right"}}
        (fmt-time position)]
       [:div {:style {:flex 1 :height "3px" :background $bg3 :border-radius "2px"
                      :cursor "pointer" :position "relative"}
              :on-click (fn [^js e]
                          (let [r (.getBoundingClientRect (.-currentTarget e))
                                pos (* (/ (- (.-clientX e) (.-left r)) (.-width r)) duration)]
                            (seek! pos)))}
        [:div {:style {:position "absolute" :left 0 :top 0 :height "100%"
                       :width (str pct "%") :background $accent
                       :border-radius "2px" :transition "width 0.8s linear"}}]]
       [:span {:style {:font-size "10px" :color $text2 :min-width "32px"}}
        (fmt-time duration)]]]

     ;; 右：音量
     [:div {:style {:display "flex" :align-items "center" :justify-content "flex-end" :gap "6px"}}
      [:span {:style {:font-size "12px" :opacity 0.5}}
       (cond (zero? volume) "🔇" (< volume 50) "🔉" :else "🔊")]
      [:input {:type "range" :min 0 :max 100 :value volume
               :style {:width "80px"
                       :accent-color $accent
                       :cursor "pointer"}
               :on-change #(set-volume! (js/parseInt (.. % -target -value)))}]
      [:span {:style {:font-size "11px" :color $text2 :min-width "28px"}}
       (str volume "%")]]]))

;; ── 左栏：文件夹列表 ──────────────────────────────────────────────────────────

(rum/defc left-panel < rum/reactive
  [groups]
  (let [sel (rum/react *selected-folder)]
    [:div.mp-left-panel
     {:style {:width "200px" :flex-shrink 0
              :background $bg2
              :border-right (str "1px solid " $border)
              :overflow-y "auto"
              :display "flex" :flex-direction "column"}}

     ;; 标题
     [:div {:style {:padding "10px 12px 6px"
                    :font-size "11px" :font-weight "700"
                    :text-transform "uppercase" :letter-spacing "0.06em"
                    :color $text2 :flex-shrink 0}}
      "文件夹"]

     ;; "全部" 项
     [:div {:on-click #(reset! *selected-folder nil)
            :style {:padding "6px 12px"
                    :cursor "pointer"
                    :font-size "13px"
                    :font-weight (if (nil? sel) "600" "400")
                    :color (if (nil? sel) $accent $text)
                    :background (if (nil? sel) $accent2 "transparent")
                    :border-radius "4px"
                    :margin "0 6px"
                    :transition "background 0.1s"}}
      (str "全部 (" (reduce + 0 (map #(count (:tracks %)) groups)) ")")]

     ;; 各文件夹
     (for [{:keys [folder tracks]} groups]
       [:div {:key folder
              :on-click #(reset! *selected-folder folder)
              :style {:padding "6px 12px"
                      :cursor "pointer"
                      :font-size "13px"
                      :font-weight (if (= sel folder) "600" "400")
                      :color (if (= sel folder) $accent $text)
                      :background (if (= sel folder) $accent2 "transparent")
                      :border-radius "4px"
                      :margin "0 6px"
                      :display "flex" :align-items "center"
                      :justify-content "space-between"
                      :gap "6px"
                      :transition "background 0.1s"}}
        [:span {:style {:overflow "hidden" :text-overflow "ellipsis" :white-space "nowrap"}}
         folder]
        [:span {:style {:font-size "11px" :color $text2 :flex-shrink 0}}
         (count tracks)]])]))

;; ── 中栏：曲目列表 ────────────────────────────────────────────────────────────

(rum/defc track-row < rum/reactive
  [index track]
  (let [current (:current (rum/react *player-state))
        active? (= (:path track) (:path current))
        status  (:status (rum/react *player-state))]
    [:div.mp-track-row
     {:on-click    #(play-track! track)
      :on-double-click #(play-track! track)
      :style {:display "grid"
              :grid-template-columns "32px 1fr auto"
              :align-items "center"
              :padding "5px 12px"
              :border-radius "4px"
              :cursor "pointer"
              :background (if active? $accent2 "transparent")
              :color (if active? $accent $text)
              :transition "background 0.1s"}}
     ;; 序号 / 播放指示
     [:span {:style {:font-size "12px" :color $text2 :text-align "center"}}
      (if active?
        (if (= status :playing) "▶" "⏸")
        (str (inc index)))]
     ;; 曲名
     [:span {:style {:font-size "13px"
                     :font-weight (if active? "600" "400")
                     :white-space "nowrap" :overflow "hidden"
                     :text-overflow "ellipsis"
                     :padding "0 8px"}}
      (:name track)]
     ;; 文件夹（仅在"全部"视图时显示）
     [:span {:style {:font-size "11px" :color $text2 :opacity 0.6}}
      (:folder track)]]))

(rum/defc center-panel < rum/reactive
  [groups]
  (let [sel      (rum/react *selected-folder)
        ps       (rum/react *player-state)
        tracks   (if (nil? sel)
                   (:playlist ps)
                   (->> groups (filter #(= (:folder %) sel)) first :tracks vec))]
    [:div.mp-center-panel
     {:style {:flex 1 :min-width 0 :display "flex" :flex-direction "column" :overflow "hidden"}}

     ;; 列表头
     [:div {:style {:display "grid"
                    :grid-template-columns "32px 1fr auto"
                    :padding "8px 12px 6px"
                    :border-bottom (str "1px solid " $border)
                    :flex-shrink 0}}
      [:span {:style {:font-size "11px" :color $text2 :text-align "center"}} "#"]
      [:span {:style {:font-size "11px" :color $text2 :padding "0 8px"}} "曲名"]
      [:span {:style {:font-size "11px" :color $text2}} "文件夹"]]

     ;; 曲目列表
     [:div {:style {:flex 1 :overflow-y "auto" :padding "4px 4px"}}
      (if (empty? tracks)
        [:div {:style {:display "flex" :align-items "center" :justify-content "center"
                       :height "100%" :color $text2 :font-size "14px" :opacity 0.5}}
         "此文件夹下没有音乐文件"]
        (map-indexed
         (fn [i t] (track-row i t))
         tracks))]]))

;; ── 主页面 ────────────────────────────────────────────────────────────────────

(rum/defcs music-player-page
  < rum/reactive
  (rum/local nil   ::cfg)
  (rum/local false ::scanning?)
  (rum/local nil   ::scan-err)
  {:did-mount
   (fn [state]
     (js/console.log "[music-player] did-mount: loading config...")
     (p/let [cfg (mp-cfg/<get-config)]
       (js/console.log "[music-player] raw config from DB:" (clj->js cfg))
       (let [merged (merge mp-cfg/default-config cfg)]
         (js/console.log "[music-player] merged config:" (clj->js merged))
         (reset! (::cfg state) merged)
         (if (string/blank? (:music-folder merged))
           (js/console.warn "[music-player] music-folder is blank, skip scan")
           (do
             (js/console.log "[music-player] starting scan of:" (:music-folder merged))
             (reset! (::scanning? state) true)
             (-> (load-folder! (:music-folder merged))
                 (.then  #(do (js/console.log "[music-player] scan done, tracks:" (count %))
                              (reset! (::scanning? state) false)))
                 (.catch #(do (js/console.error "[music-player] scan error:" %)
                              (reset! (::scanning? state) false)
                              (reset! (::scan-err state) (.-message %)))))))))
     state)
   :will-unmount
   (fn [state] (stop-poll!) state)}
  [state]
  (let [ps        (rum/react *player-state)
        cfg       @(::cfg state)
        scanning? (rum/react (::scanning? state))
        scan-err  (rum/react (::scan-err state))
        {:keys [playlist error]} ps
        no-folder? (string/blank? (:music-folder cfg ""))
        no-mpv?    (string/blank? (:mpv-path cfg ""))
        groups    (group-by-folder playlist)]

    [:div.music-player-root
     {:style {:display "flex" :flex-direction "column"
              :height "100%" :background $bg
              :font-family "var(--ls-font-family, sans-serif)"}}

     ;; ── 顶部工具栏 ──
     [:div.mp-toolbar
      {:style {:height "42px" :flex-shrink 0
               :background $bg2
               :border-bottom (str "1px solid " $border)
               :display "flex" :align-items "center"
               :padding "0 16px" :gap "12px"}}
      [:span {:style {:font-size "14px" :font-weight "700" :color $text
                      :display "flex" :align-items "center" :gap "6px"}}
       "🎵 音乐播放器"]
      [:div {:style {:width "1px" :height "20px" :background $border}}]
      ;; 曲目数量徽章
      (if scanning?
        [:span {:style {:font-size "12px" :color $text2}} "⏳ 扫描中…"]
        (when (pos? (count playlist))
          [:span {:style {:font-size "12px" :color $text2}}
           (str (count playlist) " 首 · " (count groups) " 个文件夹")]))
      ;; 刷新按钮 + mpv 状态
      [:div {:style {:margin-left "auto" :display "flex" :align-items "center" :gap "10px"}}
       ;; 刷新列表按钮
       (when (not no-folder?)
         [:button
          {:title    "重新扫描音乐文件夹"
           :disabled scanning?
           :on-click (fn []
                       (when-not scanning?
                         (swap! (::scanning? state) (constantly true))
                         (reset! (::scan-err state) nil)
                         (-> (load-folder! (:music-folder cfg))
                             (.then  #(reset! (::scanning? state) false))
                             (.catch #(do (reset! (::scanning? state) false)
                                          (reset! (::scan-err state) (.-message %)))))))
           :style {:background "none" :border (str "1px solid " $border)
                   :border-radius "5px" :padding "3px 10px"
                   :font-size "12px" :cursor "pointer" :color $text2
                   :opacity (if scanning? 0.4 0.8)}}
          (if scanning? "扫描中…" "↻ 刷新")])
       ;; mpv 状态
       (if (:mpv-ready? ps)
         [:span {:style {:font-size "11px" :color "#22c55e"}} "● mpv 就绪"]
         [:span {:style {:font-size "11px" :color $text2 :opacity 0.5}}
          (if no-mpv? "未配置 mpv" "○ mpv 未启动")])]]

     ;; ── 错误横幅 ──
     (when (or error scan-err)
       [:div {:style {:background "#fee2e2" :color "#991b1b"
                      :padding "6px 16px" :font-size "12px" :flex-shrink 0}}
        (or error scan-err)])

     ;; ── mpv 未配置警告 ──
     (when no-mpv?
       [:div {:style {:background "#fef9c3" :color "#92400e"
                      :padding "5px 16px" :font-size "12px" :flex-shrink 0}}
        "⚠ 未配置 mpv，暂无法播放。前往 设置 → 音乐播放器 填写 mpv 路径。"])

     ;; ── 主内容区：左栏 + 中栏 ──
     (if no-folder?
       ;; 空态引导
       [:div {:style {:flex 1 :display "flex" :flex-direction "column"
                      :align-items "center" :justify-content "center" :gap "12px"}}
        [:div {:style {:font-size "48px" :opacity 0.2}} "🎵"]
        [:p {:style {:font-size "15px" :font-weight "600" :color $text :margin 0}}
         "请先配置音乐文件夹"]
        [:p {:style {:font-size "13px" :color $text2 :margin 0}}
         "前往 设置 → 音乐播放器 → 音乐文件夹"]]

       ;; 正常三栏
       [:div {:style {:flex 1 :display "flex" :min-height 0 :overflow "hidden"}}
        (left-panel groups)
        (center-panel groups)])

     ;; ── 底部播放控制栏（始终固定）──
     (bottom-bar)]))

(defn all-music-player []
  (music-player-page))
