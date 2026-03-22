(ns frontend.components.whiteboard
  "Whiteboard page component backed by Excalidraw.

   Architecture:
     frontend.extensions.excalidraw.api  – element builders (main bundle)
     frontend.extensions.excalidraw.core – Excalidraw React wrapper (lazy bundle)
     frontend.components.whiteboard      – page UI / toolbar / block-picker (main bundle)"
  (:require [clojure.string :as string]
            [frontend.db :as db]
            [frontend.db-mixins :as db-mixins]
            [frontend.db.async :as db-async]
            [frontend.db.react :as react]
            [frontend.extensions.excalidraw.api :as ex-api]
            [frontend.handler.notification :as notification]
            [frontend.handler.route :as route-handler]
            [frontend.handler.whiteboard :as whiteboard-handler]
            [frontend.search :as search]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]
            [rum.core :as rum]
            [shadow.lazy :as lazy]))

;; ── lazy-load Excalidraw module ───────────────────────────────────────────────

#_:clj-kondo/ignore
(def ^:private lazy-excalidraw
  (lazy/loadable frontend.extensions.excalidraw.core/editor))

(defonce ^:private *excalidraw-loaded? (atom false))

(defn- ensure-excalidraw-loaded! [on-done]
  (if @*excalidraw-loaded?
    (on-done)
    (lazy/load lazy-excalidraw
               (fn []
                 (reset! *excalidraw-loaded? true)
                 (on-done)))))

;; ── tags bar (floating, real Logseq block/tags) ─────────────────────────────

