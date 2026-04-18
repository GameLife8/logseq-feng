(ns electron.server
  (:require ["@fastify/cors" :as FastifyCORS]
            ["electron" :refer [ipcMain]]
            ["fastify" :as Fastify]
            ["fs-extra" :as fs-extra]
            ["path" :as node-path]
            [camel-snake-kebab.core :as csk]
            [cljs-bean.core :as bean]
            [clojure.string :as string]
            [electron.configs :as cfgs]
            [electron.logger :as logger]
            [electron.utils :as utils]
            [electron.window :as window]
            [logseq.cli.common.mcp.server :as cli-common-mcp-server]
            [promesa.core :as p]))

(defonce ^:private *win (atom nil))
(defonce ^:private *server (atom nil))

(defn get-host [] (or (cfgs/get-item :server/host) "127.0.0.1"))
(defn get-port [] (or (cfgs/get-item :server/port) 12315))

(defonce *state
  (atom nil))

(defn- reset-state!
  []
  (reset! *state {:status    nil                            ;; :running :starting :closing :closed :error
                  :error     nil
                  :host      (get-host)
                  :port      (get-port)
                  :tokens    (cfgs/get-item :server/tokens)
                  :autostart (cfgs/get-item :server/autostart)
                  :mcp-enabled? (cfgs/get-item :server/mcp-enabled?)}))

(defn- set-status!
  ([status] (set-status! status nil))
  ([status error]
   (swap! *state assoc :status status :error error)))

(defn load-state-to-renderer!
  ([] (load-state-to-renderer! @*state))
  ([s]
   (doseq [^js w (window/get-all-windows)]
     (utils/send-to-renderer w :syncAPIServerState s))))

(defn set-config!
  [config]
  (when-let [config (and (map? config) (dissoc config :status))]
    (reset! *state (merge @*state config))
    (doseq [[k v] config]
      (cfgs/set-item! (keyword (str "server/" (name k))) v))
    (load-state-to-renderer!)))

