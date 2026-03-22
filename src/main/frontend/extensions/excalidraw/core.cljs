(ns frontend.extensions.excalidraw.core
  "Excalidraw React component – loaded as a separate shadow-cljs lazy module.
   @excalidraw/excalidraw is loaded as a webpack bundle (excalidraw-bundle.js)
   and exposed as window.ExcalidrawLib global.

   Canvas data persistence strategy:
   - Fast write cache  : native localStorage, saved every 3 s while editing
   - Authoritative store: Logseq DB (via on-save-data / on-load-data callbacks)
   - On back  : explicit save to localStorage + DB BEFORE navigation (on-back callback)
   - On unmount: fallback save to localStorage

   IMPORTANT: Excalidraw is a React.memo-wrapped function component.
   Using :ref throws 'Function components cannot be given refs' and never fires.
   The correct API is the :excalidrawAPI prop, which accepts a callback fn(api)."
  (:require ["@excalidraw/excalidraw" :refer [Excalidraw]]
            [frontend.extensions.excalidraw.api :as ex-api]
            [frontend.state :as state]
            [goog.object :as gobj]
            [rum.core :as rum]))

;; ── localStorage fast cache ───────────────────────────────────────────────────

(defn- ls-key [page-uuid] (str "whiteboard-data-" page-uuid))

(defn- load-from-ls
  "Load canvas data from localStorage.
   Handles both raw JSON (current) and legacy pr-str-wrapped JSON (old storage)."
  [page-uuid]
  (when-let [raw (.getItem js/localStorage (ls-key page-uuid))]
    (try
      (let [parsed (js/JSON.parse raw)]
        (if (string? parsed) (js/JSON.parse parsed) parsed))
      (catch :default _ nil))))

(defn- save-to-ls! [page-uuid ^js api]
  (when (and api page-uuid)
    (let [els    (.getSceneElements api)
          astate (.getAppState api)
          data   (js/JSON.stringify
                  #js {:elements els
                       :appState #js {:scrollX (gobj/get astate "scrollX")
                                      :scrollY (gobj/get astate "scrollY")
                                      :zoom    (gobj/get astate "zoom")}})]
      (.setItem js/localStorage (ls-key page-uuid) data))))

(defn- canvas-json [^js api]
  (let [els    (.getSceneElements api)
        astate (.getAppState api)]
    (js/JSON.stringify
     #js {:elements els
          :appState #js {:scrollX (gobj/get astate "scrollX")
                         :scrollY (gobj/get astate "scrollY")
                         :zoom    (gobj/get astate "zoom")}})))

;; ── library (shared across whiteboards, per graph) ───────────────────────────

(defn- lib-key []
  (str "wb-library-" (state/get-current-repo)))

