(ns frontend.worker.visual-doc
  "VISUAL-DOC-SIDECAR: worker-only sqlite sidecar for whiteboard and mind-map
   content. These records must stay out of DataScript so page manifests remain
   lightweight in both worker and main-thread replicas.

   Schema v3 introduces normalized mind-map node rows and normalized whiteboard
   element rows so visual docs are no longer stored only as single blobs."
  (:require [cljs-bean.core :as bean]))

(def sidecar-path "/visual-doc.sqlite")

(def ^:private current-schema-version 3)
(def ^:private blob-storage-format "blob")
(def ^:private mind-map-node-storage-format "mind_map_nodes")
(def ^:private whiteboard-element-storage-format "whiteboard_elements")

(defn expected-storage-format
  [doc-type]
  (case doc-type
    "mind-map" mind-map-node-storage-format
    "whiteboard" whiteboard-element-storage-format
    blob-storage-format))

(defn- exec-ignore-error!
  [^js db sql]
  (try
    (.exec db sql)
    (catch :default _
      nil)))

(defn- first-row
  [rows]
  (when (pos? (alength rows))
    (aget rows 0)))

(defn- parse-json
  [json-str]
  (when (seq json-str)
    (try
      (js->clj (js/JSON.parse json-str) :keywordize-keys true)
      (catch :default _
        nil))))

(defn- stringify
  [value]
  (js/JSON.stringify (clj->js value)))

