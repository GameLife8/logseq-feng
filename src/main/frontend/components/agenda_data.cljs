(ns frontend.components.agenda-data
  (:require [frontend.db.async :as db-async]))

(def task-pull-spec
  '[:db/id
    :block/uuid :block/title :block/created-at :block/updated-at
    :logseq.property/scheduled :logseq.property/deadline
    {:logseq.property/status [:db/ident :block/title]
     :block/tags              [:db/id :block/title]
     :block/page              [:db/id :block/title :block/uuid :block/journal-day]}])

(defn <load-tasks
  "Loads task-like blocks from the DB worker.

   Blocks are included when they have either a status, a scheduled date, or a
   deadline so date-only tasks are still visible before a status is assigned."
  [repo]
  (db-async/<q repo {}
               '[:find [(pull ?block ?pull-spec) ...]
                 :in $ ?pull-spec
                 :where
                 (or-join [?block]
                   [?block :logseq.property/status _]
                   [?block :logseq.property/scheduled _]
                   [?block :logseq.property/deadline _])]
               task-pull-spec))

(defn task-status-ident
  [task]
  (get-in task [:logseq.property/status :db/ident]))

(defn task-active?
  [task]
  (let [ident (task-status-ident task)]
    (not (contains? #{:logseq.property/status.done
                      :logseq.property/status.canceled} ident))))

(defn journal-day->ms
  [journal-day]
  (let [s     (str journal-day)
        year  (js/parseInt (.substring s 0 4))
        month (dec (js/parseInt (.substring s 4 6)))
        day   (js/parseInt (.substring s 6 8))]
    (.getTime (js/Date. year month day))))

(defn prop->ms
  [value]
  (cond
    (number? value) value
    (and (map? value) (:block/journal-day value))
    (journal-day->ms (:block/journal-day value))
    :else nil))

(defn task-property-date-info
  [task]
  (let [scheduled-ms (prop->ms (:logseq.property/scheduled task))
        deadline-ms  (prop->ms (:logseq.property/deadline task))]
    (cond
      deadline-ms {:ms deadline-ms :source :deadline}
      scheduled-ms {:ms scheduled-ms :source :scheduled}
      :else nil)))

(defn task-date-info
  [task]
  (or (task-property-date-info task)
      (when-let [journal-day (get-in task [:block/page :block/journal-day])]
        {:ms (journal-day->ms journal-day)
         :source :journal})
      {:ms (or (:block/created-at task) 0)
       :source :created}))

(defn task-date-ms
  [task]
  (:ms (task-date-info task)))
