(ns frontend.handler.excalidraw-config
  "Manages Excalidraw/Whiteboard user settings stored as a dedicated page entity.

   Config page:  title  = \"logseq/excalidraw\"
                 tag    = \"ConfigPage\" class entity (find or create with :class? true)
                 attr   = :block/excalidraw-config  (JSON string)

   Config map keys (ClojureScript, keywordized):
     :embed-whitelist    – newline-separated domain list, e.g. \"example.com\\nyoutube.com\".
                           Use \"*\" (alone) to allow every URL.
                           Empty string / nil → block all iframe embeds.
     :font-path-virgil   – absolute path (or file:// URL) to a TTF/OTF/WOFF2 that
                           overrides Excalidraw's built-in Virgil (font-family 1).
                           Leave blank to use the built-in font.
     :font-path-helvetica – same for Helvetica (font-family 2).
     :font-path-cascadia  – same for Cascadia (font-family 3)."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.handler.common.page :as common-page-handler]
            [frontend.handler.notification :as notification]
            [frontend.state :as state]
            [promesa.core :as p]))

;; ── constants ─────────────────────────────────────────────────────────────────

(def ^:private config-page-title "logseq/excalidraw")
(def ^:private config-attr       :block/excalidraw-config)
(def ^:private tag-title         "ConfigPage")

(def default-config
  {:embed-whitelist     ""   ; block all by default (empty = deny all)
   :font-path-virgil    ""   ; built-in Virgil
   :font-path-helvetica ""   ; built-in Helvetica
   :font-path-cascadia  ""}) ; built-in Cascadia

;; ── read ─────────────────────────────────────────────────────────────────────

(defn get-config
  "Read config from the dedicated page entity.  Returns default-config if the
   page doesn't exist yet or the attribute is missing / malformed."
  []
  (if-let [page (db/get-page config-page-title)]
    (if-let [raw (get page config-attr)]
      (try (merge default-config
                  (js->clj (js/JSON.parse raw) :keywordize-keys true))
           (catch :default e
             (js/console.warn "[ex-cfg] JSON parse error" e)
             default-config))
      default-config)
    default-config))

(defn <get-config
  "Async version of get-config.
   Uses db-async/<get-block to fetch the config page directly from the worker
   DB, bypassing the lazy main-thread DataScript replica.  This ensures the
   :block/excalidraw-config attribute is available even on first load after a
   page refresh.  Returns a Promise resolving to the config map."
  []
  (let [repo (state/get-current-repo)]
    (js/console.log "[ex-cfg] <get-config called, repo=" repo)
    (p/let [page (db-async/<get-block repo config-page-title :children? false)]
      (js/console.log "[ex-cfg] <get-block returned page=" (clj->js page)
                      "db/id=" (when page (:db/id page))
                      "all-keys=" (when page (clj->js (keys (into {} page))))
                      ":block/excalidraw-config=" (when page (:block/excalidraw-config page)))
      (if-let [raw (and page (:block/excalidraw-config page))]
        (try (let [cfg (merge default-config
                              (js->clj (js/JSON.parse raw) :keywordize-keys true))]
               (js/console.log "[ex-cfg] parsed config=" (clj->js cfg))
               cfg)
             (catch :default e
               (js/console.warn "[ex-cfg] async JSON parse error" e)
               default-config))
        (do
          (js/console.log "[ex-cfg] no :block/excalidraw-config found, returning default")
          default-config)))))

;; ── write ─────────────────────────────────────────────────────────────────────

(defn- <ensure-class-tag!
  "Find or create a Class entity (valid for :block/tags) with the given title.
   Class entities have :logseq.class/Tag in their :block/tags.
   Uses {:class? true} option which causes create! to build a proper class entity
   with a :db/ident in the user.class namespace."
  [title]
  (let [database (db/get-db)
        ;; Search for an existing class with this title
        ;; [:find [?e ...]] 返回直接值向量，用 first，不能用 ffirst
        existing-eid (when database
                       (first (d/q '[:find [?e ...]
                                     :in $ ?t
                                     :where [?e :block/title ?t]
                                            [?e :block/tags ?tag]
                                            [?tag :db/ident :logseq.class/Tag]]
                                   database title)))]
    (if existing-eid
      (do (js/console.log "[ex-cfg] found existing class tag" title "id=" existing-eid)
          (p/resolved (db/entity existing-eid)))
      (do (js/console.log "[ex-cfg] creating class tag" title)
          (common-page-handler/<create! title {:redirect? false :class? true})))))

(defn- ensure-config-page!
  "Find or create the config page, tagging it with the 'Excalidraw' class tag.
   Returns a Promise<page-entity>."
  []
  (let [existing (db/get-page config-page-title)]
    (if existing
      ;; Page exists – ensure it still has the tag (idempotent)
      (p/let [tag (<ensure-class-tag! tag-title)]
        (when (and tag existing
                   (not (some #(= (:db/id tag) (:db/id %))
                              (:block/tags existing))))
          (js/console.log "[ex-cfg] re-applying Excalidraw tag to existing config page")
          (db/transact! (state/get-current-repo)
                        [{:db/id      (:db/id existing)
                          :block/tags #{(:db/id tag)}}]
                        {:outliner-op :save-block}))
        existing)
      ;; Page doesn't exist – create page, then create/find tag, then apply
      (p/let [page (common-page-handler/<create! config-page-title {:redirect? false})
              tag  (<ensure-class-tag! tag-title)]
        (when (and page tag)
          (js/console.log "[ex-cfg] tagging new config page with" tag-title "id=" (:db/id tag))
          (db/transact! (state/get-current-repo)
                        [{:db/id      (:db/id page)
                          :block/tags #{(:db/id tag)}}]
                        {:outliner-op :save-block}))
        page))))

(defn save-config!
  "Persist `config-map` to the config page entity.
   Creates the page (and tags it) if it doesn't exist yet."
  [config-map]
  (js/console.log "[ex-cfg] save-config!" (clj->js config-map))
  (p/let [page (ensure-config-page!)]
    (if page
      (let [repo   (state/get-current-repo)
            merged (merge default-config config-map)]
        (db/transact! repo
                      [{:db/id             (:db/id page)
                        config-attr        (js/JSON.stringify (clj->js merged))
                        :block/updated-at  (.now js/Date)}]
                      {:outliner-op :save-block})
        (js/console.log "[ex-cfg] saved OK"))
      (notification/show! "无法创建 Excalidraw 配置页" :error))))

;; ── embed-whitelist helpers ───────────────────────────────────────────────────

(defn parse-whitelist
  "Parse the newline-separated whitelist string into a vector of trimmed domain strings."
  [raw]
  (->> (string/split (or raw "") #"\n")
       (map string/trim)
       (filter seq)
       vec))

(defn make-validate-embeddable
  "Return a value suitable for Excalidraw's :validateEmbeddable prop.
   - nil / empty whitelist → false (block all)
   - whitelist contains \"*\" → true (allow all)
   - otherwise → a JS function checking the URL hostname against the whitelist"
  [whitelist-str]
  (let [domains (parse-whitelist whitelist-str)]
    (cond
      (empty? domains)
      false

      (some #{"*"} domains)
      true

      :else
      (fn [url]
        (try
          (let [hostname (.-hostname (js/URL. url))]
            (boolean (some (fn [d]
                             (or (= hostname d)
                                 (string/ends-with? hostname (str "." d))))
                           domains)))
          (catch :default _ false))))))