(defn- normalize-mind-map-node
  [node fallback-id]
  (let [data (or (:data node) {})
        uid  (or (:uid data) fallback-id (str (random-uuid)))]
    (-> node
        (assoc :data (assoc data :uid uid))
        (update :children #(vec (or % []))))))

(defn- flatten-mind-map-node
  [node parent-id child-order]
  (let [node'    (normalize-mind-map-node node nil)
        node-id  (get-in node' [:data :uid])
        children (vec (:children node'))
        row      {:node_id     node-id
                  :parent_id   parent-id
                  :child_order child-order
                  :node_json   (stringify (dissoc node' :children))
                  :node_text   (get-in node' [:data :text])}]
    (into [row]
          (mapcat (fn [[idx child]]
                    (flatten-mind-map-node child node-id idx)))
          (map-indexed vector children))))

(defn- flatten-mind-map
  [json-str]
  (when-let [root-node (some-> json-str parse-json)]
    (flatten-mind-map-node root-node nil 0)))

(defn- row->clj
  [row]
  (some-> row
          bean/->clj
          (update :updated_at #(when (some? %) (long %)))
          (update :schema_version #(when (some? %) (long %)))))

(defn- mind-map-node-rows
  [^js db page-uuid]
  (when (seq page-uuid)
    (->> (.exec db #js {:sql "select page_uuid, node_id, parent_id, child_order, node_json, node_text
                              from mind_map_nodes
                              where page_uuid = ?
                              order by child_order asc"
                        :bind #js [page-uuid]
                        :rowMode "object"})
         array-seq
         (map row->clj)
         vec)))

(defn- hydrate-mind-map
  [rows]
  (let [node-by-id (into {}
                         (map (fn [{:keys [node_id node_json]}]
                                [node_id (assoc (or (parse-json node_json) {}) :children [])]))
                         rows)
        children-by-parent (reduce (fn [acc {:keys [parent_id] :as row}]
                                     (update acc parent_id (fnil conj []) row))
                                   {}
                                   rows)
        build-node (fn build-node [node-id]
                     (when-let [node (get node-by-id node-id)]
                       (let [child-rows (sort-by :child_order (get children-by-parent node-id))]
                         (assoc node :children (into [] (keep #(build-node (:node_id %))) child-rows)))))
        root-id    (some->> (get children-by-parent nil)
                            (sort-by :child_order)
                            first
                            :node_id)]
    (when root-id
      (build-node root-id))))

(defn- by-id
  [rows id-key]
  (into {} (map (juxt id-key identity)) rows))

(defn- changed-row?
  [existing incoming fields]
  (boolean
   (some #(not= (get existing %) (get incoming %)) fields)))

(defn- delete-missing-rows!
  [^js db table page-uuid id-column stale-ids]
  (doseq [stale-id stale-ids]
    (.exec db #js {:sql (str "delete from " table " where page_uuid = ? and " id-column " = ?")
                   :bind #js [page-uuid stale-id]})))

(defn- upsert-mind-map-node-row!
  [^js db page-uuid {:keys [node_id parent_id child_order node_json node_text]}]
  (.exec db #js {:sql "insert into mind_map_nodes (page_uuid, node_id, parent_id, child_order, node_json, node_text)
                       values (?, ?, ?, ?, ?, ?)
                       on conflict(page_uuid, node_id) do update set
                         parent_id = excluded.parent_id,
                         child_order = excluded.child_order,
                         node_json = excluded.node_json,
                         node_text = excluded.node_text"
                 :bind #js [page-uuid node_id parent_id child_order node_json node_text]}))

(defn- flatten-whiteboard
  [json-str]
  (when-let [scene (some-> json-str parse-json)]
    {:elements (mapv (fn [[idx element]]
                       (let [element' (assoc element :id (or (:id element) (str (random-uuid))))]
                         {:element_id    (:id element')
                          :element_type  (:type element')
                          :element_order idx
                          :element_json  (stringify element')}))
                     (map-indexed vector (vec (or (:elements scene) []))))
     :app-state (or (:appState scene) {})}))

(defn- whiteboard-element-rows
  [^js db page-uuid]
  (when (seq page-uuid)
    (->> (.exec db #js {:sql "select page_uuid, element_id, element_type, element_order, element_json
                              from whiteboard_elements
                              where page_uuid = ?
                              order by element_order asc"
                        :bind #js [page-uuid]
                        :rowMode "object"})
         array-seq
         (map row->clj)
         vec)))

(defn- whiteboard-scene-meta
  [^js db page-uuid]
  (when (seq page-uuid)
    (let [rows (.exec db #js {:sql "select page_uuid, app_state_json
                                    from whiteboard_scene_meta
                                    where page_uuid = ?
                                    limit 1"
                              :bind #js [page-uuid]
                              :rowMode "object"})]
      (some-> rows first-row row->clj))))

(defn- hydrate-whiteboard
  [element-rows scene-meta]
  {:elements (mapv (fn [{:keys [element_json]}]
                     (or (parse-json element_json) {}))
                   (sort-by :element_order element-rows))
   :appState (or (some-> scene-meta :app_state_json parse-json) {})})

(defn- upsert-whiteboard-element-row!
  [^js db page-uuid {:keys [element_id element_type element_order element_json]}]
  (.exec db #js {:sql "insert into whiteboard_elements (page_uuid, element_id, element_type, element_order, element_json)
                       values (?, ?, ?, ?, ?)
                       on conflict(page_uuid, element_id) do update set
                         element_type = excluded.element_type,
                         element_order = excluded.element_order,
                         element_json = excluded.element_json"
                 :bind #js [page-uuid element_id element_type element_order element_json]}))

(defn- ensure-doc-columns!
  [^js db]
  (exec-ignore-error! db "alter table visual_docs add column schema_version integer not null default 1")
  (exec-ignore-error! db "alter table visual_docs add column storage_format text not null default 'blob'")
  db)

(defn ensure-table!
  [^js db]
  (.exec db "create table if not exists visual_docs (
               page_uuid text primary key,
               doc_type text not null,
               content text not null,
               updated_at integer not null,
               schema_version integer not null default 1,
               storage_format text not null default 'blob'
             )")
  (ensure-doc-columns! db)
  (.exec db "create index if not exists idx_visual_docs_type_updated_at
             on visual_docs(doc_type, updated_at desc)")
  (.exec db "create table if not exists mind_map_nodes (
               page_uuid text not null,
               node_id text not null,
               parent_id text,
               child_order integer not null,
               node_json text not null,
               node_text text,
               primary key (page_uuid, node_id)
             )")
  (.exec db "create index if not exists idx_mind_map_nodes_page_parent_order
             on mind_map_nodes(page_uuid, parent_id, child_order)")
  (.exec db "create table if not exists whiteboard_elements (
               page_uuid text not null,
               element_id text not null,
               element_type text,
               element_order integer not null,
               element_json text not null,
               primary key (page_uuid, element_id)
             )")
  (.exec db "create index if not exists idx_whiteboard_elements_page_order
             on whiteboard_elements(page_uuid, element_order)")
  (.exec db "create table if not exists whiteboard_scene_meta (
               page_uuid text primary key,
               app_state_json text not null
             )")
  (.exec db (str "pragma user_version = " current-schema-version))
  db)

(defn- upsert-blob-doc!
  [^js db {:keys [page-uuid doc-type content updated-at]}]
  (.exec db #js {:sql "insert into visual_docs (page_uuid, doc_type, content, updated_at, schema_version, storage_format)
                       values (?, ?, ?, ?, ?, ?)
                       on conflict(page_uuid) do update set
                         doc_type = excluded.doc_type,
                         content = excluded.content,
                         updated_at = excluded.updated_at,
                         schema_version = excluded.schema_version,
                         storage_format = excluded.storage_format"
                 :bind #js [page-uuid doc-type content updated-at current-schema-version blob-storage-format]})
  {:page-uuid      page-uuid
   :doc-type       doc-type
   :updated-at     updated-at
   :schema-version current-schema-version
   :storage-format blob-storage-format})

(defn- upsert-mind-map-doc!
  [^js db {:keys [page-uuid doc-type content updated-at]}]
  (if-let [rows (flatten-mind-map content)]
    (let [root-node (hydrate-mind-map rows)
          content'  (or (some-> root-node stringify) content)
          existing  (mind-map-node-rows db page-uuid)
          old-by-id (by-id existing :node_id)
          new-by-id (by-id rows :node_id)
          stale-ids (remove #(contains? new-by-id %) (keys old-by-id))
          changed?  (fn [incoming]
                      (let [existing-row (get old-by-id (:node_id incoming))]
                        (or (nil? existing-row)
                            (changed-row? existing-row incoming
                                          [:parent_id :child_order :node_json :node_text]))))]
      (delete-missing-rows! db "mind_map_nodes" page-uuid "node_id" stale-ids)
      (doseq [row rows
              :when (changed? row)]
        (upsert-mind-map-node-row! db page-uuid row))
      (.exec db #js {:sql "insert into visual_docs (page_uuid, doc_type, content, updated_at, schema_version, storage_format)
                           values (?, ?, ?, ?, ?, ?)
                           on conflict(page_uuid) do update set
                             doc_type = excluded.doc_type,
                             content = excluded.content,
                             updated_at = excluded.updated_at,
                             schema_version = excluded.schema_version,
                             storage_format = excluded.storage_format"
                     :bind #js [page-uuid doc-type content' updated-at current-schema-version mind-map-node-storage-format]})
      {:page-uuid      page-uuid
       :doc-type       doc-type
       :updated-at     updated-at
       :schema-version current-schema-version
       :storage-format mind-map-node-storage-format
       :content        content'})
    (upsert-blob-doc! db {:page-uuid page-uuid
                          :doc-type doc-type
                          :content content
                          :updated-at updated-at})))

(defn- upsert-whiteboard-doc!
  [^js db {:keys [page-uuid doc-type content updated-at]}]
  (if-let [{:keys [elements app-state]} (flatten-whiteboard content)]
    (let [content'     (stringify {:elements (mapv (fn [{:keys [element_json]}]
                                                    (or (parse-json element_json) {}))
                                                  (sort-by :element_order elements))
                                   :appState app-state})
          existing     (whiteboard-element-rows db page-uuid)
          old-by-id    (by-id existing :element_id)
          new-by-id    (by-id elements :element_id)
          stale-ids    (remove #(contains? new-by-id %) (keys old-by-id))
          app-state-js (stringify app-state)
          scene-meta   (whiteboard-scene-meta db page-uuid)
          changed?     (fn [incoming]
                         (let [existing-row (get old-by-id (:element_id incoming))]
                           (or (nil? existing-row)
                               (changed-row? existing-row incoming
                                             [:element_type :element_order :element_json]))))]
      (delete-missing-rows! db "whiteboard_elements" page-uuid "element_id" stale-ids)
      (doseq [row elements
              :when (changed? row)]
        (upsert-whiteboard-element-row! db page-uuid row))
      (when (not= (:app_state_json scene-meta) app-state-js)
        (.exec db #js {:sql "insert into whiteboard_scene_meta (page_uuid, app_state_json)
                             values (?, ?)
                             on conflict(page_uuid) do update set
                               app_state_json = excluded.app_state_json"
                       :bind #js [page-uuid app-state-js]}))
      (.exec db #js {:sql "insert into visual_docs (page_uuid, doc_type, content, updated_at, schema_version, storage_format)
                           values (?, ?, ?, ?, ?, ?)
                           on conflict(page_uuid) do update set
                             doc_type = excluded.doc_type,
                             content = excluded.content,
                             updated_at = excluded.updated_at,
                             schema_version = excluded.schema_version,
                             storage_format = excluded.storage_format"
                     :bind #js [page-uuid doc-type content' updated-at current-schema-version whiteboard-element-storage-format]})
      {:page-uuid      page-uuid
       :doc-type       doc-type
       :updated-at     updated-at
       :schema-version current-schema-version
       :storage-format whiteboard-element-storage-format
       :content        content'})
    (upsert-blob-doc! db {:page-uuid page-uuid
                          :doc-type doc-type
                          :content content
                          :updated-at updated-at})))

(defn upsert-doc!
  [^js db {:keys [doc-type] :as doc}]
  (case doc-type
    "mind-map" (upsert-mind-map-doc! db doc)
    "whiteboard" (upsert-whiteboard-doc! db doc)
    (upsert-blob-doc! db doc)))

(defn get-doc
  [^js db page-uuid]
  (when (seq page-uuid)
    (let [rows (.exec db #js {:sql "select page_uuid, doc_type, content, updated_at, schema_version, storage_format
                                    from visual_docs
                                    where page_uuid = ?
                                    limit 1"
                              :bind #js [page-uuid]
                              :rowMode "object"})
          row  (some-> rows first-row row->clj)]
      (when row
        (case (:storage_format row)
          "mind_map_nodes"
          (let [root-node (some-> (mind-map-node-rows db page-uuid) hydrate-mind-map)]
            (assoc row :content (or (some-> root-node stringify)
                                    (:content row))))

          "whiteboard_elements"
          (let [scene (hydrate-whiteboard (or (whiteboard-element-rows db page-uuid) [])
                                          (whiteboard-scene-meta db page-uuid))]
            (assoc row :content (or (some-> scene stringify)
                                    (:content row))))

          row)))))

(defn delete-doc!
  [^js db page-uuid]
  (if (seq page-uuid)
    (do
      (.exec db #js {:sql "delete from mind_map_nodes where page_uuid = ?"
                     :bind #js [page-uuid]})
      (.exec db #js {:sql "delete from whiteboard_elements where page_uuid = ?"
                     :bind #js [page-uuid]})
      (.exec db #js {:sql "delete from whiteboard_scene_meta where page_uuid = ?"
                     :bind #js [page-uuid]})
      (.exec db #js {:sql "delete from visual_docs where page_uuid = ?"
                     :bind #js [page-uuid]})
      true)
    false))