;; ── collapsible toolbar (module-level stable React component ref) ────────────
;;
;; Defined OUTSIDE rum/defcs so React always sees the same function reference.
;; This avoids remounting (and state loss) on every Rum re-render.
;;
;; Props (JS object):
;;   pageTitle     – current whiteboard title string
;;   saveAndBack   – fn() save + navigate back
;;   onRename      – fn(string) called with new title
;;   renderTags    – fn() → ReactElement  (Rum tags-bar from main bundle)
;;   onInsertBlock – fn()
;;   onBlockClick  – fn(block-id-string)
;;   selEl         – current selected Excalidraw element map | nil
(def ^:private toolbar-buttons
  (fn toolbar-buttons [^js props]
    (let [page-title      (gobj/get props "pageTitle")
          save-and-back!  (gobj/get props "saveAndBack")
          on-rename       (gobj/get props "onRename")
          render-tags     (gobj/get props "renderTags")
          on-insert-block (gobj/get props "onInsertBlock")
          on-block-click  (gobj/get props "onBlockClick")
          sel-el          (gobj/get props "selEl")
          ;; hooks – must be unconditionally at top level
          [open?     set-open!]    (rum/use-state false)
          [editing?  set-editing!] (rum/use-state false)
          [input-val set-input!]   (rum/use-state page-title)
          commit!  (fn []
                     (let [t (.trim input-val)]
                       (when (and (seq t) on-rename) (on-rename t)))
                     (set-editing! false))]
      ;; Sync the rename input when the upstream page-title prop changes
      (js/React.useEffect (fn [] (set-input! page-title) js/undefined)
                          #js [page-title])

      (if-not open?
        ;; ── collapsed: single "☰ 工具" button ──────────────────────────────
        (js/React.createElement
         "button"
         #js {:title   "展开工具栏"
              :onClick #(set-open! true)
              :style   #js {:display "flex" :alignItems "center" :gap "5px"
                            :padding "5px 10px"
                            :background "var(--lx-gray-03,#f3f4f6)"
                            :color "var(--lx-gray-12,#111)"
                            :border "1px solid var(--lx-gray-06,#e5e7eb)"
                            :borderRadius "6px" :cursor "pointer"
                            :fontSize "13px" :whiteSpace "nowrap"}}
         "☰ 工具")

        ;; ── expanded: full button row ────────────────────────────────────────
        (js/React.createElement
         "div"
         #js {:style #js {:display "flex" :gap "6px" :alignItems "center"}}

         ;; ← 返回
         (js/React.createElement
          "button"
          #js {:title   "保存并返回白板列表"
               :onClick save-and-back!
               :style   #js {:display "flex" :alignItems "center" :gap "4px"
                             :padding "5px 10px"
                             :background "var(--lx-gray-03,#f3f4f6)"
                             :color "var(--lx-gray-12,#111)"
                             :border "1px solid var(--lx-gray-06,#e5e7eb)"
                             :borderRadius "6px" :cursor "pointer"
                             :fontSize "13px" :whiteSpace "nowrap"}}
          "← 返回")

         ;; Title: editable chip (click to rename, Enter/Escape to confirm/cancel)
         (if editing?
           (js/React.createElement
            "input"
            #js {:type      "text"
                 :autoFocus true
                 :value     input-val
                 :onChange  (fn [^js e] (set-input! (.. e -target -value)))
                 :onBlur    (fn [] (js/setTimeout commit! 150))
                 :onKeyDown (fn [^js e]
                              (case (.-key e)
                                "Enter"  (commit!)
                                "Escape" (do (set-input! page-title)
                                             (set-editing! false))
                                nil))
                 :style     #js {:padding "4px 8px"
                                 :border "1px solid var(--lx-gray-07,#d1d5db)"
                                 :borderRadius "6px" :fontSize "13px"
                                 :width "140px" :outline "none"}})
           (js/React.createElement
            "span"
            #js {:title   "点击重命名"
                 :onClick (fn [] (set-input! page-title) (set-editing! true))
                 :style   #js {:padding "4px 10px"
                               :background "var(--lx-gray-02,#f9fafb)"
                               :border "1px solid var(--lx-gray-05,#e5e7eb)"
                               :borderRadius "6px" :fontSize "13px"
                               :fontWeight "600" :maxWidth "180px"
                               :overflow "hidden" :textOverflow "ellipsis"
                               :whiteSpace "nowrap" :cursor "pointer"}}
            page-title))

         ;; + 插入块
         (js/React.createElement
          "button"
          #js {:title   "搜索并插入 Logseq 块卡片到画布"
               :onClick (fn [] (when on-insert-block (on-insert-block)))
               :style   #js {:display "flex" :alignItems "center" :gap "4px"
                             :padding "5px 10px"
                             :background "#6366f1" :color "#fff"
                             :border "none" :borderRadius "6px"
                             :cursor "pointer" :fontSize "13px"
                             :whiteSpace "nowrap"}}
          "+ 插入块")

         ;; → 侧边栏 (only when a block card is selected on the canvas)
         (when (and sel-el on-block-click)
           (let [bid (some-> sel-el
                             (gobj/get "customData")
                             (gobj/get "blockId"))]
             (when bid
               (js/React.createElement
                "button"
                #js {:title   "在侧边栏中打开此块"
                     :onClick (fn [] (on-block-click bid))
                     :style   #js {:display "flex" :alignItems "center" :gap "4px"
                                   :padding "5px 10px"
                                   :background "var(--lx-gray-03,#f3f4f6)"
                                   :color "var(--lx-gray-12,#111)"
                                   :border "1px solid var(--lx-gray-06,#e5e7eb)"
                                   :borderRadius "6px" :cursor "pointer"
                                   :fontSize "13px" :whiteSpace "nowrap"}}
                "→ 侧边栏"))))

         ;; × 收起
         (js/React.createElement
          "button"
          #js {:title   "收起工具栏"
               :onClick #(set-open! false)
               :style   #js {:display "flex" :alignItems "center"
                             :padding "5px 8px"
                             :background "var(--lx-gray-03,#f3f4f6)"
                             :color "var(--lx-gray-12,#111)"
                             :border "1px solid var(--lx-gray-06,#e5e7eb)"
                             :borderRadius "6px" :cursor "pointer"
                             :fontSize "14px" :lineHeight "1" :opacity "0.6"}}
          "×"))))))



