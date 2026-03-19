(ns frontend.components.whiteboard
  "Whiteboard page component backed by Excalidraw.

   Architecture:
     frontend.extensions.excalidraw.api  – element builders (main bundle)
     frontend.extensions.excalidraw.core – Excalidraw React wrapper (lazy bundle)
     frontend.components.whiteboard      – page UI / toolbar / block-picker (main bundle)"
  (:require [clojure.string :as string]
            [frontend.db :as db]
            [frontend.extensions.excalidraw.api :as ex-api]
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

;; ── block-picker panel ────────────────────────────────────────────────────────

(rum/defcs block-picker
  "Floating search panel to pick a block and insert it as a card.
   Props: :on-insert fn({:block-id :block-title :page-title})
          :on-close  fn()"
  < rum/reactive
  (rum/local "" ::query)
  (rum/local [] ::results)
  (rum/local false ::searching?)
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
              (on-insert {:block-id    (str uuid)
                          :block-title title
                          :page-title  (or page "")})
              (on-close))}
           [:div.text-sm.font-medium
            {:style {:overflow "hidden" :textOverflow "ellipsis"
                     :whiteSpace "nowrap" :lineHeight "1.4"}}
            title]
           (when page
             [:div {:style {:fontSize "11px" :opacity "0.55" :marginTop "1px"}}
              page])])])]))

;; ── toolbar ───────────────────────────────────────────────────────────────────

(rum/defc whiteboard-toolbar
  [{:keys [page-title on-insert-block on-back]}]
  [:div.wb-toolbar
   {:style {:position     "absolute"
            :top          0
            :left         0
            :right        0
            :height       "48px"
            :background   "var(--lx-gray-01, #fff)"
            :borderBottom "1px solid var(--lx-gray-05, #e5e7eb)"
            :display      "flex"
            :alignItems   "center"
            :padding      "0 12px"
            :gap          "8px"
            :zIndex       100}}
   (shui/button-ghost-icon :arrow-left {:title "返回" :on-click on-back})
   (ui/icon "layout-board" {:size 16 :class "opacity-60 shrink-0"})
   [:span.font-semibold.text-sm.flex-1.truncate page-title]
   (shui/button
    {:size     :sm
     :variant  :outline
     :on-click on-insert-block
     :class    "gap-1 shrink-0"}
    (ui/icon "plus" {:size 14})
    "插入块")])

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
  [state {:keys [page-uuid on-api-ready on-block-click]}]
  (let [loaded? (rum/react (::loaded? state))]
    [:div.wb-canvas {:style {:width "100%" :height "100%"}}
     (if loaded?
       (@lazy-excalidraw
        {:page-uuid      page-uuid
         :on-api-ready   on-api-ready
         :on-block-click on-block-click})
       [:div.flex.items-center.justify-center.h-full
        [:div.text-sm.opacity-60 "正在加载白板编辑器…"]])]))

;; ── full whiteboard page ──────────────────────────────────────────────────────

(rum/defcs whiteboard-page
  "Full-page whiteboard.  :page-entity is the DataScript entity for the page."
  < rum/reactive
  (rum/local false ::show-picker?)
  (rum/local nil   ::canvas-api)
  [state {:keys [page-entity]}]
  (let [*show-picker (::show-picker? state)
        *canvas-api  (::canvas-api state)
        show-picker  (rum/react *show-picker)
        page-uuid    (str (:block/uuid page-entity))
        page-title   (or (:block/title page-entity) "Untitled Whiteboard")]
    [:div.whiteboard-page
     {:style {:position "relative" :width "100%" :height "100vh" :overflow "hidden"}}
     ;; toolbar
     (whiteboard-toolbar
      {:page-title      page-title
       :on-back         #(js/history.back)
       :on-insert-block #(swap! *show-picker not)})
     ;; canvas
     [:div {:style {:position "absolute" :top "48px" :left 0 :right 0 :bottom 0}}
      (whiteboard-canvas
       {:page-uuid      page-uuid
        :on-api-ready   (fn [api] (reset! *canvas-api api))
        :on-block-click (fn [bid] (whiteboard-handler/open-block-in-sidebar! bid))})]
     ;; block picker overlay
     (when show-picker
       (block-picker
        {:on-close  #(reset! *show-picker false)
         :on-insert (fn [{:keys [block-id block-title page-title]}]
                      (when-let [api @*canvas-api]
                        (ex-api/insert-block-elements!
                         api block-id block-title page-title)))}))]))

;; ── route entry-point ─────────────────────────────────────────────────────────

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
