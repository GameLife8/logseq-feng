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

;; ── Layouts ───────────────────────────────────────────────────────────────────

(def ^:private layouts
  [["logicalStructure"      "逻辑结构"]
   ["mindMap"               "思维导图"]
   ["catalogOrganization"   "目录组织"]
   ["organizationStructure" "组织结构"]
   ["timeline"              "时间线"]
   ["verticalTimeline"      "垂直时间线"]
   ["fishbone"              "鱼骨图"]])

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
  (rum/local false ::can-undo?)
  (rum/local false ::can-redo?)
  (rum/local 100   ::zoom-pct)
  (rum/local "logicalStructure" ::cur-layout)
  (rum/local false ::show-layout?)
  (rum/local false ::show-export?)
  (rum/local false ::readonly?)
  (rum/local false ::unsaved?)
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
                (fn [_node active-list]
                  (reset! (::node-active? state)
                          (pos? (.-length active-list)))))
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
                                      (contains? #{"Delete" "Backspace"} key) "REMOVE_NODE"
                                      (and ctrl (= key "z"))               "BACK"
                                      (and ctrl
                                           (or (= key "y")
                                               (and (.-shiftKey e) (= key "Z")))) "FORWARD"
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
             (reset! (::focusout-handler state) focusout-handler)))))
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
         (.removeEventListener container "focusout" fo-handler       false))
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
  (let [*container   (::container-ref state)
        *instance    (::instance state)
        *file-input  (::file-input-ref state)
        node-active? (rum/react (::node-active? state))
        can-undo?    (rum/react (::can-undo? state))
        can-redo?    (rum/react (::can-redo? state))
        zoom-pct     (rum/react (::zoom-pct state))
        cur-layout   (rum/react (::cur-layout state))
        show-layout? (rum/react (::show-layout? state))
        show-export? (rum/react (::show-export? state))
        readonly?    (rum/react (::readonly? state))
        unsaved?     (rum/react (::unsaved? state))
        cmd!         (fn [c] (when-let [i @*instance] (.execCommand ^js i c)))]

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

     ;; ── canvas ──────────────────────────────────────────────────────────────
     [:div
      {:ref   (fn [el] (reset! *container el))
       :style {:flex "1" :width "100%" :overflow "hidden"}}]

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
         [:span {:key "enter"} "Enter: 同级节点"]
         [:span {:key "tab"} "Tab: 子节点"]
         [:span {:key "del"} "Delete: 删除"]
         [:span {:key "undo"} "Ctrl+Z/Y: 撤销/重做"]
         [:span {:key "dbl"} "双击空白: 回到根节点"]))
      [:div {:style {:flex "1"}}]
      (when unsaved?
        [:span {:style {:color "#f59e0b"}} "未保存"])
      [:span (str zoom-pct "%")]]]))

;; Export for shadow.lazy loadable
(def ^:export editor mind-map-editor)
