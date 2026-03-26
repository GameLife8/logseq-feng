(ns frontend.handler.excalidraw-config
  "Manages Excalidraw/Whiteboard user settings stored as a dedicated page entity.

   Config page:  title  = \"logseq/excalidraw\"
                 tag    = :logseq.class/Excalidraw  (system class)
                 attr   = :block/excalidraw-config  (JSON string)

   Config map keys (ClojureScript, keywordized):
     :embed-whitelist  – newline-separated domain list, e.g. \"example.com\\nyoutube.com\".
                         Use \"*\" (alone) to allow every URL.
                         Empty string / nil → block all iframe embeds.
     :font-family      – integer font family used as Excalidraw default:
                           1 = Virgil（手写体）
                           2 = Helvetica（常规）
                           3 = Cascadia（等宽体）"
  (:require [frontend.db :as db]
            [frontend.handler.common.page :as common-page-handler]
            [frontend.handler.notification :as notification]
            [frontend.state :as state]
            [promesa.core :as p]))

;; ── constants ─────────────────────────────────────────────────────────────────

(def ^:private config-page-title "logseq/excalidraw")
(def ^:private config-attr       :block/excalidraw-config)

(def default-config
  {:embed-whitelist ""     ; block all by default (empty = deny all)
   :font-family     1})    ; 1 = Virgil

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

;; ── write ─────────────────────────────────────────────────────────────────────

(defn- ensure-config-page!
  "Find or create the config page.  Returns a Promise<page-entity>."
  []
  (if-let [existing (db/get-page config-page-title)]
    (p/resolved existing)
    (p/let [page (common-page-handler/<create! config-page-title {:redirect? false})]
      (when page
        (let [repo (state/get-current-repo)]
          ;; Tag with system class :logseq.class/Excalidraw (if it exists)
          (when-let [cls (db/entity :logseq.class/Excalidraw)]
            (js/console.log "[ex-cfg] tagging config page with :logseq.class/Excalidraw id=" (:db/id cls))
            (db/transact! repo
                          [{:db/id (:db/id page) :block/tags #{(:db/id cls)}}]
                          {:outliner-op :save-block}))))
      page)))

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
  (->> (clojure.string/split (or raw "") #"\n")
       (map clojure.string/trim)
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
                                 (clojure.string/ends-with? hostname (str "." d))))
                           domains)))
          (catch :default _ false))))))
