(ns frontend.extensions.mind-map.core
  "simple-mind-map canvas component.

   Loaded as a separate shadow-cljs lazy module (:mind-map).
   simple-mind-map is bundled via webpack into static/js/mind-map-bundle.js
   and exposed as window.SimpleMindMap.

   Data persistence:
   - Fast write cache  : localStorage, saved every 3s while editing
   - Authoritative store: Logseq DB (via on-save-data / on-load-data)

   Features (aligned with obsidian-simplemindmap plugin):
   - Export: PNG / SVG / JSON download
   - Import: JSON file picker
   - Readonly mode toggle
   - ResizeObserver for responsive canvas
   - Full toolbar: undo/redo, node ops, zoom, layout picker"
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
  (let [lib js/SimpleMindMap]
    (if (fn? lib)
      lib
      (when lib (.-default lib)))))

;; ── Download helper ───────────────────────────────────────────────────────────

(defn- trigger-download! [href filename]
  (let [a (.createElement js/document "a")]
    (set! (.-href a) href)
    (set! (.-download a) filename)
    (.click a)))

(defn- export-json! [instance title]
  (when instance
    (let [data (js/JSON.stringify (.getData ^js instance) nil 2)
          blob (js/Blob. #js [data] #js {:type "application/json"})
          url  (.createObjectURL js/URL blob)]
      (trigger-download! url (str (or title "mind-map") ".json"))
      (js/setTimeout #(.revokeObjectURL js/URL url) 2000))))

(defn- export-image! [instance type title]
  (when instance
    (-> (.export ^js instance type true (or title "mind-map"))
        (.then (fn [url]
                 (trigger-download! url (str (or title "mind-map") "." type))))
        (.catch (fn [e] (js/console.error "Export failed:" e))))))

(defn- import-from-file! [instance file]
  (when (and instance file)
    (let [reader (js/FileReader.)]
      (set! (.-onload reader)
            (fn [e]
              (try
                (let [data (js/JSON.parse (.. e -target -result))]
                  (.setData ^js instance data))
                (catch :default err
                  (js/console.error "Import JSON parse failed:" err)))))
      (.readAsText reader file))))

;; ── Toolbar helpers ───────────────────────────────────────────────────────────

(def ^:private toolbar-btn-style
  {:display        "flex"
   :alignItems     "center"
   :justifyContent "center"
   :minWidth       "30px"
   :height         "30px"
   :padding        "0 8px"
   :background     "transparent"
   :border         "1px solid transparent"
   :borderRadius   "5px"
   :cursor         "pointer"
   :fontSize       "13px"
   :color          "var(--lx-gray-11,#374151)"
   :transition     "background 0.1s, border-color 0.1s"
   :userSelect     "none"
   :whiteSpace     "nowrap"})

(def ^:private toolbar-btn-disabled
  {:opacity       "0.35"
   :cursor        "not-allowed"
   :pointerEvents "none"})

(def ^:private toolbar-sep-style
  {:width      "1px"
   :height     "22px"
   :background "var(--lx-gray-05,#e5e7eb)"
   :margin     "0 4px"
   :flexShrink "0"})

(defn- tb-btn
  "Toolbar icon button with tooltip. Calls (handler) on click when not disabled."
  [label title handler & {:keys [disabled? active?]}]
  [:button
   {:title    title
    :disabled (boolean disabled?)
    :on-click (when-not disabled? handler)
    :style    (merge toolbar-btn-style
                     (when disabled? toolbar-btn-disabled)
                     (when active? {:background  "var(--lx-gray-05,#e5e7eb)"
                                    :border     "1px solid var(--lx-gray-07,#9ca3af)"}))}
   label])

(defn- tb-sep []
  [:div {:style toolbar-sep-style}])

;; ── Dropdown menu ─────────────────────────────────────────────────────────────

(defn- tb-dropdown
  "Generic dropdown attached to a toolbar button.
   items: seq of {:label str :on-click fn :active? bool}"
  [btn-label btn-title show? toggle-fn items]
  [:div {:style {:position "relative"}}
   (tb-btn btn-label btn-title toggle-fn :active? show?)
   (when show?
     [:div
      {:style {:position     "absolute"
               :top          "34px"
               :left         "0"
               :background   "var(--ls-primary-background-color,#fff)"
               :border       "1px solid var(--lx-gray-05,#e5e7eb)"
               :borderRadius "6px"
               :boxShadow    "0 4px 12px rgba(0,0,0,0.12)"
               :zIndex       "200"
               :minWidth     "140px"
               :padding      "4px 0"}}
      (for [{:keys [label on-click active?]} items]
        [:div
         {:key      label
          :on-click on-click
          :style    (merge {:padding  "6px 14px"
                            :fontSize "13px"
                            :cursor   "pointer"
                            :color    "var(--lx-gray-11,#374151)"}
                           (when active?
                             {:fontWeight "600"
                              :color      "var(--ls-link-text-color,#4f46e5)"}))}
         label])])])

;; ── Context menu helpers ──────────────────────────────────────────────────────

(defn- ctx-item [label shortcut handler & {:keys [danger? disabled?]}]
  [:div
   {:on-click       (when-not disabled?
                      (fn [^js e] (.stopPropagation e) (handler)))
    :on-mouse-enter (when-not disabled?
                      (fn [^js e]
                        (set! (.. e -currentTarget -style -background)
                              "var(--lx-gray-03,#f3f4f6)")))
    :on-mouse-leave (when-not disabled?
                      (fn [^js e]
                        (set! (.. e -currentTarget -style -background)
                              "transparent")))
    :style {:display        "flex"
            :justifyContent "space-between"
            :alignItems     "center"
            :padding        "6px 14px"
            :gap            "16px"
            :fontSize       "13px"
            :cursor         (if disabled? "default" "pointer")
            :color          (cond
                              disabled? "var(--lx-gray-07,#d1d5db)"
                              danger?   "var(--rx-red-09,#dc2626)"
                              :else     "var(--lx-gray-11,#374151)")}}
   [:span label]
   (when (seq shortcut)
     [:span {:style {:fontSize "11px" :color "var(--lx-gray-08,#9ca3af)"}}
      shortcut])])

(defn- ctx-sep []
  [:div {:style {:height "1px" :background "var(--lx-gray-04,#f3f4f6)" :margin "3px 0"}}])

(defn- ctx-menu-panel
  [menu node-active? is-root? close! cmd! instance-atom]
  (when menu
    (let [{:keys [x y]} menu
          na!          (fn [f] (fn [] (close!) (f)))
          assoc-avail? (boolean (when-let [i @instance-atom]
                                  (.-associativeLine ^js i)))
          px           (min x (- (.-innerWidth js/window) 232 8))
          py           (min y (- (.-innerHeight js/window) 480 8))]
      [:div
       {:style           {:position "fixed" :inset "0" :zIndex "500"}
        :on-click        (fn [^js e] (.stopPropagation e) (close!))
        :on-context-menu (fn [^js e] (.preventDefault e) (.stopPropagation e) (close!))}
       [:div
        {:style    {:position     "fixed"
                    :left         (str px "px")
                    :top          (str py "px")
                    :background   "var(--ls-primary-background-color,#fff)"
                    :border       "1px solid var(--lx-gray-05,#e5e7eb)"
                    :borderRadius "8px"
                    :boxShadow    "0 4px 16px rgba(0,0,0,0.15)"
                    :zIndex       "501"
                    :minWidth     "220px"
                    :padding      "4px 0"
                    :userSelect   "none"}
         :on-click (fn [^js e] (.stopPropagation e))}
        (ctx-item "插入同级节点" "Enter"  (na! #(cmd! "INSERT_NODE"))
                  :disabled? (or (not node-active?) is-root?))
        (ctx-item "插入子节点"   "Tab"    (na! #(cmd! "INSERT_CHILD_NODE"))
                  :disabled? (not node-active?))
        (ctx-item "插入父节点"   ""       (na! #(cmd! "INSERT_PARENT_NODE"))
                  :disabled? (or (not node-active?) is-root?))
        (ctx-item "插入概要"     "Ctrl+G" (na! #(cmd! "ADD_GENERALIZATION"))
                  :disabled? (not node-active?))
        (ctx-sep)
        (ctx-item "上移节点"     ""       (na! #(cmd! "UP_NODE"))
                  :disabled? (or (not node-active?) is-root?))
        (ctx-item "下移节点"     ""       (na! #(cmd! "DOWN_NODE"))
                  :disabled? (or (not node-active?) is-root?))
        (ctx-item "展开所有节点" ""       (na! #(cmd! "EXPAND_ALL")))
        (ctx-item "折叠所有节点" ""       (na! #(cmd! "UNEXPAND_ALL")))
        (ctx-sep)
        (ctx-item "删除节点"       "Delete"         (na! #(cmd! "REMOVE_NODE"))
                  :danger? true :disabled? (or (not node-active?) is-root?))
        (ctx-item "仅删除当前节点" "Shift+Backspace" (na! #(cmd! "REMOVE_CURRENT_NODE"))
                  :danger? true :disabled? (or (not node-active?) is-root?))
        (ctx-sep)
        (ctx-item "复制节点" "Ctrl+C"
                  (na! #(when-let [i @instance-atom]
                          (.copy ^js (.-renderer ^js i)))))
        (ctx-item "剪切节点" "Ctrl+X"   (na! #(cmd! "CUT_NODE"))
                  :disabled? (or (not node-active?) is-root?))
        (ctx-item "粘贴节点" "Ctrl+V"   (na! #(cmd! "PASTE_NODE"))
                  :disabled? (not node-active?))
        (ctx-sep)
        (ctx-item "去除自定义样式" "" (na! #(cmd! "REMOVE_CUSTOM_STYLES"))
                  :disabled? (not node-active?))
        (when (and node-active? assoc-avail?)
          (ctx-sep))
        (when (and node-active? assoc-avail?)
          (ctx-item "添加关联线" ""
                    (na! #(when-let [i @instance-atom]
                            (.createLineFromActiveNode
                             ^js (.-associativeLine ^js i))))))]])))

;; ── Layouts ───────────────────────────────────────────────────────────────────

(def ^:private layouts
  [["logicalStructure"      "逻辑结构"]
   ["mindMap"               "思维导图"]
   ["catalogOrganization"   "目录组织"]
   ["organizationStructure" "组织结构"]
   ["timeline"              "时间线"]
   ["verticalTimeline"      "垂直时间线"]
   ["fishbone"              "鱼骨图"]])

;; ── Node style panel ──────────────────────────────────────────────────────────

(defn- rgb->hex [s]
  (when-let [[_ r g b] (re-matches #"rgba?\((\d+),\s*(\d+),\s*(\d+).*" s)]
    (letfn [(pad [x] (let [h (.toString (js/parseInt x) 16)]
                       (if (= 1 (count h)) (str "0" h) h)))]
      (str "#" (pad r) (pad g) (pad b)))))

(defn- ensure-hex [c]
  (cond
    (nil? c)                          "#333333"
    (re-matches #"#[0-9a-fA-F]{6}" c) (.toLowerCase ^js c)
    (and (string? c) (.startsWith ^js c "rgb")) (or (rgb->hex c) "#333333")
    :else "#333333"))

(defn- sp-title [text]
  [:div {:style {:fontSize     "11px"
                 :fontWeight   "700"
                 :letterSpacing "0.6px"
                 :color        "var(--lx-gray-09,#6b7280)"
                 :padding      "10px 0 4px 0"
                 :borderBottom "1px solid var(--lx-gray-04,#f3f4f6)"
                 :marginBottom "6px"}}
   text])

(defn- sp-row [& children]
  (into [:div {:style {:display "flex" :alignItems "center"
                       :gap "6px" :flexWrap "wrap" :marginBottom "6px"}}]
        children))

(defn- sp-label [text]
  [:span {:style {:fontSize "12px" :color "var(--lx-gray-09,#6b7280)"
                  :minWidth "26px" :flexShrink "0"}}
   text])

(defn- sp-color [current-val on-change]
  (let [hex (ensure-hex current-val)]
    [:label {:style {:cursor "pointer" :display "inline-block"
                     :flexShrink "0" :position "relative"}}
     [:span {:style {:display "block" :width "58px" :height "22px"
                     :borderRadius "4px"
                     :border "1px solid var(--lx-gray-06,#d1d5db)"
                     :background (or current-val "#ffffff")}}]
     [:input {:type      "color"
              :value     hex
              :style     {:position "absolute" :opacity "0"
                          :width "1px" :height "1px" :top "0" :left "0"}
              :on-change (fn [^js e] (on-change (.. e -target -value)))}]]))

(defn- sp-select [value options on-change & {:keys [width]}]
  [:select
   {:value     (str value)
    :on-change (fn [^js e] (on-change (.. e -target -value)))
    :style     {:fontSize   "12px"
                :padding    "2px 4px"
                :border     "1px solid var(--lx-gray-06,#d1d5db)"
                :borderRadius "4px"
                :background "var(--ls-primary-background-color,#fff)"
                :color      "var(--lx-gray-11,#374151)"
                :width      (or width "auto")
                :cursor     "pointer"}}
   (for [[v lbl] options]
     [:option {:key v :value (str v)} lbl])])

(defn- sp-toggle [text active? on-click extra-style]
  [:button
   {:on-click on-click
    :style    (merge {:fontSize "13px" :padding "2px 8px"
                      :border   "1px solid var(--lx-gray-06,#d1d5db)"
                      :borderRadius "4px" :cursor "pointer"
                      :background   (if active?
                                      "var(--lx-accent-09,#4f46e5)"
                                      "var(--ls-primary-background-color,#fff)")
                      :color        (if active?
                                      "#fff"
                                      "var(--lx-gray-11,#374151)")}
                     extra-style)}
   text])

(def ^:private font-families
  [["微软雅黑, Microsoft YaHei" "微软雅黑"]
   ["宋体, SimSun, Songti SC"   "宋体"]
   ["楷体, STKaiti"              "楷体"]
   ["黑体, SimHei, Heiti SC"    "黑体"]
   ["Arial, sans-serif"          "Arial"]
   ["Times New Roman, serif"     "Times New Roman"]
   ["Courier New, monospace"     "Courier New"]])

(def ^:private font-sizes
  [["10" "10"] ["12" "12"] ["14" "14"] ["16" "16"]
   ["18" "18"] ["24" "24"] ["32" "32"] ["48" "48"]])

(def ^:private text-aligns
  [["left" "左对齐"] ["center" "居中"] ["right" "右对齐"]])

(def ^:private dash-options
  [["none"    "实线"]
   ["5,5"     "短虚线"]
   ["10,10"   "长虚线"]
   ["5,5,1,5" "点划线"]
   ["1,5"     "细点线"]])

(def ^:private width-options
  [["0" "0"] ["1" "1"] ["2" "2"] ["3" "3"] ["4" "4"] ["5" "5"] ["6" "6"]])

(def ^:private radius-options
  [["0" "0"] ["2" "2"] ["4" "4"] ["6" "6"] ["8" "8"] ["10" "10"] ["14" "14"] ["20" "20"]])

(def ^:private shape-options
  [["rectangle"                "矩形"]
   ["roundedRectangle"         "圆角矩形"]
   ["ellipse"                  "椭圆"]
   ["circle"                   "圆形"]
   ["diamond"                  "菱形"]
   ["parallelogram"            "平行四边形"]
   ["octagonalRectangle"       "八边形"]
   ["outerTriangularRectangle" "外三角矩形"]
   ["innerTriangularRectangle" "内三角矩形"]])

(def ^:private marker-dirs
  [["end" "尾部"] ["start" "头部"]])

(defn- node-style-panel [node-styles set-style! close-fn]
  (let [s node-styles
        st (fn [k v] (set-style! (name k) v))]
    [:div.mind-map-style-panel
     {:style {:width       "268px"
              :flex-shrink "0"
              :border-left "1px solid var(--lx-gray-05,#e5e7eb)"
              :background  "var(--ls-secondary-background-color,#f9fafb)"
              :overflow-y  "auto"
              :overflow-x  "hidden"
              :padding     "0 12px 20px 12px"}}

     ;; ── panel header ──────────────────────────────────────────────────────
     [:div {:style {:display         "flex"
                    :justify-content "space-between"
                    :align-items     "center"
                    :padding         "8px 0 6px 0"
                    :border-bottom   "1px solid var(--lx-gray-05,#e5e7eb)"
                    :margin-bottom   "2px"}}
      [:span {:style {:font-size "13px" :font-weight "600"
                      :color     "var(--lx-gray-12,#111827)"}}
       "节点样式"]
      [:button {:on-click close-fn
                :style    {:background "transparent" :border "none"
                           :cursor "pointer" :font-size "18px" :line-height "1"
                           :color "var(--lx-gray-08,#9ca3af)" :padding "0"}}
       "×"]]

     ;; ── 文字 ──────────────────────────────────────────────────────────────
     (sp-title "文字")
     (sp-row
      (sp-select (get s :fontFamily "微软雅黑, Microsoft YaHei")
                 font-families #(st :fontFamily %) :width "96px")
      (sp-select (str (get s :fontSize 14))
                 font-sizes #(st :fontSize (js/parseInt %)) :width "58px")
      (sp-select (get s :textAlign "left")
                 text-aligns #(st :textAlign %) :width "72px"))
     (sp-row
      ;; text color swatch (A)
      [:label {:style {:cursor "pointer" :display "inline-flex"
                       :align-items "center" :gap "2px"
                       :border "1px solid var(--lx-gray-06,#d1d5db)"
                       :border-radius "4px" :padding "2px 6px"
                       :background "var(--ls-primary-background-color,#fff)"
                       :position "relative"}}
       [:span {:style {:font-weight "600" :font-size "13px"
                       :color "var(--lx-gray-11,#374151)"}} "A"]
       [:span {:style {:width "14px" :height "3px" :border-radius "2px"
                       :background (or (:color s) "#333333")
                       :display "block"}}]
       [:input {:type "color" :value (ensure-hex (:color s))
                :style {:position "absolute" :opacity "0"
                        :width "1px" :height "1px"}
                :on-change #(st :color (.. % -target -value))}]]
      (sp-toggle "B" (= "bold" (get s :fontWeight))
                 #(st :fontWeight (if (= "bold" (get s :fontWeight)) "normal" "bold"))
                 {:font-weight "bold"})
      (sp-toggle "I" (= "italic" (get s :fontStyle))
                 #(st :fontStyle (if (= "italic" (get s :fontStyle)) "normal" "italic"))
                 {:font-style "italic"})
      (sp-toggle "U" (= "underline" (get s :textDecoration))
                 #(st :textDecoration (if (= "underline" (get s :textDecoration))
                                        "none" "underline"))
                 {:text-decoration "underline"})
      (sp-toggle "S" (= "line-through" (get s :textDecoration))
                 #(st :textDecoration (if (= "line-through" (get s :textDecoration))
                                        "none" "line-through"))
                 {:text-decoration "line-through"}))

     ;; ── 边框 ──────────────────────────────────────────────────────────────
     (sp-title "边框")
     (sp-row
      (sp-label "颜色")
      (sp-color (:borderColor s) #(st :borderColor %))
      (sp-label "样式")
      (sp-select (get s :borderDasharray "none")
                 dash-options #(st :borderDasharray %) :width "68px"))
     (sp-row
      (sp-label "宽度")
      (sp-select (str (get s :borderWidth 2))
                 width-options #(st :borderWidth (js/parseInt %)) :width "58px")
      (sp-label "圆角")
      (sp-select (str (get s :borderRadius 5))
                 radius-options #(st :borderRadius (js/parseInt %)) :width "58px"))

     ;; ── 背景 ──────────────────────────────────────────────────────────────
     (sp-title "背景")
     (sp-row
      (sp-label "颜色")
      (sp-color (:fillColor s) #(st :fillColor %)))

     ;; ── 形状 ──────────────────────────────────────────────────────────────
     (sp-title "形状")
     (sp-row
      (sp-select (get s :shape "rectangle")
                 shape-options #(st :shape %) :width "150px"))

     ;; ── 线条 ──────────────────────────────────────────────────────────────
     (sp-title "线条")
     (sp-row
      (sp-label "颜色")
      (sp-color (:lineColor s) #(st :lineColor %))
      (sp-label "样式")
      (sp-select (get s :lineDasharray "none")
                 dash-options #(st :lineDasharray %) :width "68px"))
     (sp-row
      (sp-label "宽度")
      (sp-select (str (get s :lineWidth 2))
                 width-options #(st :lineWidth (js/parseInt %)) :width "58px")
      (sp-label "箭头")
      (sp-select (get s :lineMarkerDir "end")
                 marker-dirs #(do (st :showLineMarker true)
                                  (st :lineMarkerDir %))
                 :width "58px"))]))

;; ── Main component ────────────────────────────────────────────────────────────

(rum/defcs mind-map-editor
  "Core mind map canvas component.

   Props map:
     :map-id       – unique string identifier (used for localStorage key)
     :map-title    – display title string
     :on-back      – fn() navigate back
     :on-load-data – fn(map-id) → JSON-string | nil
     :on-save-data – fn(map-id, json-string)"
  < rum/reactive
  (rum/local nil   ::instance)
  (rum/local nil   ::container-ref)
  (rum/local nil   ::timer-id)
  (rum/local nil   ::resize-observer)
  (rum/local nil   ::file-input-ref)
  (rum/local nil   ::key-handler)
  (rum/local nil   ::focusin-handler)
  (rum/local nil   ::focusout-handler)
  ;; reactive toolbar state
  (rum/local false ::node-active?)
  (rum/local false ::node-is-root?)
  (rum/local false ::can-undo?)
  (rum/local false ::can-redo?)
  (rum/local 100   ::zoom-pct)
  (rum/local "logicalStructure" ::cur-layout)
  (rum/local false ::show-layout?)
  (rum/local false ::show-export?)
  (rum/local false ::readonly?)
  (rum/local false ::unsaved?)
  ;; context menu
  (rum/local nil   ::ctx-menu)
  (rum/local nil   ::ctx-handler)
  ;; style panel
  (rum/local false ::show-style-panel?)
  (rum/local {}    ::node-styles)
  {:did-mount
   (fn [state]
     (let [args         (-> state :rum/args first)
           map-id       (:map-id args)
           on-load-data (:on-load-data args)
           container    @(::container-ref state)
           MindMapCtor  (get-mind-map-ctor)]
       (when (and container MindMapCtor)
         (let [saved-json (or (when on-load-data (on-load-data map-id)) nil)
               init-data  (or (when saved-json
                                (try (let [p (js/JSON.parse saved-json)]
                                       (when (and p (.-data p) (.-text (.-data p))) p))
                                     (catch :default _ nil)))
                              (load-from-ls map-id)
                              default-data)
               dark?      (= "dark" (state/sub :ui/theme))
               theme-cfg  (if dark?
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
               instance   (MindMapCtor.
                           #js {:el                                   container
                                :data                                 init-data
                                :theme                                "default"
                                :themeConfig                          theme-cfg
                                :fit                                  true
                                :fitPadding                           60
                                :mousewheelAction                     "zoom"
                                :mousewheelZoomActionReverse          false
                                :scaleRatio                           0.15
                                :minZoomRatio                         15
                                :maxZoomRatio                         500
                                :readonly                             false
                                :enableAutoEnterTextEditWhenKeydown   true
                                :selectTextOnEnterEditText            true
                                :enableDblclickBackToRootNode         true
                                :textAutoWrapWidth                    280
                                :defaultInsertSecondLevelNodeText     "子节点"
                                :defaultInsertBelowSecondLevelNodeText "子节点"
                                :addHistoryTime                       150
                                :hoverRectColor                       (if dark?
                                                                        "rgba(99,102,241,0.7)"
                                                                        "rgba(30,41,59,0.2)")})
               timer      (js/setInterval
                           (fn []
                             (when-let [inst @(::instance state)]
                               (save-to-ls! map-id (.getData ^js inst))
                               (reset! (::unsaved? state) false)))
                           3000)
               ;; ResizeObserver: resize mind map when container changes size
               ro         (js/ResizeObserver.
                           (fn [entries]
                             (when-let [inst @(::instance state)]
                               (let [entry (aget entries 0)
                                     rect  (.-contentRect entry)
                                     w     (.-width rect)
                                     h     (.-height rect)]
                                 (when (and (> w 10) (> h 10))
                                   (.resize ^js inst w h))))))]
           ;; ── wire reactive events ────────────────────────────────────────
           (.on instance "back_forward"
                (fn [idx len]
                  (reset! (::can-undo? state) (> idx 0))
                  (reset! (::can-redo? state) (< idx (dec len)))))
           (.on instance "node_active"
                (fn [^js node active-list]
                  (let [active? (pos? (.-length active-list))]
                    (reset! (::node-active? state)  active?)
                    (reset! (::node-is-root? state) (boolean (and node (.-isRoot node))))
                    (when (and active? node)
                      (reset! (::node-styles state)
                              {:fontFamily     (.getStyle ^js node "fontFamily")
                               :fontSize       (.getStyle ^js node "fontSize")
                               :textAlign      (.getStyle ^js node "textAlign")
                               :color          (.getStyle ^js node "color")
                               :fontWeight     (.getStyle ^js node "fontWeight")
                               :fontStyle      (.getStyle ^js node "fontStyle")
                               :textDecoration (.getStyle ^js node "textDecoration")
                               :borderColor    (.getStyle ^js node "borderColor")
                               :borderWidth    (.getStyle ^js node "borderWidth")
                               :borderDasharray (.getStyle ^js node "borderDasharray")
                               :borderRadius   (.getStyle ^js node "borderRadius")
                               :fillColor      (.getStyle ^js node "fillColor")
                               :shape          (.getStyle ^js node "shape")
                               :lineColor      (.getStyle ^js node "lineColor")
                               :lineWidth      (.getStyle ^js node "lineWidth")
                               :lineDasharray  (.getStyle ^js node "lineDasharray")
                               :lineMarkerDir  (.getStyle ^js node "lineMarkerDir")})))))
           (.on instance "scale"
                (fn [s]
                  (reset! (::zoom-pct state)
                          (js/Math.round (* s 100)))))
           (.on instance "data_change"
                (fn [] (reset! (::unsaved? state) true)))
           (.observe ro container)
           (reset! (::instance state) instance)
           (reset! (::timer-id state) timer)
           (reset! (::resize-observer state) ro)
           ;; ── native keyboard interception (capture phase) ────────────────
           ;; Logseq's KeyboardShortcutHandler owns Tab/Enter/Delete/Ctrl+Z/Y
           ;; at the document-bubble level. We intercept in container-capture
           ;; (fires before document-bubble) so SimpleMindMap gets these keys.
           (let [key-handler
                 (fn [^js e]
                   (let [target       (.-target e)
                         text-input?  (or (= "INPUT"    (.-tagName target))
                                          (= "TEXTAREA" (.-tagName target))
                                          (.-isContentEditable target))]
                     (when-not (or (.-isComposing e) text-input?)
                       (let [key  (.-key e)
                             ctrl (or (.-ctrlKey e) (.-metaKey e))]
                         (when-let [cmd
                                    (cond
                                      (= key "Tab")                        "INSERT_CHILD_NODE"
                                      (= key "Enter")                      "INSERT_NODE"
                                      (and (.-shiftKey e) (= key "Backspace")) "REMOVE_CURRENT_NODE"
                                      (contains? #{"Delete" "Backspace"} key) "REMOVE_NODE"
                                      (and ctrl (= key "z"))               "BACK"
                                      (and ctrl
                                           (or (= key "y")
                                               (and (.-shiftKey e) (= key "Z")))) "FORWARD"
                                      (and ctrl (= key "g"))               "ADD_GENERALIZATION"
                                      :else nil)]
                           (.preventDefault e)
                           (.stopPropagation e)
                           (when-let [i @(::instance state)]
                             (.execCommand ^js i cmd)))))))
                 focusin-handler
                 (fn [_e] (state/set-block-component-editing-mode! true))
                 focusout-handler
                 (fn [^js e]
                   (when-not (and (.-relatedTarget e)
                                  (.contains container (.-relatedTarget e)))
                     (state/set-block-component-editing-mode! false)))]
             (.addEventListener container "keydown"  key-handler      true)
             (.addEventListener container "focusin"  focusin-handler  false)
             (.addEventListener container "focusout" focusout-handler false)
             (reset! (::key-handler state)      key-handler)
             (reset! (::focusin-handler state)  focusin-handler)
             (reset! (::focusout-handler state) focusout-handler))
           ;; ── right-click context menu ─────────────────────────────────────
           (let [ctx-handler
                 (fn [^js e]
                   (.preventDefault e)
                   (.stopPropagation e)
                   (reset! (::ctx-menu state)
                           {:x (.-clientX e) :y (.-clientY e)}))]
             (.addEventListener container "contextmenu" ctx-handler false)
             (reset! (::ctx-handler state) ctx-handler)))))
     state)
   :will-unmount
   (fn [state]
     (let [args         (-> state :rum/args first)
           map-id       (:map-id args)
           on-save-data (:on-save-data args)
           timer        @(::timer-id state)
           ro           @(::resize-observer state)
           instance     @(::instance state)
           container    @(::container-ref state)
           key-handler  @(::key-handler state)
           fi-handler   @(::focusin-handler state)
           fo-handler   @(::focusout-handler state)]
       (when timer (js/clearInterval timer))
       (when ro (.disconnect ^js ro))
       (when (and container key-handler)
         (.removeEventListener container "keydown"  key-handler      true)
         (.removeEventListener container "focusin"  fi-handler       false)
         (.removeEventListener container "focusout" fo-handler       false)
         (when-let [ctx-h @(::ctx-handler state)]
           (.removeEventListener container "contextmenu" ctx-h false)))
       (state/set-block-component-editing-mode! false)
       (when instance
         (let [data (.getData ^js instance)]
           (save-to-ls! map-id data)
           (when on-save-data
             (on-save-data map-id (js/JSON.stringify data)))
           (reset! (::unsaved? state) false)
           (.destroy ^js instance))))
     state)}
  [state {:keys [map-id map-title on-back _on-load-data _on-save-data]}]
  (let [*container    (::container-ref state)
        *instance     (::instance state)
        *file-input   (::file-input-ref state)
        node-active?  (rum/react (::node-active? state))
        node-is-root? (rum/react (::node-is-root? state))
        can-undo?     (rum/react (::can-undo? state))
        can-redo?     (rum/react (::can-redo? state))
        zoom-pct      (rum/react (::zoom-pct state))
        cur-layout    (rum/react (::cur-layout state))
        show-layout?  (rum/react (::show-layout? state))
        show-export?  (rum/react (::show-export? state))
        readonly?     (rum/react (::readonly? state))
        unsaved?      (rum/react (::unsaved? state))
        ctx-menu       (rum/react (::ctx-menu state))
        show-style?    (rum/react (::show-style-panel? state))
        node-styles    (rum/react (::node-styles state))
        cmd!           (fn [c] (when-let [i @*instance] (.execCommand ^js i c)))
        set-style!     (fn [prop value]
                         (when-let [i @*instance]
                           (let [node (aget (.. ^js i -renderer -activeNodeList) 0)]
                             (when node
                               (.execCommand ^js i "SET_NODE_STYLE" node prop value)
                               (swap! (::node-styles state) assoc (keyword prop) value)))))]

    [:div.mind-map-wrapper
     {:style {:width "100%" :height "100%" :display "flex" :flexDirection "column"
              :position "relative"}}

     ;; ── hidden file input for import ─────────────────────────────────────────
     [:input
      {:type      "file"
       :accept    ".json"
       :style     {:display "none"}
       :ref       (fn [el] (reset! *file-input el))
       :on-change (fn [e]
                    (when-let [file (aget (.. e -target -files) 0)]
                      (import-from-file! @*instance file))
                    ;; reset so same file can be re-imported
                    (set! (.. e -target -value) ""))}]

     ;; ── toolbar ─────────────────────────────────────────────────────────────
     [:div.mind-map-toolbar
      {:style {:display      "flex"
               :alignItems   "center"
               :gap          "2px"
               :padding      "4px 10px"
               :borderBottom "1px solid var(--lx-gray-05,#e5e7eb)"
               :background   "var(--ls-secondary-background-color,#f9fafb)"
               :flexShrink   "0"
               :flexWrap     "wrap"}}

      ;; ← 返回 + 标题
      (tb-btn "← 返回" "保存并返回"
              (fn []
                (when-let [i @*instance]
                  (save-to-ls! map-id (.getData ^js i)))
                (when on-back (on-back))))
      [:span
       {:style {:padding      "4px 8px"
                :fontSize     "13px"
                :fontWeight   "600"
                :maxWidth     "180px"
                :overflow     "hidden"
                :textOverflow "ellipsis"
                :whiteSpace   "nowrap"
                :color        "var(--lx-gray-11,#374151)"}}
       (or map-title "思维导图")
       (when unsaved?
         [:span {:style {:color      "#f59e0b"
                         :marginLeft "4px"
                         :fontSize   "10px"
                         :title      "有未保存的更改（3 秒后自动保存）"}}
          "●"])]

      (tb-sep)

      ;; 撤销 / 重做
      (tb-btn "↩ 撤销" "撤销 (Ctrl+Z)"   #(cmd! "BACK")    :disabled? (or readonly? (not can-undo?)))
      (tb-btn "↪ 重做" "重做 (Ctrl+Y)"   #(cmd! "FORWARD") :disabled? (or readonly? (not can-redo?)))

      (tb-sep)

      ;; 节点操作（只读时禁用）
      (tb-btn "⊕ 同级" "插入同级节点 (Enter)"
              #(cmd! "INSERT_NODE") :disabled? (or readonly? (not node-active?)))
      (tb-btn "⊕ 子节点" "插入子节点 (Tab)"
              #(cmd! "INSERT_CHILD_NODE") :disabled? (or readonly? (not node-active?)))
      (tb-btn "⊖ 删除" "删除节点 (Delete)"
              #(cmd! "REMOVE_NODE") :disabled? (or readonly? (not node-active?)))

      (tb-sep)

      ;; 上移 / 下移
      (tb-btn "↑" "上移节点"
              #(cmd! "UP_NODE") :disabled? (or readonly? (not node-active?)))
      (tb-btn "↓" "下移节点"
              #(cmd! "DOWN_NODE") :disabled? (or readonly? (not node-active?)))

      (tb-sep)

      ;; 缩放控制
      (tb-btn "−" "缩小"
              #(when-let [i @*instance]
                 (let [v (.-view ^js i)
                       s (.-scale ^js v)]
                   (.setScale ^js v (max 0.15 (* s 0.85))))))
      [:span
       {:style {:fontSize  "12px"
                :minWidth  "42px"
                :textAlign "center"
                :color     "var(--lx-gray-10,#6b7280)"}}
       (str zoom-pct "%")]
      (tb-btn "+" "放大"
              #(when-let [i @*instance]
                 (let [v (.-view ^js i)
                       s (.-scale ^js v)]
                   (.setScale ^js v (min 5 (* s 1.18))))))
      (tb-btn "⊡" "适应画布"
              #(when-let [i @*instance] (.fit ^js (.-view ^js i))))

      (tb-sep)

      ;; 布局选择
      (tb-dropdown
       (str "布局 ▾")
       "切换布局"
       show-layout?
       #(do (swap! (::show-layout? state) not)
            (reset! (::show-export? state) false))
       (for [[k label] layouts]
         {:label    label
          :active?  (= k cur-layout)
          :on-click (fn []
                      (when-let [i @*instance]
                        (.setLayout ^js i k))
                      (reset! (::cur-layout state) k)
                      (reset! (::show-layout? state) false))}))

      (tb-sep)

      ;; 导出菜单
      (tb-dropdown
       "导出 ▾"
       "导出思维导图"
       show-export?
       #(do (swap! (::show-export? state) not)
            (reset! (::show-layout? state) false))
       [{:label    "导出 JSON"
         :on-click (fn []
                     (export-json! @*instance map-title)
                     (reset! (::show-export? state) false))}
        {:label    "导出 PNG"
         :on-click (fn []
                     (export-image! @*instance "png" map-title)
                     (reset! (::show-export? state) false))}
        {:label    "导出 SVG"
         :on-click (fn []
                     (export-image! @*instance "svg" map-title)
                     (reset! (::show-export? state) false))}])

      ;; 导入 JSON
      (tb-btn "导入" "从 JSON 文件导入"
              (fn []
                (when-let [el @*file-input]
                  (.click el)))
              :disabled? readonly?)

      (tb-sep)

      ;; 关联线（仅 AssociativeLine 插件加载后显示）
      (when (and @*instance (.-associativeLine ^js @*instance))
        (tb-sep))
      (when (and @*instance (.-associativeLine ^js @*instance))
        (tb-btn "↔ 关联线" "添加关联线：选中源节点后点击目标节点"
                #(when-let [i @*instance]
                   (.createLineFromActiveNode ^js (.-associativeLine ^js i)))
                :disabled? (not node-active?)))

      (tb-sep)

      ;; 节点样式面板
      (tb-btn "⊞ 样式" "打开节点样式面板"
              #(swap! (::show-style-panel? state) not)
              :active? show-style?)

      (tb-sep)

      ;; 只读模式切换
      (tb-btn (if readonly? "✎ 编辑" "👁 只读")
              (if readonly? "切换到编辑模式" "切换到只读模式")
              (fn []
                (let [next-ro (not readonly?)]
                  (reset! (::readonly? state) next-ro)
                  (when-let [i @*instance]
                    ;; v0.14 API: setMode replaces setReadonly
                    (.setMode ^js i (if next-ro "readonly" "edit")))))
              :active? readonly?)]

     ;; ── canvas + style panel ────────────────────────────────────────────────
     [:div {:style {:flex "1" :display "flex" :overflow "hidden"}}
      ;; canvas
      [:div
       {:ref   (fn [el] (reset! *container el))
        :style {:flex "1" :min-width "0" :overflow "hidden"}}]
      ;; style panel (right sidebar)
      (when show-style?
        (node-style-panel
         node-styles
         set-style!
         #(reset! (::show-style-panel? state) false)))]

     ;; ── context menu ────────────────────────────────────────────────────────
     (ctx-menu-panel ctx-menu node-active? node-is-root?
                     #(reset! (::ctx-menu state) nil)
                     cmd! *instance)

     ;; ── status bar ──────────────────────────────────────────────────────────
     [:div
      {:style {:display    "flex"
               :alignItems "center"
               :gap        "12px"
               :padding    "3px 12px"
               :borderTop  "1px solid var(--lx-gray-04,#f3f4f6)"
               :background "var(--ls-secondary-background-color,#f9fafb)"
               :flexShrink "0"
               :fontSize   "11px"
               :color      "var(--lx-gray-09,#9ca3af)"}}
      (if readonly?
        [:span {:style {:color "#f59e0b" :fontWeight "600"}} "只读模式"]
        (list
         [:span {:key "enter"} "Enter: 同级"]
         [:span {:key "tab"} "Tab: 子节点"]
         [:span {:key "del"} "Delete: 删除"]
         [:span {:key "sdel"} "Shift+⌫: 仅删当前"]
         [:span {:key "undo"} "Ctrl+Z/Y: 撤销/重做"]
         [:span {:key "ctrlg"} "Ctrl+G: 概要"]
         [:span {:key "rclick"} "右键: 更多操作"]))
      [:div {:style {:flex "1"}}]
      (when unsaved?
        [:span {:style {:color "#f59e0b"}} "未保存"])
      [:span (str zoom-pct "%")]]]))

;; Export for shadow.lazy loadable
(def ^:export editor mind-map-editor)
