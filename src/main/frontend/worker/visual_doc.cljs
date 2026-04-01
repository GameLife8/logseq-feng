(ns frontend.worker.visual-doc
  "VISUAL-DOC-SIDECAR: worker-only sqlite sidecar for whiteboard and mind-map
   content. These records must stay out of DataScript so page manifests remain
   lightweight in both worker and main-thread replicas.

   Schema v2 introduces normalized mind-map node rows so the mind-map payload is
   no longer stored only as a single blob."
  (:require [cljs-bean.core :as bean]))

(def sidecar-path "/visual-doc.sqlite")

(def ^:private current-schema-version 2)
(def ^:private blob-storage-format "blob")
(def ^:private mind-map-node-storage-format "mind_map_nodes")

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
        row      {:node-id     node-id
                  :parent-id   parent-id
                  :child-order child-order
                  :node-json   (stringify (dissoc node' :children))
                  :node-text   (get-in node' [:data :text])}]
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
                         (assoc node :children (mapv #(build-node (:node_id %)) child-rows)))))
        root-id    (some->> (get children-by-parent nil)
                            (sort-by :child_order)
                            first
                            :node_id)]
    (when root-id
      (build-node root-id))))

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
          content'  (or (some-> root-node stringify) content)]
      (.exec db #js {:sql "delete from mind_map_nodes where page_uuid = ?"
                     :bind #js [page-uuid]})
      (doseq [{:keys [node-id parent-id child-order node-json node-text]} rows]
        (.exec db #js {:sql "insert into mind_map_nodes (page_uuid, node_id, parent_id, child_order, node_json, node_text)
                             values (?, ?, ?, ?, ?, ?)"
                       :bind #js [page-uuid node-id parent-id child-order node-json node-text]}))
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

(defn upsert-doc!
  [^js db {:keys [doc-type] :as doc}]
  (if (= "mind-map" doc-type)
    (upsert-mind-map-doc! db doc)
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
        (if (= mind-map-node-storage-format (:storage_format row))
          (let [root-node (some-> (mind-map-node-rows db page-uuid) hydrate-mind-map)]
            (assoc row :content (or (some-> root-node stringify)
                                    (:content row))))
          row)))))

(defn delete-doc!
  [^js db page-uuid]
  (when (seq page-uuid)
    (.exec db #js {:sql "delete from mind_map_nodes where page_uuid = ?"
                   :bind #js [page-uuid]})
    (.exec db #js {:sql "delete from visual_docs where page_uuid = ?"
                   :bind #js [page-uuid]}))
  true)
