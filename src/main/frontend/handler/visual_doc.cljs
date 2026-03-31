(ns frontend.handler.visual-doc
  "Shared helpers for visual documents backed by a page-level JSON blob."
  (:require [frontend.db :as db]
            [frontend.db.async :as db-async]
            [promesa.core :as p]))

(def ^:private cache-version 1)

(defn cache-key
  [cache-prefix page-uuid]
  (str cache-prefix "-" page-uuid))

(defn- wrapped-cache?
  [value]
  (and (map? value)
       (= cache-version (:version value))
       (number? (:saved-at value))
       (string? (:data value))))

(defn read-doc-cache
  "Reads a localStorage cache entry.

   Current cache entries are wrapped with {:version :saved-at :data}.
   Legacy entries are treated as raw JSON strings without timestamps."
  [cache-prefix page-uuid]
  (when-let [raw (and (seq page-uuid)
                      (.getItem js/localStorage (cache-key cache-prefix page-uuid)))]
    (try
      (let [parsed (js->clj (js/JSON.parse raw) :keywordize-keys true)]
        (cond
          (wrapped-cache? parsed)
          parsed

          (string? parsed)
          {:version  0
           :saved-at nil
           :data     parsed}

          :else
          {:version  0
           :saved-at nil
           :data     raw}))
      (catch :default _
        {:version  0
         :saved-at nil
         :data     raw}))))

(defn save-doc-cache!
  "Writes a timestamped localStorage cache entry and returns the saved payload."
  [cache-prefix page-uuid json-str]
  (when (and (seq page-uuid) (seq json-str))
    (let [payload {:version  cache-version
                   :saved-at (.now js/Date)
                   :data     json-str}]
      (.setItem js/localStorage
                (cache-key cache-prefix page-uuid)
                (js/JSON.stringify (clj->js payload)))
      payload)))

(defn choose-newer-source
  "Chooses the authoritative startup payload between DB and local cache.

   Legacy caches without a timestamp only win when the DB has no data."
  [{:keys [db-json db-updated-at cache]}]
  (let [db-json'       (when (seq db-json) db-json)
        cache-json     (some-> cache :data seq)
        cache-saved-at (:saved-at cache)
        cache-newer?   (and cache-json
                            (number? cache-saved-at)
                            (> cache-saved-at (or db-updated-at 0)))]
    (cond
      cache-newer?
      {:source        :cache
       :json          cache-json
       :db-updated-at db-updated-at
       :cache-saved-at cache-saved-at
       :needs-flush?  true}

      db-json'
      {:source        :db
       :json          db-json'
       :db-updated-at db-updated-at
       :cache-saved-at cache-saved-at
       :needs-flush?  false}

      cache-json
      {:source        :cache
       :json          cache-json
       :db-updated-at db-updated-at
       :cache-saved-at cache-saved-at
       :needs-flush?  (number? cache-saved-at)}

      :else
      {:source        :empty
       :json          nil
       :db-updated-at db-updated-at
       :cache-saved-at cache-saved-at
       :needs-flush?  false})))

(defn <load-doc
  "Loads a page-level JSON document from the worker DB and local cache."
  [repo page-uuid attr cache-prefix]
  (if-not (seq page-uuid)
    (p/resolved {:source :empty
                 :json nil
                 :needs-flush? false})
    (p/let [result (db-async/<pull repo
                                   [:db/id :block/updated-at attr]
                                   [:block/uuid (uuid page-uuid)])
            cache  (read-doc-cache cache-prefix page-uuid)]
      (choose-newer-source {:db-json       (get result attr)
                            :db-updated-at (:block/updated-at result)
                            :cache         cache}))))

(defn- <ensure-page-id
  [repo page-uuid]
  (if-let [page (db/entity [:block/uuid (uuid page-uuid)])]
    (p/resolved (:db/id page))
    (p/let [page (db-async/<pull repo
                                 [:db/id]
                                 [:block/uuid (uuid page-uuid)])]
      (:db/id page))))

(defn <flush-doc!
  "Writes a page-level JSON document to the DB and bumps :block/updated-at."
  [repo page-uuid attr json-str]
  (if-not (and (seq repo) (seq page-uuid) (seq json-str))
    (p/resolved false)
    (p/let [page-id (<ensure-page-id repo page-uuid)]
      (if-not page-id
        false
        (let [updated-at (.now js/Date)]
          (p/let [_ (db/transact! repo
                                  [{:db/id            page-id
                                    attr              json-str
                                    :block/updated-at updated-at}]
                                  {:outliner-op :save-block})]
            {:updated-at updated-at})))))))
