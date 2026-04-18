(ns frontend.worker.visual-doc
  "VISUAL-DOC-SIDECAR: worker-only sqlite sidecar for whiteboard and mind-map
   content. These records must stay out of DataScript so page manifests remain
   lightweight in both worker and main-thread replicas.

   Schema v4 keeps the durable manifest row as a blob snapshot while normalized
   rows become derived indexes for large documents. Saves must succeed on the
   blob path even when background index rebuilds fail."
  (:require [cljs-bean.core :as bean]
            [logseq.common.util :as common-util]))

(def sidecar-path "/visual-doc.sqlite")

(def ^:private current-schema-version 5)
(def ^:private blob-storage-format "blob")
(def ^:private mind-map-node-storage-format "mind_map_nodes")
(def ^:private whiteboard-element-storage-format "whiteboard_elements")
(def ^:private normalized-min-content-bytes (* 256 1024))

(defn expected-storage-format
  [_doc-type]
  blob-storage-format)

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

(defn- exec-select
  "Run a SELECT query and return results as a vector of CLJS maps.
   Avoids rowMode:'object' which fails on OpfsSAHPoolDb in setTimeout contexts."
  [^js db sql bind-arr]
  (let [results  (volatile! [])
        col-arr  #js []]
    (.exec db #js {:sql         sql
                   :bind        bind-arr
                   :columnNames col-arr
                   :callback    (fn [row]
                                  (let [obj #js {}]
                                    (dotimes [i (alength row)]
                                      (aset obj (aget col-arr i) (aget row i)))
                                    (vswap! results conj (bean/->clj obj))))})
    @results))

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

(defn- error-message
  [error]
  (or (some-> error ex-message)
      (some-> error .-message)
      (str error)))

(defn- value-debug
  [value]
  {:type    (str (type value))
   :nil?    (nil? value)
   :string? (string? value)
   :number? (number? value)
   :map?    (map? value)
   :vector? (vector? value)
   :count   (when (or (string? value) (vector? value) (seqable? value))
              (count value))})

(defn- log-derived-index-error!
  [stage payload error]
  (js/console.error
   (str "[visual-doc-debug] " stage)
   (clj->js (assoc payload :message (error-message error)))))

(defn- mind-map-row-debug
  [{:keys [node_id parent_id child_order node_json node_text]}]
  {:node-id     (value-debug node_id)
   :parent-id   (value-debug parent_id)
   :child-order (value-debug child_order)
   :node-json   (value-debug node_json)
   :node-text   (value-debug node_text)})

(defn- whiteboard-row-debug
  [{:keys [element_id element_type element_order element_json]}]
  {:element-id    (value-debug element_id)
   :element-type  (value-debug element_type)
   :element-order (value-debug element_order)
   :element-json  (value-debug element_json)})

(defn- content-size-bytes
  [content]
  (let [content' (str (or content ""))]
    (if (exists? js/TextEncoder)
      (.-length (.encode (js/TextEncoder.) content'))
      (count content'))))

(defn- supports-derived-index?
  [doc-type]
  (contains? #{"mind-map" "whiteboard"} (str doc-type)))

(defn- index-format-for-doc-type
  [doc-type]
  (case (str doc-type)
    "mind-map" mind-map-node-storage-format
    "whiteboard" whiteboard-element-storage-format
    nil))

(defn- wants-derived-index?
  [doc-type content-size]
  (and (supports-derived-index? doc-type)
       (>= (or content-size 0) normalized-min-content-bytes)))

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
        row      {:node_id     (str node-id)
                  :parent_id   (some-> parent-id str)
                  :child_order (js/Number child-order)
                  :node_json   (stringify (dissoc node' :children))
                  :node_text   (let [t (get-in node' [:data :text])]
                                 (when (some? t) (str t)))}]
    (into [row]
          (mapcat (fn [[idx child]]
                    (flatten-mind-map-node child node-id idx)))
          (map-indexed vector children))))

(defn- flatten-mind-map
  [json-str]
  (when-let [root-node (some-> json-str parse-json)]
    (flatten-mind-map-node root-node nil 0)))

(defn- normalize-number-fields
  "Coerce known numeric fields to js/Number to avoid 32-bit int truncation."
  [m]
  (cond-> m
    (some? (:updated_at m))        (update :updated_at js/Number)
    (some? (:schema_version m))    (update :schema_version js/Number)
    (some? (:content_size m))      (update :content_size js/Number)
    (some? (:source_updated_at m)) (update :source_updated_at js/Number)
    (some? (:built_at m))          (update :built_at js/Number)
    (some? (:child_order m))       (update :child_order js/Number)
    (some? (:element_order m))     (update :element_order js/Number)))

(defn- row->clj
  "Convert a raw JS object row to CLJS map with number coercion."
  [row]
  (some-> row
          bean/->clj
          normalize-number-fields))

(defn- normalize-manifest-row
  [row]
  (let [content-size (:content_size row)]
    (if (and row (seq (:content row)) (or (nil? content-size) (zero? content-size)))
      (assoc row :content_size (content-size-bytes (:content row)))
      row)))

(defn- mind-map-node-rows
  [^js db page-uuid]
  (when (seq page-uuid)
    (mapv normalize-number-fields
          (exec-select db
            "select page_uuid, node_id, parent_id, child_order, node_json, node_text
             from mind_map_nodes where page_uuid = ? order by child_order asc"
            #js [(str page-uuid)]))))

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

(defn- upsert-mind-map-node-row!
  [^js db page-uuid {:keys [node_id parent_id child_order node_json node_text]}]
  (try
    (.exec db #js {:sql "insert into mind_map_nodes (page_uuid, node_id, parent_id, child_order, node_json, node_text)
                         values (?, ?, ?, ?, ?, ?)
                         on conflict(page_uuid, node_id) do update set
                           parent_id = excluded.parent_id,
                           child_order = excluded.child_order,
                           node_json = excluded.node_json,
                           node_text = excluded.node_text"
                   :bind #js [(str page-uuid)
                              (str node_id)
                              (some-> parent_id str)
                              (js/Number child_order)
                              (str node_json)
                              node_text]})
    (catch :default error
      (log-derived-index-error!
       "mind-map node upsert failed"
       {:page-uuid (value-debug page-uuid)
        :row       (mind-map-row-debug {:node_id node_id
                                        :parent_id parent_id
                                        :child_order child_order
                                        :node_json node_json
                                        :node_text node_text})}
       error)
      (throw error))))

(defn- flatten-whiteboard
  [json-str]
  (when-let [scene (some-> json-str parse-json)]
    {:elements (mapv (fn [[idx element]]
                       (let [element' (assoc element :id (or (:id element) (str (random-uuid))))]
                         {:element_id    (str (:id element'))
                          :element_type  (some-> (:type element') str)
                          :element_order (js/Number idx)
                          :element_json  (stringify element')}))
                     (map-indexed vector (vec (or (:elements scene) []))))
     :app-state (or (:appState scene) {})}))

(defn- whiteboard-element-rows
  [^js db page-uuid]
  (when (seq page-uuid)
    (mapv normalize-number-fields
          (exec-select db
            "select page_uuid, element_id, element_type, element_order, element_json
             from whiteboard_elements where page_uuid = ? order by element_order asc"
            #js [(str page-uuid)]))))

(defn- whiteboard-scene-meta
  [^js db page-uuid]
  (when (seq page-uuid)
    (first (exec-select db
             "select page_uuid, app_state_json from whiteboard_scene_meta where page_uuid = ? limit 1"
             #js [(str page-uuid)]))))

(defn- hydrate-whiteboard
  [element-rows scene-meta]
  {:elements (mapv (fn [{:keys [element_json]}]
                     (or (parse-json element_json) {}))
                   (sort-by :element_order element-rows))
   :appState (or (some-> scene-meta :app_state_json parse-json) {})})

(defn- upsert-whiteboard-element-row!
  [^js db page-uuid {:keys [element_id element_type element_order element_json]}]
  (try
    (.exec db #js {:sql "insert into whiteboard_elements (page_uuid, element_id, element_type, element_order, element_json)
                         values (?, ?, ?, ?, ?)
                         on conflict(page_uuid, element_id) do update set
                           element_type = excluded.element_type,
                           element_order = excluded.element_order,
                           element_json = excluded.element_json"
                   :bind #js [(str page-uuid)
                              (str element_id)
                              (some-> element_type str)
                              (js/Number element_order)
                              (str element_json)]})
    (catch :default error
      (log-derived-index-error!
       "whiteboard element upsert failed"
       {:page-uuid (value-debug page-uuid)
        :row       (whiteboard-row-debug {:element_id element_id
                                          :element_type element_type
                                          :element_order element_order
                                          :element_json element_json})}
       error)
      (throw error))))

(defn- ensure-doc-columns!
  [^js db]
  (exec-ignore-error! db "alter table visual_docs add column schema_version integer not null default 1")
  (exec-ignore-error! db "alter table visual_docs add column storage_format text not null default 'blob'")
  (exec-ignore-error! db "alter table visual_docs add column content_size integer not null default 0")
  (exec-ignore-error! db "alter table visual_docs add column write_token text not null default ''")
  db)

(defn ensure-table!
  [^js db]
  (.exec db "create table if not exists visual_docs (
               page_uuid text primary key,
               doc_type text not null,
               content text not null,
               updated_at integer not null,
               schema_version integer not null default 1,
               storage_format text not null default 'blob',
               content_size integer not null default 0,
               write_token text not null default ''
              )")
  (ensure-doc-columns! db)
  (.exec db "create index if not exists idx_visual_docs_type_updated_at
             on visual_docs(doc_type, updated_at desc)")
  (.exec db "create table if not exists visual_doc_indexes (
               page_uuid text primary key,
               doc_type text not null,
               index_format text not null,
               source_updated_at integer not null,
               built_at integer not null
             )")
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

(defn- manifest-row
  [^js db page-uuid]
  (when (seq page-uuid)
     (let [rows (exec-select db
                  "select page_uuid, doc_type, content, updated_at, schema_version, storage_format, content_size, write_token
                   from visual_docs where page_uuid = ? limit 1"
                  #js [(str page-uuid)])]
      (some-> (first rows) normalize-number-fields normalize-manifest-row))))

(defn- index-state
  [^js db page-uuid]
  (when (seq page-uuid)
    (some-> (first (exec-select db
                     "select page_uuid, doc_type, index_format, source_updated_at, built_at
                      from visual_doc_indexes where page_uuid = ? limit 1"
                     #js [(str page-uuid)]))
            normalize-number-fields)))

(defn- upsert-visual-docs-row!
  "Shared helper to insert/update the visual_docs manifest row."
  [^js db page-uuid doc-type content updated-at storage-format content-size write-token]
  (.exec db #js {:sql "insert into visual_docs (page_uuid, doc_type, content, updated_at, schema_version, storage_format, content_size, write_token)
                       values (?, ?, ?, ?, ?, ?, ?, ?)
                       on conflict(page_uuid) do update set
                         doc_type = excluded.doc_type,
                         content = excluded.content,
                         updated_at = excluded.updated_at,
                         schema_version = excluded.schema_version,
                         storage_format = excluded.storage_format,
                         content_size = excluded.content_size,
                         write_token = excluded.write_token"
                  :bind #js [(str page-uuid)
                             (str doc-type)
                             (str content)
                             (js/Number updated-at)
                             current-schema-version
                             (str storage-format)
                             (js/Number content-size)
                             (str (or write-token ""))]}))

(defn- set-index-state!
  [^js db page-uuid doc-type source-updated-at built-at]
  (when-let [index-format (index-format-for-doc-type doc-type)]
    (try
      (.exec db #js {:sql "insert into visual_doc_indexes (page_uuid, doc_type, index_format, source_updated_at, built_at)
                           values (?, ?, ?, ?, ?)
                           on conflict(page_uuid) do update set
                             doc_type = excluded.doc_type,
                             index_format = excluded.index_format,
                             source_updated_at = excluded.source_updated_at,
                             built_at = excluded.built_at"
                     :bind #js [(str page-uuid)
                                (str doc-type)
                                (str index-format)
                                (js/Number source-updated-at)
                                (js/Number built-at)]})
      (catch :default error
        (log-derived-index-error!
         "index-state upsert failed"
         {:page-uuid         (value-debug page-uuid)
          :doc-type          (value-debug doc-type)
          :index-format      (value-debug index-format)
          :source-updated-at (value-debug source-updated-at)
          :built-at          (value-debug built-at)}
         error)
        (throw error)))))

(defn- clear-derived-index-state!
  [^js db page-uuid]
  (when (seq page-uuid)
    (.exec db #js {:sql "delete from visual_doc_indexes where page_uuid = ?"
                   :bind #js [(str page-uuid)]})))

(defn- derived-index-current?
  [^js db {:keys [page_uuid doc_type updated_at content_size]}]
  (when (wants-derived-index? doc_type content_size)
    (let [expected-format (index-format-for-doc-type doc_type)
          state           (index-state db page_uuid)]
      (and state
           (= (:doc_type state) (str doc_type))
           (= (:index_format state) expected-format)
           (= (:source_updated_at state) updated_at)))))

(defn- clear-derived-index-rows!
  [^js db page-uuid]
  (let [page-uuid' (str page-uuid)]
    (.exec db #js {:sql "delete from mind_map_nodes where page_uuid = ?"
                   :bind #js [page-uuid']})
    (.exec db #js {:sql "delete from whiteboard_elements where page_uuid = ?"
                   :bind #js [page-uuid']})
    (.exec db #js {:sql "delete from whiteboard_scene_meta where page_uuid = ?"
                   :bind #js [page-uuid']})))

(defn- clear-derived-index!
  [^js db page-uuid]
  (clear-derived-index-state! db page-uuid)
  (clear-derived-index-rows! db page-uuid))

(defn- run-in-transaction!
  [^js db f]
  (.exec db "BEGIN")
  (try
    (let [result (f)]
      (.exec db "COMMIT")
      result)
    (catch :default e
      (exec-ignore-error! db "ROLLBACK")
      (throw e))))

(defn- upsert-whiteboard-scene-meta!
  [^js db page-uuid app-state]
  (try
    (.exec db #js {:sql "insert into whiteboard_scene_meta (page_uuid, app_state_json)
                         values (?, ?)
                         on conflict(page_uuid) do update set
                           app_state_json = excluded.app_state_json"
                   :bind #js [(str page-uuid)
                              (str (stringify app-state))]})
    (catch :default error
      (log-derived-index-error!
       "whiteboard scene meta upsert failed"
       {:page-uuid (value-debug page-uuid)
        :app-state (value-debug app-state)}
       error)
      (throw error))))

(defn- upsert-blob-doc!
  [^js db {:keys [page-uuid doc-type content updated-at write-token]}]
  (let [content'      (str content)
        content-size  (content-size-bytes content')
        needs-index?  (wants-derived-index? doc-type content-size)]
    ;; Atomic: either both the manifest row and the derived-index clear succeed
    ;; together or both roll back. Without the transaction, a mid-operation
    ;; crash can leave visual_docs advanced past its matching index state,
    ;; making `derived-index-current?` return true while rows are stale.
    (run-in-transaction!
     db
     (fn []
       (upsert-visual-docs-row! db page-uuid doc-type content' updated-at blob-storage-format content-size write-token)
       (if needs-index?
         (clear-derived-index-state! db page-uuid)
         (clear-derived-index! db page-uuid))
       {:page-uuid      page-uuid
        :doc-type       doc-type
        :updated-at     updated-at
        :schema-version current-schema-version
        :storage-format blob-storage-format
        :content-size   content-size
        :write-token    (or write-token "")}))))

(defn- rebuild-mind-map-index!
  [^js db {:keys [page_uuid doc_type content updated_at]}]
  (if-let [rows (flatten-mind-map content)]
    (run-in-transaction!
     db
     (fn []
       (clear-derived-index-rows! db page_uuid)
       (doseq [row rows]
         (upsert-mind-map-node-row! db page_uuid row))
       (set-index-state! db page_uuid doc_type updated_at (common-util/time-ms))
       {:status     :rebuilt
        :page-uuid  page_uuid
        :doc-type   doc_type
        :updated-at updated_at}))
    (do
      (clear-derived-index! db page_uuid)
      {:status :invalid-content
       :page-uuid page_uuid
       :doc-type doc_type
       :updated-at updated_at})))

(defn- rebuild-whiteboard-index!
  [^js db {:keys [page_uuid doc_type content updated_at]}]
  (if-let [{:keys [elements app-state]} (flatten-whiteboard content)]
    (run-in-transaction!
     db
     (fn []
       (clear-derived-index-rows! db page_uuid)
       (doseq [row elements]
         (upsert-whiteboard-element-row! db page_uuid row))
       (upsert-whiteboard-scene-meta! db page_uuid app-state)
       (set-index-state! db page_uuid doc_type updated_at (common-util/time-ms))
       {:status     :rebuilt
        :page-uuid  page_uuid
        :doc-type   doc_type
        :updated-at updated_at}))
    (do
      (clear-derived-index! db page_uuid)
      {:status :invalid-content
       :page-uuid page_uuid
       :doc-type doc_type
       :updated-at updated_at})))

(defn upsert-doc!
  [^js db doc]
  (upsert-blob-doc! db doc))

(defn rebuild-derived-index!
  [^js db {:keys [page-uuid doc-type updated-at]}]
  (if-let [row (manifest-row db page-uuid)]
    (cond
      (not= (:doc_type row) (str doc-type))
      {:status :stale
       :page-uuid page-uuid
       :doc-type (:doc_type row)
       :updated-at (:updated_at row)}

      (not= (:updated_at row) (js/Number updated-at))
      {:status :stale
       :page-uuid page-uuid
       :doc-type (:doc_type row)
       :updated-at (:updated_at row)}

      (not (wants-derived-index? (:doc_type row) (:content_size row)))
      (do
        (clear-derived-index! db page-uuid)
        {:status :cleared
         :page-uuid page-uuid
         :doc-type (:doc_type row)
         :updated-at (:updated_at row)})

      :else
      (case (:doc_type row)
        "mind-map" (rebuild-mind-map-index! db row)
        "whiteboard" (rebuild-whiteboard-index! db row)
        (do
          (clear-derived-index! db page-uuid)
          {:status :unsupported
           :page-uuid page-uuid
           :doc-type (:doc_type row)
           :updated-at (:updated_at row)})))
    {:status :missing
     :page-uuid page-uuid
     :doc-type doc-type
     :updated-at updated-at}))

(defn get-doc
  [^js db page-uuid]
  (when-let [row (manifest-row db page-uuid)]
    (let [row' (cond-> row
                 (derived-index-current? db row)
                 (assoc :derived_index_current true))]
      ;; Schema v5+ only writes `blob` (see upsert-blob-doc!). The two non-blob
      ;; branches below hydrate legacy rows written by earlier schema versions
      ;; (pre-v5), where the canonical payload lived in the per-type side
      ;; tables (mind_map_nodes / whiteboard_elements). Kept for backward
      ;; compatibility — no active write path lands here any more.
      (case (:storage_format row')
      "mind_map_nodes"              ; legacy pre-v5 rows only
      (let [root-node (some-> (mind-map-node-rows db page-uuid) hydrate-mind-map)]
        (assoc row' :content (or (some-> root-node stringify)
                                 (:content row'))))

      "whiteboard_elements"         ; legacy pre-v5 rows only
      (let [scene (hydrate-whiteboard (or (whiteboard-element-rows db page-uuid) [])
                                      (whiteboard-scene-meta db page-uuid))]
        (assoc row' :content (or (some-> scene stringify)
                                 (:content row'))))

      row'))))

(defn delete-doc!
  ([^js db page-uuid]
   (delete-doc! db page-uuid nil))
  ([^js db page-uuid write-token]
  (if (seq page-uuid)
    (if (and (seq write-token)
             (not= (:write_token (manifest-row db page-uuid)) (str write-token)))
      false
      (do
        (clear-derived-index! db page-uuid)
        (.exec db #js {:sql "delete from visual_docs where page_uuid = ?"
                       :bind #js [(str page-uuid)]})
        true))
    false)))
