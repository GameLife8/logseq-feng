(ns frontend.components.repo
  (:require [clojure.string :as string]
            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.db :as db]
            [frontend.handler.graph :as graph]
            [frontend.handler.notification :as notification]
            [frontend.handler.repo :as repo-handler]
            [frontend.handler.route :as route-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [frontend.util.text :as text-util]
            [goog.object :as gobj]
            [logseq.common.util :as common-util]
            [logseq.shui.hooks :as hooks]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]
            [rum.core :as rum]))

(rum/defc normalized-graph-label
  [{:keys [url] :as graph} on-click]
  (when graph
    [:span.flex.items-center
     (let [local-dir (config/get-local-dir url)
           graph-name (text-util/get-graph-name-from-path url)]
       [:a.flex.items-center {:title local-dir
                              :on-click #(on-click graph)}
        [:span graph-name]])]))

(defn sort-repos-with-metadata-local
  [repos]
  (if-let [m (and (seq repos) (graph/get-metadata-local))]
    (->> repos
         (map (fn [r] (merge r (get m (:url r)))))
         (sort (fn [r1 r2]
                 (compare (or (:last-seen-at r2) (:created-at r2))
                          (or (:last-seen-at r1) (:created-at r1))))))
    repos))

(defn- safe-locale-date
  [dst]
  (when (number? dst)
    (try
      (.toLocaleString (js/Date. dst))
      (catch js/Error _e nil))))

(rum/defc repos-inner
  "Graph list in `All graphs` page"
  [repos]
  (for [{:keys [root url created-at last-seen-at] :as repo}
        (sort-repos-with-metadata-local repos)
        :let [graph-name (config/db-graph-name url)]]
    [:div.flex.justify-between.mb-2.items-center.group {:key url
                                                        "data-testid" url}
     [:div
      [:span.flex.items-center.gap-1
       (normalized-graph-label repo
                               (fn []
                                 (when root
                                   (state/pub-event! [:graph/switch url]))))]
      (when-let [time (some-> (or last-seen-at created-at) (safe-locale-date))]
        [:small.text-muted-foreground (str "Last opened at: " time)])]

     [:div.controls
      [:div.flex.flex-row.items-center
       (when (util/electron?)
         [:a.text-xs.items-center.text-gray-08.hover:underline.hidden.group-hover:flex
          {:on-click #(util/open-url (str "file://" root))}
          (shui/tabler-icon "folder-pin") [:span.pl-1 root]])

       (shui/dropdown-menu
        (shui/dropdown-menu-trigger
         {:asChild true}
         (shui/button
          {:variant "ghost"
           :class "graph-action-btn !px-1"
           :size :sm}
          (ui/icon "dots" {:size 15})))
        (shui/dropdown-menu-content
         {:align "end"}
         (when root
           (shui/dropdown-menu-item
            {:key "delete-locally"
             :class "delete-local-graph-menu-item"
             :on-click (fn []
                         (let [prompt-str (str "Are you sure you want to permanently delete the graph \"" graph-name "\" from Logseq?")]
                           (-> (shui/dialog-confirm!
                                [:p.font-medium.-my-4 prompt-str
                                 [:span.my-2.flex.font-normal.opacity-75
                                  [:small "⚠️ Notice that we can't recover this graph after being deleted. Make sure you have backups before deleting it."]]])
                               (p/then (fn []
                                         (repo-handler/remove-repo! repo))))))}
            "Delete local graph"))))]]]))

(rum/defc repos-cp < rum/reactive
  []
  (let [repos (state/sub [:me :repos])
        repos (util/distinct-by :url repos)
        repos (cond->>
               (remove #(= (:url %) config/demo-repo) repos)
                true
                (filter (fn [item]
                          ;; use `config/db-based-graph?` to avoid loading old file graphs
                          (config/db-based-graph? (:url item)))))]
    [:div#graphs
     (when-not (util/capacitor?)
       [:h1.title (t :graph/all-graphs)])

     [:div.pl-1.content
      {:class (when-not (util/mobile?) "mt-8")}
      (when-not (util/mobile?)
        [:div.flex.flex-row.my-8
         [:div.mr-8
          (ui/button
           "Create a new graph"
           :on-click #(state/pub-event! [:graph/new-db-graph]))]])

      [:div
       [:h2.text-lg.font-medium.mb-4 (t :graph/local-graphs)]
       (when (seq repos)
         (repos-inner repos))]]]))

(defn- repos-dropdown-links [repos current-repo & {:as opts}]
  (let [switch-repos (if-not (nil? current-repo)
                       (remove (fn [repo] (= current-repo (:url repo))) repos) repos) ; exclude current repo
        repo-links (mapv
                    (fn [{:keys [url] :as graph}]
                      (let [repo-url url
                            short-repo-name (text-util/get-graph-name-from-path repo-url)]
                        (when short-repo-name
                          {:title [:span.flex.items-center.title-wrap short-repo-name]
                           :hover-detail repo-url ;; show full path on hover
                           :options {:on-click
                                     (fn [e]
                                       (when-let [on-click (:on-click opts)]
                                         (on-click e))
                                       (if (gobj/get e "shiftKey")
                                         (state/pub-event! [:graph/open-new-window url])
                                         (state/pub-event! [:graph/switch url])))}})))
                    switch-repos)]
    (->> repo-links (remove nil?))))

(defn- repos-footer []
  [:div.cp__repos-quick-actions
   {:on-click #(shui/popup-hide!)}

   (when-not config/publishing?
     (shui/button
      {:size :sm :variant :ghost
       :on-click #(state/pub-event! [:graph/new-db-graph])}
      (shui/tabler-icon "database-plus")
      [:span (if util/electron? "Create db graph" "Create new graph")]))

   (when-not config/publishing?
     (shui/button
      {:size :sm :variant :ghost
       :on-click (fn [] (route-handler/redirect! {:to :import}))}
      (shui/tabler-icon "database-import")
      [:span (t :import-notes)]))

   (when-not config/publishing?
     (shui/button {:size :sm :variant :ghost
                   :on-click (fn []
                               (if (util/capacitor?)
                                 (state/pub-event! [:mobile/set-tab "graphs"])
                                 (route-handler/redirect-to-all-graphs)))}
                  (shui/tabler-icon "layout-2") [:span (t :all-graphs)]))])

(rum/defcs repos-dropdown-content < rum/reactive
  [_state & {:keys [contentid footer?] :as opts
             :or {footer? true}}]
  (let [current-repo (state/sub :git/current-repo)
        repos (state/sub [:me :repos])
        repos (sort-repos-with-metadata-local repos)
        repos (util/distinct-by :url repos)
        items-fn #(repos-dropdown-links repos current-repo opts)
        header-fn #(when (> (count repos) 1) ; show switch to if there are multiple repos
                     [:div.font-medium.md:text-sm.md:opacity-50.p-2.flex.flex-row.justify-between.items-center
                      [:h4.pb-1 (t :left-side-bar/switch)]])]

    [:div
     {:class (when (<= (count repos) 1) "no-repos")}
     (header-fn)
     [:div.cp__repos-list-wrap
      (for [{:keys [hr item hover-detail title options icon]} (items-fn)]
        (let [on-click' (:on-click options)
              href' (:href options)
              menu-item (if (util/mobile?) ui/menu-link shui/dropdown-menu-item)]
          (if hr
            (if (util/mobile?) [:hr.py-2] (shui/dropdown-menu-separator))
            (menu-item
             (assoc options
                    :title hover-detail
                    :on-click (fn [^js e]
                                (when on-click'
                                  (when-not (false? (on-click' e))
                                    (shui/popup-hide! contentid)))))
             (or item
                 (if href'
                   [:a.flex.items-center.w-full
                    {:href href' :on-click #(shui/popup-hide! contentid)
                     :style {:color "inherit"}} title]
                   [:span.flex.items-center.gap-1.w-full
                    icon [:div title]]))))))]
     (when footer?
       (repos-footer))]))

(rum/defcs graphs-selector < rum/reactive
  [_state]
  (let [current-repo (state/get-current-repo)
        repo-name (when current-repo (db/get-repo-name current-repo))
        short-repo-name (if current-repo
                          (db/get-short-repo-name repo-name)
                          "Select a Graph")]
    [:div.cp__graphs-selector.flex.items-center.justify-between
     [:a.item.flex.items-center.gap-1.select-none
      {:title current-repo
       :on-click (fn [^js e]
                   (shui/popup-show! (.closest (.-target e) "a")
                                     (fn [{:keys [id]}] (repos-dropdown-content {:contentid id}))
                                     {:as-dropdown? true
                                      :content-props {:class "repos-list"}
                                      :align :start}))}
      [:span.thumb (shui/tabler-icon "topology-star" {:size 16})]
      [:strong short-repo-name]
      (shui/tabler-icon "selector" {:size 18})]]))

;; Update invalid-graph-name-warning if characters change
(def multiplatform-reserved-chars ":\\*\\?\"<>|\\#\\\\")

(def reserved-chars-pattern
  (re-pattern (str "[" multiplatform-reserved-chars "]+")))

(defn include-reserved-chars?
  "Includes reserved characters that would broken FS"
  [s]
  (common-util/safe-re-find reserved-chars-pattern s))

(defn invalid-graph-name-warning
  []
  (notification/show!
   [:div
    [:p "Graph name can't contain following reserved characters:"]
    [:ul
     [:li "< (less than)"]
     [:li "> (greater than)"]
     [:li ": (colon)"]
     [:li "\" (double quote)"]
     [:li "/ (forward slash)"]
     [:li "\\ (backslash)"]
     [:li "| (vertical bar or pipe)"]
     [:li "? (question mark)"]
     [:li "* (asterisk)"]
     [:li "# (hash)"]
      ;; `+` is used to encode path that includes `:` or `/`
     [:li "+ (plus)"]]]
   :warning false))

(defn invalid-graph-name?
  "Returns boolean indicating if DB graph name is invalid. Must be kept in sync with invalid-graph-name-warning"
  [graph-name]
  (or (include-reserved-chars? graph-name)
      (string/includes? graph-name "+")
      (string/includes? graph-name "/")))

(rum/defc new-db-graph-inner
  []
  (let [[creating-db? set-creating-db?] (hooks/use-state false)
        input-ref (hooks/create-ref)
        new-db-f (fn new-db-f
                   [graph-name]
                   (when-not (or (string/blank? graph-name)
                                 creating-db?)
                     (if (invalid-graph-name? graph-name)
                       (invalid-graph-name-warning)
                       (do
                         (set-creating-db? true)
                         (p/let [_repo (repo-handler/new-db! graph-name {})]
                           (set-creating-db? false)
                           (shui/dialog-close!))))))
        submit! (fn submit!
                  [^js e click?]
                  (when-let [value (and (or click? (= (gobj/get e "key") "Enter"))
                                        (util/trim-safe (.-value (rum/deref input-ref))))]
                    (new-db-f value)))]
    (hooks/use-effect!
     (fn []
       (when-let [^js input (hooks/deref input-ref)]
         (js/setTimeout #(.focus input) 32)))
     [])

    [:div.new-graph.flex.flex-col.gap-4.p-1.pt-2
     (shui/input
      {:disabled creating-db?
       :ref input-ref
       :placeholder "your graph name"
       :on-key-down submit!
       :autoComplete "off"})
     (shui/button
      {:on-click #(submit! % true)
       :on-key-down submit!}
      (if creating-db?
        (ui/loading "Creating graph")
        "Submit"))]))

(rum/defc new-db-graph
  []
  (new-db-graph-inner))
