(ns frontend.extensions.mind-map.core
  "simple-mind-map canvas component.

   Loaded as a separate shadow-cljs lazy module (:mind-map).
   simple-mind-map is bundled via webpack into static/js/mind-map-bundle.js
   and exposed as window.SimpleMindMap.

   Data persistence:
   - Fast write cache  : localStorage, saved every 3s while editing
   - Authoritative store: Logseq DB (via on-save-data / on-load-data)"
  (:require [rum.core :as rum]
            [frontend.state :as state]))

;; ── localStorage fast cache ───────────────────────────────────────────────────

(defn- ls-key [map-id] (str "mind-map-data-" map-id))

(defn- load-from-ls [map-id]
  (when-let [raw (.getItem js/localStorage (ls-key map-id))]
    (try (js/JSON.parse raw)
         (catch :default _ nil))))

(defn- save-to-ls! [map-id data]
  (when (and map-id data)
    (.setItem js/localStorage (ls-key map-id)
              (js/JSON.stringify data))))

;; ── default root node ─────────────────────────────────────────────────────────

(def ^:private default-data
  #js {:data #js {:text "中心主题"}
       :children #js []})

;; ── MindMap constructor ───────────────────────────────────────────────────────

(defn- get-mind-map-ctor []
  ;; window.SimpleMindMap is the default export (a class) exposed by webpack
  (let [lib js/SimpleMindMap]
    (if (fn? lib)
      lib
      (when lib (.-default lib)))))

;; ── Main component ────────────────────────────────────────────────────────────

(rum/defcs mind-map-editor
  "Core mind map canvas component.

   Props map:
     :map-id       – unique string identifier (used for localStorage key)
     :map-title    – display title string
     :on-back      – fn() navigate back
     :on-load-data – fn(map-id) → JSON-string | nil
     :on-save-data – fn(map-id, json-string)"
  < rum/static
  (rum/local nil   ::instance)
  (rum/local nil   ::container-ref)
  (rum/local nil   ::timer-id)
  {:did-mount
   (fn [state]
     (let [args         (-> state :rum/args first)
           map-id       (:map-id args)
           on-load-data (:on-load-data args)
           container    @(::container-ref state)
           MindMapCtor  (get-mind-map-ctor)]
       (when (and container MindMapCtor)
         (let [saved-json  (or (when on-load-data (on-load-data map-id))
                               nil)
               init-data   (or (when saved-json
                                 (try (js/JSON.parse saved-json)
                                      (catch :default _ nil)))
                               (load-from-ls map-id)
                               default-data)
               dark?       (= "dark" (state/sub :ui/theme))
               ;; themeConfig 完整覆盖默认 #549688 绿色主题，适配明/暗两种模式
               theme-cfg   (if dark?
                              #js {:backgroundColor "#1a1b26"
                                   :lineColor       "#4b5563"
                                   :lineWidth       2
                                   :lineStyle       "curve"
                                   :lineRadius      8
                                   :root   #js {:fillColor    "#4f46e5"
                                                :color        "#f8fafc"
                                                :fontSize     16
                                                :fontWeight   "bold"
                                                :borderRadius 8
                                                :paddingX     20
                                                :paddingY     10}
                                   :second #js {:fillColor    "#1e1b4b"
                                                :borderColor  "#4f46e5"
                                                :color        "#c7d2fe"
                                                :fontSize     14
                                                :borderRadius 6
                                                :marginX      90
                                                :marginY      28}
                                   :node   #js {:fillColor    "transparent"
                                                :borderColor  "#374151"
                                                :color        "#9ca3af"
                                                :fontSize     13
                                                :borderRadius 4
                                                :marginX      60
                                                :marginY      18}}
                              #js {:backgroundColor "#f8fafc"
                                   :lineColor       "#94a3b8"
                                   :lineWidth       2
                                   :lineStyle       "curve"
                                   :lineRadius      8
                                   :root   #js {:fillColor    "#1e293b"
                                                :color        "#f8fafc"
                                                :fontSize     16
                                                :fontWeight   "bold"
                                                :borderRadius 8
                                                :paddingX     20
                                                :paddingY     10}
                                   :second #js {:fillColor    "#f1f5f9"
                                                :borderColor  "#94a3b8"
                                                :color        "#1e293b"
                                                :fontSize     14
                                                :borderRadius 6
                                                :marginX      90
                                                :marginY      28}
                                   :node   #js {:fillColor    "transparent"
                                                :borderColor  "#e2e8f0"
                                                :color        "#475569"
                                                :fontSize     13
                                                :borderRadius 4
                                                :marginX      60
                                                :marginY      18}})
               instance    (MindMapCtor.
                            #js {:el          container
                                 :data        init-data
                                 :theme       "default"
                                 :themeConfig theme-cfg
                                 ;; 视图
                                 :fit         true
                                 :fitPadding  60
                                 ;; 缩放：滚轮缩放（更符合思维导图直觉）
                                 :mousewheelAction            "zoom"
                                 :mousewheelZoomActionReverse false
                                 :scaleRatio                  0.15
                                 :minZoomRatio                15
                                 :maxZoomRatio                500
                                 ;; 编辑体验
                                 :readonly                            false
                                 :enableAutoEnterTextEditWhenKeydown  true
                                 :selectTextOnEnterEditText           true
                                 :enableDblclickBackToRootNode        true
                                 :textAutoWrapWidth                   280
                                 ;; 新节点默认文字
                                 :defaultInsertSecondLevelNodeText          "子节点"
                                 :defaultInsertBelowSecondLevelNodeText     "子节点"
                                 ;; 历史记录防抖 150ms
                                 :addHistoryTime  150
                                 ;; hover 边框颜色跟随主题
                                 :hoverRectColor  (if dark?
                                                    "rgba(99,102,241,0.7)"
                                                    "rgba(30,41,59,0.2)")})
               timer       (js/setInterval
                            (fn []
                              (when-let [inst @(::instance state)]
                                (let [data (.getData inst)]
                                  (save-to-ls! map-id data))))
                            3000)]
           (reset! (::instance state) instance)
           (reset! (::timer-id state) timer))))
     state)
   :will-unmount
   (fn [state]
     (let [args         (-> state :rum/args first)
           map-id       (:map-id args)
           on-save-data (:on-save-data args)
           timer        @(::timer-id state)
           instance     @(::instance state)]
       (when timer (js/clearInterval timer))
       (when instance
         (let [data (.getData instance)]
           (save-to-ls! map-id data)
           (when on-save-data
             (on-save-data map-id (js/JSON.stringify data)))
           (.destroy instance))))
     state)}
  [state {:keys [map-id map-title on-back _on-load-data _on-save-data]}]
  (let [*container (::container-ref state)
        *instance  (::instance state)]
    [:div.mind-map-wrapper
     {:style {:width "100%" :height "100%" :display "flex" :flexDirection "column"}}

     ;; ── toolbar ─────────────────────────────────────────────────────────────
     [:div.mind-map-toolbar
      {:style {:display        "flex"
               :alignItems     "center"
               :gap            "8px"
               :padding        "6px 12px"
               :borderBottom   "1px solid var(--lx-gray-05,#e5e7eb)"
               :background     "var(--lx-gray-02,#f9fafb)"
               :flexShrink     "0"}}

      ;; ← 返回
      [:button
       {:title   "保存并返回"
        :on-click (fn []
                    (when-let [inst @*instance]
                      (let [data (.getData inst)]
                        (save-to-ls! map-id data)))
                    (when on-back (on-back)))
        :style   {:padding      "4px 10px"
                  :background   "var(--lx-gray-03,#f3f4f6)"
                  :color        "var(--lx-gray-12,#111)"
                  :border       "1px solid var(--lx-gray-06,#e5e7eb)"
                  :borderRadius "6px"
                  :cursor       "pointer"
                  :fontSize     "13px"}}
       "← 返回"]

      ;; title chip
      [:span
       {:style {:padding      "4px 10px"
                :background   "var(--lx-gray-02,#f9fafb)"
                :border       "1px solid var(--lx-gray-05,#e5e7eb)"
                :borderRadius "6px"
                :fontSize     "13px"
                :fontWeight   "600"
                :maxWidth     "200px"
                :overflow     "hidden"
                :textOverflow "ellipsis"
                :whiteSpace   "nowrap"}}
       (or map-title "思维导图")]

      [:div {:style {:flex "1"}}]

      ;; 重置视图
      [:button
       {:title    "重置视图到中心"
        :on-click (fn []
                    (when-let [inst @(::instance state)]
                      (.fit (.-view inst))))
        :style    {:padding      "4px 10px"
                   :background   "var(--lx-gray-03,#f3f4f6)"
                   :color        "var(--lx-gray-12,#111)"
                   :border       "1px solid var(--lx-gray-06,#e5e7eb)"
                   :borderRadius "6px"
                   :cursor       "pointer"
                   :fontSize     "13px"}}
       "⊡ 适应"]]

     ;; ── canvas ──────────────────────────────────────────────────────────────
     [:div
      {:ref   (fn [el] (reset! *container el))
       :style {:flex     "1"
               :width    "100%"
               :overflow "hidden"
               :background "var(--ls-primary-background-color,#fff)"}}]]))

;; Export for shadow.lazy loadable
(def ^:export editor mind-map-editor)
