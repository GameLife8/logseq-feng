(ns frontend.handler.whiteboard
  "Handlers for creating, opening and managing whiteboard pages."
  (:require [clojure.string :as string]
            [frontend.db :as db]
            [frontend.handler.common.page :as common-page-handler]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.route :as route-handler]
            [frontend.state :as state]
            [frontend.storage :as storage]
            [promesa.core :as p]))

;; ── whiteboard registry (localStorage) ───────────────────────────────────────
;; We track whiteboard page UUIDs per repo in localStorage so we can list them
;; in the sidebar without adding a DB schema property.

(defn- registry-key []
  (str "whiteboard-pages-" (state/get-current-repo)))

(defn- get-whiteboard-uuids []
  (or (storage/get (registry-key)) []))

(defn- add-whiteboard-uuid! [uuid-str]
  (let [existing (get-whiteboard-uuids)
        updated  (vec (distinct (cons uuid-str existing)))]
    (storage/set (registry-key) updated)))

;; ── public API ────────────────────────────────────────────────────────────────

(defn get-all-whiteboards
  "Returns all whiteboard page entities (filters out deleted pages)."
  []
  (->> (get-whiteboard-uuids)
       (keep (fn [uuid-str]
               (when (seq uuid-str)
                 (db/entity [:block/uuid (uuid uuid-str)]))))
       (filter identity)))

(defn <create-whiteboard!
  "Creates a new whiteboard page and redirects to it."
  [name]
  (let [title (string/trim (or name "Untitled Whiteboard"))]
    (p/let [page (common-page-handler/<create! title {:redirect? false})]
      (when page
        (let [uuid-str (str (:block/uuid page))]
          (add-whiteboard-uuid! uuid-str)
          (route-handler/redirect!
           {:to          :whiteboard
            :path-params {:name uuid-str}})
          page)))))

(defn redirect-to-whiteboard!
  "Navigate to the whiteboard identified by page-uuid (string or uuid)."
  [page-uuid]
  (route-handler/redirect!
   {:to          :whiteboard
    :path-params {:name (str page-uuid)}}))

(defn open-block-in-sidebar!
  "Open a block (by UUID string) in the right sidebar."
  [block-id-str]
  (when (seq block-id-str)
    (editor-handler/open-block-in-sidebar! (uuid block-id-str))))