(defn- setup-state-watch!
  []
  (add-watch *state ::ws #(load-state-to-renderer! %4))
  #(remove-watch *state ::ws))

(defn type-proxy-api? [s]
  (when (string? s)
    (string/starts-with? s "logseq.")))

(defn resolve-real-api-method
  [s]
  (when-not (string/blank? s)
    (if (type-proxy-api? s)
      (let [s' (string/split (string/trim s) ".")
            ns (some-> (second s') str (string/lower-case))
            method (some-> (last s') str)]
        (csk/->snake_case (str ns "@" method)))
      (string/trim s))))

(defn- validate-auth-token
  [token]
  (let [token (string/replace token "Bearer " "")]
    (when-let [valid-tokens (cfgs/get-item :server/tokens)]
      (when (or (string/blank? token)
                (not (some #(or (= % token)
                                (= (:value %) token)) valid-tokens)))
        (throw (js/Error. "Access Denied!"))))))

(defn- api-pre-handler!
  [^js req ^js rep callback]
  (if (= "/" (.-url req))
    (callback)
    (try
      (let [^js headers (.-headers req)]
        (validate-auth-token (.-authorization headers))
        (callback))
      (catch js/Error e
        (-> rep
            (.code 401)
            (.send e))))))

(defonce ^:private *cid (volatile! 0))
(defn- invoke-logseq-api!
  [method args]
  (p/create
   (fn [resolve _reject]
     (let [sid        (vswap! *cid inc)
           ret-handle (fn [^js _w ret] (resolve ret))]
       (utils/send-to-renderer @*win :invokeLogseqAPI {:syncId sid :method method :args args})
       (.handleOnce ipcMain (str ::sync! sid) ret-handle)))))

(defn- api-handler!
  [^js req ^js rep]
  (if-let [^js body (.-body req)]
    (if-let [method (resolve-real-api-method (.-method body))]
      (-> (invoke-logseq-api! method (.-args body))
          (p/then #(do
                     ;; Responses with an :error key are unexpected failures from electron.listener
                     (when-let [msg (and % (aget % "error"))]
                       (.code rep 500)
                       (js/console.error "Unexpected API error:" msg))
                     (.send rep %)))
          (p/catch #(.send rep %)))
      (-> rep
          (.code 400)
          (.send (js/Error. ":method of body is missing!"))))
    (throw (js/Error. "Body{:method :args} is required!"))))

;; ── REST v1 routes ──────────────────────────────────────────────────────────
;; Shared invoke helper for /api/v1/* — bridges HTTP → renderer via the same
;; IPC channel as /api, but with per-route method/args shaping instead of a
;; JSON-RPC body. Mirrors api-handler!'s `:error` → HTTP 500 convention.

(defn- truthy-query?
  [^js query k]
  (let [v (and query (aget query k))]
    (and v (= "true" (string/lower-case (str v))))))

(defn- send-rest-result!
  [^js rep result]
  (when-let [msg (and result (aget result "error"))]
    (.code rep 500)
    (js/console.error "Unexpected API error:" msg))
  (.send rep result))

(defn- rest-invoke!
  [^js rep method args]
  (-> (invoke-logseq-api! method args)
      (p/then #(send-rest-result! rep %))
      (p/catch #(.send rep %))))

(defn- v1-list-pages!
  [^js req ^js rep]
  (rest-invoke! rep "cli@list_pages"
                #js [#js {:expand (truthy-query? (.-query req) "expand")}]))

(defn- v1-get-page!
  [^js req ^js rep]
  (let [^js params (.-params req)
        name       (and params (.-name params))]
    (if (string/blank? name)
      (-> rep (.code 400) (.send (js/Error. "path parameter :name is required")))
      (rest-invoke! rep "cli@get_page_data" #js [name]))))

(defn- v1-get-block!
  [^js req ^js rep]
  (let [^js params (.-params req)
        uuid       (and params (.-uuid params))]
    (if (string/blank? uuid)
      (-> rep (.code 400) (.send (js/Error. "path parameter :uuid is required")))
      (rest-invoke! rep "block@get_block"
                    #js [uuid #js {:includePage (truthy-query? (.-query req) "includePage")}]))))

(defn- v1-get-block-tree!
  [^js req ^js rep]
  (let [^js params (.-params req)
        uuid       (and params (.-uuid params))]
    (if (string/blank? uuid)
      (-> rep (.code 400) (.send (js/Error. "path parameter :uuid is required")))
      (rest-invoke! rep "block@get_block"
                    #js [uuid #js {:includeChildren true :includePage true}]))))

(defn- v1-upsert!
  [^js req ^js rep]
  (let [^js body (.-body req)]
    (if-not body
      (-> rep (.code 400) (.send (js/Error. "Body{:operations [:dryRun]} is required")))
      (let [operations (.-operations body)
            dry-run    (.-dryRun body)]
        (if-not operations
          (-> rep (.code 400) (.send (js/Error. "body.operations is required (array)")))
          (rest-invoke! rep "cli@upsert_nodes"
                        #js [operations #js {:dry-run (boolean dry-run)}]))))))

;; Visual-doc routes. Whiteboards, sheets, and mind-maps are persisted in the
;; worker-managed sidecar (see `storage-model` skill) so their scene payload
;; is NOT reachable via upsertNodes. Each kind gets a flat create/read/update
;; REST triple that delegates to the already-wired cli API methods.
(def ^:private visual-doc-kinds
  {:whiteboard {:segment "whiteboards" :create-method "cli@create_whiteboard" :doc-type "whiteboard"}
   :sheet      {:segment "sheets"      :create-method "cli@create_sheet"      :doc-type "sheet"}
   :mind-map   {:segment "mind-maps"   :create-method "cli@create_mind_map"   :doc-type "mind-map"}})

(defn- v1-visual-doc-create-fn
  [{:keys [create-method]}]
  (fn [^js req ^js rep]
    (let [^js body (.-body req)
          nm       (and body (.-name body))]
      (if (or (nil? nm) (string/blank? (str nm)))
        (-> rep (.code 400) (.send (js/Error. "body.name is required")))
        (rest-invoke! rep create-method #js [nm])))))

(defn- v1-visual-doc-get-fn
  [{:keys [doc-type]}]
  (fn [^js req ^js rep]
    (let [^js params (.-params req)
          uuid       (and params (.-uuid params))]
      (if (string/blank? uuid)
        (-> rep (.code 400) (.send (js/Error. "path parameter :uuid is required")))
        (rest-invoke! rep "cli@get_visual_doc" #js [uuid doc-type])))))

(defn- v1-visual-doc-put-fn
  [{:keys [doc-type]}]
  (fn [^js req ^js rep]
    (let [^js params (.-params req)
          uuid       (and params (.-uuid params))
          ^js body   (.-body req)
          json-str   (and body (.-json body))]
      (cond
        (string/blank? uuid)
        (-> rep (.code 400) (.send (js/Error. "path parameter :uuid is required")))

        (or (not (string? json-str)) (string/blank? json-str))
        (-> rep (.code 400) (.send (js/Error. "body.json is required (full JSON payload as string — this is an overwrite, not a patch)")))

        :else
        (rest-invoke! rep "cli@update_visual_doc" #js [uuid doc-type json-str])))))

(defn- register-visual-doc-routes!
  [^js server]
  (doseq [[_ info] visual-doc-kinds]
    (let [collection (str "/api/v1/" (:segment info))
          item       (str collection "/:uuid")]
      (.post server collection (v1-visual-doc-create-fn info))
      (.get  server item       (v1-visual-doc-get-fn info))
      (.put  server item       (v1-visual-doc-put-fn info))))
  server)

(defn- register-v1-routes!
  [^js server]
  (doto server
    (.get    "/api/v1/pages"              v1-list-pages!)
    (.get    "/api/v1/pages/:name"        v1-get-page!)
    (.get    "/api/v1/blocks/:uuid"       v1-get-block!)
    (.get    "/api/v1/blocks/:uuid/tree"  v1-get-block-tree!)
    (.post   "/api/v1/upsert"             v1-upsert!)
    (register-visual-doc-routes!)))

(defn close!
  []
  (when (and @*server (contains? #{:running :error nil} (:status @*state)))
    (logger/debug "[server] closing ...")
    (set-status! :closing)
    (-> (.close @*server)
        (p/then (fn []
                  (reset! *server nil)
                  (set-status! :closed)))
        (p/catch (fn [^js e]
                   (set-status! :running e))))))

(defn- initialize-mcp-routes [^js server]
  (let [api-fn (fn api-fn [meth args]
                 (if-let [meth' (resolve-real-api-method meth)]
                   (invoke-logseq-api! meth' args)
                   #js {:error (str "No method found for " (pr-str meth))}))
        mcp-server (cli-common-mcp-server/create-mcp-api-server api-fn)]
    (logger/debug "[server] MCP routes initialized")
    (.post server "/mcp"
           #(cli-common-mcp-server/handle-post-request mcp-server {:port (get-port)
                                                                   :host (get-host)} %1 %2))
    (.get server "/mcp" cli-common-mcp-server/handle-get-request)
    (.delete server "/mcp" cli-common-mcp-server/handle-get-request)))

(defn start!
  []
  (-> (p/let [_     (close!)
              _     (set-status! :starting)
              ^js s (Fastify. #js {:logger                (not utils/win32?)
                                   :requestTimeout        (* 1000 42)
                                   :forceCloseConnections true})
              ;; middlewares
              _     (.register s FastifyCORS #js {:origin "*"})
              ;; hooks & routes
              _     (doto s
                      (.addHook "preHandler" api-pre-handler!)
                      (.post "/api" api-handler!)
                      (register-v1-routes!)
                      (.get "/" (fn [_ ^js rep]
                                  (let [html (fs-extra/readFileSync (.join node-path js/__dirname "./docs/api_server.html"))
                                        HOST (get-host)
                                        PORT (get-port)
                                        html (-> (str html)
                                                 (string/replace-first "${HOST}" HOST)
                                                 (string/replace-first "${PORT}" PORT))]
                                    (doto rep (.type "text/html")
                                          (.send html))))))
              _ (when (:mcp-enabled? @*state)
                  (initialize-mcp-routes s))
              ;; listen port
              _     (.listen s (bean/->js (select-keys @*state [:host :port])))]
        (reset! *server s)
        (set-status! :running))
      (p/then (fn [] (logger/debug "[server] start successfully!")))
      (p/catch (fn [^js e]
                 (set-status! :error e)
                 (logger/error "[server] start error! " e)))))

(defn do-server!
  [action]
  (case (keyword action)
    :start (when (contains? #{nil :closed :error} (:status @*state))
             (start!))
    :stop (close!)
    :restart (p/do! (close!) (start!))
    :else :dune))

(defn setup!
  [^js win]
  (reset! *win win)
  (let [t (setup-state-watch!)]
    (reset-state!) t))
