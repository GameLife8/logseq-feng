(ns frontend.components.sheet
  "Spreadsheet component: macro registration + DB bridge + gallery + page.

   Architecture:
     frontend.extensions.sheet.core   — Univer wrapper (lazy module, zero DB deps)
     frontend.handler.sheet           — sidecar persistence + CRUD
     frontend.components.sheet        — macro embed card + gallery + full-page editor"
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.db.react :as react]
            [frontend.extensions.sheet.preview :as sheet-preview]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.sheet :as sheet-handler]
            [frontend.db-mixins :as db-mixins]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [frontend.components.macro :as macro]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]
            [rum.core :as rum]
            [shadow.lazy :as lazy]))

;; ── Lazy-load the sheet module ───────────────────────────────────────────────

#_:clj-kondo/ignore
(def ^:private lazy-sheet
  (lazy/loadable frontend.extensions.sheet.core/editor))

(defonce ^:private *sheet-loaded? (atom false))

(defn- ensure-sheet-loaded! [on-done]
  (if @*sheet-loaded?
    (on-done)
    (lazy/load lazy-sheet
               (fn []
                 (reset! *sheet-loaded? true)
                 (on-done)))))

;; ── Inline embed card (read-only preview + action buttons) ──────────────────

(rum/defcs sheet-embed-card
  "Read-only inline embed card for {{sheet page-uuid}}.
   Shows an HTML table preview. Action buttons: refresh, edit (→ full-page), delete.
   Matches the whiteboard/mind-map embed card pattern."
  < rum/reactive
  (rum/local nil   ::preview-html)
  (rum/local false ::doc-loaded?)
  (rum/local false ::mounted?)
  {:did-mount
   (fn [state]
     (reset! (::mounted? state) true)
     (let [page-uuid (-> state :rum/args first)]
       ;; Guard the async load: in long pages with many embed cards, the user
       ;; can scroll a card out of view (Rum unmounts) before <load-sheet-doc
       ;; resolves. Without this guard Rum logs "cannot update unmounted
       ;; component" and the state atoms leak.
       (p/let [doc-info (sheet-handler/<load-sheet-doc page-uuid)]
         (when @(::mounted? state)
           (reset! (::doc-loaded? state) true)
           (when-let [json (:json doc-info)]
             (reset! (::preview-html state) (sheet-preview/build-table-html json))))))
     state)
   :will-unmount
   (fn [state]
     (reset! (::mounted? state) false)
     state)}
  [state page-uuid config]
  (let [preview-html (rum/react (::preview-html state))
        doc-loaded?  (rum/react (::doc-loaded? state))
        page         (when (and page-uuid (util/uuid-string? page-uuid))
                       (db/entity [:block/uuid (uuid page-uuid)]))
        sheet-title  (or (:block/title page) "Sheet")
        block-id     (:block/uuid (:block config))
        refresh!     (fn [e]
                       (util/stop e)
                       (p/let [doc-info (sheet-handler/<load-sheet-doc page-uuid)]
                         (when (and @(::mounted? state) (:json doc-info))
                           (reset! (::preview-html state)
                                   (sheet-preview/build-table-html (:json doc-info))))))
        open!        (fn [e]
                       (util/stop e)
                       (sheet-handler/redirect-to-sheet! page-uuid))
        remove!      (fn [e]
                       (util/stop e)
                       (when block-id
                         (editor-handler/delete-block-aux! {:block/uuid block-id})))
        action-button
        (fn [{:keys [title on-click icon class]}]
          [:button {:type "button"
                    :title title
                    :class (str "visual-doc-embed-action" (when class (str " " class)))
                    :on-pointer-down util/stop-propagation
                    :on-click on-click}
           (ui/icon icon {:size 14})])]
    [:div.sheet-embed-card.visual-doc-embed-card.forbid-edit
     {:on-pointer-down util/stop-propagation}
     ;; Toolbar (hover-reveal via existing CSS)
     [:div.visual-doc-embed-toolbar
      [:button.visual-doc-embed-title
       {:type "button"
        :on-pointer-down util/stop-propagation
        :on-click open!}
       (ui/icon "table" {:size 14})
       [:span.visual-doc-embed-title-text sheet-title]]
      [:div.visual-doc-embed-actions
       (action-button {:title "Refresh preview"
                       :on-click refresh!
                       :icon "refresh"})
       (action-button {:title "Open spreadsheet"
                       :on-click open!
                       :icon "edit"})
       (when block-id
         (action-button {:title "Remove embed"
                         :on-click remove!
                         :icon "trash"
                         :class "visual-doc-embed-action-danger"}))]]
     ;; Preview area (click → open full-page editor)
     [:div.visual-doc-embed-preview.sheet-embed-preview
      {:on-pointer-down util/stop-propagation
       :on-click #(sheet-handler/redirect-to-sheet! page-uuid)}
      (cond
        (not doc-loaded?)
        [:div.visual-doc-embed-empty
         (ui/icon "table" {:size 32 :class "opacity-30"})
         [:span "Loading..."]]

        (seq preview-html)
        [:div.sheet-embed-preview-inner
         {:dangerouslySetInnerHTML {:__html preview-html}}]

        :else
        [:div.visual-doc-embed-empty
         (ui/icon "table" {:size 40})
         [:span "Open spreadsheet"]])]]))

;; ── Macro registration: {{sheet page-uuid}} ──────────────────────────────────

(macro/register "sheet"
  (fn [config options]
    (when-let [page-uuid (first (:arguments options))]
      (sheet-embed-card page-uuid config))))

;; ── Full-page sheet editor (route component) ─────────────────────────────────

(rum/defcs sheet-page
  "Full-page sheet editor, mounted as a route view at /sheet/:name."
  < rum/reactive
  (rum/local false ::loaded?)
  (rum/local {:loaded? false :json nil :needs-flush? false :source :empty}
             ::initial-doc)
  {:did-mount
   (fn [state]
     (let [route-match (first (:rum/args state))
           page-uuid   (get-in route-match [:path-params :name])]
       (when (seq page-uuid)
         (ensure-sheet-loaded!
          (fn [] (reset! (::loaded? state) true)))
         (p/let [doc-info (sheet-handler/<load-sheet-doc page-uuid)]
           (reset! (::initial-doc state)
                   (merge {:loaded? true} doc-info)))))
     state)}
  [state route-match]
  (let [page-uuid      (get-in route-match [:path-params :name])
        editor-loaded? (rum/react (::loaded? state))
        {:keys [loaded? json needs-flush?]} (rum/react (::initial-doc state))
        doc-loaded?    loaded?
        page           (when (and page-uuid (util/uuid-string? page-uuid))
                         (db/entity [:block/uuid (uuid page-uuid)]))
        sheet-title    (or (:block/title page) "Sheet")]
    [:div.sheet-full-page
     {:style {:width "100%" :height "100%" :display "flex" :flex-direction "column"}}
     ;; Header
     [:div.sheet-page-header
      {:style {:display "flex" :align-items "center" :gap "12px"
               :padding "12px 20px"
               :border-bottom "1px solid var(--lx-gray-05, #e5e7eb)"}}
      [:button
       {:on-click #(js/window.history.back)
        :style    {:background "none" :border "none" :cursor "pointer"
                   :padding "4px" :border-radius "6px" :display "flex"
                   :color "var(--ls-secondary-text-color, #666)"}}
       (ui/icon "arrow-left" {:size 18})]
      (ui/icon "table" {:size 18 :class "opacity-60"})
      [:h1 {:style {:font-size "16px" :font-weight "600" :margin 0}} sheet-title]]
     ;; Editor area
     [:div.sheet-page-editor
      {:style {:flex 1 :min-height 0 :overflow "hidden"}}
      (cond
        (not (seq page-uuid))
        [:div {:style {:padding "40px" :text-align "center" :opacity 0.5}}
         "页面 UUID 无效"]

        (or (not editor-loaded?) (not doc-loaded?))
        [:div {:style {:padding "40px" :text-align "center"
                       :color "var(--ls-secondary-text-color,#666)"}}
         "Loading spreadsheet..."]

        :else
        ;; Key the editor by page-uuid so a route change to a different sheet
        ;; forces React to unmount the old Univer instance and mount a fresh
        ;; one for the new page. Without this, the Rum :did-update handler
        ;; would just update atoms on the existing workbook and autosave ticks
        ;; could write the previous sheet's content to the new page-uuid.
        (rum/with-key
          (@lazy-sheet
           {:sheet-id       page-uuid
            :sheet-title    sheet-title
            :initial-json   json
            :needs-initial-flush? needs-flush?
            :on-save-data   sheet-handler/save-sheet-to-db!})
          (str "sheet-editor-" page-uuid)))]]))

;; ── Gallery: all sheets ──────────────────────────────────────────────────────

(rum/defcs all-sheets
  "Gallery page: lists all sheets with create, rename, and delete."
  < rum/reactive db-mixins/query
  (rum/local false ::creating?)
  (rum/local "" ::new-name)
  (rum/local nil ::editing-uuid)
  (rum/local "" ::rename-val)
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

        ;; Find Sheet class entity ID for reactive subscription
        sheet-class-id (when-let [db (db/get-db)]
                         (first (d/q '[:find [?e ...]
                                       :where [?e :block/title "Sheet"]
                                              [?e :block/tags ?t]
                                              [?t :db/ident :logseq.class/Tag]]
                                     db)))
        ;; Subscribe to Sheet class object changes
        sheet-atom     (when (and repo sheet-class-id)
                         (react/q repo [:frontend.worker.react/objects sheet-class-id]
                                  {:query-fn (fn [_db _] (sheet-handler/get-all-sheets))}
                                  nil))
        sheets         (or (some-> sheet-atom rum/react)
                           (sheet-handler/get-all-sheets))

        do-create!
        (fn []
          (let [trimmed (string/trim new-name)]
            (when (seq trimmed)
              (p/let [result (sheet-handler/<create-sheet! trimmed nil)]
                (when result
                  (reset! *creating? false)
                  (reset! *new-name ""))))))

        do-rename!
        (fn []
          (let [trimmed (string/trim rename-val)]
            (when (and (seq trimmed) editing-uuid)
              (p/let [_ (sheet-handler/<rename-sheet! editing-uuid trimmed)]
                (reset! *editing-uuid nil)
                (reset! *rename-val "")))))

        cancel-rename!
        (fn [] (reset! *editing-uuid nil) (reset! *rename-val ""))]

    [:div.all-sheets-page
     {:style {:padding "24px 28px" :maxWidth "1000px" :margin "0 auto"}}

     ;; ── Header ──────────────────────────────────────────────────────────────
     [:div.flex.items-center.gap-3.mb-6
      (ui/icon "table" {:size 22 :class "opacity-70"})
      [:h1.text-xl.font-bold.flex-1 "表格"]
      (if creating?
        [:div.flex.items-center.gap-2
         [:input
          {:type        "text"
           :auto-focus  true
           :placeholder "表格名称…"
           :value       new-name
           :style       {:fontSize "13px" :padding "5px 10px"
                         :borderRadius "6px"
                         :border "1px solid var(--lx-gray-07, #d1d5db)"
                         :outline "none" :width "200px"}
           :on-change   #(reset! *new-name (.. % -target -value))
           :on-key-down (fn [^js e]
                          (case (.-key e)
                            "Enter"  (do-create!)
                            "Escape" (do (reset! *creating? false)
                                         (reset! *new-name ""))
                            nil))}]
         (shui/button {:size :sm :on-click do-create!} "创建")
         (shui/button {:size :sm :variant :ghost
                       :on-click #(do (reset! *creating? false) (reset! *new-name ""))}
                      "取消")]
        (shui/button
         {:size :sm
          :on-click (fn [] (reset! *creating? true) (reset! *new-name ""))}
         (ui/icon "plus" {:size 14 :class "mr-1"})
         "新建表格"))]

     ;; ── Card grid ───────────────────────────────────────────────────────────
     (if (seq sheets)
       [:div.sheet-gallery-grid
        {:style {:display             "grid"
                 :gridTemplateColumns "repeat(3, 1fr)"
                 :gap                 "16px"}}
        (doall
         (for [s     sheets
               :let  [s-uuid  (str (:block/uuid s))
                      s-title (or (:block/title s) "未命名")
                      renaming? (= editing-uuid s-uuid)]]
           [:div.sheet-gallery-card
            {:key            (str "sheetcard-" s-uuid)
             :style          {:border       "1px solid var(--lx-gray-05, #e5e7eb)"
                              :borderRadius "10px"
                              :overflow     "hidden"
                              :cursor       "pointer"
                              :transition   "box-shadow 0.15s, transform 0.15s"
                              :background   "var(--lx-gray-01, #fff)"}
             :on-mouse-enter #(let [^js st (.. % -currentTarget -style)
                                    ^js as (some-> % .-currentTarget
                                                   (.querySelector ".sheet-card-actions") .-style)]
                                (set! (.-boxShadow st) "0 4px 16px rgba(0,0,0,0.12)")
                                (set! (.-transform st) "translateY(-2px)")
                                (when as (set! (.-opacity as) "1")))
             :on-mouse-leave #(let [^js st (.. % -currentTarget -style)
                                    ^js as (some-> % .-currentTarget
                                                   (.querySelector ".sheet-card-actions") .-style)]
                                (set! (.-boxShadow st) "none")
                                (set! (.-transform st) "none")
                                (when as (set! (.-opacity as) "0")))
             :on-click       (fn [^js e]
                               (when-not (some-> (.. e -target)
                                                 (.closest ".sheet-card-actions, .sheet-rename-input"))
                                 (sheet-handler/redirect-to-sheet! s-uuid)))}

            ;; Thumbnail placeholder (no visual preview for sheets)
            [:div
             {:style {:height "120px" :display "flex" :align-items "center"
                      :justify-content "center"
                      :background "var(--lx-gray-03, #f9fafb)"}}
             (ui/icon "table" {:size 40 :class "opacity-15"})]

            ;; Footer: title + action buttons
            [:div.flex.items-center.gap-2.px-3.py-2
             {:style {:borderTop "1px solid var(--lx-gray-05, #e5e7eb)"}}
             (ui/icon "table" {:size 13 :class "opacity-50 shrink-0"})
             (if renaming?
               [:input.sheet-rename-input
                {:type        "text"
                 :auto-focus  true
                 :value       rename-val
                 :style       {:flex "1" :fontSize "13px"
                               :padding "1px 4px" :borderRadius "4px"
                               :border "1px solid var(--lx-gray-07,#d1d5db)"
                               :outline "none" :minWidth 0}
                 :on-change   #(reset! *rename-val (.. % -target -value))
                 :on-blur     (fn [] (js/setTimeout cancel-rename! 150))
                 :on-key-down (fn [^js e]
                                (case (.-key e)
                                  "Enter"  (do-rename!)
                                  "Escape" (cancel-rename!)
                                  nil))}]
               [:span.text-sm.font-medium.truncate {:style {:flex "1"}} s-title])
             [:div.sheet-card-actions
              {:style {:display "flex" :gap "2px" :opacity "0"
                       :transition "opacity 0.15s"}}
              [:button
               {:title    "重命名"
                :on-click (fn [^js e]
                            (.stopPropagation e)
                            (reset! *editing-uuid s-uuid)
                            (reset! *rename-val s-title))
                :style    {:background "none" :border "none" :cursor "pointer"
                           :padding "2px 4px" :border-radius "4px"
                           :font-size "12px" :opacity "0.6"
                           :color "var(--lx-gray-11,#374151)"}}
               (ui/icon "pencil" {:size 13})]
              [:button
               {:title    "删除"
                :on-click (fn [^js e]
                            (.stopPropagation e)
                            (let [uid (keyword (str (random-uuid)))]
                              (notification/show!
                               [:div
                                [:div {:style {:font-weight "600" :margin-bottom "6px"}}
                                 (str "删除「" s-title "」？")]
                                [:div {:style {:font-size "12px" :opacity "0.7" :margin-bottom "12px"}}
                                 "此操作不可撤销。"]
                                [:div {:style {:display "flex" :gap "8px"}}
                                 [:button {:on-click (fn []
                                                       (notification/clear! uid)
                                                       (sheet-handler/<delete-sheet! s-uuid))
                                           :style {:padding "4px 12px" :border-radius "6px"
                                                   :border "none" :background "#ef4444" :color "#fff"
                                                   :font-size "12px" :cursor "pointer" :font-weight "600"}}
                                  "确认删除"]
                                 [:button {:on-click #(notification/clear! uid)
                                           :style {:padding "4px 12px" :border-radius "6px"
                                                   :border "1px solid var(--lx-gray-06,#e5e7eb)"
                                                   :background "var(--lx-gray-03,#f3f4f6)"
                                                   :font-size "12px" :cursor "pointer"}}
                                  "取消"]]]
                               :warning false uid nil nil)))
                :style    {:background "none" :border "none" :cursor "pointer"
                           :padding "2px 4px" :border-radius "4px"
                           :font-size "12px" :color "#ef4444" :opacity "0.6"}}
               (ui/icon "trash" {:size 13})]]]]))]

       ;; Empty state
       [:div.flex.flex-col.items-center.justify-center.gap-4
        {:style {:paddingTop "80px"}}
        (ui/icon "table" {:size 56 :class "opacity-20"})
        [:div.text-sm.opacity-50 "还没有表格，点击「新建表格」开始"]])]))
