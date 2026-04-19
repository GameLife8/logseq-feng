(ns frontend.handler.whiteboard
  "Handlers for creating, opening and managing whiteboard pages.

   Whiteboard pages follow the same visual-doc modeling as sheets and
   mind-maps: each page keeps normal Page semantics and is additionally tagged
   with a hidden user-defined `Whiteboard` class entity.

   VISUAL-DOC-SIDECAR: the page entity is a lightweight manifest only.
   The full Excalidraw payload lives in the worker sqlite sidecar."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [frontend.db :as db]
            [frontend.handler.common.page :as common-page-handler]
            [frontend.handler.db-based.property :as db-property-handler]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.page :as page-handler]
            [frontend.handler.route :as route-handler]
            [frontend.handler.visual-doc :as visual-doc]
            [frontend.state :as state]
            [promesa.core :as p]))

(def canvas-attr :block/whiteboard-canvas)
(def canvas-cache-prefix "whiteboard-data")
(def ^:private whiteboard-class-title "Whiteboard")

(defn get-whiteboard-class-tag
  "Returns the hidden user-defined Whiteboard class entity, if it exists."
  []
  (when-let [database (db/get-db)]
    (some->> (d/q '[:find [?e ...]
                    :in $ ?title
                    :where [?e :block/title ?title]
                           [?e :block/tags ?tag]
                           [?tag :db/ident :logseq.class/Tag]
                           [?e :db/ident ?ident]
                           [(namespace ?ident) ?ns]
                           [(= ?ns "user.class")]
                           [(missing? $ ?e :logseq.property/deleted-at)]]
                  database whiteboard-class-title)
             first
             db/entity)))

(defn <ensure-whiteboard-class-tag!
  "Find or create the hidden `Whiteboard` class entity used to classify
   whiteboard pages."
  []
  (if-let [ent (get-whiteboard-class-tag)]
    (do
      (when-not (:logseq.property/hide? ent)
        (db/transact! [{:db/id (:db/id ent) :logseq.property/hide? true}]))
      (p/resolved ent))
    (p/let [ent (common-page-handler/<create! whiteboard-class-title {:redirect? false
                                                                      :class? true})]
      (when-let [eid (:db/id ent)]
        (db/transact! [{:db/id eid :logseq.property/hide? true}]))
      ent)))

