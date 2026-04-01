(ns frontend.handler.visual-doc
  "VISUAL-DOC-SIDECAR: shared helpers for whiteboard and mind-map documents.

   Page entities keep only lightweight manifest metadata in DataScript.
   The full document payload lives in the worker sqlite sidecar and is loaded
   on demand without transacting that payload back into the main-thread replica."
  (:require [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.state :as state]
            [promesa.core :as p]))

(def ^:private cache-version 1)

(defn cache-key
  [cache-prefix page-uuid]
  (str cache-prefix "-" page-uuid))

(defn clear-doc-cache!
  [cache-prefix page-uuid]
  (when (seq page-uuid)
    (.removeItem js/localStorage (cache-key cache-prefix page-uuid))))

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

(defn- attr->doc-type
  [attr]
  (case attr
    :block/whiteboard-canvas :whiteboard
    :block/mind-map-data :mind-map
    :visual-doc))

(defn- normalize-worker-result
  [result]
  (when (map? result)
    {:page-uuid  (:page-uuid result)
     :doc-type   (:doc-type result)
     :content    (:content result)
     :updated-at (:updated-at result)
     :storage    (:storage result)}))

(defn <load-doc
  "Loads the visual document payload from the worker sidecar first.

   If the sidecar has no document yet, worker code falls back to the legacy page
   attribute without transacting that payload into the main-thread DataScript."
  [repo page-uuid attr cache-prefix]
  (if-not (seq page-uuid)
    (p/resolved {:source :empty
                 :json nil
                 :needs-flush? false})
    (p/let [result (some-> (state/<invoke-db-worker :thread-api/visual-doc-get
                                                    repo
                                                    page-uuid
                                                    (attr->doc-type attr)
                                                    attr)
                           normalize-worker-result)
            cache  (read-doc-cache cache-prefix page-uuid)]
      (let [selected       (choose-newer-source {:db-json       (:content result)
                                                 :db-updated-at (:updated-at result)
                                                 :cache         cache})
            legacy-source? (= :legacy-db (:storage result))]
        (assoc selected
               :storage (:storage result)
               :needs-flush? (or (:needs-flush? selected)
                                 legacy-source?))))))

(defn- <ensure-page-id
  [repo page-uuid]
  (when (seq page-uuid)
    (p/let [page (db-async/<pull repo
                                 [:db/id]
                                 [:block/uuid (uuid page-uuid)])]
      (:db/id page))))

(defn <flush-doc!
  "Writes the visual document payload to the worker sidecar and updates only the
   page manifest metadata in DataScript.

   The legacy page attribute is retracted after a successful sidecar write so the
   page entity no longer carries the large payload."
  [repo page-uuid attr json-str]
  (if-not (and (seq repo) (seq page-uuid) (seq json-str))
    (p/resolved false)
    (p/let [page-id (<ensure-page-id repo page-uuid)]
      (if-not page-id
        false
        (p/let [sidecar-result (state/<invoke-db-worker :thread-api/visual-doc-upsert
                                                        repo
                                                        page-uuid
                                                        (attr->doc-type attr)
                                                        json-str)
                updated-at    (or (:updated-at sidecar-result) (.now js/Date))
                _             (db/transact! repo
                                            [[:db/retract page-id attr]
                                             {:db/id            page-id
                                              :block/updated-at updated-at}]
                                            {:outliner-op :save-block})]
          {:updated-at updated-at})))))

(defn <delete-doc!
  "Deletes the visual document payload from the worker sidecar and clears the
   local draft cache. Callers should delete the page manifest afterwards."
  [repo page-uuid cache-prefix]
  (if-not (and (seq repo) (seq page-uuid))
    (p/resolved false)
    (p/let [result (state/<invoke-db-worker :thread-api/visual-doc-delete repo page-uuid)]
      (clear-doc-cache! cache-prefix page-uuid)
      result)))
