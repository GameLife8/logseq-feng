(ns frontend.handler.visual-doc-test
  (:require [cljs.test :refer [is testing use-fixtures]]
            [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.handler.visual-doc :as visual-doc]
            [frontend.state :as state]
            [frontend.test.helper :as test-helper :include-macros true :refer [deftest-async]]
            [promesa.core :as p]))

(def ^:private pending-key-prefix "visual-doc-pending-manifest-")
(def ^:private repo "visual-doc-test-repo")
(def ^:private page-uuid "11111111-1111-4111-8111-111111111111")
(def ^:private page-lookup {:db/id 42 :block/updated-at 10})
(defonce ^:private *original-local-storage (atom nil))

(defn- make-local-storage
  []
  (let [store (atom (array-map))
        api   #js {}]
    (letfn [(sync-length! []
              (aset api "length" (count @store)))]
      (aset api "getItem" (fn [key]
                            (get @store (str key) nil)))
      (aset api "setItem" (fn [key value]
                            (swap! store assoc (str key) (str value))
                            (sync-length!)))
      (aset api "removeItem" (fn [key]
                               (swap! store dissoc (str key))
                               (sync-length!)))
      (aset api "clear" (fn []
                          (reset! store (array-map))
                          (sync-length!)))
      (aset api "key" (fn [idx]
                        (nth (vec (keys @store)) idx nil)))
      (sync-length!)
      api)))

(defn- read-pending-storage
  [repo-name]
  (some-> (.getItem js/localStorage (str pending-key-prefix repo-name))
          js/JSON.parse
          (js->clj :keywordize-keys true)))

(defn- install-local-storage!
  []
  (reset! *original-local-storage (aget js/globalThis "localStorage"))
  (aset js/globalThis "localStorage" (make-local-storage)))

(defn- restore-local-storage!
  []
  (if (some? @*original-local-storage)
    (aset js/globalThis "localStorage" @*original-local-storage)
    (.deleteProperty js/Reflect js/globalThis "localStorage"))
  (reset! *original-local-storage nil))

(use-fixtures :each
  {:before install-local-storage!
   :after restore-local-storage!})

(deftest-async flush-doc-queues-manifest-retry-when-manifest-write-fails
  (let [upsert-token  (atom nil)
        rollback-calls (atom [])]
    (p/with-redefs
      [visual-doc/schedule-pending-manifest-drain! (fn [_repo] nil)
       visual-doc/<sleep (fn [_ms] (p/resolved nil))
       db-async/<pull (fn [_repo _pattern _lookup]
                        (p/resolved page-lookup))
       db/transact! (fn [& _]
                      (p/rejected (js/Error. "manifest write failed")))
       state/<invoke-db-worker-direct-pass
       (fn [method & args]
         (case method
           :thread-api/visual-doc-upsert
           (let [[_repo _page-uuid _doc-type _content write-token] args]
             (reset! upsert-token write-token)
             (p/resolved {:updated-at 1729000000000
                          :write-token write-token
                          :storage "sidecar"}))

           :thread-api/visual-doc-delete-if-token
           (do
             (swap! rollback-calls conj args)
             (p/resolved true))

           (p/resolved nil)))]
      (p/let [result  (visual-doc/<flush-doc! repo page-uuid :block/whiteboard-canvas "{\"type\":\"scene\"}")
              pending (read-pending-storage repo)
              entry   (some-> pending :entries vals first)]
        (testing "failed manifest writes stay durable in sidecar and enqueue a reconcile"
          (is (= :pending-retry (:manifest-status result)))
          (is (= @upsert-token (:write-token result)))
          (is (empty? @rollback-calls))
          (is (= "block/whiteboard-canvas" (:attr entry)))
          (is (= 1729000000000 (:updated-at entry)))
          (is (= @upsert-token (:write-token entry)))
          (is (= 0 (:attempts entry))))))))

(deftest-async flush-doc-rolls-back-sidecar-when-page-disappears
  (let [upsert-token    (atom nil)
        pull-count      (atom 0)
        rollback-calls  (atom [])]
    (p/with-redefs
      [visual-doc/schedule-pending-manifest-drain! (fn [_repo] nil)
       visual-doc/<sleep (fn [_ms] (p/resolved nil))
       db-async/<pull (fn [_repo _pattern _lookup]
                        (let [count' (swap! pull-count inc)]
                          (p/resolved (when (= count' 1) page-lookup))))
       db/transact! (fn [& _]
                      (p/rejected (js/Error. "manifest write failed")))
       state/<invoke-db-worker-direct-pass
       (fn [method & args]
         (case method
           :thread-api/visual-doc-upsert
           (let [[_repo _page-uuid _doc-type _content write-token] args]
             (reset! upsert-token write-token)
             (p/resolved {:updated-at 1729000000100
                          :write-token write-token
                          :storage "sidecar"}))

           :thread-api/visual-doc-delete-if-token
           (do
             (swap! rollback-calls conj args)
             (p/resolved true))

           (p/resolved nil)))]
      (p/let [result  (visual-doc/<flush-doc! repo page-uuid :block/mind-map-data "{\"root\":{}}")
              pending (read-pending-storage repo)]
        (testing "if the page is gone before manifest repair, the matching sidecar write is rolled back"
          (is (false? result))
          (is (= 1 (count @rollback-calls)))
          (is (= [repo page-uuid @upsert-token] (first @rollback-calls)))
          (is (nil? pending)))))))

(deftest-async flush-doc-retries-worker-upsert-before-applying-manifest
  (let [upsert-attempts (atom 0)]
    (p/with-redefs
      [visual-doc/schedule-pending-manifest-drain! (fn [_repo] nil)
       visual-doc/<sleep (fn [_ms] (p/resolved nil))
       db-async/<pull (fn [_repo _pattern _lookup]
                        (p/resolved page-lookup))
       db/transact! (fn [& _]
                      (p/resolved true))
       state/<invoke-db-worker-direct-pass
       (fn [method & args]
         (case method
           :thread-api/visual-doc-upsert
           (let [[_repo _page-uuid _doc-type _content write-token] args
                 attempt (swap! upsert-attempts inc)]
             (if (< attempt 3)
               (p/rejected (js/Error. (str "upsert attempt " attempt " failed")))
               (p/resolved {:updated-at 1729000000200
                            :write-token write-token
                            :storage "sidecar"})))

           (p/resolved nil)))]
      (p/let [result  (visual-doc/<flush-doc! repo page-uuid :block/sheet-data "{\"sheets\":{}}")
              pending (read-pending-storage repo)]
        (testing "transient worker failures are retried before surfacing a save failure"
          (is (= 3 @upsert-attempts))
          (is (= :applied (:manifest-status result)))
          (is (nil? pending)))))))
