(ns frontend.handler.mind-map
  "思维导图数据持久化：支持多个思维导图文档。

   存储策略：
   - 每个思维导图 = 一个独立的 page 实体
   - 数据以 JSON 字符串存储在 :block/mind-map-data 属性上
   - 通过 :block/mind-map-data 属性识别思维导图页面
   - 同 :block/whiteboard-canvas，属于 ad-hoc 属性，无需 schema 注册"
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

(def mind-map-attr :block/mind-map-data)
(def mind-map-cache-prefix "mind-map-data")

(declare mind-map-name-exists?)

#_(defn get-all-mind-maps
  "返回所有思维导图页面实体（按更新时间倒序）。"
  []
  (when-let [database (db/get-db)]
    (->> (d/q '[:find (pull ?b [:db/id :block/uuid :block/title :block/updated-at])
                :where [?b :block/mind-map-data _]]
              database)
         (map first)
         (filter :block/title)
         (sort-by #(or (:block/updated-at %) 0) >))))

#_(defn- mind-map-name-exists?
  [title]
  (some #(= (string/lower-case (or (:block/title %) ""))
            (string/lower-case title))
        (get-all-mind-maps)))

#_(defn save-mind-map-to-db!
  "将思维导图 JSON 存储到对应 page 实体的 :block/mind-map-data。
   Returns a promise that resolves truthy only after the DB flush completes."
  [page-uuid json-str]
  (if-not (and (seq page-uuid) (seq json-str))
    (p/resolved false)
    (-> (visual-doc/<flush-doc! (state/get-current-repo) page-uuid mind-map-attr json-str)
        (p/then boolean)
        (p/catch (fn [error]
                   (js/console.error "[mind-map] save-mind-map-to-db! failed:" error)
                   false)))))

#_(defn <load-mind-map-doc
  "Loads the mind map document using the worker DB first, then resolves
   whether DB or local cache is newer."
  [page-uuid]
  (visual-doc/<load-doc (state/get-current-repo) page-uuid mind-map-attr mind-map-cache-prefix))

#_(defn load-mind-map-from-db
  "从 page 实体读取思维导图 JSON，若不存在则返回 nil。"
  [page-uuid]
  (when (seq page-uuid)
    (let [data (mind-map-attr (db/entity [:block/uuid (uuid page-uuid)]))]
      (when (seq data) data))))

(defn- <ensure-mindmap-class-tag!
  "找到或创建名为 'MindMap' 的 Class 实体（可作为 :block/tags 的合法值）。
   Class 实体的 :block/tags 包含 :logseq.class/Tag。
   使用 {:class? true} 创建，确保生成正确的 :db/ident。
   同时设置 :logseq.property/hide? 使其不出现在 All Pages 列表。"
  []
  (let [database (db/get-db)
        ;; [:find [?e ...]] 返回直接值向量，用 first，不能用 ffirst
        existing-eid (when database
                       (first (d/q '[:find [?e ...]
                                     :where [?e :block/title "MindMap"]
                                            [?e :block/tags ?tag]
                                            [?tag :db/ident :logseq.class/Tag]]
                                   database)))]
    (if existing-eid
      (let [ent (db/entity existing-eid)]
        ;; 确保已有实体也标记为隐藏
        (when-not (:logseq.property/hide? ent)
          (db/transact! [{:db/id existing-eid :logseq.property/hide? true}]))
        (p/resolved ent))
      (p/let [ent (common-page-handler/<create! "MindMap" {:redirect? false :class? true})]
        ;; 新创建的 class 标记为隐藏，不显示在 All Pages
        (when-let [eid (:db/id ent)]
          (db/transact! [{:db/id eid :logseq.property/hide? true}]))
        ent))))

