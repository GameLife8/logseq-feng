(ns frontend.handler.whiteboard
  "Handlers for creating, opening and managing whiteboard pages.

   Whiteboard pages are tagged with :logseq.class/Whiteboard in :block/tags
   so they remain queryable from DataScript and visible in the Pages view.

   VISUAL-DOC-SIDECAR: the page entity is a lightweight manifest only.
   The full Excalidraw payload lives in the worker sqlite sidecar."
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
                                          [?t :db/ident :logseq.class/Whiteboard]
                                          [(missing? $ ?b :db/ident)]
                                          [(missing? $ ?b :logseq.property/deleted-at)]]
                                 database)
                           (map first))
          ;; Strategy 2: user tag named "Whiteboard"
          with-user-tag (->> (d/q '[:find (pull ?b [:db/id :block/uuid :block/title :block/updated-at])
                                     :where [?t :block/title "Whiteboard"]
                                            [(missing? $ ?t :db/ident)]
                                            [?b :block/tags ?t]
                                            [(missing? $ ?b :db/ident)]
                                            [(missing? $ ?b :logseq.property/deleted-at)]]
                                   database)
                             (map first))
          result (->> (concat with-class with-user-tag)
                      (into {} (map (juxt :db/id identity)))  ; deduplicate by :db/id
                      vals
                      (filter :block/title)
                      (sort-by #(or (:block/updated-at %) 0) >))]
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
   Resolves truthy only after the DB flush completes."
  [page-uuid canvas-json]
  (if-not (and (seq page-uuid) (seq canvas-json))
    (do
      (js/console.warn "[whiteboard] save-canvas-to-db! skipped: missing page-uuid or canvas-json")
      (p/resolved false))
    (-> (visual-doc/<flush-doc! (state/get-current-repo) page-uuid canvas-attr canvas-json)
        (p/then (fn [result]
                  (boolean result)))
        (p/catch (fn [error]
                   (js/console.error "[whiteboard] save-canvas-to-db! failed:" error)
                   false)))))

(defn <load-canvas-doc
  "Loads the whiteboard document using the worker DB first, then resolves
   whether DB or local cache is newer."
  [page-uuid]
  (visual-doc/<load-doc (state/get-current-repo) page-uuid canvas-attr canvas-cache-prefix))

(defn load-canvas-from-db
  "Best-effort preview reader for gallery thumbnails from local draft cache."
  [page-uuid]
  (when (seq page-uuid)
    (some-> (visual-doc/read-doc-cache canvas-cache-prefix page-uuid) :data)))

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
  "Creates a new whiteboard page, tags it as a whiteboard, and optionally redirects.
   Tagging strategy (applied in order, both if possible):
   1. Tag with :logseq.class/Whiteboard system class (if the class exists in DB)
   2. Tag with the 'Whiteboard' user tag page (find or create it)
   Returns nil with a warning notification if the name already exists.
   Pass {:redirect? false} to skip navigation (e.g. when embedding via slash command)."
  ([name] (<create-whiteboard! name {}))
  ([name {:keys [redirect?] :or {redirect? true}}]
   (let [title (string/trim (or name "Untitled Whiteboard"))]
     (if (whiteboard-name-exists? title)
       (do (notification/show! (str "白板「" title "」已存在，请使用不同的名称") :warning)
           nil)
       (p/let [page (common-page-handler/<create! title {:redirect? false})]
         (when page
           (let [repo     (state/get-current-repo)
                 tags-ids (atom #{})]
             ;; Strategy 1: system class tag
             (when-let [wclass (db/entity :logseq.class/Whiteboard)]
               (swap! tags-ids conj (:db/id wclass)))
              ;; Strategy 2: user tag page named "Whiteboard"
              (let [database (db/get-db)
                   tag-eid  (first (d/q '[:find [?e ...]
                                          :where [?e :block/title "Whiteboard"]
                                                 [(missing? $ ?e :db/ident)]]
                                        database))]
               (when tag-eid
                 (swap! tags-ids conj tag-eid)))
             ;; Apply all collected tags in one transaction
             (when (seq @tags-ids)
               (db/transact! repo
                             [{:db/id      (:db/id page)
                               :block/tags @tags-ids}]
                             {:outliner-op :save-block})))
           (when redirect?
             (route-handler/redirect!
              {:to          :whiteboard
               :path-params {:name (str (:block/uuid page))}}))
           page))))))

#_(defn <delete-whiteboard!
  "Deletes a whiteboard page. Shows success notification on completion."
  [page-uuid-str]
  (let [page (db/entity [:block/uuid (uuid page-uuid-str)])]
    (cond
      (nil? page)
      (do
        (notification/show! "白板页面未找到" :warning)
        (p/resolved false))

      (:db/ident page)
      (do
        (notification/show! "内置白板页面不能删除" :warning)
        (p/resolved false))

      :else
      (common-page-handler/<delete!
       (uuid page-uuid-str)
       (fn [] (notification/show! "白板已删除" :success))))))

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

;; VISUAL-DOC-SIDECAR: redefine delete flow near EOF so the sidecar payload is
;; removed before the page manifest, without rewriting the whole file again.
(defn <delete-whiteboard!
  "Deletes a whiteboard page after removing its sidecar payload."
  [page-uuid-str]
  (let [page (db/entity [:block/uuid (uuid page-uuid-str)])]
    (cond
      (nil? page)
      (do
        (notification/show! "白板页面未找到" :warning)
        (p/resolved false))

      (:db/ident page)
      (do
        (notification/show! "内置白板页面不能删除" :warning)
        (p/resolved false))

      (:logseq.property/deleted-at page)
      (do
        (notification/show! "该白板已被删除" :warning)
        (p/resolved false))

      :else
      (p/do!
       (visual-doc/<delete-doc! (state/get-current-repo) page-uuid-str canvas-cache-prefix)
       (common-page-handler/<delete!
        (uuid page-uuid-str)
        (fn [] (notification/show! "白板已删除" :success))
        :error-handler (fn [] (notification/show! "删除白板失败" :error)))))))
