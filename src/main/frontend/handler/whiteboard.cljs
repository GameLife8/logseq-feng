(ns frontend.handler.whiteboard
  "Handlers for creating, opening and managing whiteboard pages.

   Whiteboard pages are tagged with :logseq.class/Whiteboard in :block/tags
   so they remain queryable from DataScript and visible in the Pages view.

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

;; ── whiteboard identification ─────────────────────────────────────────────────

(defn get-all-whiteboards
  "Returns whiteboard page entities from the local DataScript replica (best-effort).
   Used only for duplicate-name checks; the gallery UI uses react/q for authoritative data.

   A page qualifies as a whiteboard if either:
     - it is tagged with the :logseq.class/Whiteboard system class, or
     - it is tagged with a user tag page titled \"Whiteboard\" (no :db/ident).
   Pages with :db/ident (built-ins) and :logseq.property/deleted-at are excluded.
   Uses a single or-join so DataScript returns a deduplicated set natively."
  []
  (when-let [database (db/get-db)]
    (->> (d/q '[:find (pull ?b [:db/id :block/uuid :block/title :block/updated-at])
                :where
                (or-join [?b]
                         (and [?b :block/tags ?sys]
                              [?sys :db/ident :logseq.class/Whiteboard])
                         (and [?tag :block/title "Whiteboard"]
                              [(missing? $ ?tag :db/ident)]
                              [?b :block/tags ?tag]))
                [(missing? $ ?b :db/ident)]
                [(missing? $ ?b :logseq.property/deleted-at)]]
              database)
         (map first)
         (filter :block/title)
         (sort-by #(or (:block/updated-at %) 0) >))))

(defn- whiteboard-name-exists?
  "Returns true if a whiteboard with the given title already exists (case-insensitive)."
  [title]
  (some #(= (string/lower-case (or (:block/title %) ""))
            (string/lower-case title))
        (get-all-whiteboards)))

;; ── canvas data (stored on the page entity) ───────────────────────────────────

(defn save-canvas-to-db!
  "Saves Excalidraw canvas as a JSON string on the whiteboard page entity.
   Also bumps :block/updated-at so the gallery sorts correctly.
   Resolves truthy only after the DB flush completes."
  [page-uuid canvas-json]
  (if-not (and (seq page-uuid) (seq canvas-json))
    (do
      (js/console.warn "[whiteboard] save-canvas-to-db! skipped: missing page-uuid or canvas-json")
      (p/resolved false))
    (-> (visual-doc/<flush-doc! (state/get-current-repo) page-uuid canvas-attr canvas-json)
        (p/then (fn [result]
                  (boolean result)))
        (p/catch (fn [error]
                   (js/console.error "[whiteboard] save-canvas-to-db! failed:" error)
                   false)))))

(defn <load-canvas-doc
  "Loads the whiteboard document using the worker DB first, then resolves
   whether DB or local cache is newer."
  [page-uuid]
  (visual-doc/<load-doc (state/get-current-repo) page-uuid canvas-attr canvas-cache-prefix))

(defn load-canvas-from-db
  "Best-effort preview reader for gallery thumbnails from local draft cache."
  [page-uuid]
  (when (seq page-uuid)
    (some-> (visual-doc/read-doc-cache canvas-cache-prefix page-uuid) :data)))

;; ── tag management ────────────────────────────────────────────────────────────

(def ^:private system-tag-idents
  "Tag idents that should not be shown as user tags in the whiteboard toolbar."
  #{:logseq.class/Whiteboard :logseq.class/Page :logseq.class/Journal})

(defn get-page-user-tags
  "Returns user-visible tags for a page entity (excludes internal system tags)."
  [page-entity]
  (->> (:block/tags page-entity)
       (remove #(contains? system-tag-idents (:db/ident %)))))

(defn search-tags
  "Returns user-created tag entities whose titles match `query`.
   Excludes built-in system classes (entities with :db/ident) which cannot
   be assigned to pages via set-block-property!.
   If query is blank, returns all eligible tag entities."
  [query]
  (when-let [database (db/get-db)]
    (let [all-tags (->> (d/q '[:find (pull ?b [:db/id :db/ident :block/uuid :block/title])
                               :where [?b :block/tags :logseq.class/Tag]]
                             database)
                        (map first)
                        (filter :block/title)
                        (remove :db/ident))]   ; built-in entities have :db/ident; user tags do not
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
  "Removes a tag entity from a whiteboard page. Uses the outliner op so that
   the remove goes through the same middleware as `add-tag-to-page!` above —
   both paths now produce symmetric tx metadata and fire the same hooks."
  [page-uuid tag-entity]
  (when (and page-uuid tag-entity)
    (db-property-handler/delete-property-value!
     [:block/uuid page-uuid] :block/tags (:db/id tag-entity))))

;; ── public API ────────────────────────────────────────────────────────────────

(def ^:private initial-canvas-json
  "Minimal Excalidraw scene written to cache + sidecar on create so that a
   whiteboard navigated away from before first edit still resolves to a valid
   empty scene instead of an empty sidecar + empty cache fallthrough."
  "{\"elements\":[],\"appState\":{\"viewBackgroundColor\":\"#ffffff\"},\"files\":{}}")

(defn <create-whiteboard!
  [name opts]
  (let [redirect? (if (some? opts) (get opts :redirect? true) true)
        title     (string/trim (or name "Untitled Whiteboard"))]
    (if (whiteboard-name-exists? title)
      (do (notification/show! (str "白板「" title "」已存在，请使用不同的名称") :warning)
          nil)
      (p/let [page (common-page-handler/<create! title {:redirect? false})]
        (when page
          (let [repo       (state/get-current-repo)
                page-uuid  (str (:block/uuid page))
                tags-ids   (atom #{})]
            ;; Strategy 1: system class tag
            (when-let [wclass (db/entity :logseq.class/Whiteboard)]
              (swap! tags-ids conj (:db/id wclass)))
             ;; Strategy 2: user tag page named "Whiteboard"
             (let [database (db/get-db)
                  tag-eid  (first (d/q '[:find [?e ...]
                                         :where [?e :block/title "Whiteboard"]
                                                [(missing? $ ?e :db/ident)]]
                                       database))]
              (when tag-eid
                (swap! tags-ids conj tag-eid)))
            ;; Apply all collected tags in one transaction
            (when (seq @tags-ids)
              (db/transact! repo
                            [{:db/id      (:db/id page)
                              :block/tags @tags-ids}]
                            {:outliner-op :save-block}))
            ;; Seed the cache + sidecar with an empty scene so the gallery
            ;; thumbnail + editor load path always finds something authoritative
            ;; — without this, navigating away before first edit left the page
            ;; with no scene payload anywhere. Best-effort: a sidecar failure
            ;; is logged but does not fail page creation.
            (visual-doc/save-doc-cache! canvas-cache-prefix page-uuid initial-canvas-json)
            (-> (visual-doc/<flush-doc! repo page-uuid canvas-attr initial-canvas-json)
                (p/catch (fn [error]
                           (js/console.error "[whiteboard] initial scene seed failed:" error)
                           nil))))
          (when redirect?
            (route-handler/redirect!
             {:to          :whiteboard
              :path-params {:name (str (:block/uuid page))}}))
          page)))))

(defn <rename-whiteboard!
  "Renames a whiteboard page. Rejects duplicate names (case-insensitive)."
  [page-uuid-str new-name]
  (let [trimmed (string/trim new-name)]
    (if (whiteboard-name-exists? trimmed)
      (do (notification/show! (str "白板「" trimmed "」已存在，请使用不同的名称") :warning)
          nil)
      (p/do!
       (page-handler/rename! page-uuid-str trimmed)
       (notification/show! "白板已重命名" :success)))))

(defn redirect-to-whiteboard!
  "Navigate to the whiteboard identified by page-uuid (string or uuid)."
  [page-uuid]
  (route-handler/redirect!
   {:to          :whiteboard
    :path-params {:name (str page-uuid)}}))

(defn open-block-in-sidebar!
  "Open a block (by UUID string) in the right sidebar."
  [block-id-str]
  (when (seq block-id-str)
    (editor-handler/open-block-in-sidebar! (uuid block-id-str))))

;; VISUAL-DOC-SIDECAR: redefine delete flow near EOF so the sidecar payload is
;; cleaned only after the manifest delete succeeds.
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
