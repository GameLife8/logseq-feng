(ns frontend.handler.visual-doc
  "VISUAL-DOC-SIDECAR: shared helpers for whiteboard and mind-map documents.

   Page entities keep only lightweight manifest metadata in DataScript.
   The full document payload lives in the worker sqlite sidecar and is loaded
   on demand without transacting that payload back into the main-thread replica."
  (:require [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.state :as state]
            [goog.object]
            [promesa.core :as p]))

(def ^:private cache-version 1)
(def ^:private lru-max-entries 5)
(def ^:private pending-manifest-version 1)
(def ^:private pending-manifest-base-delay-ms 1000)
(def ^:private pending-manifest-max-delay-ms 60000)
(def ^:private worker-upsert-retry-delays-ms [0 250 1000])

(defonce ^:private *pending-manifest-drains (atom #{}))
(defonce ^:private *pending-manifest-timers (atom {}))

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

(defn- evict-lru-caches!
  "Keeps only the `lru-max-entries` most-recently-saved cache entries for `cache-prefix`.
   Scans localStorage for keys matching `cache-prefix-*`, reads their `saved-at`
   timestamps, and removes the oldest entries that exceed the limit.
   Also removes associated thumbnail entries for mind-map caches."
  [cache-prefix]
  (let [prefix  (str cache-prefix "-")
        n       (.-length js/localStorage)
        entries (loop [i 0 acc []]
                  (if (>= i n)
                    acc
                    (let [k (.key js/localStorage i)]
                      (if (and k (.startsWith k prefix))
                        (let [saved-at (try
                                         (-> (.getItem js/localStorage k)
                                             js/JSON.parse
                                             (goog.object/get "saved-at"))
                                         (catch :default _ nil))]
                          (recur (inc i) (conj acc {:key k
                                                    :uuid (subs k (count prefix))
                                                    :saved-at (or saved-at 0)})))
                        (recur (inc i) acc)))))
        sorted  (->> entries (sort-by :saved-at >) vec)]
    (when (> (count sorted) lru-max-entries)
      (doseq [{:keys [key uuid]} (subvec sorted lru-max-entries)]
        (.removeItem js/localStorage key)
        ;; Also clean up mind-map thumbnails if this is a mind-map cache
        (when (= cache-prefix "mind-map-data")
          (.removeItem js/localStorage (str "mind-map-thumb-" uuid)))))))

(defn save-doc-cache!
  "Writes a timestamped localStorage cache entry and returns the saved payload.
   Also evicts stale entries beyond the LRU limit for this cache prefix."
  [cache-prefix page-uuid json-str]
  (when (and (seq page-uuid) (seq json-str))
    (let [payload {:version  cache-version
                   :saved-at (.now js/Date)
                   :data     json-str}]
      (.setItem js/localStorage
                (cache-key cache-prefix page-uuid)
                (js/JSON.stringify (clj->js payload)))
      (evict-lru-caches! cache-prefix)
      payload)))

(declare schedule-pending-manifest-drain! <drain-pending-manifests!)

(defn- pending-manifest-key
  [repo]
  (str "visual-doc-pending-manifest-" repo))

(defn- retry-delay-ms
  [attempt]
  (js/Math.round
   (min pending-manifest-max-delay-ms
        (* pending-manifest-base-delay-ms
           (js/Math.pow 2 (max 0 (or attempt 0)))))))

(defn- wrapped-pending-manifests?
  [value]
  (and (map? value)
       (= pending-manifest-version (:version value))
       (map? (:entries value))))

(defn- parse-pending-manifest-attr
  [entry]
  (let [attr (:attr entry)]
    (cond
      (keyword? attr)
      attr

      (string? attr)
      (let [attr' (if (and (seq attr) (= ":" (subs attr 0 1)))
                    (subs attr 1)
                    attr)]
        (or
         (case attr'
           "whiteboard-canvas" :block/whiteboard-canvas
           "mind-map-data" :block/mind-map-data
           "sheet-data" :block/sheet-data
           nil)
         (when-let [[_ attr-ns attr-name] (re-matches #"([^/]+)/(.+)" attr')]
           (keyword attr-ns attr-name))
         (when (seq attr')
           (keyword attr'))))

      :else
      nil)))

(defn- normalize-pending-manifest-entry
  [page-uuid entry]
  (let [page-uuid'  (or (some-> (:page-uuid entry) str)
                        (some-> page-uuid str))
        attr        (parse-pending-manifest-attr entry)
        updated-at  (some-> (:updated-at entry) js/Number)
        attempts    (js/Number (or (:attempts entry) 0))
        next-at     (js/Number (or (:next-at entry) (.now js/Date)))
        write-token (some-> (:write-token entry) str)]
    (when (and (seq page-uuid')
               attr
               (number? updated-at)
               (not (js/isNaN updated-at)))
      {:page-uuid   page-uuid'
       :attr        attr
       :updated-at  updated-at
       :attempts    attempts
       :next-at     next-at
       :write-token write-token})))

(defn- clear-pending-manifest-timer!
  [repo]
  (when-let [timer (get @*pending-manifest-timers repo)]
    (js/clearTimeout timer)
    (swap! *pending-manifest-timers dissoc repo)))

(defn- read-pending-manifest-writes
  [repo]
  (if-let [raw (and (seq repo)
                    (.getItem js/localStorage (pending-manifest-key repo)))]
    (try
      (let [parsed  (js->clj (js/JSON.parse raw) :keywordize-keys true)
            entries (cond
                      (wrapped-pending-manifests? parsed)
                      (:entries parsed)

                      (map? parsed)
                      parsed

                      :else
                      nil)]
        (into {}
              (keep (fn [[page-uuid entry]]
                      (when-let [entry' (normalize-pending-manifest-entry page-uuid entry)]
                        [(:page-uuid entry') entry'])))
              entries))
      (catch :default _
        {}))
    {}))

(defn- persist-pending-manifest-writes!
  [repo entries]
  (when (seq repo)
    (if (seq entries)
      (.setItem js/localStorage
                (pending-manifest-key repo)
                (js/JSON.stringify
                 (clj->js {:version pending-manifest-version
                           :entries entries})))
      (.removeItem js/localStorage (pending-manifest-key repo)))
    (schedule-pending-manifest-drain! repo)
    entries))

(defn- upsert-pending-manifest!
  [repo {:keys [page-uuid] :as entry}]
  (when (and (seq repo) (seq page-uuid))
    (persist-pending-manifest-writes!
     repo
     (assoc (read-pending-manifest-writes repo) page-uuid entry))))

(defn- remove-pending-manifest!
  [repo page-uuid]
  (when (and (seq repo) (seq page-uuid))
    (persist-pending-manifest-writes!
     repo
     (dissoc (read-pending-manifest-writes repo) page-uuid))))

(defn- queue-pending-manifest!
  [repo page-uuid attr updated-at write-token & {:keys [attempts immediate?]
                                                 :or {attempts 0
                                                      immediate? false}}]
  (upsert-pending-manifest!
   repo
   {:page-uuid   page-uuid
    :attr        (if-let [attr-ns (namespace attr)]
                   (str attr-ns "/" (name attr))
                   (name attr))
    :updated-at  updated-at
    :attempts    attempts
    :next-at     (+ (.now js/Date)
                    (if immediate?
                      0
                      (retry-delay-ms attempts)))
    :write-token write-token}))

(defn- due-pending-manifest?
  [entry now]
  (<= (or (:next-at entry) 0) now))

(defn- <sleep
  [ms]
  (p/create (fn [resolve _reject]
              (js/setTimeout resolve ms))))

(defn- <invoke-worker-upsert-with-retries
  [repo page-uuid doc-type json-str write-token]
  (let [attempt-count (count worker-upsert-retry-delays-ms)]
    (letfn [(step [idx]
              (-> (p/let [result (state/<invoke-db-worker-direct-pass :thread-api/visual-doc-upsert
                                                                       repo
                                                                       page-uuid
                                                                       doc-type
                                                                       json-str
                                                                       write-token)]
                    (if (or result (>= idx (dec attempt-count)))
                      result
                      (p/let [_ (<sleep (nth worker-upsert-retry-delays-ms (inc idx)))]
                        (step (inc idx)))))
                  (p/catch (fn [error]
                             (if (>= idx (dec attempt-count))
                               (p/rejected error)
                               (p/let [_ (<sleep (nth worker-upsert-retry-delays-ms (inc idx)))]
                                 (step (inc idx))))))))]
      (step 0))))

(defn choose-newer-source
  "Chooses the authoritative startup payload between DB and local cache.

   Legacy caches without a timestamp only win when the DB has no data."
  [{:keys [db-json db-updated-at cache]}]
  (let [db-json'       (when (seq db-json) db-json)
        cache-json     (some-> cache :data)
        cache-json'    (when (seq cache-json) cache-json)
        cache-saved-at (:saved-at cache)
        cache-newer?   (and cache-json'
                            (number? cache-saved-at)
                            (> cache-saved-at (or db-updated-at 0)))]
    (cond
      cache-newer?
      {:source        :cache
       :json          cache-json'
       :db-updated-at db-updated-at
       :cache-saved-at cache-saved-at
       :needs-flush?  true}

      db-json'
      {:source        :db
       :json          db-json'
       :db-updated-at db-updated-at
       :cache-saved-at cache-saved-at
       :needs-flush?  false}

      cache-json'
      {:source        :cache
       :json          cache-json'
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
    :block/sheet-data :sheet
    :visual-doc))

(defn- normalize-worker-result
  [result]
  (let [result' (cond
                  (map? result)
                  result

                  (some? result)
                  (js->clj result :keywordize-keys true)

                  :else
                  nil)]
    (when (map? result')
      {:page-uuid  (:page-uuid result')
       :doc-type   (:doc-type result')
       :content    (:content result')
       :updated-at (:updated-at result')
       :write-token (some-> (:write-token result') str)
       :storage    (some-> (:storage result') name keyword)})))

(defn- <pull-page-manifest
  [repo page-uuid]
  (when (seq page-uuid)
    (db-async/<pull repo
                    [:db/id :block/updated-at]
                    [:block/uuid (uuid page-uuid)])))

(defn <load-doc
  "Loads the visual document payload from the worker sidecar first.

   The sidecar is the only authoritative store for new visual documents. Local
   draft cache can still win when it is newer than the durable sidecar copy."
  [repo page-uuid attr cache-prefix]
  (if-not (seq page-uuid)
    (p/resolved {:source :empty
                 :json nil
                 :needs-flush? false})
    (p/let [_            (<drain-pending-manifests! repo)
            raw-result   (state/<invoke-db-worker-direct-pass :thread-api/visual-doc-get
                                                              repo
                                                              page-uuid
                                                              (name (attr->doc-type attr)))
            result       (normalize-worker-result raw-result)
            cache        (read-doc-cache cache-prefix page-uuid)
            page-manifest (<pull-page-manifest repo page-uuid)]
      (when (and (:db/id page-manifest)
                 (seq (:write-token result))
                 (> (or (:updated-at result) 0)
                    (or (:block/updated-at page-manifest) 0)))
        (queue-pending-manifest! repo
                                 page-uuid
                                 attr
                                 (:updated-at result)
                                 (:write-token result)
                                 :immediate? true))
      (assoc (choose-newer-source {:db-json       (:content result)
                                   :db-updated-at (:updated-at result)
                                   :cache         cache})
             :storage (:storage result)))))

(defn- <ensure-page-id
  [repo page-uuid]
  (when (seq page-uuid)
    (p/let [page (<pull-page-manifest repo page-uuid)]
      (:db/id page))))

(defn- <apply-manifest!
  [repo page-id attr updated-at]
  (p/let [_ (db/transact! repo
                          [[:db/retract page-id attr]
                           {:db/id            page-id
                            :block/updated-at updated-at}]
                          {:outliner-op :save-block})]
    true))

(defn- <delete-sidecar-doc-if-token!
  [repo page-uuid write-token]
  (if-not (and (seq repo) (seq page-uuid) (seq write-token))
    (p/resolved false)
    (state/<invoke-db-worker-direct-pass :thread-api/visual-doc-delete-if-token
                                         repo
                                         page-uuid
                                         write-token)))

(defn- reschedule-pending-manifest!
  [repo {:keys [attempts] :as entry}]
  (let [attempt' (inc (or attempts 0))
        next-at  (+ (.now js/Date) (retry-delay-ms attempt'))
        entry'   (assoc entry
                        :attempts attempt'
                        :next-at next-at)]
    (upsert-pending-manifest! repo entry')
    entry'))

(defn- <process-pending-manifest-entry!
  [repo {:keys [page-uuid attr updated-at write-token] :as entry}]
  (-> (p/let [raw-result    (state/<invoke-db-worker-direct-pass :thread-api/visual-doc-get
                                                                 repo
                                                                 page-uuid
                                                                 (name (attr->doc-type attr)))
              sidecar-doc   (normalize-worker-result raw-result)]
        (cond
          (nil? sidecar-doc)
          (do
            (remove-pending-manifest! repo page-uuid)
            :missing-sidecar)

          (and (seq write-token)
               (not= (:write-token sidecar-doc) write-token))
          (do
            (remove-pending-manifest! repo page-uuid)
            :stale)

          :else
          (p/let [page-id (<ensure-page-id repo page-uuid)]
            (if-not page-id
              (do
                (when (seq write-token)
                  (-> (<delete-sidecar-doc-if-token! repo page-uuid write-token)
                      (p/catch (fn [error]
                                 (js/console.error "[visual-doc] conditional rollback failed:" error)
                                 false))))
                (remove-pending-manifest! repo page-uuid)
                :page-missing)
              (-> (<apply-manifest! repo page-id attr updated-at)
                  (p/then (fn [_]
                            (remove-pending-manifest! repo page-uuid)
                            :applied)))))))
      (p/catch (fn [error]
                 (reschedule-pending-manifest! repo entry)
                 (js/console.warn "[visual-doc] pending manifest retry scheduled:" error)
                 :retry-scheduled))))

(defn- <drain-pending-manifests!
  [repo]
  (if (or (not (seq repo))
          (contains? @*pending-manifest-drains repo))
    (p/resolved nil)
    (do
      (swap! *pending-manifest-drains conj repo)
      (clear-pending-manifest-timer! repo)
      (let [entries (->> (vals (read-pending-manifest-writes repo))
                         (sort-by :next-at)
                         vec)]
        (-> (reduce (fn [chain entry]
                      (p/let [_ chain]
                        (when (due-pending-manifest? entry (.now js/Date))
                          (<process-pending-manifest-entry! repo entry))))
                    (p/resolved nil)
                    entries)
            (p/finally (fn []
                         (swap! *pending-manifest-drains disj repo)
                         (schedule-pending-manifest-drain! repo))))))))

(defn- schedule-pending-manifest-drain!
  [repo]
  (clear-pending-manifest-timer! repo)
  (when (and (seq repo)
             (not (contains? @*pending-manifest-drains repo)))
    (let [entries (vals (read-pending-manifest-writes repo))]
      (when (seq entries)
        (let [now     (.now js/Date)
              next-at (apply min (map #(or (:next-at %) now) entries))
              delay   (max 0 (- next-at now))
              timer   (js/setTimeout
                       (fn []
                         (-> (<drain-pending-manifests! repo)
                             (p/catch (fn [error]
                                        (js/console.error "[visual-doc] pending manifest drain failed:" error)
                                        false))))
                       delay)]
          (swap! *pending-manifest-timers assoc repo timer))))))

(defn <flush-doc!
  "Writes the visual document payload to the worker sidecar and updates only the
   page manifest metadata in DataScript.

   The legacy page attribute is retracted after a successful sidecar write so the
   page entity no longer carries the large payload."
  [repo page-uuid attr json-str]
  (if-not (and (seq repo) (seq page-uuid) (seq json-str))
    (p/resolved false)
    (p/let [_       (<drain-pending-manifests! repo)
            page-id (<ensure-page-id repo page-uuid)]
      (if-not page-id
        false
        (-> (p/let [write-token    (str (random-uuid))
                    raw-result     (<invoke-worker-upsert-with-retries repo
                                                                       page-uuid
                                                                       (name (attr->doc-type attr))
                                                                       json-str
                                                                       write-token)
                    sidecar-result (normalize-worker-result raw-result)]
              (if-not sidecar-result
                false
                (let [updated-at  (or (:updated-at sidecar-result) (.now js/Date))
                      write-token (or (:write-token sidecar-result) write-token)]
                  (-> (<apply-manifest! repo page-id attr updated-at)
                      (p/then (fn [_]
                                (remove-pending-manifest! repo page-uuid)
                                {:updated-at      updated-at
                                 :write-token     write-token
                                 :manifest-status :applied}))
                      (p/catch (fn [error]
                                 (js/console.warn "[visual-doc] manifest update failed after sidecar write:" error)
                                 (p/let [page-id' (<ensure-page-id repo page-uuid)]
                                   (if page-id'
                                     (do
                                       (queue-pending-manifest! repo page-uuid attr updated-at write-token)
                                       {:updated-at      updated-at
                                        :write-token     write-token
                                        :manifest-status :pending-retry})
                                     (do
                                       (when (seq write-token)
                                         (-> (<delete-sidecar-doc-if-token! repo page-uuid write-token)
                                             (p/catch (fn [rollback-error]
                                                        (js/console.error "[visual-doc] conditional rollback failed:" rollback-error)
                                                        false))))
                                       (remove-pending-manifest! repo page-uuid)
                                       false)))))))))
            (p/catch (fn [err]
                       (js/console.error "[visual-doc] <flush-doc! exception:" err)
                       false)))))))

(defn <delete-sidecar-doc!
  "Deletes the visual document payload from the worker sidecar."
  [repo page-uuid]
  (if-not (and (seq repo) (seq page-uuid))
    (p/resolved false)
    (p/let [result (state/<invoke-db-worker-direct-pass :thread-api/visual-doc-delete repo page-uuid)]
      (when result
        (remove-pending-manifest! repo page-uuid))
      result)))

(defn <delete-doc!
  "Deletes the visual document payload from the worker sidecar and clears the
   local draft cache after the sidecar delete succeeds."
  [repo page-uuid cache-prefix]
  (if-not (and (seq repo) (seq page-uuid))
    (p/resolved false)
    (p/let [result (<delete-sidecar-doc! repo page-uuid)]
      (when result
        (clear-doc-cache! cache-prefix page-uuid))
      result)))
