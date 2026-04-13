(ns frontend.components.sheet
  "Spreadsheet component: macro registration + DB bridge.

   Architecture:
     frontend.extensions.sheet.core   — Univer wrapper (lazy module, zero DB deps)
     frontend.handler.sheet           — sidecar persistence
     frontend.components.sheet        — macro embed card + search picker"
  (:require [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.sheet :as sheet-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [frontend.components.macro :as macro]
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

;; ── Inline sheet editor (full embed in block) ────────────────────────────────

(rum/defcs sheet-inline-editor
  "Full inline sheet editor rendered inside a block.
   Loads data from sidecar, mounts the lazy Univer component."
  < rum/reactive
  (rum/local false ::loaded?)
  (rum/local {:loaded? false :json nil :needs-flush? false :source :empty}
             ::initial-doc)
  {:did-mount
   (fn [state]
     (let [page-uuid (-> state :rum/args first)]
       (ensure-sheet-loaded!
        (fn [] (reset! (::loaded? state) true)))
       (p/let [doc-info (sheet-handler/<load-sheet-doc page-uuid)]
         (reset! (::initial-doc state)
                 (merge {:loaded? true} doc-info))))
     state)}
  [state page-uuid config]
  (let [editor-loaded? (rum/react (::loaded? state))
        {:keys [loaded? json needs-flush?]} (rum/react (::initial-doc state))
        doc-loaded? loaded?
        page (when (and page-uuid (util/uuid-string? page-uuid))
               (db/entity [:block/uuid (uuid page-uuid)]))
        sheet-title (or (:block/title page) "Sheet")
        block-id (:block/uuid (:block config))]
    (cond
      (or (not editor-loaded?) (not doc-loaded?))
      [:div.sheet-embed-card.visual-doc-embed-card.forbid-edit
       {:on-pointer-down util/stop-propagation}
       [:div {:style {:padding "20px" :text-align "center"
                      :color "var(--ls-secondary-text-color,#666)"}}
        "Loading spreadsheet..."]]

      :else
      [:div.sheet-embed-card.visual-doc-embed-card.forbid-edit
       {:on-pointer-down util/stop-propagation}
       ;; Toolbar
       [:div.visual-doc-embed-toolbar
        [:div.visual-doc-embed-title
         (ui/icon "table" {:size 14})
         [:span.visual-doc-embed-title-text sheet-title]]
        [:div.visual-doc-embed-actions
         (when block-id
           [:button {:type "button"
                     :title "Remove embed"
                     :class "visual-doc-embed-action visual-doc-embed-action-danger"
                     :on-pointer-down util/stop-propagation
                     :on-click (fn [e]
                                 (util/stop e)
                                 (when block-id
                                   (editor-handler/delete-block-aux! {:block/uuid block-id})))}
            (ui/icon "trash" {:size 14})])]]
       ;; Sheet editor area
       [:div.sheet-editor-area
        {:style {:width "100%" :height "500px" :overflow "hidden"}}
        (@lazy-sheet
         {:sheet-id       page-uuid
          :sheet-title    sheet-title
          :initial-json   json
          :needs-initial-flush? needs-flush?
          :on-save-data   sheet-handler/save-sheet-to-db!})]])))

;; ── Macro registration: {{sheet page-uuid}} ──────────────────────────────────

(macro/register "sheet"
  (fn [config options]
    (when-let [page-uuid (first (:arguments options))]
      (sheet-inline-editor page-uuid config))))
