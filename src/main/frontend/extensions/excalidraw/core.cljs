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

;; ── Excalidraw component ──────────────────────────────────────────────────────

(rum/defcs excalidraw-editor
  "Core Excalidraw canvas component.

   Props map:
     :page-uuid       – UUID string of the whiteboard page
     :page-title      – Display title string shown in the toolbar chip
     :on-back         – fn() called to navigate back (after save completes)
     :on-block-click  – fn called with block-id-string when a card is clicked
     :on-api-ready    – fn called with the ExcalidrawImperativeAPI once mounted
     :on-insert-block – fn called when the user clicks '+ 插入块' in canvas toolbar
     :on-load-data    – fn(page-uuid) → JSON-string | nil  (reads from DB)
     :on-save-data    – fn(page-uuid, json-string)          (writes to DB)"
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
       ;; Inject/update CSS: hide shortcut keys in Excalidraw menus
       ;; Always update content so new builds take effect immediately
       (let [el (or (.getElementById js/document "excalidraw-custom-css")
                    (let [new-el (.createElement js/document "style")]
                      (set! (.-id new-el) "excalidraw-custom-css")
                      (.. js/document -head (appendChild new-el))
                      new-el))]
         (set! (.-textContent el)
               (str ".excalidraw .dropdown-menu-item__shortcut { display: none !important; }
"
                    ".excalidraw .context-menu-item__shortcut { display: none !important; }
"
                    ".excalidraw .context-menu-option__shortcut { display: none !important; }"))))
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
                 on-insert-block on-load-data on-save-data render-tags]}]
  (let [*api      (::api state)
        *sel-el   (::selected-block-el state)
        *dirty?   (::dirty? state)
        *library  (::library-items state)
        init-data (or (when on-load-data
                        (try
                          (when-let [json-str (on-load-data page-uuid)]
                            (js/JSON.parse json-str))
                          (catch :default _ nil)))
                      (load-from-ls page-uuid))
        ;; Save then navigate – called from the back button
        save-and-back!
        (fn []
          (let [api @*api]
            (when api
              (save-to-ls! page-uuid api)
              (when on-save-data (on-save-data page-uuid (canvas-json api)))))
          ;; on-back triggers notification + route change (in main bundle)
          (when on-back (on-back)))]

    [:div.excalidraw-wrapper
     {:style {:width "100%" :height "100%" :position "relative"}}

     (js/React.createElement
      Excalidraw
      #js {;; ── IMPORTANT: use :excalidrawAPI, NOT :ref ──────────────────────
           ;; Excalidraw is React.memo(FunctionComponent). Passing :ref warns
           ;; "Function components cannot be given refs" and never fires.
           ;; :excalidrawAPI accepts a callback fn(api) and is the correct API.
           :excalidrawAPI    (fn [^js api]
                               (reset! *api api)
                               (when on-api-ready (on-api-ready api)))
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
           :onPointerUp      (fn [_active-tool _pointer-state]
                               (when-let [el @*sel-el]
                                 (let [bid (some-> el
                                                   (gobj/get "customData")
                                                   (gobj/get "blockId"))]
                                   (when (and (seq bid) on-block-click)
                                     (on-block-click bid)))))
           ;; Top-right: back + title + insert block + sidebar
           :renderTopRightUI
           (fn []
             (let [sel-el @*sel-el
                   bid    (some-> sel-el (gobj/get "customData") (gobj/get "blockId"))
                   title  (some-> sel-el (gobj/get "customData") (gobj/get "blockTitle"))]
               (js/React.createElement
                "div"
                #js {:style #js {:display "flex" :gap "6px" :alignItems "center"}}
                ;; ← 返回
                (js/React.createElement
                 "button"
                 #js {:title   "保存并返回白板列表"
                      :onClick save-and-back!
                      :style   #js {:display      "flex"
                                    :alignItems   "center"
                                    :gap          "4px"
                                    :padding      "5px 10px"
                                    :background   "var(--lx-gray-03,#f3f4f6)"
                                    :color        "var(--lx-gray-12,#111)"
                                    :border       "1px solid var(--lx-gray-06,#e5e7eb)"
                                    :borderRadius "6px"
                                    :cursor       "pointer"
                                    :fontSize     "13px"
                                    :whiteSpace   "nowrap"}}
                 "← 返回")
                ;; Title chip
                (when (seq page-title)
                  (js/React.createElement
                   "span"
                   #js {:style #js {:padding      "4px 10px"
                                    :background   "var(--lx-gray-02,#f9fafb)"
                                    :border       "1px solid var(--lx-gray-05,#e5e7eb)"
                                    :borderRadius "6px"
                                    :fontSize     "13px"
                                    :fontWeight   "600"
                                    :maxWidth     "180px"
                                    :overflow     "hidden"
                                    :textOverflow "ellipsis"
                                    :whiteSpace   "nowrap"}}
                   page-title))
                ;; Tags bar (reactive Rum component from main bundle)
                (when render-tags (render-tags))
                ;; 插入块
                (js/React.createElement
                 "button"
                 #js {:title   "搜索并插入 Logseq 块卡片到画布"
                      :onClick (fn [] (when on-insert-block (on-insert-block)))
                      :style   #js {:display      "flex"
                                    :alignItems   "center"
                                    :gap          "4px"
                                    :padding      "5px 10px"
                                    :background   "#6366f1"
                                    :color        "#fff"
                                    :border       "none"
                                    :borderRadius "6px"
                                    :cursor       "pointer"
                                    :fontSize     "13px"
                                    :whiteSpace   "nowrap"}}
                 "+ 插入块")
                ;; 侧边栏 (conditional)
                (when (seq bid)
                  (js/React.createElement
                   "button"
                   #js {:title   (str "在侧边栏打开: " title)
                        :onClick (fn [^js e]
                                   (.stopPropagation e)
                                   (when on-block-click (on-block-click bid)))
                        :style   #js {:display      "flex"
                                      :alignItems   "center"
                                      :gap          "5px"
                                      :padding      "5px 10px"
                                      :background   "#8b5cf6"
                                      :color        "#fff"
                                      :border       "none"
                                      :borderRadius "6px"
                                      :cursor       "pointer"
                                      :fontSize     "13px"
                                      :whiteSpace   "nowrap"}}
                   "→ 侧边栏")))))})]))

;; Export for shadow.lazy loadable
(def ^:export editor excalidraw-editor)
