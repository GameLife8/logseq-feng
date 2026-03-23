(ns frontend.handler.mind-map
  "思维导图数据持久化：将导图 JSON 存储在 Logseq DB 的 page 实体上。

   存储策略：
   - 每个图谱有一个固定的「思维导图」页面（按 :block/title 查找或自动创建）
   - 导图数据以 JSON 字符串存储在该页面的 :block/mind-map-data 属性上
   - 同 Excalidraw 的 :block/whiteboard-canvas，属于 ad-hoc 属性，无需 schema 注册"
  (:require [frontend.db :as db]
            [frontend.handler.common.page :as common-page-handler]
            [frontend.state :as state]
            [promesa.core :as p]))

(def ^:private page-title "思维导图")

(defn- find-mind-map-page
  "在当前图谱中查找思维导图页面，返回实体或 nil。"
  []
  (when-let [database (db/get-db)]
    (let [result (datascript.core/q
                  '[:find ?e :in $ ?title :where [?e :block/title ?title]]
                  database page-title)]
      (when-let [eid (ffirst result)]
        (db/entity eid)))))

(defn <ensure-mind-map-page!
  "找到或创建思维导图页面，返回 Promise<entity>。"
  []
  (if-let [page (find-mind-map-page)]
    (p/resolved page)
    (common-page-handler/<create! page-title {:redirect? false})))

(defn save-mind-map-to-db!
  "将思维导图 JSON 字符串保存到 DB。
   map-id 参数保留以兼容 on-save-data 回调签名，实际使用页面实体。"
  [_map-id json-str]
  (when (seq json-str)
    (-> (<ensure-mind-map-page!)
        (p/then (fn [page]
                  (when-let [eid (:db/id page)]
                    (db/transact! (state/get-current-repo)
                                  [{:db/id             eid
                                    :block/mind-map-data json-str
                                    :block/updated-at   (.now js/Date)}]
                                  {:outliner-op :save-block})))))))

(defn load-mind-map-from-db
  "从 DB 读取思维导图 JSON 字符串，若页面不存在则返回 nil。"
  [_map-id]
  (some-> (find-mind-map-page)
          :block/mind-map-data))
