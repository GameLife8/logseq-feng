(ns frontend.components.music-player
  "本地音乐播放器主界面。
   架构：
   - Electron IPC → mpv 进程（用户配置的外部 mpv 二进制）
   - 本组件只负责 UI 和状态管理，不直接调用音频 API
   - 所有 mpv 控制通过 electron/ipc/invoke ':mpv/*' 完成"
  (:require [clojure.string :as string]
            [frontend.handler.music-player-config :as mp-cfg]
            [frontend.state :as state]
            [frontend.util :as util]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]
            [rum.core :as rum]))

;; ── Electron IPC 桥接 ────────────────────────────────────────────────────────

(defn- mpv-invoke!
  "向 Electron 主进程发送 mpv 控制命令，返回 Promise。
   非 Electron 环境下静默忽略。"
  [action & [args]]
  (when (util/electron?)
    (js/window.apis.invoke "mpv-control"
                           (clj->js (merge {:action action} args)))))

;; ── 全局播放器状态 ─────────────────────────────────────────────────────────────
;; 使用 defonce 保证跨路由导航时状态不丢失

(defonce *player-state
  (atom {:status      :stopped    ; :stopped | :playing | :paused
         :current     nil          ; 当前曲目 {:path "..." :name "..."}
         :playlist    []           ; 当前文件夹的曲目列表
         :position    0            ; 播放位置（秒）
         :duration    0            ; 时长（秒）
         :volume      80           ; 音量 0-100
         :mpv-ready?  false        ; mpv 进程是否就绪
         :error       nil}))       ; 错误信息

(defonce *poll-timer (atom nil))

;; ── 工具函数 ──────────────────────────────────────────────────────────────────

(defn- fmt-time [secs]
  (let [s (int (or secs 0))
        m (quot s 60)
        r (mod s 60)]
    (str m ":" (when (< r 10) "0") r)))

(defn- music-ext? [name]
  (some #(string/ends-with? (string/lower-case name) %)
        [".mp3" ".flac" ".ogg" ".wav" ".aac" ".m4a" ".opus" ".wma"]))

