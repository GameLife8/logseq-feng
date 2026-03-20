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

;; ── scene → wrapper pixel coordinate conversion ───────────────────────────────

(defn- scene->wrapper-px
  "Convert a scene coordinate to pixels relative to the wrapper div.
   Formula: viewportPx = (scene + scroll) * zoom  (relative to canvas element)
            wrapperPx  = viewportPx + offsetLeft/Top - wrapperRect.left/top"
  [scene-x scene-y ^js app-state ^js wrapper-el]
  (when (and app-state wrapper-el)
    (let [scroll-x  (gobj/get app-state "scrollX")
          scroll-y  (gobj/get app-state "scrollY")
          zoom      (or (gobj/getValueByKeys app-state "zoom" "value") 1)
          offset-l  (or (gobj/get app-state "offsetLeft") 0)
          offset-t  (or (gobj/get app-state "offsetTop") 0)
          rect      (.getBoundingClientRect wrapper-el)
          wrapper-l (.-left rect)
          wrapper-t (.-top rect)]
      {:x (+ (* (+ scene-x scroll-x) zoom) offset-l (- wrapper-l))
       :y (+ (* (+ scene-y scroll-y) zoom) offset-t (- wrapper-t))})))

;; ── Excalidraw component ──────────────────────────────────────────────────────

(rum/defcs excalidraw-editor
  "Core Excalidraw canvas component.

   Props map:
     :page-uuid       – UUID string of the whiteboard page
     :page-title      – Display title string shown in the toolbar chip
     :on-back         – fn() called to navigate back (after save completes)
     :on-block-click  – fn called with block-id-string to open in sidebar
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
  (rum/local nil   ::wrapper-el)        ; DOM ref to .excalidraw-wrapper div
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
                 on-insert-block on-load-data on-save-data render-tags]}]
  (let [*api      (::api state)
        *sel-el   (::selected-block-el state)
        *dirty?   (::dirty? state)
        *library  (::library-items state)
        *wrapper  (::wrapper-el state)
        init-data (or (when on-load-data
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
          (when on-back (on-back)))

        ;; Compute floating icon position for selected block (nil when none selected)
        icon-info
        (when-let [sel @*sel-el]
          (when-let [api @*api]
            (let [bid   (some-> sel (gobj/get "customData") (gobj/get "blockId"))
                  title (some-> sel (gobj/get "customData") (gobj/get "blockTitle"))]
              (when (seq bid)
                (when-let [pos (scene->wrapper-px
                                (+ (gobj/get sel "x") (gobj/get sel "width"))
                                (gobj/get sel "y")
                                (.getAppState api)
                                @*wrapper)]
                  (assoc pos :bid bid :title title))))))]

    [:div.excalidraw-wrapper
     {:style {:width "100%" :height "100%" :position "relative"}
      ;; Capture DOM ref so we can compute getBoundingClientRect for overlay positioning
      :ref   (fn [el] (reset! *wrapper el))}

     (js/React.createElement
      Excalidraw
      #js {;; ── IMPORTANT: use :excalidrawAPI, NOT :ref ──────────────────────
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
           ;; Top-right: back + title + insert block + tags
           :renderTopRightUI
           (fn []
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
               "+ 插入块")))})

     ;; ── Floating sidebar-open icon ────────────────────────────────────────────
     ;; Positioned at the top-right corner of the selected Logseq block card.
     ;; Uses HTML overlay (not canvas) so it can respond to Rum state changes.
     (when icon-info
       (let [{:keys [x y bid title]} icon-info]
         [:button.wb-block-open-btn
          {:title    (str "在侧边栏打开: " title)
           :on-click (fn [^js e]
                       (.stopPropagation e)
                       (.preventDefault e)
                       (when on-block-click (on-block-click bid)))
           :style    {:position         "absolute"
                      :left             (str (js/Math.round x) "px")
                      :top              (str (js/Math.round (- y 22)) "px")
                      :width            "20px"
                      :height           "20px"
                      :display          "flex"
                      :align-items      "center"
                      :justify-content  "center"
                      :background       "#6366f1"
                      :color            "#fff"
                      :border           "none"
                      :border-radius    "4px"
                      :cursor           "pointer"
                      :font-size        "12px"
                      :z-index          200
                      :line-height      "1"
                      :pointer-events   "all"
                      :box-shadow       "0 1px 4px rgba(99,102,241,0.4)"}}
          "↗"]))]))

;; Export for shadow.lazy loadable
(def ^:export editor excalidraw-editor)
