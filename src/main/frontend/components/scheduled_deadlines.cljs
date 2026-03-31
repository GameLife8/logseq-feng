(ns frontend.components.scheduled-deadlines
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [frontend.components.agenda-data :as agenda-data]
            [frontend.components.block :as block]
            [frontend.components.content :as content]
            [frontend.components.editor :as editor]
            [frontend.date :as date]
            [frontend.db :as db]
            [frontend.db.utils :as db-utils]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [promesa.core :as p]
            [rum.core :as rum]))

(def ^:private watch-attrs
  #{:logseq.property/status
    :logseq.property/scheduled
    :logseq.property/deadline})

(defn- scheduled-or-deadlines?
  [page-name]
  (and (date/valid-journal-title? (string/capitalize page-name))
       (not (true? (state/scheduled-deadlines-disabled?)))
       (= (string/lower-case page-name) (string/lower-case (date/journal-name)))))

(defn- scheduled-range
  [page-name]
  (when-let [journal-day (some-> page-name string/capitalize date/journal-title->int)]
    (let [start-ms (agenda-data/journal-day->ms journal-day)
          future-days (state/get-scheduled-future-days)
          end-ms (+ start-ms (* (inc future-days) 86400000) -1)]
      {:start-ms start-ms
       :end-ms end-ms})))

(defn- keep-scheduled-or-deadline?
  [task start-ms end-ms]
  (when-let [{:keys [ms source]} (agenda-data/task-property-date-info task)]
    (and (agenda-data/task-active? task)
         (contains? #{:scheduled :deadline} source)
         (<= start-ms ms end-ms))))

(defn- <load-scheduled-or-deadlines
  [page-name]
  (when-let [repo (and (scheduled-or-deadlines? page-name) (state/get-current-repo))]
    (when-let [{:keys [start-ms end-ms]} (scheduled-range page-name)]
      (p/let [tasks (agenda-data/<load-tasks repo)]
        (some->> tasks
                 (filter #(keep-scheduled-or-deadline? % start-ms end-ms))
                 (sort-by agenda-data/task-date-ms)
                 (mapv (fn [task]
                         (or (db/entity (:db/id task)) task)))
                 seq
                 db-utils/group-by-page)))))

(rum/defcs scheduled-and-deadlines < rum/reactive
  (rum/local nil ::result)
  {:did-mount
   (fn [state]
     (let [page-name (first (:rum/args state))
           *result (::result state)
           *timer (volatile! nil)
           do-load! (fn []
                      (-> (p/let [result (<load-scheduled-or-deadlines page-name)]
                            (reset! *result result))
                          (p/catch (fn [_] nil))))]
       (when (scheduled-or-deadlines? page-name)
         (-> state/app-ready-promise
             (p/then
              (fn [_]
                (when-let [conn (db/get-db (state/get-current-repo) false)]
                  ;; Refresh the folded list when task dates or status change.
                  (d/listen! conn ::scheduled-deadlines-auto-refresh
                             (fn [{:keys [tx-data]}]
                               (when (some #(contains? watch-attrs (:a %)) tx-data)
                                 (js/clearTimeout @*timer)
                                 (vreset! *timer (js/setTimeout do-load! 400))))))
                (do-load!)))
             (p/catch (fn [_] nil))))
       (assoc state ::refresh-timer *timer)))
   :will-unmount
   (fn [state]
     (when-let [*timer (::refresh-timer state)]
       (js/clearTimeout @*timer))
     (when-let [conn (db/get-db (state/get-current-repo) false)]
       (d/unlisten! conn ::scheduled-deadlines-auto-refresh))
     state)}
  [state page-name]
  (let [scheduled-or-deadlines (rum/react (::result state))]
    (when (seq scheduled-or-deadlines)
      [:div.scheduled-or-deadlines
       (ui/foldable
        [:div.text-sm.font-medium "Scheduled and Deadline"]
        (fn []
          [:div.scheduled-deadlines.references-blocks.mb-6
           (let [ref-hiccup (block/->hiccup scheduled-or-deadlines
                                            {:id (str page-name "-agenda")
                                             :ref? true
                                             :group-by-page? true
                                             :editor-box editor/box}
                                            {})]
             (content/content page-name {:hiccup ref-hiccup}))])
        {:title-trigger? true
         :default-collapsed? true})])))