(defn- track-name [path]
  (last (string/split path #"[/\\]")))

;; ── MPV 控制函数 ──────────────────────────────────────────────────────────────

(defn- start-poll! []
  (when-not @*poll-timer
    (reset! *poll-timer
            (js/setInterval
             (fn []
               (p/let [status (mpv-invoke! "status")]
                 (when status
                   (let [s (js->clj status :keywordize-keys true)]
                     (swap! *player-state merge
                            {:status   (cond (:playing? s) :playing
                                             (:paused? s)  :paused
                                             :else         :stopped)
                             :position (:position s 0)
                             :duration (:duration s 0)
                             :volume   (:volume s (:volume @*player-state))})))))
             1000))))

(defn- stop-poll! []
  (when-let [t @*poll-timer]
    (js/clearInterval t)
    (reset! *poll-timer nil)))

(defn- ensure-mpv-ready! []
  (if (:mpv-ready? @*player-state)
    (p/resolved true)
    (p/let [cfg   (mp-cfg/<get-config)
            path  (:mpv-path cfg)
            _     (when (string/blank? path)
                    (swap! *player-state assoc :error "请先在设置中配置 mpv 路径")
                    (js/Promise.reject "no-mpv-path"))
            reply (mpv-invoke! "start" {:mpv-path path
                                        :volume   (:volume cfg 80)})]
      (if (.-ok ^js reply)
        (do (swap! *player-state assoc :mpv-ready? true :error nil)
            (start-poll!)
            true)
        (do (swap! *player-state assoc
                   :error (str "启动 mpv 失败: " (.-message ^js reply)))
            false)))))

(defn play-track! [track]
  (p/do!
   (ensure-mpv-ready!)
   (p/let [reply (mpv-invoke! "play" {:path (:path track)})]
     (when reply
       (swap! *player-state assoc
              :current  track
              :status   :playing
              :position 0)))))

(defn play-pause! []
  (p/let [s (:status @*player-state)]
    (if (= s :playing)
      (p/do! (mpv-invoke! "pause")
             (swap! *player-state assoc :status :paused))
      (p/do! (mpv-invoke! "resume")
             (swap! *player-state assoc :status :playing)))))

(defn- next-track! []
  (let [{:keys [playlist current]} @*player-state
        idx  (when current (first (keep-indexed #(when (= (:path %2) (:path current)) %1) playlist)))
        next (when idx (get playlist (mod (inc idx) (count playlist))))]
    (when next (play-track! next))))

(defn- prev-track! []
  (let [{:keys [playlist current position]} @*player-state]
    ;; 前3秒内：回到上一曲；之后：重头播放
    (if (> position 3)
      (mpv-invoke! "seek" {:pos 0})
      (let [idx  (when current (first (keep-indexed #(when (= (:path %2) (:path current)) %1) playlist)))
            prev (when idx (get playlist (mod (dec idx) (count playlist))))]
        (when prev (play-track! prev))))))

(defn- set-volume! [v]
  (swap! *player-state assoc :volume v)
  (mpv-invoke! "set-volume" {:value v}))

(defn- seek! [pos]
  (mpv-invoke! "seek" {:pos pos}))

;; ── 加载文件夹 ─────────────────────────────────────────────────────────────────

(defn- load-folder! [folder]
  (p/let [reply (mpv-invoke! "list-music" {:folder folder})]
    (when reply
      (let [files (js->clj reply :keywordize-keys true)
            tracks (mapv (fn [f] {:path (:path f) :name (track-name (:path f))}) files)]
        (swap! *player-state assoc :playlist tracks)
        tracks))))

;; ── UI 组件 ───────────────────────────────────────────────────────────────────

(rum/defc progress-bar
  [{:keys [position duration on-seek]}]
  (let [pct (if (> duration 0) (* 100 (/ position duration)) 0)]
    [:div.mp-progress-wrap
     {:style {:position "relative" :height "4px" :background "var(--lx-gray-05,#e5e7eb)"
              :borderRadius "2px" :cursor "pointer" :margin "8px 0"}
      :on-click (fn [^js e]
                  (let [rect (.getBoundingClientRect (.-currentTarget e))
                        x    (- (.-clientX e) (.-left rect))
                        w    (.-width rect)
                        pos  (* (/ x w) duration)]
                  (on-seek pos)))}
     [:div {:style {:position "absolute" :left 0 :top 0 :height "100%"
                    :width (str pct "%")
                    :background "var(--rx-accent-09,#6366f1)"
                    :borderRadius "2px"
                    :transition "width 0.5s linear"}}]]))

(rum/defc volume-bar
  [volume on-change]
  [:div.mp-volume {:style {:display "flex" :align-items "center" :gap "6px"}}
   [:span {:style {:opacity 0.6 :font-size "13px"}}
    (if (zero? volume) "🔇" (if (< volume 50) "🔉" "🔊"))]
   [:input
    {:type "range" :min 0 :max 100 :value volume
     :style {:width "70px" :accentColor "var(--rx-accent-09,#6366f1)"}
     :on-change (fn [^js e] (on-change (js/parseInt (.. e -target -value))))}]])

(rum/defc player-controls
  [{:keys [status on-prev on-play-pause on-next]}]
  (let [playing? (= status :playing)]
    [:div.mp-controls {:style {:display "flex" :align-items "center"
                                :justify-content "center" :gap "12px"}}
     [:button.mp-btn
      {:on-click on-prev
       :title "上一曲"
       :style {:background "none" :border "none" :cursor "pointer"
               :opacity 0.7 :font-size "18px" :padding "4px 8px"}}
      "⏮"]
     [:button.mp-btn-main
      {:on-click on-play-pause
       :title (if playing? "暂停" "播放")
       :style {:background "var(--rx-accent-09,#6366f1)"
               :border "none" :cursor "pointer"
               :color "#fff" :font-size "20px"
               :width "40px" :height "40px"
               :borderRadius "50%" :display "flex"
               :align-items "center" :justify-content "center"}}
      (if playing? "⏸" "▶")]
     [:button.mp-btn
      {:on-click on-next
       :title "下一曲"
       :style {:background "none" :border "none" :cursor "pointer"
               :opacity 0.7 :font-size "18px" :padding "4px 8px"}}
      "⏭"]]))

(rum/defc track-item
  [{:keys [track index current-path on-play]}]
  (let [active? (= (:path track) current-path)]
    [:div.mp-track-item
     {:key (:path track)
      :on-click #(on-play track)
      :style {:display "flex" :align-items "center" :gap "8px"
              :padding "6px 12px" :cursor "pointer" :border-radius "6px"
              :background (if active? "var(--rx-accent-03,#ede9fe)" "transparent")
              :transition "background 0.15s"}}
     [:span {:style {:width "24px" :text-align "right" :opacity 0.4
                     :font-size "12px" :flex-shrink 0}}
      (if active? "♪" (str (inc index)))]
     [:span {:style {:flex 1 :font-size "13px"
                     :font-weight (if active? "600" "400")
                     :white-space "nowrap" :overflow "hidden"
                     :text-overflow "ellipsis"
                     :color (if active? "var(--rx-accent-11,#4338ca)" "inherit")}}
      (:name track)]]))

(rum/defc now-playing-bar
  [{:keys [current status position duration on-play-pause on-prev on-next on-seek volume on-volume]}]
  [:div.mp-now-playing
   {:style {:padding "16px 20px"
            :border-bottom "1px solid var(--lx-gray-05,#e5e7eb)"}}
   ;; 曲目名称
   [:div {:style {:font-size "14px" :font-weight "600"
                  :white-space "nowrap" :overflow "hidden"
                  :text-overflow "ellipsis" :margin-bottom "8px"
                  :opacity (if current 1 0.4)}}
    (or (when current (str "♪  " (:name current)))
        "未在播放")]
   ;; 进度条
   (when current
     (progress-bar {:position position :duration duration :on-seek on-seek}))
   ;; 时间 + 控制 + 音量
   [:div {:style {:display "flex" :align-items "center" :justify-content "space-between"
                  :gap "8px" :margin-top "4px"}}
    [:span {:style {:font-size "11px" :opacity 0.5 :min-width "70px"}}
     (when current (str (fmt-time position) " / " (fmt-time duration)))]
    (player-controls {:status status
                      :on-prev on-prev
                      :on-play-pause on-play-pause
                      :on-next on-next})
    (volume-bar volume on-volume)]])

;; ── 主页面 ────────────────────────────────────────────────────────────────────

(rum/defcs music-player-page
  < rum/reactive
  (rum/local nil ::cfg)
  (rum/local false ::loading?)
  {:did-mount
   (fn [state]
     (p/let [cfg (mp-cfg/<get-config)]
       (reset! (::cfg state) cfg)
       (when (and cfg (not (string/blank? (:music-folder cfg))))
         (load-folder! (:music-folder cfg))))
     state)
   :will-unmount
   (fn [state]
     (stop-poll!)
     state)}
  [state]
  (let [ps        (rum/react *player-state)
        cfg       (rum/react (::cfg state))
        loading?  (rum/react (::loading? state))
        {:keys [status current playlist position duration volume error]} ps
        no-folder? (string/blank? (:music-folder cfg ""))
        no-mpv?    (string/blank? (:mpv-path cfg ""))]
    [:div.music-player-page
     {:style {:display "flex" :flex-direction "column" :height "100%"
              :min-height 0}}

     ;; ── 顶部标题栏 ──
     [:div {:style {:padding "16px 20px 12px"
                    :border-bottom "1px solid var(--lx-gray-05,#e5e7eb)"
                    :display "flex" :align-items "center" :gap "10px"}}
      [:span {:style {:font-size "18px"}} "🎵"]
      [:h1 {:style {:margin 0 :font-size "16px" :font-weight "700"}} "音乐播放器"]
      (when (not (:mpv-ready? ps))
        [:span {:style {:font-size "11px" :opacity 0.4 :margin-left "auto"}}
         "mpv 未启动"])]

     ;; ── 错误提示 ──
     (when error
       [:div {:style {:margin "8px 16px" :padding "8px 12px"
                      :background "#fee2e2" :color "#991b1b"
                      :border-radius "6px" :font-size "12px"}}
        error])

     ;; ── 配置提示 ──
     (when (or no-folder? no-mpv?)
       [:div {:style {:margin "12px 16px" :padding "12px 16px"
                      :background "var(--lx-gray-02,#f9fafb)"
                      :border "1px dashed var(--lx-gray-06,#d1d5db)"
                      :border-radius "8px" :font-size "13px"}}
        [:p {:style {:margin "0 0 8px" :font-weight "600"}} "⚙️ 请先完成配置"]
        (when no-mpv?
          [:p {:style {:margin "0 0 4px" :opacity 0.7}} "• 设置 → 音乐播放器 → 配置 mpv 路径"])
        (when no-folder?
          [:p {:style {:margin 0 :opacity 0.7}} "• 设置 → 音乐播放器 → 选择音乐文件夹"])])

     ;; ── 播放器控制区 ──
     (now-playing-bar
      {:current     current
       :status      status
       :position    position
       :duration    duration
       :volume      volume
       :on-play-pause play-pause!
       :on-prev     prev-track!
       :on-next     next-track!
       :on-seek     seek!
       :on-volume   set-volume!})

     ;; ── 曲目列表 ──
     (if (empty? playlist)
       [:div {:style {:flex 1 :display "flex" :align-items "center"
                      :justify-content "center" :opacity 0.4 :font-size "14px"}}
        (if no-folder? "未配置音乐文件夹" "文件夹中没有音乐文件")]
       [:div.mp-track-list
        {:style {:flex 1 :overflow-y "auto" :padding "8px 8px"}}
        (map-indexed
         (fn [i track]
           (track-item {:key i :track track :index i
                        :current-path (:path current)
                        :on-play play-track!}))
         playlist)])]))

(defn all-music-player
  "路由入口，由 routes.cljs 调用。"
  []
  (music-player-page))