(rum/defcs tags-bar
  "Tags dropdown button in the whiteboard toolbar.
   Click to open a panel showing selected tags and a search to add/remove tags."
  < rum/reactive
  (rum/local false ::open?)
  (rum/local "" ::tag-input)
  (rum/local [] ::tag-results)
  [state page-uuid page-entity]
  (let [*open?   (::open? state)
        *input   (::tag-input state)
        *results (::tag-results state)
        open?    (rum/react *open?)
        _        (rum/react *input)
        results  (rum/react *results)
        tags     (whiteboard-handler/get-page-user-tags page-entity)
        cnt      (count tags)
        close!   (fn []
                   (reset! *open? false)
                   (reset! *input "")
                   (reset! *results []))]
    [:div {:style {:position "relative" :display "inline-block"}}
     ;; ── Trigger button ──────────────────────────────────────────────────────
     [:button
      {:on-click #(if open? (close!) (do (reset! *open? true)
                                         (reset! *results (whiteboard-handler/search-tags ""))))
       :style    {:display       "flex"
                  :align-items   "center"
                  :gap           "5px"
                  :padding       "5px 10px"
                  :background    (if open? "var(--lx-gray-04,#e5e7eb)" "var(--lx-gray-03,#f3f4f6)")
                  :color         "var(--lx-gray-12,#111)"
                  :border        "1px solid var(--lx-gray-06,#e5e7eb)"
                  :border-radius "6px"
                  :cursor        "pointer"
                  :font-size     "13px"
                  :white-space   "nowrap"}}
      "🏷"
      (if (pos? cnt) (str "标签 (" cnt ")") "+ 标签")]

     ;; ── Dropdown panel ──────────────────────────────────────────────────────
     (when open?
       [:div {:style {:position      "absolute"
                      :top           "calc(100% + 6px)"
                      :right         0
                      :background    "var(--lx-gray-01, #fff)"
                      :border        "1px solid var(--lx-gray-06,#e5e7eb)"
                      :border-radius "10px"
                      :box-shadow    "0 6px 20px rgba(0,0,0,0.15)"
                      :z-index       3000
                      :min-width     "220px"
                      :padding       "10px 10px 8px"}}

        ;; Header
        [:div {:style {:display         "flex"
                       :justify-content "space-between"
                       :align-items     "center"
                       :margin-bottom   "8px"}}
         [:span {:style {:font-size "12px" :font-weight "600" :opacity "0.6"}} "标签"]
         [:button {:on-click close!
                   :style {:background "none" :border "none" :cursor "pointer"
                           :opacity "0.4" :font-size "16px" :line-height "1" :padding "0 2px"}}
          "×"]]

        ;; Already-selected tags
        (if (seq tags)
          [:div {:style {:margin-bottom "6px"}}
           (for [tag tags]
             [:div {:key   (str "sel-" (:db/id tag))
                    :style {:display         "flex"
                            :align-items     "center"
                            :justify-content "space-between"
                            :padding         "4px 8px"
                            :border-radius   "6px"
                            :background      "#eef2ff"
                            :margin-bottom   "3px"}}
              [:span {:style {:font-size "12px" :color "#4f46e5"}} (str "#" (:block/title tag))]
              [:button {:on-click (fn [^js e]
                                    (.stopPropagation e)
                                    (whiteboard-handler/remove-tag-from-page! (uuid page-uuid) tag))
                        :style {:background "none" :border "none" :cursor "pointer"
                                :opacity "0.5" :font-size "12px" :padding "0 2px"
                                :line-height "1"}}
               "×"]])]
          [:div {:style {:font-size "12px" :opacity "0.4" :text-align "center"
                         :padding "4px 0 6px"}}
           "暂无标签"])

        ;; Divider
        [:hr {:style {:border "none" :border-top "1px solid var(--lx-gray-05,#e5e7eb)"
                      :margin "6px 0"}}]

        ;; Search input
        [:input {:type        "text"
                 :placeholder "搜索标签…"
                 :value       @*input
                 :style       {:display       "block"
                               :width         "100%"
                               :padding       "5px 8px"
                               :border-radius "6px"
                               :border        "1px solid var(--lx-gray-07,#d1d5db)"
                               :outline       "none"
                               :font-size     "12px"
                               :box-sizing    "border-box"}
                 :on-change   (fn [^js e]
                                (let [q (.. e -target -value)]
                                  (reset! *input q)
                                  (reset! *results (whiteboard-handler/search-tags q))))
                 :on-focus    #(reset! *results (whiteboard-handler/search-tags ""))
                 :on-blur     (fn [] (js/setTimeout close! 200))
                 :on-key-down (fn [^js e]
                                (when (= "Escape" (.-key e)) (close!)))}]

        ;; Candidates list (all tags, already-selected shown with ✓)
        (when (seq results)
          [:div {:style {:max-height "160px" :overflow-y "auto" :margin-top "4px"}}
           (for [tag-entity results
                 :let [already? (some #(= (:db/id %) (:db/id tag-entity)) tags)]]
             [:div {:key          (str "res-" (:db/id tag-entity))
                    :style        {:display         "flex"
                                   :align-items     "center"
                                   :justify-content "space-between"
                                   :padding         "5px 8px"
                                   :border-radius   "6px"
                                   :cursor          "pointer"
                                   :background      (if already? "var(--lx-gray-03,#f3f4f6)" "transparent")
                                   :margin-bottom   "2px"}
                    :on-mouse-enter #(when-not already?
                                       (set! (.. % -currentTarget -style -background)
                                             "var(--lx-gray-04,#f3f4f6)"))
                    :on-mouse-leave #(when-not already?
                                       (set! (.. % -currentTarget -style -background) "transparent"))
                    :on-mouse-down  (fn [^js e]
                                      (.preventDefault e)
                                      (if already?
                                        (whiteboard-handler/remove-tag-from-page! (uuid page-uuid) tag-entity)
                                        (do (whiteboard-handler/add-tag-to-page! (uuid page-uuid) tag-entity)
                                            (reset! *input "")
                                            (reset! *results (whiteboard-handler/search-tags "")))))}
              [:span {:style {:font-size "12px"}} (str "#" (:block/title tag-entity))]
              (when already?
                [:span {:style {:font-size "10px" :color "#6366f1" :opacity "0.8"}} "✓"])])])
        ])]))


;; ── block-picker panel ────────────────────────────────────────────────────────

