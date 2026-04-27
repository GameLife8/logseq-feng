(ns electron.db
  "Provides SQLite dbs for electron and manages files of those dbs.

   Supports a per-graph custom root directory: users can pick any folder
   when creating a graph, and its db.sqlite + backups are stored there
   instead of the default `<homedir>/logseq/graphs/<graph>` location.

   The map of custom paths lives in electron userData configs.edn under
   `:db/graph-paths`. Because the IPC boundary routes JS objects through
   `cljs-bean/->clj` (which keywordizes keys), every lookup in that map
   must tolerate both string and keyword repo names."
  (:require ["fs-extra" :as fs]
            ["path" :as node-path]
            [clojure.string :as string]
            [electron.backup-file :as backup-file]
            [electron.configs :as cfgs]
            [electron.logger :as logger]
            [logseq.cli.common.graph :as cli-common-graph]
            [logseq.common.config :as common-config]
            [logseq.db.common.sqlite :as common-sqlite]))

;; ---------------------------------------------------------------------------
;; graph-paths config helpers
;; ---------------------------------------------------------------------------

(defn- as-keyword
  "Coerce a repo name into a keyword usable as a graph-paths map key.
   Strings like \"logseq_db_foo\" become `:logseq_db_foo`, already-keywords
   pass through, everything else returns nil."
  [repo]
  (cond
    (keyword? repo) repo
    (string? repo) (keyword repo)
    :else nil))

(defn- as-string
  "Coerce to a plain string repo name (removes leading `:` for keywords)."
  [repo]
  (cond
    (keyword? repo) (name repo)
    (string? repo) repo
    :else nil))

(defn- normalize-graph-paths
  "Ensures every key in the raw config map is a keyword and every value a
   trimmed string. Drops entries that cannot be coerced. The persisted form
   is always keyword-keyed."
  [raw]
  (reduce-kv
   (fn [acc k v]
     (if-let [k' (as-keyword k)]
       (if (and (string? v) (seq (string/trim v)))
         (assoc acc k' (string/trim v))
         acc)
       acc))
   {}
   (or raw {})))

(defn get-graph-paths
  "Reads the current `:db/graph-paths` map from configs.edn, normalized."
  []
  (normalize-graph-paths (cfgs/get-item common-config/db-graph-paths-config-key)))

(defn- lookup-graph-path
  "Returns the custom directory for `repo` if one is configured, else nil.
   Tolerates both string and keyword forms of `repo`."
  [repo]
  (let [m (get-graph-paths)]
    (or (get m (as-keyword repo))
        (get m (as-string repo)))))

(defn set-graph-path!
  "Persist a custom directory for `repo`. The directory is created if it
   does not already exist. Passing nil/blank `root` removes any override.
   Returns the effective directory path."
  [repo root]
  (let [k (as-keyword repo)
        current (get-graph-paths)]
    (if (and k (string? root) (seq (string/trim root)))
      (let [dir (string/trim root)
            _ (fs/ensureDirSync dir)
            next-map (assoc current k dir)]
        (cfgs/set-item! common-config/db-graph-paths-config-key next-map)
        dir)
      (do
        (when k
          (let [next-map (dissoc current k)]
            (cfgs/set-item! common-config/db-graph-paths-config-key next-map)))
        nil))))

(defn unset-graph-path!
  "Remove the custom directory override for `repo` (if any)."
  [repo]
  (set-graph-path! repo nil))

;; ---------------------------------------------------------------------------
;; path composition
;; ---------------------------------------------------------------------------

(defn- default-graph-dir
  "Default location under `<homedir>/logseq/graphs/<sanitized-db-name>`."
  [db-name]
  (node-path/join (cli-common-graph/get-db-graphs-dir)
                  (common-sqlite/sanitize-db-name db-name)))

(defn get-graph-dir
  "Resolves the on-disk directory that holds a graph's db.sqlite and
   backups. Uses the per-graph override if configured, otherwise the
   default under the graphs root."
  [db-name]
  (or (lookup-graph-path db-name)
      (default-graph-dir db-name)))

(defn- graph-db-path
  [db-name]
  (node-path/join (get-graph-dir db-name) "db.sqlite"))

(defn- graph-backups-path
  [db-name]
  (node-path/join (get-graph-dir db-name) "backups"))

;; ---------------------------------------------------------------------------
;; directory bootstrap
;; ---------------------------------------------------------------------------

(defn ensure-graphs-dir!
  "Ensure the default graphs-root directory exists. Still required because
   `getGraphs` scans it for legacy / default-located graphs."
  []
  (fs/ensureDirSync (cli-common-graph/get-db-graphs-dir)))

(defn ensure-graph-dir!
  "Ensure the specific graph's directory (custom or default) exists and
   return its absolute path."
  [db-name]
  (ensure-graphs-dir!)
  (let [graph-dir (get-graph-dir db-name)]
    (fs/ensureDirSync graph-dir)
    graph-dir))

;; ---------------------------------------------------------------------------
;; db read / write / unlink
;; ---------------------------------------------------------------------------

(defn get-db
  [db-name]
  (let [_ (ensure-graph-dir! db-name)
        db-path (graph-db-path db-name)]
    (when (fs/existsSync db-path)
      (fs/readFileSync db-path))))

(defn save-db!
  [db-name data]
  (let [_ (ensure-graph-dir! db-name)
        db-path (graph-db-path db-name)
        old-data (when (fs/existsSync db-path) (fs/readFileSync db-path))
        backups-path (graph-backups-path db-name)]
    (when old-data
      (backup-file/backup-file db-name nil nil
                               ".sqlite"
                               old-data
                               {:backups-dir backups-path
                                :truncate-daily? true
                                :keep-versions 12}))
    (fs/writeFileSync db-path data)))

(defn unlink-graph!
  "Move a graph's directory to the 'Unlinked graphs' folder under the
   default graphs root (regardless of whether the source was custom-pathed).
   Clears any custom-path override."
  [repo]
  (let [db-name (common-sqlite/sanitize-db-name repo)
        src-path (get-graph-dir repo)
        unlinked (node-path/join (cli-common-graph/get-db-graphs-dir)
                                 common-config/unlinked-graphs-dir)
        new-path (node-path/join unlinked db-name)
        new-path-exists? (fs/existsSync new-path)
        dest-path (if new-path-exists?
                    (node-path/join unlinked (str db-name "-" (random-uuid)))
                    new-path)]
    (when (fs/existsSync src-path)
      (fs/ensureDirSync unlinked)
      (try
        (fs/moveSync src-path dest-path)
        (catch :default e
          (logger/error ::unlink-graph {:repo repo :src src-path :dest dest-path} e))))
    (unset-graph-path! repo)))

;; ---------------------------------------------------------------------------
;; graph enumeration
;; ---------------------------------------------------------------------------

(defn get-custom-path-graphs
  "Returns full graph names (with `logseq_db_` prefix) for any entries in
   `:db/graph-paths` whose directory actually exists on disk. Used to
   surface custom-located graphs in the graph list alongside default ones."
  []
  (->> (get-graph-paths)
       (keep (fn [[k v]]
               (let [name (as-string k)]
                 (when (and name
                            (string/starts-with? name common-config/db-version-prefix)
                            (string? v)
                            (fs/existsSync v))
                   name))))
       vec))
