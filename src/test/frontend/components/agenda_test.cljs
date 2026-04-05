(ns frontend.components.agenda-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.components.agenda :as agenda]))

(def task->display-events
  (deref #'agenda/task->display-events))

(def group-display-events-by-day
  (deref #'agenda/group-display-events-by-day))

(def task->kanban-items
  (deref #'agenda/task->kanban-items))

(def ms->ymd
  (deref #'agenda/ms->ymd))

(deftest agenda-display-event-projection
  (testing "scheduled + deadline on the same day collapse into one deadline event"
    (let [events (task->display-events
                  {:block/uuid (random-uuid)
                   :block/created-at 1700000000000
                   :logseq.property/scheduled 1776037200000
                   :logseq.property/deadline 1776040800000})]
      (is (= 1 (count events)))
      (is (= [:deadline]
             (mapv :agenda/source events)))
      (is (= [1776040800000]
             (mapv :agenda/ms events)))))
  (testing "scheduled + deadline on different days render as two events"
    (let [events (task->display-events
                  {:block/uuid (random-uuid)
                   :block/created-at 1700000000000
                   :logseq.property/scheduled 1776037200000
                   :logseq.property/deadline 1776123600000})]
      (is (= [:scheduled :deadline]
             (mapv :agenda/source events)))
      (is (= [1776037200000 1776123600000]
             (mapv :agenda/ms events)))))
  (testing "date-less tasks fall back to created-at"
    (let [events (task->display-events
                  {:block/uuid (random-uuid)
                   :block/created-at 1700000000000})]
      (is (= [{:source :created :ms 1700000000000}]
             (mapv (fn [event]
                     {:source (:agenda/source event)
                      :ms (:agenda/ms event)})
                   events))))))

(deftest display-events-group-by-day
  (testing "the same task can project to two different days for month and day panels"
    (let [scheduled-ms 1776037200000
          deadline-ms  1776123600000
          scheduled-ymd (ms->ymd scheduled-ms)
          deadline-ymd  (ms->ymd deadline-ms)
          grouped      (group-display-events-by-day
                        [{:block/uuid (random-uuid)
                          :block/title "Test"
                          :block/created-at 1700000000000
                          :logseq.property/scheduled scheduled-ms
                          :logseq.property/deadline deadline-ms}])]
      (is (= 1 (count (get grouped scheduled-ymd))))
      (is (= [:scheduled]
             (mapv :agenda/source (get grouped scheduled-ymd))))
      (is (= 1 (count (get grouped deadline-ymd))))
      (is (= [:deadline]
             (mapv :agenda/source (get grouped deadline-ymd)))))))

(deftest kanban-item-projection
  (testing "same-day scheduled and deadline only render the deadline card"
    (let [items (task->kanban-items
                 {:block/uuid (random-uuid)
                  :block/created-at 1700000000000
                  :logseq.property/scheduled 1776037200000
                  :logseq.property/deadline 1776040800000}
                 1700000000000
                 0)]
      (is (= [:deadline]
             (mapv :agenda/kanban-column items)))
      (is (= [:deadline]
             (mapv :agenda/source items)))))
  (testing "different-day scheduled and deadline render planned plus deadline cards"
    (let [scheduled-ms 1776037200000
          deadline-ms  1776123600000
          items        (task->kanban-items
                        {:block/uuid (random-uuid)
                         :block/created-at 1700000000000
                         :logseq.property/scheduled scheduled-ms
                         :logseq.property/deadline deadline-ms}
                        1700000000000
                        scheduled-ms)]
      (is (= [:planned :deadline]
             (mapv :agenda/kanban-column items)))
      (is (= [:scheduled :deadline]
             (mapv :agenda/source items)))))
  (testing "overdue deadlines suppress backlog and planned projections"
    (let [scheduled-ms 1776037200000
          deadline-ms  1776123600000
          items        (task->kanban-items
                        {:block/uuid (random-uuid)
                         :block/created-at 1700000000000
                         :logseq.property/scheduled scheduled-ms
                         :logseq.property/deadline deadline-ms}
                        (inc deadline-ms)
                        (inc scheduled-ms))]
      (is (= [:overdue]
             (mapv :agenda/kanban-column items)))
      (is (= [:deadline]
             (mapv :agenda/source items))))))

(deftest kanban-backlog-projection
  (testing "manual backlog keeps a backlog card and can still show an upcoming deadline"
    (let [items (task->kanban-items
                 {:block/uuid (random-uuid)
                  :block/created-at 1700000000000
                  :logseq.property/deadline 1776123600000
                  :logseq.property/status {:db/ident :logseq.property/status.backlog}}
                 1700000000000
                 1800000000000)]
      (is (= [:backlog :deadline]
             (mapv :agenda/kanban-column items)))
      (is (= [:created :deadline]
             (mapv :agenda/source items)))))
  (testing "open tasks without deadlines age into backlog from created-at"
    (let [created-ms 1700000000000
          items      (task->kanban-items
                      {:block/uuid (random-uuid)
                       :block/created-at created-ms}
                      1770000000000
                      (inc created-ms))]
      (is (= [:backlog]
             (mapv :agenda/kanban-column items)))
      (is (= [:created]
             (mapv :agenda/source items))))))
