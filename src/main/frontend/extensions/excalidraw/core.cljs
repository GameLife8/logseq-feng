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
;;   pageTitle          – current whiteboard title string
;;   saveAndBack        – fn() save + navigate back
;;   onRename           – fn(string) called with new title
;;   renderTags         – fn() → ReactElement  (Rum tags-bar from main bundle)
;;   onShowLinkedBlocks – fn(element-id-string) open linked-blocks panel for selected el
;;   selElId            – string ID of the single selected element | nil
(def ^:private toolbar-buttons
  (fn toolbar-buttons [^js props]
    (let [page-title          (gobj/get props "pageTitle")
          save-and-back!      (gobj/get props "saveAndBack")
          on-rename           (gobj/get props "onRename")
          render-tags         (gobj/get props "renderTags")
          on-show-linked      (gobj/get props "onShowLinkedBlocks")
          sel-el-id           (gobj/get props "selElId")
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

         ;; 🔗 链接块 — shown only when exactly one element is selected
         (when (and sel-el-id on-show-linked)
           (js/React.createElement
            "button"
            #js {:title   "管理此元素的关联块和备注"
                 :onClick (fn []
                            (js/console.log "[wb-toolbar] 🔗 clicked for el:" sel-el-id)
                            (on-show-linked sel-el-id))
                 :style   #js {:display "flex" :alignItems "center" :gap "4px"
                               :padding "5px 10px"
                               :background "#6366f1" :color "#fff"
                               :border "none" :borderRadius "6px"
                               :cursor "pointer" :fontSize "13px"
                               :whiteSpace "nowrap"}}
            "🔗 链接块"))

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
     :page-uuid          – UUID string of the whiteboard page
     :page-title         – Display title string shown in the toolbar chip
     :on-back            – fn() called to navigate back (after save completes)
     :on-show-linked-blocks – fn(element-id-str) open linked-blocks panel in parent
     :on-selection-change   – fn(element-id-str|nil) fired on every selection change
     :on-api-ready       – fn called with the ExcalidrawImperativeAPI once mounted
     :on-load-data       – fn(page-uuid) → JSON-string | nil  (reads from DB)
     :on-save-data       – fn(page-uuid, json-string)          (writes to DB)"
  < rum/static
  (rum/local nil    ::api)
  (rum/local nil    ::sel-el-id)   ; ID string of selected element, or nil
  (rum/local false  ::dirty?)
  (rum/local nil    ::timer-id)
  (rum/local nil    ::library-items)
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
  [state {:keys [page-uuid page-title on-back on-api-ready
                 on-show-linked-blocks on-selection-change
                 on-load-data on-save-data render-tags on-rename
                 validate-embeddable default-font-family]}]
  (let [*api        (::api state)
        *sel-el-id  (::sel-el-id state)
        *dirty?     (::dirty? state)
        *library    (::library-items state)
        ;; Merge saved canvas appState with configured defaults (e.g. font-family).
        ;; The saved state takes priority so user's last choice is preserved.
        apply-font  (fn [data]
                      (when data
                        (if default-font-family
                          (let [astate (or (.-appState data) #js {})
                                ;; Only set if not already saved in this canvas
                                cur-font (gobj/get astate "currentItemFontFamily")]
                            (if cur-font
                              data
                              (js/Object.assign
                               #js {} data
                               #js {:appState (js/Object.assign #js {} astate
                                                                #js {:currentItemFontFamily default-font-family})})))
                          data)))
        init-data   (apply-font
                     (or (when on-load-data
                           (try
                             (when-let [json-str (on-load-data page-uuid)]
                               (js/JSON.parse json-str))
                             (catch :default _ nil)))
                         (load-from-ls page-uuid)))
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
           ;; validateEmbeddable controls Excalidraw's own domain whitelist.
           ;; Value comes from the user's excalidraw settings config:
           ;;   false  → block all (default when whitelist is empty)
           ;;   true   → allow all (whitelist contains "*")
           ;;   fn(url)→ custom check against whitelist domains
           :validateEmbeddable (or validate-embeddable false)
           :libraryItems     (or @*library #js [])
           :onLibraryChange  (fn [^js items]
                               (reset! *library items)
                               (.setItem js/localStorage (lib-key)
                                         (js/JSON.stringify items)))
           :onChange         (fn [_elements ^js app-state _files]
                               (reset! *dirty? true)
                               ;; Track selected element ID for the 🔗 toolbar button
                               (let [sel-ids (js/Object.keys
                                              (or (gobj/get app-state "selectedElementIds") #js {}))
                                     sel-id  (when (= 1 (.-length sel-ids)) (aget sel-ids 0))]
                                 (js/console.log "[wb] onChange sel-el-id:" sel-id)
                                 (reset! *sel-el-id sel-id)
                                 (when on-selection-change (on-selection-change sel-id))))
           ;; Top-right: collapsible toolbar (collapsed by default → click "☰ 工具" to expand)
           :renderTopRightUI
           (fn []
             (js/React.createElement
              toolbar-buttons
              #js {:pageTitle          page-title
                   :saveAndBack        save-and-back!
                   :onRename           on-rename
                   :renderTags         render-tags
                   :onShowLinkedBlocks (fn [el-id]
                                         (when on-show-linked-blocks
                                           (on-show-linked-blocks el-id)))
                   :selElId            @*sel-el-id}))})]))

;; Export for shadow.lazy loadable
(def ^:export editor excalidraw-editor)
