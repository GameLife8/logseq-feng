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
            [frontend.state :as state]
            [promesa.core :as p]))

(defn get-all-mind-maps
  "返回所有思维导图页面实体（按更新时间倒序）。"
  []
  (when-let [database (db/get-db)]
    (->> (d/q '[:find (pull ?b [:db/id :block/uuid :block/title :block/updated-at])
                :where [?b :block/mind-map-data _]]
              database)
         (map first)
         (filter :block/title)
         (sort-by #(or (:block/updated-at %) 0) >))))

(defn- mind-map-name-exists?
  [title]
  (some #(= (string/lower-case (or (:block/title %) ""))
            (string/lower-case title))
        (get-all-mind-maps)))

(defn save-mind-map-to-db!
  "将思维导图 JSON 存储到对应 page 实体的 :block/mind-map-data。"
  [page-uuid json-str]
  (when (and (seq page-uuid) (seq json-str))
    (when-let [page (db/entity [:block/uuid (uuid page-uuid)])]
      (db/transact! (state/get-current-repo)
                    [{:db/id             (:db/id page)
                      :block/mind-map-data json-str
                      :block/updated-at   (.now js/Date)}]
                    {:outliner-op :save-block})
      true)))

(defn load-mind-map-from-db
  "从 page 实体读取思维导图 JSON，若不存在则返回 nil。"
  [page-uuid]
  (when (seq page-uuid)
    (let [data (:block/mind-map-data (db/entity [:block/uuid (uuid page-uuid)]))]
      (when (seq data) data))))

(defn- <ensure-mindmap-class-tag!
  "找到或创建名为 'MindMap' 的 Class 实体（可作为 :block/tags 的合法值）。
   Class 实体的 :block/tags 包含 :logseq.class/Tag。
   使用 {:class? true} 创建，确保生成正确的 :db/ident。"
  []
  (let [database (db/get-db)
        existing-eid (when database
                       (ffirst (d/q '[:find [?e ...]
                                      :where [?e :block/title "MindMap"]
                                             [?e :block/tags ?tag]
                                             [?tag :db/ident :logseq.class/Tag]]
                                    database)))]
    (if existing-eid
      (do (js/console.log "[mind-map] found existing MindMap class tag, id=" existing-eid)
          (p/resolved (db/entity existing-eid)))
      (do (js/console.log "[mind-map] creating MindMap class tag")
          (common-page-handler/<create! "MindMap" {:redirect? false :class? true})))))

(defn <create-mind-map!
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
                               :block/mind-map-data (str "{\"data\":{\"text\":\"" title "\"},\"children\":[]}")
                               :block/updated-at   (.now js/Date)}
                        (some? tag) (assoc :block/tags #{(:db/id tag)}))]
            (db/transact! repo [tx] {:outliner-op :save-block}))
          (route-handler/redirect!
           {:to          :mind-map
            :path-params {:name (str (:block/uuid page))}})
          page)))))

(defn <delete-mind-map!
  "删除思维导图页面。"
  [page-uuid-str]
  (common-page-handler/<delete!
   (uuid page-uuid-str)
   (fn [] (notification/show! "思维导图已删除" :success))))

(defn <rename-mind-map!
  "重命名思维导图页面。"
  [page-uuid-str new-name]
  (let [trimmed (string/trim new-name)]
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
