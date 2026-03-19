(ns frontend.extensions.excalidraw.core
  "Excalidraw React component – loaded as a separate shadow-cljs lazy module.
   Requires @excalidraw/excalidraw which is only in this lazy bundle.
   Element-manipulation helpers live in frontend.extensions.excalidraw.api
   (main bundle) so the whiteboard UI can call them without loading Excalidraw."
  (:require ["@excalidraw/excalidraw" :refer [Excalidraw]]
            [frontend.extensions.excalidraw.api :as ex-api]
            [frontend.state :as state]
            [frontend.storage :as storage]
            [goog.object :as gobj]
            [logseq.shui.hooks :as hooks]
            [rum.core :as rum]))

;; ── storage ───────────────────────────────────────────────────────────────────

(defn- storage-key [page-uuid] (str "whiteboard-data-" page-uuid))

(defn- load-data [page-uuid]
  (when-let [raw (storage/get (storage-key page-uuid))]
    (try (js/JSON.parse raw) (catch :default _ nil))))

(defn- save-data! [page-uuid ^js api]
  (when (and api page-uuid)
    (let [els   (.getSceneElements api)
          astate (.getAppState api)
          data  (js/JSON.stringify
                 #js {:elements els
                      :appState #js {:scrollX (gobj/get astate "scrollX")
                                     :scrollY (gobj/get astate "scrollY")
                                     :zoom    (gobj/get astate "zoom")}})]
      (storage/set (storage-key page-uuid) data))))

;; ── Excalidraw component ──────────────────────────────────────────────────────

(rum/defcs excalidraw-editor
  "Core Excalidraw canvas component.

   Props map:
     :page-uuid      – UUID string of the whiteboard page (used as storage key)
     :on-block-click – fn called with block-id-string when a card link is clicked
     :on-api-ready   – fn called with the ExcalidrawImperativeAPI once mounted"
  < rum/static
  (rum/local nil  ::api)
  (rum/local nil  ::selected-block-el)
  (rum/local false ::dirty?)
  {:will-unmount (fn [state]
                   (let [api    @(::api state)
                         p-uuid (-> state :rum/args first :page-uuid)]
                     (save-data! p-uuid api))
                   state)}
  [state {:keys [page-uuid on-block-click on-api-ready]}]
  (let [*api     (::api state)
        *sel-el  (::selected-block-el state)
        *dirty?  (::dirty? state)
        init-data (load-data page-uuid)]

    ;; auto-save every 3 s when dirty
    (hooks/use-effect!
     (fn []
       (let [timer (js/setInterval
                    (fn []
                      (when @*dirty?
                        (save-data! page-uuid @*api)
                        (reset! *dirty? false)))
                    3000)]
         (fn [] (js/clearInterval timer))))
     [page-uuid])

    [:div.excalidraw-wrapper
     {:style {:width "100%" :height "100%" :position "relative"}}

     ;; Inject Excalidraw CSS once (the import is handled by shadow-cljs
     ;; for CSS-in-JS; since :ignore-asset-requires is true we do it here)
     (hooks/use-effect!
      (fn []
        (when-not (.getElementById js/document "excalidraw-css")
          (let [link (.createElement js/document "link")]
            (set! (.-rel link) "stylesheet")
            (set! (.-id link) "excalidraw-css")
            (set! (.-href link) "/static/css/excalidraw.css")
            (.appendChild (.-head js/document) link))))
      [])

     (js/React.createElement
      Excalidraw
      #js {:ref (fn [^js api]
                  (when api
                    (reset! *api api)
                    (when on-api-ready (on-api-ready api))))
           :initialData    (or init-data #js {})
           :theme          (if (= "dark" (state/sub :ui/theme)) "dark" "light")
           :UIOptions      #js {:canvasActions #js {:export    false
                                                    :loadScene false}}
           :onChange       (fn [_elements _app-state _files]
                             (reset! *dirty? true)
                             (reset! *sel-el (ex-api/get-selected-block-element @*api)))
           ;; double-click on a block card opens it in sidebar
           :onPointerUp    (fn [_active-tool _pointer-state]
                             (when-let [el @*sel-el]
                               (let [bid (some-> el
                                                 (gobj/get "customData")
                                                 (gobj/get "blockId"))]
                                 (when (and (seq bid) on-block-click)
                                   (on-block-click bid)))))
           ;; "Open in Sidebar" button shown when a block card is selected
           :renderTopRightUI
           (fn []
             (when-let [el @*sel-el]
               (let [bid   (some-> el (gobj/get "customData") (gobj/get "blockId"))
                     title (some-> el (gobj/get "customData") (gobj/get "blockTitle"))]
                 (when (seq bid)
                   (js/React.createElement
                    "button"
                    #js {:className "wb-open-sidebar-btn"
                         :title     (str "在侧边栏打开: " title)
                         :onClick   (fn [^js e]
                                      (.stopPropagation e)
                                      (when on-block-click (on-block-click bid)))
                         :style     #js {:display      "flex"
                                         :alignItems   "center"
                                         :gap          "5px"
                                         :padding      "5px 11px"
                                         :background   "#6366f1"
                                         :color        "#fff"
                                         :border       "none"
                                         :borderRadius "6px"
                                         :cursor       "pointer"
                                         :fontSize     "13px"
                                         :marginRight  "6px"}}
                    "→ 在侧边栏打开")))))})]]))

;; Export for shadow.lazy loadable
(def ^:export editor excalidraw-editor)