(rum/defcs excalidraw-editor
  "Core Excalidraw canvas component.

   Props map:
     :page-uuid       – UUID string of the whiteboard page
     :page-title      – Display title string shown in the toolbar chip
     :on-back         – fn() called to navigate back (after save completes)
     :on-block-click  – fn called with block-id-string to open block in sidebar
     :on-api-ready    – fn called with the ExcalidrawImperativeAPI once mounted
     :on-insert-block – fn called when the user clicks '+ 插入块' in canvas toolbar
     :on-load-data    – fn(page-uuid) → JSON-string | nil  (reads from DB)
     :on-save-data    – fn(page-uuid, json-string)          (writes to DB)

   Select a Logseq block card, then click '→ 侧边栏' in the top-right toolbar to open it."
  < rum/static
  (rum/local nil   ::api)
  (rum/local nil   ::selected-block-el)
  (rum/local false ::dirty?)
  (rum/local nil   ::timer-id)
  (rum/local nil   ::library-items)
  {:did-mount
   (fn [state]
     (let [*timer   (::timer-id state)
           *dirty?  (::dirty? state)
           *api     (::api state)
           *library (::library-items state)
           p-uuid   (-> state :rum/args first :page-uuid)
           timer    (js/setInterval
                     (fn []
                       (when @*dirty?
                         (save-to-ls! p-uuid @*api)
                         (reset! *dirty? false)))
                     3000)]
       (reset! *timer timer)
       (when-let [raw (.getItem js/localStorage (lib-key))]
         (try (reset! *library (js/JSON.parse raw))
              (catch :default _ nil)))
       ;; Inject/update CSS: hide shortcut keys + compact context menu
       (let [el (or (.getElementById js/document "excalidraw-custom-css")
                    (let [new-el (.createElement js/document "style")]
                      (set! (.-id new-el) "excalidraw-custom-css")
                      (.. js/document -head (appendChild new-el))
                      new-el))]
         (set! (.-textContent el)
               (str ".excalidraw .dropdown-menu-item__shortcut { display: none !important; }\n"
                    ".excalidraw .context-menu-item__shortcut { display: none !important; }\n"
                    ".excalidraw .context-menu-option__shortcut { display: none !important; }\n"
                    ".excalidraw .context-menu-item-separator { display: none !important; }\n"
                    ".excalidraw .context-menu-item__label { font-size: 12px !important; }\n"
                    ".excalidraw .context-menu-item { min-height: 28px !important; }"))))
     state)
   :will-unmount
   (fn [state]
     (let [api     @(::api state)
           timer   @(::timer-id state)
           p-uuid  (-> state :rum/args first :page-uuid)
           save-fn (-> state :rum/args first :on-save-data)]
       (when timer (js/clearInterval timer))
       (when api
         (save-to-ls! p-uuid api)
         (when save-fn (save-fn p-uuid (canvas-json api)))))
     state)}
  [state {:keys [page-uuid page-title on-back on-block-click on-api-ready
                 on-insert-block on-load-data on-save-data render-tags on-rename]}]
  (let [*api        (::api state)
        *sel-el     (::selected-block-el state)
        *dirty?     (::dirty? state)
        *library    (::library-items state)
        init-data   (or (when on-load-data
                          (try
                            (when-let [json-str (on-load-data page-uuid)]
                              (js/JSON.parse json-str))
                            (catch :default _ nil)))
                        (load-from-ls page-uuid))
        save-and-back!
        (fn []
          (let [api @*api]
            (when api
              (save-to-ls! page-uuid api)
              (when on-save-data (on-save-data page-uuid (canvas-json api)))))
          (when on-back (on-back)))]

    [:div.excalidraw-wrapper
     {:style {:width "100%" :height "100%" :position "relative"}}

     (js/React.createElement
      Excalidraw
      #js {;; ── IMPORTANT: use :excalidrawAPI, NOT :ref ──────────────────────
           :excalidrawAPI    (fn [^js api]
                               (reset! *api api)
                               (when on-api-ready (on-api-ready api))
                               ;; Fallback: if scene empty after init (timing race with DB),
                               ;; reload from DB first, then localStorage.
                               (when (zero? (.-length (.getSceneElements api)))
                                 (when-let [data (or (when on-load-data
                                                       (try (when-let [s (on-load-data page-uuid)]
                                                              (js/JSON.parse s))
                                                            (catch :default _ nil)))
                                                     (load-from-ls page-uuid))]
                                   (.updateScene api #js {:elements (.-elements data)
                                                          :appState (.-appState data)}))))
           :initialData      (or init-data #js {})
           :langCode         "zh-CN"
           :theme            (if (= "dark" (state/sub :ui/theme)) "dark" "light")
           :UIOptions        #js {:canvasActions #js {:export    false
                                                      :loadScene false}}
           :libraryItems     (or @*library #js [])
           :onLibraryChange  (fn [^js items]
                               (reset! *library items)
                               (.setItem js/localStorage (lib-key)
                                         (js/JSON.stringify items)))
           :onChange         (fn [_elements _app-state _files]
                               (reset! *dirty? true)
                               (reset! *sel-el (ex-api/get-selected-block-element @*api)))
           ;; Top-right: collapsible toolbar (collapsed by default → click "☰ 工具" to expand)
           :renderTopRightUI
           (fn []
             (js/React.createElement
              toolbar-buttons
              #js {:pageTitle     page-title
                   :saveAndBack   save-and-back!
                   :onRename      on-rename
                   :renderTags    render-tags
                   :onInsertBlock (fn [] (when on-insert-block (on-insert-block)))
                   :onBlockClick  on-block-click
                   :selEl         @*sel-el}))})]))

;; Export for shadow.lazy loadable
(def ^:export editor excalidraw-editor)
