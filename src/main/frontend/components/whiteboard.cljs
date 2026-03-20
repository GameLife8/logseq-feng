(ns frontend.components.whiteboard
  "Whiteboard page component backed by Excalidraw.

   Architecture:
     frontend.extensions.excalidraw.api  – element builders (main bundle)
     frontend.extensions.excalidraw.core – Excalidraw React wrapper (lazy bundle)
     frontend.components.whiteboard      – page UI / toolbar / block-picker (main bundle)"
  (:require [clojure.string :as string]
            [frontend.db :as db]
            [frontend.db-mixins :as db-mixins]
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
  "Floating tags bar overlay. Tags are real Logseq block/tags stored in DB.
   Only existing Tag-class entities can be added."
  < rum/reactive
  (rum/local false ::adding?)
  (rum/local "" ::tag-input)
  (rum/local [] ::tag-results)
  [state page-uuid page-entity]
  (let [*adding  (::adding? state)
        *input   (::tag-input state)
        *results (::tag-results state)
        adding?  (rum/react *adding)
        _query   (rum/react *input)
        results  (rum/react *results)
        tags     (whiteboard-handler/get-page-user-tags page-entity)]
    [:div.wb-float-tags
     {:style {:display    "flex"
              :align-items "center"
              :gap         "4px"
              :flex-wrap   "wrap"
              :position    "relative"}}
     (for [tag tags]
       [:span
        {:key   (str (:db/id tag))
         :style {:background    "rgba(255,255,255,0.92)"
                 :border        "1px solid var(--lx-gray-05, #e5e7eb)"
                 :border-radius "12px"
                 :padding       "1px 8px 1px 6px"
                 :display       "inline-flex"
                 :align-items   "center"
                 :gap           "3px"
                 :font-size     "11px"
                 :box-shadow    "0 1px 3px rgba(0,0,0,0.08)"}}
        [:span {:on-click #(route-handler/redirect-to-page! (:block/title tag))
                :style {:cursor "pointer"}}
         (str "#" (:block/title tag))]
        [:span
         {:style    {:opacity "0.45" :cursor "pointer" :font-size "10px"}
          :on-click (fn [^js e]
                      (.stopPropagation e)
                      (whiteboard-handler/remove-tag-from-page! (uuid page-uuid) tag))}
         "×"]])
     (if adding?
       [:div {:style {:position "relative" :display "inline-flex" :align-items "center"}}
        [:input
         {:auto-focus  true
          :type        "text"
          :value       @*input
          :placeholder "搜索标签…"
          :style       {:font-size     "11px"
                        :width         "110px"
                        :height        "20px"
                        :outline       "none"
                        :border        "1px solid var(--lx-gray-07, #d1d5db)"
                        :border-radius "10px"
                        :padding       "0 8px"
                        :background    "rgba(255,255,255,0.95)"}
          :on-change   (fn [^js e]
                         (let [q (.. e -target -value)]
                           (reset! *input q)
                           (reset! *results (whiteboard-handler/search-tags q))))
          :on-focus    #(reset! *results (whiteboard-handler/search-tags ""))
          :on-blur     (fn []
                         (js/setTimeout
                          (fn []
                            (reset! *adding false)
                            (reset! *input "")
                            (reset! *results []))
                          150))
          :on-key-down (fn [^js e]
                         (case (.-key e)
                           "Escape" (do (reset! *adding false)
                                        (reset! *input "")
                                        (reset! *results []))
                           nil))}]
        (when (seq results)
          [:div
           {:style {:position   "absolute"
                    :top        "24px"
                    :left       0
                    :background "var(--lx-gray-02, #fff)"
                    :border     "1px solid var(--lx-gray-06, #e5e7eb)"
                    :borderRadius "8px"
                    :boxShadow  "0 4px 12px rgba(0,0,0,0.15)"
                    :zIndex     3000
                    :minWidth   "160px"
                    :maxHeight  "200px"
                    :overflowY  "auto"
                    :fontSize   "12px"}}
           (for [tag-entity results]
             [:div
              {:key      (str (:db/id tag-entity))
               :style    {:padding "6px 12px" :cursor "pointer"
                          :borderBottom "1px solid var(--lx-gray-04, #f3f4f6)"}
               :on-mouse-down
               (fn [^js e]
                 (.preventDefault e)
                 (whiteboard-handler/add-tag-to-page! (uuid page-uuid) tag-entity)
                 (reset! *adding false)
                 (reset! *input "")
                 (reset! *results []))}
              (str "#" (:block/title tag-entity))])])]
       [:span
        {:style    {:font-size    "13px"
                    :cursor       "pointer"
                    :background   "var(--lx-gray-03, #f3f4f6)"
                    :border       "1px solid var(--lx-gray-06, #e5e7eb)"
                    :border-radius "6px"
                    :padding      "5px 10px"
                    :display      "inline-flex"
                    :align-items  "center"
                    :user-select  "none"
                    :white-space  "nowrap"}
         :on-click (fn []
                     (reset! *adding true)
                     (reset! *results (whiteboard-handler/search-tags "")))}
        "+ 标签"])]))

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
  [state {:keys [page-uuid page-title on-back on-api-ready on-block-click on-insert-block render-tags]}]
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
    [:div.whiteboard-page
     {:style {:position "relative" :width "100%" :height "100vh"}}

     ;; canvas fills entire viewport; tags are rendered inside renderTopRightUI
     [:div {:style {:position "absolute" :inset 0 :overflow "hidden"}}
      (whiteboard-canvas
       {:page-uuid       page-uuid
        :page-title      page-title
        :on-back         on-back
        :on-api-ready    (fn [api] (reset! *canvas-api api))
        :on-block-click  (fn [bid] (whiteboard-handler/open-block-in-sidebar! bid))
        :on-insert-block #(swap! *show-picker not)
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
           *svg      (::svg-html state)]
       ;; Read canvas JSON from DB (authoritative store)
       (when-let [raw (whiteboard-handler/load-canvas-from-db page-uuid)]
         (try
           (let [data     (js/JSON.parse raw)
                 elements (.-elements data)]
             (when (and elements
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
                            ;; Constrain SVG to fill the thumbnail box
                            (.setAttribute svg "width" "100%")
                            (.setAttribute svg "height" "100%")
                            (.setAttribute svg "preserveAspectRatio" "xMidYMid meet")
                            (reset! *svg (.-outerHTML svg))))
                   (.catch (fn [err]
                             (js/console.warn "SVG thumbnail export failed" err))))))
           (catch :default err
             (js/console.warn "Thumbnail parse error" err)))))
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
  [state]
  (let [*creating? (::creating? state)
        *new-name  (::new-name state)
        creating?  (rum/react *creating?)
        new-name   (rum/react *new-name)
        whiteboards (whiteboard-handler/get-all-whiteboards)]
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
                            "Enter"  (do (reset! *creating? false)
                                         (when (seq (string/trim new-name))
                                           (whiteboard-handler/<create-whiteboard! new-name)))
                            "Escape" (do (reset! *creating? false)
                                         (reset! *new-name ""))
                            nil))}]
         (shui/button
          {:size     :sm
           :on-click (fn []
                       (reset! *creating? false)
                       (when (seq (string/trim new-name))
                         (whiteboard-handler/<create-whiteboard! new-name)))}
          "创建")
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
        (for [wb whiteboards
              :let [uuid  (str (:block/uuid wb))
                    title (or (:block/title wb) "Untitled")]]
          [:div.wb-gallery-card
           {:key   (str "wbcard-" uuid)
            :style {:border       "1px solid var(--lx-gray-05, #e5e7eb)"
                    :borderRadius "10px"
                    :overflow     "hidden"
                    :cursor       "pointer"
                    :transition   "box-shadow 0.15s, transform 0.15s"
                    :background   "var(--lx-gray-01, #fff)"}
            :on-mouse-enter #(let [^js s (.. % -currentTarget -style)]
                               (set! (.-boxShadow s) "0 4px 16px rgba(0,0,0,0.12)")
                               (set! (.-transform s) "translateY(-2px)"))
            :on-mouse-leave #(let [^js s (.. % -currentTarget -style)]
                               (set! (.-boxShadow s) "none")
                               (set! (.-transform s) "none"))
            :on-click       #(whiteboard-handler/redirect-to-whiteboard! uuid)}
           ;; SVG thumbnail
           (whiteboard-thumbnail uuid)
           ;; card footer
           [:div.flex.items-center.gap-2.px-3.py-2
            {:style {:borderTop "1px solid var(--lx-gray-05, #e5e7eb)"}}
            (ui/icon "layout-board" {:size 13 :class "opacity-50 shrink-0"})
            [:span.text-sm.font-medium.truncate title]]])]
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
