(ns frontend.handler.sheet
  "Spreadsheet data persistence using the visual-doc sidecar pattern.

   Each sheet = a page entity tagged with a hidden 'Sheet' class,
   identified by :block/sheet-data attribute.
   The full workbook payload lives in the worker SQLite sidecar."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [frontend.db :as db]
            [frontend.handler.common.page :as common-page-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.page :as page-handler]
            [frontend.handler.route :as route-handler]
            [frontend.handler.visual-doc :as visual-doc]
            [frontend.state :as state]
            [promesa.core :as p]))

(def sheet-attr :block/sheet-data)
(def sheet-cache-prefix "sheet-data")

;; ── Sidecar persistence ─────────────────────────────────────────────────────

(defn save-sheet-to-db!
  "Writes the sheet payload to sidecar storage."
  [page-uuid json-str]
  (if-not (and (seq page-uuid) (seq json-str))
    (p/resolved false)
    (-> (visual-doc/<flush-doc! (state/get-current-repo) page-uuid sheet-attr json-str)
        (p/then boolean)
        (p/catch (fn [error]
                   (js/console.error "[sheet] save-sheet-to-db! failed:" error)
                   false)))))

(defn <load-sheet-doc
  "Loads the sheet payload from sidecar, resolving whether DB or cache is newer."
  [page-uuid]
  (visual-doc/<load-doc (state/get-current-repo) page-uuid sheet-attr sheet-cache-prefix))

;; ── Class tag management ────────────────────────────────────────────────────

(defn- <ensure-sheet-class-tag!
  "Find or create a 'Sheet' class entity (tagged with :logseq.class/Tag).
   Marks it :logseq.property/hide? so it doesn't appear in All Pages."
  []
  (let [database (db/get-db)
        existing-eid (when database
                       (first (d/q '[:find [?e ...]
                                     :where [?e :block/title "Sheet"]
                                            [?e :block/tags ?tag]
                                            [?tag :db/ident :logseq.class/Tag]]
                                   database)))]
    (if existing-eid
      (let [ent (db/entity existing-eid)]
        (when-not (:logseq.property/hide? ent)
          (db/transact! [{:db/id existing-eid :logseq.property/hide? true}]))
        (p/resolved ent))
      (p/let [ent (common-page-handler/<create! "Sheet" {:redirect? false :class? true})]
        (when-let [eid (:db/id ent)]
          (db/transact! [{:db/id eid :logseq.property/hide? true}]))
        ent))))

;; ── Query ───────────────────────────────────────────────────────────────────

(defn get-all-sheets
  "Returns all sheet page manifests from the local DataScript replica."
  []
  (when-let [database (db/get-db)]
    (->> (d/q '[:find (pull ?b [:db/id :block/uuid :block/title :block/updated-at])
                :where [?class :block/title "Sheet"]
                       [?class :block/tags :logseq.class/Tag]
                       [?b :block/tags ?class]
                       [(missing? $ ?b :logseq.property/deleted-at)]]
              database)
         (map first)
         (filter #(and (:block/title %)
                       (:block/uuid %)
                       (not (:db/ident %))))
         (sort-by #(or (:block/updated-at %) 0) >))))

(defn- sheet-name-exists?
  [title]
  (some #(= (string/lower-case (or (:block/title %) ""))
            (string/lower-case title))
        (get-all-sheets)))

;; ── CRUD ────────────────────────────────────────────────────────────────────

(defn <create-sheet!
  "Creates a new sheet page with a default empty workbook.
   Tags it with the Sheet class. Returns the page entity."
  [name opts]
  ;; NOTE: sheet-name-exists? is a best-effort UX guard, NOT a hard uniqueness
  ;; constraint. Two tabs (or two clients on the same graph) calling
  ;; <create-sheet! with the same title concurrently can both see the name as
  ;; free and both go on to create pages — there is no cross-client lock at
  ;; this layer. True uniqueness would require a DataScript upsert with a
  ;; retry loop or server-side enforcement, which is out of scope for the
  ;; visual-doc iteration. Duplicate titles do not corrupt data: each page
  ;; has its own :block/uuid and sidecar row.
  (let [redirect? (if (some? opts) (get opts :redirect? true) true)
        title     (string/trim (or name "新建表格"))]
    (if (sheet-name-exists? title)
      (do (notification/show! (str "表格「" title "」已存在，请使用不同的名称") :warning)
          nil)
      (p/let [page (common-page-handler/<create! title {:redirect? false})
              tag  (<ensure-sheet-class-tag!)]
        (when page
          (let [repo      (state/get-current-repo)
                page-uuid (str (:block/uuid page))
                initial   (js/JSON.stringify
                           (clj->js {:id    "workbook_1"
                                     :name  title
                                     :sheetOrder ["sheet_1"]
                                     :sheets {:sheet_1
                                              {:id         "sheet_1"
                                               :name       "Sheet1"
                                               :rowCount   50
                                               :columnCount 20
                                               :cellData   {}}}}))]
            ;; Tag with Sheet class
            (if tag
              (db/transact! repo
                            [{:db/id      (:db/id page)
                              :block/tags #{(:db/id tag)}}]
                            {:outliner-op :save-block})
              (notification/show! "Sheet 标签创建失败，页面可能不会出现在表格列表中" :warning))
            ;; Seed data
            (visual-doc/save-doc-cache! sheet-cache-prefix page-uuid initial)
            (p/let [_ (visual-doc/<flush-doc! repo page-uuid sheet-attr initial)]
              (when redirect?
                (route-handler/redirect!
                 {:to          :sheet
                  :path-params {:name page-uuid}}))
              page)))))))

(defn <rename-sheet!
  "Renames a sheet page."
  [page-uuid-str new-name]
  (let [trimmed (string/trim (or new-name ""))]
    (if (sheet-name-exists? trimmed)
      (do (notification/show! (str "表格「" trimmed "」已存在，请使用不同的名称") :warning)
          nil)
      (p/do!
       (page-handler/rename! page-uuid-str trimmed)
       (notification/show! "表格已重命名" :success)))))

(defn <delete-sheet!
  "Deletes a sheet page manifest first, then clears local cache and
   best-effort cleans sidecar storage."
  [page-uuid-str]
  (let [page (db/entity [:block/uuid (uuid page-uuid-str)])
        repo (state/get-current-repo)]
    (cond
      (nil? page)
      (do
        (notification/show! "Sheet page not found" :warning)
        (p/resolved false))

      (:db/ident page)
      (do
        (notification/show! "Built-in sheet pages cannot be deleted" :warning)
        (p/resolved false))

      (:logseq.property/deleted-at page)
      (do
        (notification/show! "This sheet has already been deleted" :warning)
        (p/resolved false))

      :else
      (common-page-handler/<delete!
       (uuid page-uuid-str)
       (fn []
         (visual-doc/clear-doc-cache! sheet-cache-prefix page-uuid-str)
         (-> (visual-doc/<delete-sidecar-doc! repo page-uuid-str)
             (p/catch (fn [error]
                        (js/console.error "[sheet] sidecar cleanup failed after page delete:" error)
                        false)))
         (notification/show! "Sheet deleted" :success))
       :error-handler (fn [] (notification/show! "Failed to delete sheet" :error))))))

(defn redirect-to-sheet!
  "Navigate to the sheet editor page."
  [page-uuid]
  (route-handler/redirect!
   {:to          :sheet
    :path-params {:name (str page-uuid)}}))
