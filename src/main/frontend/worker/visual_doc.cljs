(ns frontend.worker.visual-doc
  "VISUAL-DOC-SIDECAR: worker-only sqlite sidecar for whiteboard and mind-map
   content. These records must stay out of DataScript so page manifests remain
   lightweight in both worker and main-thread replicas."
  (:require [cljs-bean.core :as bean]))

(def sidecar-path "/visual-doc.sqlite")

(defn ensure-table!
  [^js db]
  (.exec db "create table if not exists visual_docs (
               page_uuid text primary key,
               doc_type text not null,
               content text not null,
               updated_at integer not null
             )")
  (.exec db "create index if not exists idx_visual_docs_type_updated_at
             on visual_docs(doc_type, updated_at desc)")
  db)

(defn upsert-doc!
  [^js db {:keys [page-uuid doc-type content updated-at]}]
  (.exec db #js {:sql "insert into visual_docs (page_uuid, doc_type, content, updated_at)
                       values (?, ?, ?, ?)
                       on conflict(page_uuid) do update set
                         doc_type = excluded.doc_type,
                         content = excluded.content,
                         updated_at = excluded.updated_at"
                 :bind #js [page-uuid doc-type content updated-at]})
  {:page-uuid  page-uuid
   :doc-type   doc-type
   :updated-at updated-at})

(defn get-doc
  [^js db page-uuid]
  (when (seq page-uuid)
    (let [rows (.exec db #js {:sql "select page_uuid, doc_type, content, updated_at
                                    from visual_docs
                                    where page_uuid = ?
                                    limit 1"
                              :bind #js [page-uuid]
                              :rowMode "object"})
          row  (when (pos? (alength rows)) (aget rows 0))]
      (some-> row bean/->clj (update :updated_at #(when (some? %) (long %)))))))

(defn delete-doc!
  [^js db page-uuid]
  (when (seq page-uuid)
    (.exec db #js {:sql "delete from visual_docs where page_uuid = ?"
                   :bind #js [page-uuid]}))
  true)
