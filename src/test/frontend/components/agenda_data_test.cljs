(ns frontend.components.agenda-data-test
  (:require [cljs.test :refer [deftest is testing]]
            [frontend.components.agenda-data :as agenda-data]))

(deftest task-status-ident-defaults-date-only-items
  (testing "date-only agenda items fall back to Todo"
    (is (= :logseq.property/status.todo
           (agenda-data/task-status-ident {:logseq.property/scheduled 1710000000000})))
    (is (= :logseq.property/status.todo
           (agenda-data/task-status-ident {:logseq.property/deadline 1710000000000}))))
  (testing "explicit status still wins over the fallback"
    (is (= :logseq.property/status.doing
           (agenda-data/task-status-ident
            {:logseq.property/scheduled 1710000000000
             :logseq.property/status {:db/ident :logseq.property/status.doing}})))))

(deftest task-active-uses-effective-status
  (testing "date-only Todo fallback stays active"
    (is (true? (agenda-data/task-active? {:logseq.property/deadline 1710000000000}))))
  (testing "closed statuses stay inactive"
    (is (false? (agenda-data/task-active?
                 {:logseq.property/status {:db/ident :logseq.property/status.done}})))
    (is (false? (agenda-data/task-active?
                 {:logseq.property/status {:db/ident :logseq.property/status.canceled}})))))
