(ns frontend.handler.excalidraw-config
  "Manages Excalidraw/Whiteboard user settings stored as a dedicated page entity.

   Config page:  title  = \"excalidraw-config\"
                 tag    = \"ConfigPage\" class entity (find or create with :class? true)
                 attr   = :block/excalidraw-config  (JSON string)

   NOTE: The page name cannot contain \"/\" — Logseq DB graphs reject such names.
   Using \"excalidraw-config\" (hyphen) avoids namespace splitting and the
   worker-side validation error.  The page is tagged #ConfigPage so it is
   clearly identifiable as a system settings page.

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

(def ^:private config-page-title "excalidraw-config")
(def ^:private config-attr       :block/excalidraw-config)
(def ^:private tag-title         "ConfigPage")

(def default-config
  {:embed-whitelist     ""   ; block all by default (empty = deny all)
   :font-path-virgil    ""   ; built-in Virgil
   :font-path-helvetica ""   ; built-in Helvetica
   :font-path-cascadia  ""}) ; built-in Cascadia

;; ── read ─────────────────────────────────────────────────────────────────────

(defn get-config
  "Synchronous read – only works when the page is already in the main-thread
   DataScript replica (e.g. after an async load has transacted it in).
   Returns default-config if the page is absent or the attribute is missing."
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
  "Async config load.  Queries the worker DB directly via thread-api/pull with
   [:block/name config-page-title], which the worker resolves via ldb/get-page.
   This bypasses the lazy main-thread DataScript replica so the attribute is
   always visible regardless of broadcast timing.
   Returns a Promise resolving to the config map."
  []
  (let [repo (state/get-current-repo)]
    (js/console.log "[ex-cfg] <get-config: pulling [:block/name" config-page-title "] repo=" repo)
    (p/let [result (db-async/<pull repo
                                   '[:db/id :block/name :block/title :block/excalidraw-config]
                                   [:block/name config-page-title])]
      (js/console.log "[ex-cfg] <pull result:"
                      "db/id=" (when result (:db/id result))
                      "block/name=" (when result (:block/name result))
                      "block/title=" (when result (:block/title result))
                      "excalidraw-config=" (when result (:block/excalidraw-config result)))
      (if-let [raw (and result (:block/excalidraw-config result))]
        (try (let [cfg (merge default-config
                              (js->clj (js/JSON.parse raw) :keywordize-keys true))]
               (js/console.log "[ex-cfg] parsed config:" (clj->js cfg))
               cfg)
             (catch :default e
               (js/console.warn "[ex-cfg] JSON parse error" e)
               default-config))
        (do (js/console.log "[ex-cfg] no excalidraw-config attr found, returning default")
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
  "Find or create the config page, tagging it with the ConfigPage class.

   Uses async worker lookup first (db-async/<pull) to bypass the lazy
   main-thread DataScript replica — if the page exists in the worker DB it is
   returned directly without hitting the main-thread DB.

   Returns a Promise<page-entity>."
  []
  (let [repo (state/get-current-repo)]
    ;; Step 1: check worker DB (authoritative) for the existing config page
    (p/let [existing (db-async/<pull repo
                                     '[:db/id :block/name :block/title :block/tags]
                                     [:block/name config-page-title])]
      (js/console.log "[ex-cfg] ensure-config-page! worker lookup:"
                      "result=" (clj->js existing)
                      "db/id=" (when existing (:db/id existing))
                      "block/name=" (when existing (:block/name existing)))
      (if existing
        ;; Page already exists in worker (and now transacted into main-thread by <pull)
        (do (js/console.log "[ex-cfg] config page exists, reusing db/id=" (:db/id existing))
            (db/entity (:db/id existing)))
        ;; Step 2: page not found → create it
        (p/let [page (common-page-handler/<create! config-page-title
                                                   {:redirect? false})
                _    (js/console.log "[ex-cfg] created config page:"
                                     "db/id=" (when page (:db/id page))
                                     "block/name=" (when page (:block/name page))
                                     "block/title=" (when page (:block/title page)))
                tag  (<ensure-class-tag! tag-title)]
          (when (and page tag)
            (js/console.log "[ex-cfg] tagging config page with" tag-title "id=" (:db/id tag))
            (db/transact! repo
                          [{:db/id      (:db/id page)
                            :block/tags #{(:db/id tag)}}]
                          {:outliner-op :save-block}))
          page)))))

(defn save-config!
  "Persist `config-map` to the config page entity.
   Creates the page (and tags it) if it doesn't exist yet."
  [config-map]
  (js/console.log "[ex-cfg] save-config!" (clj->js config-map))
  (p/let [page (ensure-config-page!)]
    (if page
      (let [repo   (state/get-current-repo)
            merged (merge default-config config-map)]
        (js/console.log "[ex-cfg] transacting to page db/id=" (:db/id page)
                        "block/name=" (:block/name page))
        (db/transact! repo
                      [{:db/id             (:db/id page)
                        config-attr        (js/JSON.stringify (clj->js merged))
                        :block/updated-at  (.now js/Date)}]
                      {:outliner-op :save-block})
        (js/console.log "[ex-cfg] saved OK. reloading to verify...")
        ;; Verify: re-read from worker immediately after save
        (p/let [verify (db-async/<pull repo
                                       '[:db/id :block/excalidraw-config]
                                       [:block/name config-page-title])]
          (js/console.log "[ex-cfg] post-save verify:"
                          "db/id=" (when verify (:db/id verify))
                          "excalidraw-config=" (when verify (:block/excalidraw-config verify)))))
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
