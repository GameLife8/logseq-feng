(ns frontend.handler.whiteboard
  "Handlers for creating, opening and managing whiteboard pages.

   Whiteboard pages are tagged with :logseq.class/Whiteboard in :block/tags
   so they are queryable from DataScript and visible in the Pages view.

   Canvas data is stored directly on the page entity as :block/whiteboard-canvas
   (a plain string attribute), persisted via db/transact!."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [frontend.db :as db]
            [frontend.handler.common.page :as common-page-handler]
            [frontend.handler.db-based.property :as db-property-handler]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.route :as route-handler]
            [frontend.state :as state]
            [promesa.core :as p]))

;; ── whiteboard identification ─────────────────────────────────────────────────

(defn get-all-whiteboards
  "Returns all whiteboard page entities from the DB, newest-updated first."
  []
  (when-let [database (db/get-db)]
    (->> (d/q '[:find (pull ?b [:db/id :block/uuid :block/title :block/tags :block/updated-at])
                :where [?b :block/tags ?t]
                       [?t :db/ident :logseq.class/Whiteboard]]
              database)
         (map first)
         (filter :block/title)
         (sort-by :block/updated-at >))))

(defn- whiteboard-name-exists?
  "Returns true if a whiteboard with the given title already exists (case-insensitive)."
  [title]
  (some #(= (string/lower-case (or (:block/title %) ""))
            (string/lower-case title))
        (get-all-whiteboards)))

;; ── canvas data (stored on the page entity) ───────────────────────────────────

(defn save-canvas-to-db!
  "Saves Excalidraw canvas as a JSON string on the whiteboard page entity.
   Also bumps :block/updated-at so the gallery sorts correctly.
   Returns true if the save was attempted, false if preconditions failed."
  [page-uuid canvas-json]
  (if-not (and (seq page-uuid) (seq canvas-json))
    (do (js/console.warn "[whiteboard] save-canvas-to-db! skipped: missing page-uuid or canvas-json")
        false)
    (if-let [page (db/entity [:block/uuid (uuid page-uuid)])]
      (do
        (js/console.log "[whiteboard] saving canvas to DB, page-id:" (:db/id page))
        (db/transact! (state/get-current-repo)
                      [{:db/id                   (:db/id page)
                        :block/whiteboard-canvas canvas-json
                        :block/updated-at        (.now js/Date)}]
                      {:outliner-op :save-block})
        true)
      (do (js/console.warn "[whiteboard] save-canvas-to-db! failed: page not found for uuid" page-uuid)
          false))))

(defn load-canvas-from-db
  "Returns the canvas JSON string stored on the page entity, or nil."
  [page-uuid]
  (when (seq page-uuid)
    (:block/whiteboard-canvas (db/entity [:block/uuid (uuid page-uuid)]))))

;; ── tag management ────────────────────────────────────────────────────────────

(def ^:private system-tag-idents
  "Tag idents that should not be shown as user tags in the whiteboard toolbar."
  #{:logseq.class/Whiteboard :logseq.class/Page :logseq.class/Journal})

(defn get-page-user-tags
  "Returns user-visible tags for a page entity (excludes internal system tags)."
  [page-entity]
  (->> (:block/tags page-entity)
       (remove #(contains? system-tag-idents (:db/ident %)))))

(defn search-tags
  "Returns user-created tag entities whose titles match `query`.
   Excludes built-in system classes (entities with :db/ident) which cannot
   be assigned to pages via set-block-property!.
   If query is blank, returns all eligible tag entities."
  [query]
  (when-let [database (db/get-db)]
    (let [all-tags (->> (d/q '[:find (pull ?b [:db/id :db/ident :block/uuid :block/title])
                               :where [?b :block/tags :logseq.class/Tag]]
                             database)
                        (map first)
                        (filter :block/title)
                        (remove :db/ident))]   ; built-in entities have :db/ident; user tags do not
      (if (string/blank? query)
        all-tags
        (let [q (string/lower-case (string/trim query))]
          (filter #(string/includes? (string/lower-case (or (:block/title %) "")) q) all-tags))))))

(defn add-tag-to-page!
  "Adds an existing tag entity to a whiteboard page."
  [page-uuid tag-entity]
  (when (and page-uuid tag-entity)
    (db-property-handler/set-block-property!
     [:block/uuid page-uuid] :block/tags (:db/id tag-entity))))

(defn remove-tag-from-page!
  "Removes a tag entity from a whiteboard page via a :db/retract transaction."
  [page-uuid tag-entity]
  (when (and page-uuid tag-entity)
    (when-let [page (db/entity [:block/uuid page-uuid])]
      (db/transact! (state/get-current-repo)
                    [[:db/retract (:db/id page) :block/tags (:db/id tag-entity)]]
                    {:outliner-op :save-block}))))

;; ── public API ────────────────────────────────────────────────────────────────

(defn <create-whiteboard!
  "Creates a new whiteboard page, tags it with :logseq.class/Whiteboard,
   and redirects the router to the whiteboard view.
   Returns nil (with a warning notification) if a whiteboard with the same name already exists."
  [name]
  (let [title (string/trim (or name "Untitled Whiteboard"))]
    (if (whiteboard-name-exists? title)
      (do (notification/show! (str "白板「" title "」已存在，请使用不同的名称") :warning)
          nil)
      (p/let [page (common-page-handler/<create! title {:redirect? false})]
        (when page
          (let [wclass (db/entity :logseq.class/Whiteboard)]
            (when wclass
              (db/transact! (state/get-current-repo)
                            [{:db/id       (:db/id page)
                              :block/tags  #{(:db/id wclass)}}]
                            {:outliner-op :save-block})))
          (route-handler/redirect!
           {:to          :whiteboard
            :path-params {:name (str (:block/uuid page))}})
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