(defn get-all-whiteboards
  "Returns whiteboard page entities from the local DataScript replica."
  []
  (if-let [class-tag (get-whiteboard-class-tag)]
    (when-let [database (db/get-db)]
      (->> (d/q '[:find (pull ?b [:db/id :block/uuid :block/title :block/updated-at])
                  :in $ ?class-id
                  :where [?b :block/tags ?class-id]
                         [(missing? $ ?b :db/ident)]
                         [(missing? $ ?b :logseq.property/deleted-at)]]
                database (:db/id class-tag))
           (map first)
           (filter :block/title)
           (sort-by #(or (:block/updated-at %) 0) >)))
    []))

(defn- whiteboard-name-exists?
  "Returns true if a whiteboard with the given title already exists."
  [title]
  (some #(= (string/lower-case (or (:block/title %) ""))
            (string/lower-case title))
        (get-all-whiteboards)))

(defn save-canvas-to-db!
  "Writes the whiteboard payload to sidecar storage."
  [page-uuid canvas-json]
  (if-not (and (seq page-uuid) (seq canvas-json))
    (do
      (js/console.warn "[whiteboard] save-canvas-to-db! skipped: missing page-uuid or canvas-json")
      (p/resolved false))
    (-> (visual-doc/<flush-doc! (state/get-current-repo) page-uuid canvas-attr canvas-json)
        (p/then boolean)
        (p/catch (fn [error]
                   (js/console.error "[whiteboard] save-canvas-to-db! failed:" error)
                   false)))))

(defn <load-canvas-doc
  "Loads the whiteboard payload from sidecar, resolving whether DB or cache is newer."
  [page-uuid]
  (visual-doc/<load-doc (state/get-current-repo) page-uuid canvas-attr canvas-cache-prefix))

(defn load-canvas-from-db
  "Best-effort preview reader for gallery thumbnails from local draft cache."
  [page-uuid]
  (when (seq page-uuid)
    (some-> (visual-doc/read-doc-cache canvas-cache-prefix page-uuid) :data)))

(def ^:private system-tag-idents
  "Tag idents that should not be shown as user tags in the whiteboard toolbar."
  #{:logseq.class/Whiteboard :logseq.class/Page :logseq.class/Journal})

(defn- whiteboard-class-tag?
  [tag-entity]
  (or (= :logseq.class/Whiteboard (:db/ident tag-entity))
      (and (= whiteboard-class-title (:block/title tag-entity))
           (= "user.class" (namespace (:db/ident tag-entity))))))

(defn get-page-user-tags
  "Returns user-visible tags for a page entity."
  [page-entity]
  (->> (:block/tags page-entity)
       (remove #(or (contains? system-tag-idents (:db/ident %))
                    (whiteboard-class-tag? %)))))

(defn search-tags
  "Returns user-created tag entities whose titles match `query`.
   Built-in and hidden class tags are excluded because they all carry :db/ident."
  [query]
  (when-let [database (db/get-db)]
    (let [all-tags (->> (d/q '[:find (pull ?b [:db/id :db/ident :block/uuid :block/title])
                               :where [?b :block/tags :logseq.class/Tag]]
                             database)
                        (map first)
                        (filter :block/title)
                        (remove :db/ident))]
      (if (string/blank? query)
        all-tags
        (let [q (string/lower-case (string/trim query))]
          (filter #(string/includes? (string/lower-case (or (:block/title %) "")) q) all-tags))))))

(defn add-tag-to-page!
  "Adds an existing tag entity to a whiteboard page."
  [page-uuid tag-entity]
  (when (and page-uuid tag-entity)
    (db-property-handler/set-block-property!
     [:block/uuid page-uuid] :block/tags (:db/id tag-entity))))

(defn remove-tag-from-page!
  "Removes a tag entity from a whiteboard page."
  [page-uuid tag-entity]
  (when (and page-uuid tag-entity)
    (db-property-handler/delete-property-value!
     [:block/uuid page-uuid] :block/tags (:db/id tag-entity))))

(def ^:private initial-canvas-json
  "Minimal Excalidraw scene seeded on create."
  "{\"elements\":[],\"appState\":{\"viewBackgroundColor\":\"#ffffff\"},\"files\":{}}")

(defn <create-whiteboard!
  [name opts]
  (let [redirect? (if (some? opts) (get opts :redirect? true) true)
        title (string/trim (or name "Untitled Whiteboard"))]
    (if (whiteboard-name-exists? title)
      (do
        (notification/show! (str "Whiteboard \"" title "\" already exists, please use a different name") :warning)
        nil)
      (p/let [page (common-page-handler/<create! title {:redirect? false})
              tag (<ensure-whiteboard-class-tag!)]
        (when page
          (let [repo (state/get-current-repo)
                page-uuid (str (:block/uuid page))]
            (if tag
              (db/transact! repo
                            [{:db/id (:db/id page)
                              :block/tags #{(:db/id tag)}}]
                            {:outliner-op :save-block})
              (notification/show! "Whiteboard class tag creation failed; the page may not appear in the whiteboard list" :warning))
            (visual-doc/save-doc-cache! canvas-cache-prefix page-uuid initial-canvas-json)
            (-> (visual-doc/<flush-doc! repo page-uuid canvas-attr initial-canvas-json)
                (p/catch (fn [error]
                           (js/console.error "[whiteboard] initial scene seed failed:" error)
                           nil))))
          (when redirect?
            (route-handler/redirect!
             {:to :whiteboard
              :path-params {:name (str (:block/uuid page))}}))
          page)))))

(defn <rename-whiteboard!
  "Renames a whiteboard page."
  [page-uuid-str new-name]
  (let [trimmed (string/trim new-name)]
    (if (whiteboard-name-exists? trimmed)
      (do
        (notification/show! (str "Whiteboard \"" trimmed "\" already exists, please use a different name") :warning)
        nil)
      (p/do!
       (page-handler/rename! page-uuid-str trimmed)
       (notification/show! "Whiteboard renamed" :success)))))

(defn redirect-to-whiteboard!
  "Navigate to the whiteboard identified by page-uuid."
  [page-uuid]
  (route-handler/redirect!
   {:to :whiteboard
    :path-params {:name (str page-uuid)}}))

(defn open-block-in-sidebar!
  "Open a block (by UUID string) in the right sidebar."
  [block-id-str]
  (when (seq block-id-str)
    (editor-handler/open-block-in-sidebar! (uuid block-id-str))))

(defn <delete-whiteboard!
  "Deletes a whiteboard page manifest first, then clears local cache and
   best-effort cleans sidecar storage."
  [page-uuid-str]
  (let [page (db/entity [:block/uuid (uuid page-uuid-str)])
        repo (state/get-current-repo)]
    (cond
      (nil? page)
      (do
        (notification/show! "Whiteboard page not found" :warning)
        (p/resolved false))

      (:db/ident page)
      (do
        (notification/show! "Built-in whiteboard pages cannot be deleted" :warning)
        (p/resolved false))

      (:logseq.property/deleted-at page)
      (do
        (notification/show! "This whiteboard has already been deleted" :warning)
        (p/resolved false))

      :else
      (common-page-handler/<delete!
       (uuid page-uuid-str)
       (fn []
         (visual-doc/clear-doc-cache! canvas-cache-prefix page-uuid-str)
         (-> (visual-doc/<delete-sidecar-doc! repo page-uuid-str)
             (p/catch (fn [error]
                        (js/console.error "[whiteboard] sidecar cleanup failed after page delete:" error)
                        false)))
         (notification/show! "Whiteboard deleted" :success))
       :error-handler (fn [] (notification/show! "Failed to delete whiteboard" :error))))))
