(ns logseq.cli.common.mcp.server
  "MCP server related fns shared between CLI and frontend"
  (:require ["@modelcontextprotocol/sdk/server/mcp.js" :refer [McpServer]]
            ["@modelcontextprotocol/sdk/server/streamableHttp.js" :refer [StreamableHTTPServerTransport]]
            ["@modelcontextprotocol/sdk/types.js" :refer [isInitializeRequest]]
            ["zod/v3" :as z] ;; zod 4 doesn't work w/ mcp - https://github.com/modelcontextprotocol/typescript-sdk/issues/925
            [promesa.core :as p]))

;; Server util fns
;; ===============
;; "Stores transports by session ID"
(defonce ^:private transports
  (atom {}))

;; See https://modelcontextprotocol.io/specification/2025-03-26/basic/transports#streamable-http
;; for how to respond to different MCP requests
(defn handle-post-request [mcp-server {:keys [port host]} req res]
  (let [session-id (aget (.-headers req) "mcp-session-id")]
    (js/console.log "POST /mcp request" session-id (pr-str (.-body req)))
    (cond
      (and session-id (@transports session-id))
      (let [^js transport (@transports session-id)]
        (.handleRequest transport (.-raw req) (.-raw res) (.-body req)))

      (and (not session-id)
           (isInitializeRequest (.-body req)))
      (let [transport (StreamableHTTPServerTransport.
                       #js {:sessionIdGenerator (comp str random-uuid)
                            :enableDnsRebindingProtection true
                            :allowedHosts #js [(str host ":" port)]})]
        (set! (.-onclose transport)
              (fn []
                (js/console.log "Transport closed" (.-sessionId transport))
                (swap! transports dissoc (.-sessionId transport))))
        (.connect mcp-server transport)
        (.handleRequest transport (.-raw req) (.-raw res) (.-body req))
        (js/console.log "Initialize sessionId" (.-sessionId transport))
        (if (.-sessionId transport)
          (swap! transports assoc (.-sessionId transport) transport)
          (js/console.error "No sessionId to initialize!"))
        res)

      :else
      (do
        (.code res 400)
        (.send res #js {:jsonrpc "2.0"
                        :error #js {:code -32000
                                    :message "Bad Request: No valid session ID provided"}
                        :id nil})))))

(defn handle-get-request
  [req res]
  (let [session-id (aget (.-headers req) "mcp-session-id")]
    (js/console.log "GET /mcp" session-id)
    (if-let [transport (and session-id (@transports session-id))]
      (.handleRequest ^js transport (.-raw req) (.-raw res))
      (-> res (.status 400) (.send res "Invalid or missing session ID")))))

(defn handle-delete-request
  [req res]
  (let [session-id (aget (.-headers req) "mcp-session-id")]
    (js/console.log "DELETE /mcp" session-id)
    (if-let [transport (and session-id (@transports session-id))]
      (do
        (.close transport)
        (-> res (.code 200) (.send #js {:ok true})))
      (-> res (.status 400) (.send res "Invalid or missing session ID")))))

(defn mcp-error-response [msg]
  #js {:content
       #js [#js {:type "text"
                 :text msg}]})

(defn mcp-success-response [data]
  (clj->js {:content
            [{:type "text"
              :text (js/JSON.stringify (clj->js data))}]}))

;; API tool fns
;; ============
(defn- unexpected-api-error [error]
  #js {:content
       #js [#js {:type "text"
                 :text (str "Unexpected API error: " (.-message error))}]})

(defn- api-tool
  "Calls API method w/ args and returns a MCP response"
  [api-fn api-method method-args]
  (-> (p/let [body (api-fn api-method method-args)]
        (if-let [error (and body (aget body "error"))]
          (mcp-error-response (str "API Error: " error))
          (mcp-success-response body)))
      (p/catch unexpected-api-error)))

(defn- api-get-page
  [call-api-fn args]
  (call-api-fn "logseq.cli.getPageData" [(aget args "pageName")]))