#_(defn <create-mind-map!
  "创建新的思维导图页面，并跳转到编辑器。"
  [name]
  (let [title (string/trim (or name "新思维导图"))]
    (if (mind-map-name-exists? title)
      (do (notification/show! (str "思维导图「" title "」已存在，请使用不同的名称") :warning)
          nil)
      (p/let [page (common-page-handler/<create! title {:redirect? false})
              tag  (<ensure-mindmap-class-tag!)]
        (when page
          ;; 写入默认 JSON + 打 MindMap 标签（合并为单次 transact!）
          (let [repo (state/get-current-repo)
                tx   (cond-> {:db/id             (:db/id page)
                               :block/mind-map-data (js/JSON.stringify
                                                     (clj->js {:data {:text title}
                                                               :children []}))
                               :block/updated-at   (.now js/Date)}
                        (some? tag) (assoc :block/tags #{(:db/id tag)}))]
            (db/transact! repo [tx] {:outliner-op :save-block}))
          (route-handler/redirect!
           {:to          :mind-map
            :path-params {:name (str (:block/uuid page))}})
          page)))))

#_(defn <delete-mind-map!
  "删除思维导图页面。"
  [page-uuid-str]
  (let [page (db/entity [:block/uuid (uuid page-uuid-str)])]
    (cond
      (nil? page)
      (do
        (notification/show! "思维导图页面未找到" :warning)
        (p/resolved false))

      (:db/ident page)
      (do
        (notification/show! "内置思维导图页面不能删除" :warning)
        (p/resolved false))

      :else
      (common-page-handler/<delete!
       (uuid page-uuid-str)
       (fn [] (notification/show! "思维导图已删除" :success))))))

(defn <rename-mind-map!
  "重命名思维导图页面。"
  [page-uuid-str new-name]
  (let [trimmed (string/trim (or new-name ""))]
    (if (mind-map-name-exists? trimmed)
      (do (notification/show! (str "思维导图「" trimmed "」已存在，请使用不同的名称") :warning)
          nil)
      (p/do!
       (page-handler/rename! page-uuid-str trimmed)
       (notification/show! "思维导图已重命名" :success)))))

(defn redirect-to-mind-map!
  "跳转到指定 UUID 的思维导图编辑器。"
  [page-uuid]
  (route-handler/redirect!
   {:to          :mind-map
    :path-params {:name (str page-uuid)}}))

;; VISUAL-DOC-SIDECAR: append clean manifest-first definitions near EOF so the
;; sidecar storage model is isolated from the older page-blob implementation.

(defn get-all-mind-maps
  "Returns all mind-map page manifests from the local DataScript replica."
  []
  (when-let [database (db/get-db)]
    (->> (d/q '[:find (pull ?b [:db/id :block/uuid :block/title :block/updated-at])
                :where [?class :block/title "MindMap"]
                       [?class :block/tags :logseq.class/Tag]
                       [?b :block/tags ?class]
                       [(missing? $ ?b :logseq.property/deleted-at)]]
              database)
         (map first)
         (filter #(and (:block/title %)
                       (:block/uuid %)
                       (not (:db/ident %))))
         (sort-by #(or (:block/updated-at %) 0) >))))

(defn- mind-map-name-exists?
  [title]
  (some #(= (string/lower-case (or (:block/title %) ""))
            (string/lower-case title))
        (get-all-mind-maps)))

(defn save-mind-map-to-db!
  "Writes the mind-map payload to sidecar storage and updates only the page
   manifest metadata."
  [page-uuid json-str]
  (if-not (and (seq page-uuid) (seq json-str))
    (p/resolved false)
    (-> (visual-doc/<flush-doc! (state/get-current-repo) page-uuid mind-map-attr json-str)
        (p/then boolean)
        (p/catch (fn [error]
                   (js/console.error "[mind-map] save-mind-map-to-db! failed:" error)
                   false)))))

(defn <load-mind-map-doc
  "Loads the mind-map payload from sidecar storage first, then resolves whether
   sidecar, legacy DB, or local cache should seed the editor."
  [page-uuid]
  (visual-doc/<load-doc (state/get-current-repo) page-uuid mind-map-attr mind-map-cache-prefix))

(defn load-mind-map-from-db
  "Best-effort preview reader for mind-map payloads from local draft cache."
  [page-uuid]
  (when (seq page-uuid)
    (some-> (visual-doc/read-doc-cache mind-map-cache-prefix page-uuid) :data)))

(defn <create-mind-map!
  [name opts]
  (let [redirect? (if (some? opts) (get opts :redirect? true) true)
        title     (string/trim (or name "新思维导图"))]
    (if (mind-map-name-exists? title)
      (do
        (notification/show! (str "思维导图「" title "」已存在，请使用不同的名称") :warning)
        nil)
      (p/let [page (common-page-handler/<create! title {:redirect? false})
              tag  (<ensure-mindmap-class-tag!)]
        (when page
          (let [repo       (state/get-current-repo)
                page-uuid  (str (:block/uuid page))
                initial-js (js/JSON.stringify
                            (clj->js {:data {:text title}
                                      :children []}))]
            (if tag
              (db/transact! repo
                            [{:db/id      (:db/id page)
                              :block/tags #{(:db/id tag)}}]
                            {:outliner-op :save-block})
              (notification/show! "MindMap 标签创建失败, 页面可能不会出现在思维导图列表中" :warning))
            (visual-doc/save-doc-cache! mind-map-cache-prefix page-uuid initial-js)
            (p/let [_ (visual-doc/<flush-doc! repo page-uuid mind-map-attr initial-js)]
              (when redirect?
                (route-handler/redirect!
                 {:to          :mind-map
                  :path-params {:name page-uuid}}))
              page)))))))

(defn <delete-mind-map!
  "Deletes a mind-map page after removing its sidecar payload and local cache."
  [page-uuid-str]
  (let [page (db/entity [:block/uuid (uuid page-uuid-str)])]
    (cond
      (nil? page)
      (do
        (notification/show! "思维导图页面未找到" :warning)
        (p/resolved false))

      (:db/ident page)
      (do
        (notification/show! "内置思维导图页面不能删除" :warning)
        (p/resolved false))

      (:logseq.property/deleted-at page)
      (do
        (notification/show! "该思维导图已被删除" :warning)
        (p/resolved false))

      :else
      (p/do!
       (visual-doc/<delete-doc! (state/get-current-repo) page-uuid-str mind-map-cache-prefix)
       (.removeItem js/localStorage (str "mind-map-thumb-" page-uuid-str))
       (common-page-handler/<delete!
        (uuid page-uuid-str)
        (fn [] (notification/show! "思维导图已删除" :success))
        :error-handler (fn [] (notification/show! "删除思维导图失败" :error)))))))
