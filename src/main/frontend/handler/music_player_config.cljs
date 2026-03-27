(ns frontend.handler.music-player-config
  "本地音乐播放器配置，存储为 DB graph 页面实体属性。

   Config page:  title  = \"music-player-config\"
                 tag    = \"ConfigPage\" class entity
                 attr   = :block/music-player-config  (JSON string)

   Config map keys:
     :music-folder  – 本地音乐文件夹的绝对路径
     :mpv-path      – mpv 可执行文件的绝对路径（不打包，外部安装）
     :volume        – 默认音量 0-100"
  (:require [datascript.core :as d]
            [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.handler.common.page :as common-page-handler]
            [frontend.handler.notification :as notification]
            [frontend.state :as state]
            [promesa.core :as p]))

;; ── 常量 ─────────────────────────────────────────────────────────────────────

(def ^:private config-page-title "music-player-config")
(def ^:private config-attr       :block/music-player-config)
(def ^:private tag-title         "ConfigPage")

(def default-config
  {:music-folder ""
   :mpv-path     ""
   :volume       80})

;; ── 读取 ─────────────────────────────────────────────────────────────────────

(defn get-config
  "同步读取（main-thread DataScript replica）。"
  []
  (if-let [page (db/get-page config-page-title)]
    (if-let [raw (get page config-attr)]
      (try (merge default-config
                  (js->clj (js/JSON.parse raw) :keywordize-keys true))
           (catch :default _ default-config))
      default-config)
    default-config))

(defn <get-config
  "异步读取，直接查询 worker DB，绕过 lazy 主线程 replica。"
  []
  (let [repo (state/get-current-repo)]
    (p/let [result (db-async/<pull repo
                                   [:db/id :block/name config-attr]
                                   [:block/name config-page-title])]
      (if-let [raw (and result (get result config-attr))]
        (try (merge default-config
                    (js->clj (js/JSON.parse raw) :keywordize-keys true))
             (catch :default _ default-config))
        default-config))))

;; ── 写入 ─────────────────────────────────────────────────────────────────────

(defn- <ensure-class-tag! [title]
  (let [database (db/get-db)
        existing-eid (when database
                       (first (d/q '[:find [?e ...]
                                     :in $ ?t
                                     :where [?e :block/title ?t]
                                            [?e :block/tags ?tag]
                                            [?tag :db/ident :logseq.class/Tag]]
                                   database title)))]
    (if existing-eid
      (p/resolved (db/entity existing-eid))
      (common-page-handler/<create! title {:redirect? false :class? true}))))

(defn- ensure-config-page! []
  (let [repo (state/get-current-repo)]
    (p/let [existing (db-async/<pull repo
                                     '[:db/id :block/name :block/title]
                                     [:block/name config-page-title])]
      (if existing
        (db/entity (:db/id existing))
        (p/let [page (common-page-handler/<create! config-page-title {:redirect? false})
                tag  (<ensure-class-tag! tag-title)]
          (when (and page tag)
            (db/transact! repo
                          [{:db/id      (:db/id page)
                            :block/tags #{(:db/id tag)}}]
                          {:outliner-op :save-block}))
          page)))))

(defn save-config!
  "持久化配置到 config 页面实体属性。"
  [config-map]
  (p/let [page (ensure-config-page!)]
    (if page
      (let [repo   (state/get-current-repo)
            merged (merge default-config config-map)]
        (db/transact! repo
                      [{:db/id            (:db/id page)
                        config-attr       (js/JSON.stringify (clj->js merged))
                        :block/updated-at (.now js/Date)}]
                      {:outliner-op :save-block}))
      (notification/show! "无法创建音乐播放器配置页" :error))))