(rum/defcs block-picker
  "Floating search panel to pick a block and insert it as a card.
   Props: :on-insert fn({:block-id :block-title :page-title})
          :on-close  fn()"
  < rum/reactive
  (rum/local "" ::query)
  (rum/local [] ::results)
  (rum/local false ::searching?)
  (rum/local "" ::custom-label)
  {:did-mount (fn [state]
                (js/setTimeout
                 #(when-let [el (.querySelector js/document ".wb-picker-input")]
                    (.focus el))
                 50)
                state)}
  [state {:keys [on-insert on-close]}]
  (let [*q      (::query state)
        *res    (::results state)
        *busy?  (::searching? state)
        *label  (::custom-label state)
        query   (rum/react *q)
        results (rum/react *res)]
    [:div.wb-block-picker
     {:style {:position     "absolute"
              :top          "58px"
              :left         "50%"
              :transform    "translateX(-50%)"
              :width        "400px"
              :maxWidth     "90vw"
              :background   "var(--lx-gray-02, #fff)"
              :border       "1px solid var(--lx-gray-07, #e5e7eb)"
              :borderRadius "10px"
              :boxShadow    "0 8px 32px rgba(0,0,0,0.2)"
              :zIndex       1000
              :padding      "14px"}}
     ;; header
     [:div.flex.items-center.justify-between.mb-3
      [:span.font-semibold.text-sm "插入块到白板"]
      [:button {:on-click on-close
                :style {:background "none" :border "none" :cursor "pointer"
                        :fontSize "18px" :lineHeight "1" :opacity "0.6"}}
       "×"]]
     ;; search input
     [:input.wb-picker-input
      {:type        "text"
       :placeholder "搜索块或页面名称…"
       :value       query
       :style       {:display     "block"
                     :width       "100%"
                     :padding     "7px 10px"
                     :borderRadius "6px"
                     :border      "1px solid var(--lx-gray-07, #d1d5db)"
                     :outline     "none"
                     :fontSize    "13px"
                     :boxSizing   "border-box"}
       :on-change
       (fn [^js e]
         (let [q (.. e -target -value)]
           (reset! *q q)
           (if (string/blank? q)
             (reset! *res [])
             (do
               (reset! *busy? true)
               (p/let [res (search/block-search
                            (state/get-current-repo) q
                            {:built-in? false :enable-snippet? false})]
                 (reset! *res (vec (take 25 (or res []))))
                 (reset! *busy? false))))))}]
     ;; custom label input
     [:div {:style {:marginTop "10px"}}
      [:div {:style {:fontSize "11px" :opacity "0.55" :marginBottom "4px"}} "自定义标签（第一行显示内容，可选）"]
      [:input
       {:type        "text"
        :placeholder "描述这个块的用途，如：待办、重要决策…"
        :value       @*label
        :style       {:display     "block"
                      :width       "100%"
                      :padding     "6px 10px"
                      :borderRadius "6px"
                      :border      "1px solid var(--lx-gray-07, #d1d5db)"
                      :outline     "none"
                      :fontSize    "12px"
                      :boxSizing   "border-box"}
        :on-change   #(reset! *label (.. % -target -value))}]]
     ;; results
     (cond
       @*busy?
       [:div.text-center.py-3.text-sm.opacity-60 "搜索中…"]

       (and (seq query) (empty? results))
       [:div.text-center.py-3.text-sm.opacity-50 "未找到结果"]

       (seq results)
       [:div.mt-2 {:style {:maxHeight "300px" :overflowY "auto"}}
        (for [block results
              :let [title (or (:block/title block) "(无标题)")
                    page  (some-> (db/entity (:db/id (:block/page block)))
                                  :block/title)
                    uuid  (:block/uuid block)]]
          [:div
           {:key (str uuid)
            :style {:padding "7px 9px" :cursor "pointer"
                    :borderRadius "6px" :marginBottom "2px"
                    :transition "background 0.1s"}
            :on-mouse-enter
            #(set! (.. % -currentTarget -style -background)
                   "var(--lx-gray-04, #f3f4f6)")
            :on-mouse-leave
            #(set! (.. % -currentTarget -style -background) "transparent")
            :on-click
            (fn []
              (on-insert {:block-id     (str uuid)
                          :block-title  title
                          :page-title   (or page "")
                          :custom-label @*label})
              (on-close))}
           [:div.text-sm.font-medium
            {:style {:overflow "hidden" :textOverflow "ellipsis"
                     :whiteSpace "nowrap" :lineHeight "1.4"}}
            title]
           (when page
             [:div {:style {:fontSize "11px" :opacity "0.55" :marginTop "1px"}}
              page])])])]))

;; ── canvas ────────────────────────────────────────────────────────────────────

(rum/defcs whiteboard-canvas
  "Renders the lazy-loaded Excalidraw component."
  < rum/reactive
  (rum/local false ::loaded?)
  {:did-mount
   (fn [state]
     (ensure-excalidraw-loaded!
      (fn [] (reset! (::loaded? state) true)))
     state)}
  [state {:keys [page-uuid page-title on-back on-api-ready on-block-click on-insert-block render-tags on-rename]}]
  (let [loaded? (rum/react (::loaded? state))]
    [:div.wb-canvas {:style {:width "100%" :height "100%"}}
     (if loaded?
       (@lazy-excalidraw
        {:page-uuid       page-uuid
         :page-title      page-title
         :on-back         on-back
         :on-api-ready    on-api-ready
         :on-block-click  on-block-click
         :on-insert-block on-insert-block
         :on-rename       on-rename
         :render-tags     render-tags
         ;; DB persistence callbacks (main bundle → lazy bundle boundary)
         :on-load-data    whiteboard-handler/load-canvas-from-db
         :on-save-data    whiteboard-handler/save-canvas-to-db!})
       [:div.flex.items-center.justify-center.h-full
        [:div.text-sm.opacity-60 "正在加载白板编辑器…"]])]))

;; ── full whiteboard page ──────────────────────────────────────────────────────

(rum/defcs whiteboard-page
  "Full-page whiteboard. Canvas fills the entire viewport.
   Back button, title and insert-block are inside Excalidraw's renderTopRightUI.
   A small floating tags bar sits at bottom-left of the canvas."
  < rum/reactive
  (rum/local false ::show-picker?)
  (rum/local nil   ::canvas-api)
  [state {:keys [page-entity]}]
  (let [*show-picker (::show-picker? state)
        *canvas-api  (::canvas-api state)
        show-picker  (rum/react *show-picker)
        page-uuid    (str (:block/uuid page-entity))
        page-title   (or (:block/title page-entity) "Untitled Whiteboard")
        ;; on-back: called by excalidraw/core AFTER save; shows toast then navigates
        on-back      (fn []
                       (notification/show! "白板已保存" :success)
                       (route-handler/redirect! {:to :all-whiteboards}))]
    ;; No overflow:hidden on outer div — allows Excalidraw dropdown menus to show
    ;; height: 100% works because container.css ensures the full parent chain has height: 100%
    ;; for margin-less pages (whiteboard / graph) with all padding stripped.
    [:div.whiteboard-page
     {:style {:position "relative" :width "100%" :height "100%"}}

     ;; canvas fills entire viewport; tags are rendered inside renderTopRightUI
     [:div {:style {:position "absolute" :inset 0 :overflow "hidden"}}
      (whiteboard-canvas
       {:page-uuid       page-uuid
        :page-title      page-title
        :on-back         on-back
        :on-api-ready    (fn [api] (reset! *canvas-api api))
        ;; Pre-load block from DB worker (local replica may not have it yet),
        ;; then open in sidebar once the entity is available.
        :on-block-click  (fn [bid]
                           (p/let [_ (db-async/<get-block (state/get-current-repo)
                                                          (uuid bid)
                                                          :children? false)]
                             (whiteboard-handler/open-block-in-sidebar! bid)))
        :on-insert-block #(swap! *show-picker not)
        :on-rename       (fn [new-title]
                           (whiteboard-handler/<rename-whiteboard! page-uuid new-title))
        ;; Pass tags as a render fn – Rum components return React elements when called
        :render-tags     (fn [] (tags-bar page-uuid page-entity))})]

     ;; block-picker overlay
     (when show-picker
       (block-picker
        {:on-close  #(reset! *show-picker false)
         :on-insert (fn [{:keys [block-id block-title page-title custom-label]}]
                      (when-let [api @*canvas-api]
                        (ex-api/insert-block-elements!
                         api block-id block-title page-title custom-label)))}))]))

;; ── whiteboard gallery ────────────────────────────────────────────────────────

(rum/defcs whiteboard-thumbnail
  "Renders an SVG thumbnail for a whiteboard from localStorage.
   Falls back to an icon placeholder if ExcalidrawLib is not loaded or data absent."
  < rum/reactive
  (rum/local nil ::svg-html)
  {:did-mount
   (fn [state]
     (let [page-uuid (-> state :rum/args first)
           *svg      (::svg-html state)
           gen-svg!  (fn []
                       (let [raw (whiteboard-handler/load-canvas-from-db page-uuid)]
                         (js/console.log "[wb] thumbnail" page-uuid "raw canvas len:" (some-> raw count))
                         (if-not raw
                           (js/console.log "[wb] thumbnail: no canvas data, using placeholder")
                           (try
                             (let [data     (js/JSON.parse raw)
                                   elements (.-elements data)]
                               (js/console.log "[wb] thumbnail elements count:"
                                               (if elements (.-length elements) "nil"))
                               (if (and elements
                                        (pos? (.-length elements))
                                        (exists? js/ExcalidrawLib)
                                        (.-exportToSvg js/ExcalidrawLib))
                                 (-> (js/ExcalidrawLib.exportToSvg
                                      #js {:elements elements
                                           :appState #js {:exportWithDarkMode false
                                                          :exportBackground   true
                                                          :backgroundColor    "#ffffff"}
                                           :files #js {}})
                                     (.then (fn [^js svg]
                                              (.setAttribute svg "width" "100%")
                                              (.setAttribute svg "height" "100%")
                                              (.setAttribute svg "preserveAspectRatio" "xMidYMid meet")
                                              (reset! *svg (.-outerHTML svg))))
                                     (.catch (fn [err]
                                               (js/console.warn "[wb] SVG thumbnail export failed" err))))
                                 (js/console.log "[wb] thumbnail: empty elements or no ExcalidrawLib")))
                             (catch :default err
                               (js/console.warn "[wb] Thumbnail parse error" err))))))]
       ;; Ensure the Excalidraw bundle (which exposes ExcalidrawLib) is loaded
       ;; before attempting SVG export. On first visit the bundle isn't loaded yet.
       (ensure-excalidraw-loaded! gen-svg!))
     state)}
  [state _page-uuid]
  (let [svg-html (rum/react (::svg-html state))]
    [:div.wb-thumbnail-area
     {:style {:width         "100%"
              :height        "130px"
              :background    "var(--lx-gray-02, #f9fafb)"
              :borderRadius  "8px 8px 0 0"
              :overflow      "hidden"
              :display       "flex"
              :alignItems    "center"
              :justifyContent "center"}}
     (if svg-html
       [:div {:style {:width "100%" :height "100%" :overflow "hidden"}
              :dangerouslySetInnerHTML {:__html svg-html}}]
       [:div.opacity-25
        (ui/icon "layout-board" {:size 36})])]))

(rum/defcs all-whiteboards
  "Gallery page listing all whiteboards in a 3-column grid with SVG thumbnails."
  < rum/reactive db-mixins/query
  (rum/local false ::creating?)
  (rum/local "" ::new-name)
  (rum/local nil  ::editing-uuid)
  (rum/local ""   ::rename-val)
  [state]
  (let [*creating?    (::creating? state)
        *new-name     (::new-name state)
        *editing-uuid (::editing-uuid state)
        *rename-val   (::rename-val state)
        creating?     (rum/react *creating?)
        new-name      (rum/react *new-name)
        editing-uuid  (rum/react *editing-uuid)
        rename-val    (rum/react *rename-val)
        repo          (state/get-current-repo)
        wclass-id     (:db/id (db/entity :logseq.class/Whiteboard))
        ;; Async reactive query via DB worker. react/q sends the query to the DB worker
        ;; (not the local replica), and re-runs automatically when whiteboard objects
        ;; change (the worker emits [::objects wclass-id] affected-keys on :block/tags txns).
        wb-atom       (when (and repo wclass-id)
                        (react/q repo [:frontend.worker.react/objects wclass-id]
                                 {:query-fn
                                  (fn [_db _]
                                    (p/let [result (db-async/<get-tag-objects repo wclass-id)]
                                      (->> result
                                           (filter :block/title)
                                           (sort-by #(or (:block/updated-at %) 0) >))))}
                                 nil))
        whiteboards   (or (some-> wb-atom rum/react) [])

        ;; Create: only close input if creation succeeded (not a duplicate)
        do-create!
        (fn []
          (let [trimmed (string/trim new-name)]
            (when (seq trimmed)
              (when (whiteboard-handler/<create-whiteboard! trimmed)
                ;; <create-whiteboard! returns nil on duplicate (shows notification)
                ;; Only close input when the promise resolves to a page
                (reset! *creating? false)
                (reset! *new-name "")))))

        ;; Commit rename
        do-rename!
        (fn []
          (let [trimmed (string/trim rename-val)]
            (when (and (seq trimmed) editing-uuid)
              (whiteboard-handler/<rename-whiteboard! editing-uuid trimmed)
              (reset! *editing-uuid nil)
              (reset! *rename-val ""))))

        cancel-rename!
        (fn [] (reset! *editing-uuid nil) (reset! *rename-val ""))]

    [:div.all-whiteboards-page
     {:style {:padding "24px 28px" :maxWidth "1000px" :margin "0 auto"}}

     ;; header
     [:div.flex.items-center.gap-3.mb-6
      (ui/icon "layout-board" {:size 22 :class "opacity-70"})
      [:h1.text-xl.font-bold.flex-1 "白板"]
      (if creating?
        [:div.flex.items-center.gap-2
         [:input
          {:type        "text"
           :auto-focus  true
           :placeholder "白板名称…"
           :value       new-name
           :style       {:fontSize "13px" :padding "5px 10px"
                         :borderRadius "6px"
                         :border "1px solid var(--lx-gray-07, #d1d5db)"
                         :outline "none" :width "180px"}
           :on-change   #(reset! *new-name (.. % -target -value))
           :on-key-down (fn [^js e]
                          (case (.-key e)
                            "Enter"  (do-create!)
                            "Escape" (do (reset! *creating? false)
                                         (reset! *new-name ""))
                            nil))}]
         (shui/button {:size :sm :on-click do-create!} "创建")
         (shui/button
          {:size :sm :variant :ghost
           :on-click (fn [] (reset! *creating? false) (reset! *new-name ""))}
          "取消")]
        (shui/button
         {:size     :sm
          :on-click (fn [] (reset! *creating? true) (reset! *new-name ""))}
         (ui/icon "plus" {:size 14 :class "mr-1"})
         "新建白板"))]

     ;; grid
     (if (seq whiteboards)
       [:div.wb-gallery-grid
        {:style {:display             "grid"
                 :gridTemplateColumns "repeat(3, 1fr)"
                 :gap                 "16px"}}
        (doall
         (for [wb whiteboards
               :let [wb-uuid  (str (:block/uuid wb))
                     wb-title (or (:block/title wb) "Untitled")
                     renaming? (= editing-uuid wb-uuid)]]
          [:div.wb-gallery-card
           {:key            (str "wbcard-" wb-uuid)
            :style          {:border       "1px solid var(--lx-gray-05, #e5e7eb)"
                             :borderRadius "10px"
                             :overflow     "hidden"
                             :cursor       "pointer"
                             :transition   "box-shadow 0.15s, transform 0.15s"
                             :background   "var(--lx-gray-01, #fff)"}
            :on-mouse-enter #(let [^js s  (.. % -currentTarget -style)
                                    ^js as (some-> % .-currentTarget
                                                   (.querySelector ".wb-card-actions") .-style)]
                               (set! (.-boxShadow s) "0 4px 16px rgba(0,0,0,0.12)")
                               (set! (.-transform s) "translateY(-2px)")
                               (when as (set! (.-opacity as) "1")))
            :on-mouse-leave #(let [^js s  (.. % -currentTarget -style)
                                    ^js as (some-> % .-currentTarget
                                                   (.querySelector ".wb-card-actions") .-style)]
                               (set! (.-boxShadow s) "none")
                               (set! (.-transform s) "none")
                               (when as (set! (.-opacity as) "0")))
            :on-click       (fn [^js e]
                              ;; Don't navigate if clicking action buttons or rename input
                              (when-not (some-> (.. e -target)
                                                (.closest ".wb-card-actions, .wb-rename-input"))
                                (whiteboard-handler/redirect-to-whiteboard! wb-uuid)))}
           ;; SVG thumbnail
           (whiteboard-thumbnail wb-uuid)
           ;; card footer
           [:div.flex.items-center.gap-2.px-3.py-2
            {:style {:borderTop "1px solid var(--lx-gray-05, #e5e7eb)"}}
            (ui/icon "layout-board" {:size 13 :class "opacity-50 shrink-0"})
            ;; Title or rename input
            (if renaming?
              [:input.wb-rename-input
               {:type        "text"
                :auto-focus  true
                :value       rename-val
                :style       {:flex       "1"
                              :fontSize   "13px"
                              :padding    "1px 4px"
                              :borderRadius "4px"
                              :border     "1px solid var(--lx-gray-07,#d1d5db)"
                              :outline    "none"
                              :minWidth   0}
                :on-change   #(reset! *rename-val (.. % -target -value))
                :on-blur     (fn [] (js/setTimeout cancel-rename! 150))
                :on-key-down (fn [^js e]
                               (case (.-key e)
                                 "Enter"  (do-rename!)
                                 "Escape" (cancel-rename!)
                                 nil))}]
              [:span.text-sm.font-medium.truncate {:style {:flex "1"}} wb-title])
            ;; Action buttons (rename + delete)
            [:div.wb-card-actions
             {:style {:display "flex" :gap "2px" :opacity "0"
                      :transition "opacity 0.15s"}
              ;; Show on card hover via inline style toggling
              :on-mouse-enter #(set! (.. % -currentTarget -style -opacity) "1")
              :on-mouse-leave #(set! (.. % -currentTarget -style -opacity) "0")}
             [:button
              {:title    "重命名"
               :on-click (fn [^js e]
                           (.stopPropagation e)
                           (reset! *editing-uuid wb-uuid)
                           (reset! *rename-val wb-title))
               :style    {:background "none" :border "none" :cursor "pointer"
                          :padding "2px 4px" :border-radius "4px"
                          :font-size "12px" :opacity "0.6"
                          :color "var(--lx-gray-11,#374151)"}}
              (ui/icon "pencil" {:size 13})]
             [:button
              {:title    "删除"
               :on-click (fn [^js e]
                           (.stopPropagation e)
                           (when (.confirm js/window (str "确定删除白板「" wb-title "」？\n此操作不可撤销。"))
                             (whiteboard-handler/<delete-whiteboard! wb-uuid)))
               :style    {:background "none" :border "none" :cursor "pointer"
                          :padding "2px 4px" :border-radius "4px"
                          :font-size "12px" :color "#ef4444"
                          :opacity "0.6"}}
              (ui/icon "trash" {:size 13})]]]]))]
       ;; empty state
       [:div.flex.flex-col.items-center.justify-center.gap-4
        {:style {:paddingTop "80px"}}
        (ui/icon "layout-board" {:size 56 :class "opacity-20"})
        [:div.text-sm.opacity-50 "还没有白板，点击「新建白板」开始"]])]))

;; ── route entry-points ─────────────────────────────────────────────────────────

(rum/defc whiteboard
  "Called by the reitit router.  Expects :path-params {:name <page-uuid>}."
  < rum/reactive
  [route-match]
  (let [raw-name (get-in route-match [:path-params :name])
        page     (or (when (util/uuid-string? raw-name)
                       (db/entity [:block/uuid (uuid raw-name)]))
                     (db/get-page raw-name))]
    (if page
      (whiteboard-page {:page-entity page})
      [:div.flex.flex-col.items-center.justify-center.gap-4
       {:style {:height "80vh"}}
       (ui/icon "layout-board" {:size 48 :class "opacity-30"})
       [:div.text-sm.opacity-60 "白板页面未找到"]
       (shui/button
        {:on-click #(whiteboard-handler/<create-whiteboard! "新白板")}
        "创建新白板")])))
