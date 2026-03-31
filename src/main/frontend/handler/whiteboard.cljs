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
             [frontend.handler.page :as page-handler]
             [frontend.handler.route :as route-handler]
             [frontend.handler.visual-doc :as visual-doc]
             [frontend.state :as state]
             [promesa.core :as p]))

(def canvas-attr :block/whiteboard-canvas)
(def canvas-cache-prefix "whiteboard-data")

;; ── whiteboard identification ─────────────────────────────────────────────────

(defn get-all-whiteboards
  "Returns whiteboard page entities from the local DataScript replica (best-effort).
   Used only for duplicate-name checks; the gallery UI uses react/q for authoritative data."
  []
  (when-let [database (db/get-db)]
    (let [;; Strategy 1: system class tag
          with-class  (->> (d/q '[:find (pull ?b [:db/id :block/uuid :block/title :block/updated-at])
                                   :where [?b :block/tags ?t]
                                          [?t :db/ident :logseq.class/Whiteboard]]
                                 database)
                           (map first))
          ;; Strategy 2: canvas attribute
          with-canvas (->> (d/q '[:find (pull ?b [:db/id :block/uuid :block/title :block/updated-at])
                                   :where [?b :block/whiteboard-canvas _]]
                                 database)
                           (map first))
          ;; Strategy 3: user tag named "Whiteboard"
          with-user-tag (->> (d/q '[:find (pull ?b [:db/id :block/uuid :block/title :block/updated-at])
                                     :where [?t :block/title "Whiteboard"]
                                            [(missing? $ ?t :db/ident)]
                                            [?b :block/tags ?t]
                                            [(missing? $ ?b :db/ident)]]
                                   database)
                             (map first))
          result (->> (concat with-class with-canvas with-user-tag)
                      (into {} (map (juxt :db/id identity)))  ; deduplicate by :db/id
                      vals
                      (filter :block/title)
                      (sort-by :block/updated-at >))]
      result)))

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

(defn <load-canvas-doc
  "Loads the whiteboard document using the worker DB first, then resolves
   whether DB or local cache is newer."
  [page-uuid]
  (visual-doc/<load-doc (state/get-current-repo) page-uuid canvas-attr canvas-cache-prefix))

(defn load-canvas-from-db
  "Returns the canvas JSON string stored on the page entity, or nil."
  [page-uuid]
  (when (seq page-uuid)
    (canvas-attr (db/entity [:block/uuid (uuid page-uuid)]))))

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
  "Creates a new whiteboard page, tags it as a whiteboard, and redirects.
   Tagging strategy (applied in order, both if possible):
   1. Tag with :logseq.class/Whiteboard system class (if the class exists in DB)
   2. Tag with the 'Whiteboard' user tag page (find or create it)
   Returns nil with a warning notification if the name already exists."
  [name]
  (let [title (string/trim (or name "Untitled Whiteboard"))]
    (if (whiteboard-name-exists? title)
      (do (notification/show! (str "白板「" title "」已存在，请使用不同的名称") :warning)
          nil)
      (p/let [page (common-page-handler/<create! title {:redirect? false})]
        (js/console.log "[wb] <create-whiteboard! created page:" (some-> page :db/id))
        (when page
          (let [repo     (state/get-current-repo)
                tags-ids (atom #{})]
            ;; Strategy 1: system class tag
            (when-let [wclass (db/entity :logseq.class/Whiteboard)]
              (js/console.log "[wb] applying :logseq.class/Whiteboard tag, id=" (:db/id wclass))
              (swap! tags-ids conj (:db/id wclass)))
             ;; Strategy 2: user tag page named "Whiteboard"
             (let [database (db/get-db)
                  tag-eid  (first (d/q '[:find [?e ...]
                                         :where [?e :block/title "Whiteboard"]
                                                [(missing? $ ?e :db/ident)]]
                                       database))]
              (if tag-eid
                (do (js/console.log "[wb] applying user 'Whiteboard' tag, id=" tag-eid)
                    (swap! tags-ids conj tag-eid))
                (js/console.log "[wb] no user 'Whiteboard' tag page found; skipping strategy 2")))
            ;; Apply all collected tags in one transaction
            (when (seq @tags-ids)
              (db/transact! repo
                            [{:db/id      (:db/id page)
                              :block/tags @tags-ids}]
                            {:outliner-op :save-block})))
          (route-handler/redirect!
           {:to          :whiteboard
            :path-params {:name (str (:block/uuid page))}})
          page)))))

(defn <delete-whiteboard!
  "Deletes a whiteboard page. Shows success notification on completion."
  [page-uuid-str]
  (common-page-handler/<delete!
   (uuid page-uuid-str)
   (fn [] (notification/show! "白板已删除" :success))))

(defn <rename-whiteboard!
  "Renames a whiteboard page. Rejects duplicate names (case-insensitive)."
  [page-uuid-str new-name]
  (let [trimmed (string/trim new-name)]
    (if (whiteboard-name-exists? trimmed)
      (do (notification/show! (str "白板「" trimmed "」已存在，请使用不同的名称") :warning)
          nil)
      (p/do!
       (page-handler/rename! page-uuid-str trimmed)
       (notification/show! "白板已重命名" :success)))))

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
