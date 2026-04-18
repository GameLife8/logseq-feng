(ns logseq.api.db-based.cli
  "API fns for CLI"
  (:require [clojure.string :as string]
            [frontend.handler.mind-map :as mind-map-handler]
            [frontend.handler.sheet :as sheet-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.handler.visual-doc :as visual-doc]
            [frontend.handler.whiteboard :as whiteboard-handler]
            [frontend.modules.outliner.op :as outliner-op]
            [frontend.modules.outliner.ui :as ui-outliner-tx]
            [frontend.state :as state]
            [logseq.cli.common.mcp.tools :as cli-common-mcp-tools]
            [logseq.common.config :as common-config]
            [logseq.db.sqlite.util :as sqlite-util]
            [promesa.core :as p]))

(defn list-tags
  [options]
  (p/let [resp (state/<invoke-db-worker :thread-api/api-list-tags
                                        (state/get-current-repo)
                                        (js->clj options :keywordize-keys true))]
    (clj->js resp)))

(defn list-properties
  [options]
  (p/let [resp (state/<invoke-db-worker :thread-api/api-list-properties
                                        (state/get-current-repo)
                                        (js->clj options :keywordize-keys true))]
    (clj->js resp)))

(defn list-pages
  [options]
  (p/let [resp (state/<invoke-db-worker :thread-api/api-list-pages
                                        (state/get-current-repo)
                                        (js->clj options :keywordize-keys true))]
    (clj->js resp)))

(defn get-page-data
  "Like get_page_blocks_tree but for MCP tools"
  [page-title]
  (p/let [resp (state/<invoke-db-worker :thread-api/api-get-page-data (state/get-current-repo) page-title)]
    (if resp
      (clj->js resp)
      #js {:error (str "Page " (pr-str page-title) " not found")})))

(defn upsert-nodes
  "Given a list of MCP operations, batch imports with resulting EDN data"
  [operations options*]
  (p/let [ops (js->clj operations :keywordize-keys true)
          {:keys [dry-run] :as options} (js->clj options* :keywordize-keys true)
          edn-data (state/<invoke-db-worker :thread-api/api-build-upsert-nodes-edn (state/get-current-repo) ops)
          {:keys [error]} (when-not dry-run
                            (ui-outliner-tx/transact!
                             {:outliner-op :batch-import-edn}
                             (outliner-op/batch-import-edn! edn-data {})))]
    (when error (throw (ex-info error {})))
    (ui-handler/re-render-root!)
    (cli-common-mcp-tools/summarize-upsert-operations ops options)))

(defn import-edn
  "Given EDN data as a transitized string, converts to EDN and imports it."
  [edn-data*]
  (p/let [edn-data (sqlite-util/transit-read edn-data*)
          {:keys [error]} (ui-outliner-tx/transact!
                           {:outliner-op :batch-import-edn}
                           (outliner-op/batch-import-edn! edn-data {}))]
    (when error (throw (ex-info error {})))
    (ui-handler/re-render-root!)))

;; Visual-doc tools (whiteboard / sheet / mind-map)
;; =================================================
;; These pages cannot be created or read through upsert-nodes because the
;; scene payload lives in a worker-owned sidecar, not in DataScript.
(defn- doc-type->attr
  [doc-type]
  (case doc-type
    "whiteboard" :block/whiteboard-canvas
    "sheet"      :block/sheet-data
    "mind-map"   :block/mind-map-data
    nil))

(defn- doc-type->cache-prefix
  [doc-type]
  (case doc-type
    "whiteboard" "whiteboard-data"
    "sheet"      "sheet-data"
    "mind-map"   "mind-map-data"
    nil))

(defn- page->result
  [page doc-type]
  (if page
    #js {:pageUuid (str (:block/uuid page))
         :title    (:block/title page)
         :docType  doc-type}
    #js {:error "Page with that title already exists or creation failed"}))

(defn create-whiteboard
  "Creates a new whiteboard page. Returns {:pageUuid :title :docType}."
  [name]
  (p/let [page (whiteboard-handler/<create-whiteboard! name {:redirect? false})]
    (page->result page "whiteboard")))

(defn create-sheet
  "Creates a new sheet page. Returns {:pageUuid :title :docType}."
  [name]
  (p/let [page (sheet-handler/<create-sheet! name {:redirect? false})]
    (page->result page "sheet")))

(defn create-mind-map
  "Creates a new mind-map page. Returns {:pageUuid :title :docType}."
  [name]
  (p/let [page (mind-map-handler/<create-mind-map! name {:redirect? false})]
    (page->result page "mind-map")))

(defn get-visual-doc
  "Loads a visual-doc payload for the given page uuid and doc-type.
   doc-type is one of: \"whiteboard\", \"sheet\", \"mind-map\"."
  [page-uuid doc-type]
  (let [attr         (doc-type->attr doc-type)
        cache-prefix (doc-type->cache-prefix doc-type)]
    (if (and attr cache-prefix)
      (p/let [result (visual-doc/<load-doc (state/get-current-repo) page-uuid attr cache-prefix)]
        (if result
          (clj->js {:pageUuid    page-uuid
                    :docType     doc-type
                    :source      (some-> (:source result) name)
                    :json        (:json result)
                    :needsFlush  (boolean (:needs-flush? result))})
          #js {:error (str "Visual-doc not found for uuid " page-uuid)}))
      #js {:error (str "Unknown docType: " (pr-str doc-type)
                       ". Expected one of \"whiteboard\", \"sheet\", \"mind-map\"")})))

(defn update-visual-doc
  "Writes a new JSON payload for a visual-doc page. Seeds the local draft cache
   then flushes to the durable sidecar + manifest."
  [page-uuid doc-type json-str]
  (let [attr         (doc-type->attr doc-type)
        cache-prefix (doc-type->cache-prefix doc-type)]
    (cond
      (not (and attr cache-prefix))
      #js {:error (str "Unknown docType: " (pr-str doc-type)
                       ". Expected one of \"whiteboard\", \"sheet\", \"mind-map\"")}

      (or (not (string? json-str)) (string/blank? json-str))
      #js {:error "json-str must be a non-empty JSON string"}

      :else
      (p/let [repo   (state/get-current-repo)
              _      (visual-doc/save-doc-cache! cache-prefix page-uuid json-str)
              result (visual-doc/<flush-doc! repo page-uuid attr json-str)]
        (if result
          (clj->js {:pageUuid       page-uuid
                    :docType        doc-type
                    :updatedAt      (:updated-at result)
                    :writeToken     (:write-token result)
                    :manifestStatus (some-> (:manifest-status result) name)})
          #js {:error (str "Flush failed. Page uuid " page-uuid " may not exist or payload is invalid.")})))))

(defn export-edn
  "Given sqlite.export options, exports the current graph as a json map with the
  :export-body key containing a transit string of the export EDN"
  [options*]
  (p/let [options (-> (js->clj options* :keywordize-keys true)
                      (update :export-type (fnil keyword :graph)))
          result (state/<invoke-db-worker :thread-api/export-edn (state/get-current-repo) options)]
    (when (:export-edn-error result)
      (throw (ex-info (str "Export EDN Error: " (:export-edn-error result)) {})))
    {:export-body (sqlite-util/transit-write result)
     :graph (string/replace-first (state/get-current-repo) common-config/db-version-prefix "")}))