(defn- api-get-block
  [call-api-fn args]
  (call-api-fn "logseq.block.getBlock"
               [(aget args "uuid")
                #js {:includePage (aget args "includePage")}]))

(defn- api-get-block-tree
  [call-api-fn args]
  (call-api-fn "logseq.block.getBlock"
               [(aget args "uuid")
                #js {:includeChildren true
                     :includePage true}]))

(defn- api-list-pages
  [call-api-fn args]
  (call-api-fn "logseq.cli.listPages" [#js {:expand (aget args "expand")}]))

(defn- api-list-tags
  [call-api-fn args]
  (call-api-fn "logseq.cli.listTags" [#js {:expand (aget args "expand")}]))

(defn- api-list-properties
  [call-api-fn args]
  (call-api-fn "logseq.cli.listProperties" [#js {:expand (aget args "expand")}]))

(defn- api-search-blocks
  [call-api-fn args]
  (call-api-fn "logseq.app.search" [(aget args "searchTerm") #js {:enable-snippet? false}]))

(defn- api-upsert-nodes
  [call-api-fn args]
  (call-api-fn "logseq.cli.upsertNodes" [(aget args "operations") #js {:dry-run (aget args "dry-run")}]))

(defn- api-create-whiteboard
  [call-api-fn args]
  (call-api-fn "logseq.cli.createWhiteboard" [(aget args "name")]))

(defn- api-create-sheet
  [call-api-fn args]
  (call-api-fn "logseq.cli.createSheet" [(aget args "name")]))

(defn- api-create-mind-map
  [call-api-fn args]
  (call-api-fn "logseq.cli.createMindMap" [(aget args "name")]))

(defn- api-get-visual-doc
  [call-api-fn args]
  (call-api-fn "logseq.cli.getVisualDoc"
               [(aget args "pageUuid") (aget args "docType")]))

(defn- api-update-visual-doc
  [call-api-fn args]
  (call-api-fn "logseq.cli.updateVisualDoc"
               [(aget args "pageUuid") (aget args "docType") (aget args "json")]))

(def ^:large-vars/data-var api-tools
  "MCP Tools when calling API server"
  {:listPages
   {:fn api-list-pages
    :config #js {:title "List Pages"
                 :description "List all pages in a graph"
                 :inputSchema
                 #js {:expand (-> (z/boolean) .optional (.describe "Provide additional detail on each page"))}}}
   :getPage
   {:fn api-get-page
    :config #js {:title "Get Page"
                 :description "Get a page's content including its blocks. A property and a tag are pages."
                 :inputSchema #js {:pageName (-> (z/string) (.describe "The page's name or uuid"))}}}
   :getBlock
   {:fn api-get-block
    :config #js {:title "Get Block"
                 :description
                 "Fetch a single block by its uuid. Returns the block's title/content, properties, tags and immediate-child references (shallow). For a full nested subtree use getBlockTree instead. Blocks are the atomic unit of a Logseq graph; use this when you have a block uuid from searchBlocks or getPage and want its full details."
                 :inputSchema
                 #js {:uuid        (-> (z/string) (.describe "The block's uuid"))
                      :includePage (-> (z/boolean) .optional (.describe "Also include the block's owning page entity in the response"))}}}
   :getBlockTree
   {:fn api-get-block-tree
    :config #js {:title "Get Block Tree"
                 :description
                 "Fetch a block and its full nested children subtree by uuid. Equivalent to getBlock with recursive expansion. Always includes the owning page. Use this when you need the outline structure beneath a block — for example to summarize a section or rewrite a subtree."
                 :inputSchema
                 #js {:uuid (-> (z/string) (.describe "The block's uuid (root of the returned subtree)"))}}}
   :upsertNodes
   {:fn api-upsert-nodes
    :config
    #js {:title "Upsert Nodes"
         :description
         "This tool must be called at most once per user request. Never re-call it unless explicitly asked.
          It takes an object with field :operations, which is an array of operation objects.
          Each operation creates or edits a page, block, tag or property. Each operation is a object
          that must have :operation, :entityType and :data fields. More about fields in an operation object:
            * :operation  - Either :add or :edit
            * :entityType - What type of node, e.g. :block, :page, :tag or :property
            * :id - For :edit, this _must_ be a string uuid. For :add, use a temporary unique string if the new page is referenced by later operations e.g. add blocks
            * :data - A map of fields to set or update. This map can have the following keys:
              * :title - A page/tag/property's name or a block's content
              * :page-id - A page string uuid of a block. Required when adding a block.
              * :tags - A list of tags as string uuids
              * :property-type - A property's type
              * :property-cardinality - A property's cardinality. Must be :one or :many
              * :property-classes - A property's list of allowed tags, each being a uuid string or a tag's name
              * :class-extends - List of parent tags, each being a uuid string or a tag's name
              * :class-properties - A tag's list of properties, each eing a uuid string or a property's name

         Example inputs with their prompt, description and data as clojure EDN:

         Description: This input adds a new block to page with id '119268a6-704f-4e9e-8c34-36dfc6133729' and update the title of a page with uuid '119268a6-704f-4e9e-8c34-36dfc6133729':

         {:operations
          [{:operation :add
            :entityType :block
            :id nil
            :data {:page-id \"119268a6-704f-4e9e-8c34-36dfc6133729\"
                   :title \"New block text\"}}
           {:operation :edit
            :entity :page
            :id \"119268a6-704f-4e9e-8c34-36dfc6133729\"
            :data {:title \"Revised page title\"}}]}

        Prompt: Add task 't1' to new page 'Inbox'
        Description: This input creates a page 'Inbox' and adds a 't1' block with tag \"00000002-1282-1814-5700-000000000000\" (task) to it:

        {:operations
          [{:operation :add
            :entityType :page
            :id \"temp-Inbox\"
            :data {:title \"Inbox\"}}
           {:operation :add
            :entityType :block
            :data {:page-id \"temp-Inbox\"
                   :title \"t1\"
                   :tags [\"00000002-1282-1814-5700-000000000000\"]}}]}

         Additional advice for building operations:
         * Before creating any page, tag or property, check that it exists with getPage"
         :inputSchema
         #js {:operations
              (z/array
               (z/object
                #js {:operation   (z/enum #js ["add" "edit"])
                     :entityType  (z/enum #js ["block" "page" "tag" "property"])
                     :id          (.optional (z/union #js [(z/string) (z/number) (z/null)]))
                     :data        (-> (z/object #js {}) (.passthrough))}))
              :dry-run (-> (z/boolean) .optional (.describe "Pretend to do batch update. Does everything except actually commit change to db e.g. validation."))}}}
   :searchBlocks
   {:fn api-search-blocks
    :config #js {:title "Search Blocks"
                 :description "Search graph for blocks containing search term"
                 :inputSchema #js {:searchTerm (z/string)}}}
   :listTags
   {:fn api-list-tags
    :config #js {:title "List Tags"
                 :description "List all tags in a graph"
                 :inputSchema
                 #js {:expand (-> (z/boolean) .optional (.describe "Provide additional detail on each tag e.g. their parents (extends) and tag properties"))}}}
   :listProperties
   {:fn api-list-properties
    :config #js {:title "List Properties"
                 :description "List all properties in a graph"
                 :inputSchema
                 #js {:expand (-> (z/boolean) .optional (.describe "Provide additional detail on each property e.g. property type, cardinality"))}}}
   :createWhiteboard
   {:fn api-create-whiteboard
    :config
    #js {:title "Create Whiteboard"
         :description
         "Create a new whiteboard page. Whiteboards are Excalidraw-based visual canvases stored in a worker-managed sidecar (NOT in the DataScript block tree), so you MUST use this tool — upsertNodes cannot create them. Returns {pageUuid, title, docType}. The whiteboard starts empty; use updateVisualDoc with docType=\"whiteboard\" to set its Excalidraw scene JSON afterwards."
         :inputSchema
         #js {:name (-> (z/string) (.describe "The whiteboard page title. Must be unique per graph (case-insensitive). Duplicates are rejected with an error."))}}}
   :createSheet
   {:fn api-create-sheet
    :config
    #js {:title "Create Sheet"
         :description
         "Create a new sheet page. Sheets are Univer-based spreadsheets stored in a worker-managed sidecar (NOT in the DataScript block tree), so you MUST use this tool — upsertNodes cannot create them. Returns {pageUuid, title, docType}. The sheet starts with a default empty workbook (50×20); use updateVisualDoc with docType=\"sheet\" to overwrite it with a full Univer workbook JSON."
         :inputSchema
         #js {:name (-> (z/string) (.describe "The sheet page title. Must be unique per graph (case-insensitive). Duplicates are rejected with an error."))}}}
   :createMindMap
   {:fn api-create-mind-map
    :config
    #js {:title "Create Mind Map"
         :description
         "Create a new mind-map page. Mind maps are hierarchical tree structures stored in a worker-managed sidecar (NOT in the DataScript block tree), so you MUST use this tool — upsertNodes cannot create them. Returns {pageUuid, title, docType}. The mind-map starts with a single root node named after the title; use updateVisualDoc with docType=\"mind-map\" to set its full tree JSON."
         :inputSchema
         #js {:name (-> (z/string) (.describe "The mind-map page title. Must be unique per graph (case-insensitive). Duplicates are rejected with an error."))}}}
   :getVisualDoc
   {:fn api-get-visual-doc
    :config
    #js {:title "Get Visual Doc"
         :description
         "Fetch the JSON payload of a whiteboard / sheet / mind-map page. Use this INSTEAD of getPage when you need the actual scene data — getPage only returns block metadata for visual-doc pages (their payload lives in the sidecar, not in blocks). Returns {pageUuid, docType, source, json, needsFlush} where json is a string of the format documented by the respective editor (Excalidraw / Univer workbook / mind-map tree)."
         :inputSchema
         #js {:pageUuid (-> (z/string) (.describe "The visual-doc page's uuid. Find it via listPages (filter by Whiteboard / Sheet / MindMap tag) or from a createX tool response."))
              :docType  (-> (z/enum #js ["whiteboard" "sheet" "mind-map"])
                            (.describe "Which visual-doc kind to load. Must match the page's actual tag."))}}}
   :updateVisualDoc
   {:fn api-update-visual-doc
    :config
    #js {:title "Update Visual Doc"
         :description
         "Overwrite a visual-doc page's JSON payload and flush it to the sidecar. Use this for ANY edit to a whiteboard / sheet / mind-map scene — upsertNodes cannot touch sidecar payloads. The json argument must be a complete, valid payload in the format for the target docType:
           * whiteboard — Excalidraw scene: {\"elements\":[...],\"appState\":{...},\"files\":{...}}
           * sheet — Univer workbook: {\"id\":\"workbook_1\",\"name\":\"...\",\"sheetOrder\":[...],\"sheets\":{...}}
           * mind-map — tree: {\"data\":{\"text\":\"root\"},\"children\":[...]}
         Fetch the current payload with getVisualDoc first, mutate it, then call this tool with the full result. Returns {pageUuid, docType, updatedAt, writeToken, manifestStatus}."
         :inputSchema
         #js {:pageUuid (-> (z/string) (.describe "The visual-doc page's uuid."))
              :docType  (-> (z/enum #js ["whiteboard" "sheet" "mind-map"])
                            (.describe "Which visual-doc kind. Must match the page's actual tag."))
              :json     (-> (z/string) (.describe "The new payload as a JSON string. Must be a complete scene — this is an overwrite, not a patch."))}}}})

(defn call-api-tool [tool-fn api-fn args]
  (tool-fn (partial api-tool api-fn) args))

;; Server fns
;; ==========
(defn create-mcp-server []
  (McpServer. #js {:name "Logseq MCP Server"
                   :version "0.1.0"}))

(defn create-mcp-api-server [api-fn]
  (let [mcp-server (create-mcp-server)]
    (doseq [[k v] api-tools]
      (.registerTool mcp-server
                     (name k)
                     (:config v)
                     (partial call-api-tool (:fn v) api-fn)))
    mcp-server))