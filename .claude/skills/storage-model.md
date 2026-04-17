# 存储模型 (storage-model)

Logseq DB 版的本地存储架构：**每个 graph = 独立的 SQLite 数据库**，存在浏览器的 OPFS（Origin Private File System）里。**没有多租户概念**，**没有远端数据库**（同步已移除，见 `db-sync-collaboration.md`）。

---

## 一、用户问的核心问题

> 新建一个 db graph 是创建新数据库，还是共用？  
> 为什么不同浏览器可以创建同名 graph？

**TL;DR**

1. 每新建一个 db graph → 在 **OPFS** 里新开一个 **SQLite pool**（独立目录），互不共享；
2. "同名 graph 可以在不同浏览器里各创一份" 的真正原因是 **OPFS 是浏览器私有存储**：
   - Chrome 的 OPFS 和 Firefox 的 OPFS **物理上就是两份独立的目录**；
   - 两份 OPFS 里都能有一个叫 "Demo" 的 pool，互相看不到；
   - 不是因为有中心服务器在分配租户，而是因为 **根本没有中心服务器**。

---

## 二、物理存储层级

```
Browser
└── Origin: http://localhost:3001            ← OPFS 的隔离边界
    └── OPFS root (navigator.storage.getDirectory())
        ├── .logseq-pool-Demo/               ← graph "Demo" 的 pool（SAH Pool VFS 目录）
        │   ├── <SAH-handle-1>               (SQLite SAH 池内部文件)
        │   ├── <SAH-handle-2>
        │   └── ...
        │   ┌─ 逻辑上在此 pool 内存放四个 SQLite 数据库 ─┐
        │   ├── /db.sqlite                   ← 主图数据（DataScript 节点、kvs 表）
        │   ├── /searchdb.sqlite             ← 全文搜索（FTS5）
        │   ├── /client-ops-db.sqlite        ← outliner 操作日志
        │   └── /visual_docs.sqlite          ← 白板/思维导图 sidecar
        │
        ├── .logseq-pool-My_graph/           ← graph "My/graph"（/ → _）
        │   └── ...
        │
        └── .logseq-pool-another_graph/
            └── ...
```

**关键事实**

| 问题 | 答案 | 证据 |
|---|---|---|
| 每个 graph 存哪？ | 一个独立的 OPFS SAH Pool（目录） | `<get-opfs-pool` [db_worker.cljs:92](src/main/frontend/worker/db_worker.cljs:92) |
| Pool 目录名怎么起？ | `.logseq-pool-{sanitize(graph-name)}` | `get-pool-name` [worker_common/util.cljc:34](src/main/frontend/worker_common/util.cljc:34) |
| 名字怎么 sanitize？ | `/ \ :` → `_` | `sanitize-db-name` [deps/db/.../sqlite.cljs:19](deps/db/src/logseq/db/common/sqlite.cljs:19) |
| 同一个 pool 里几个 DB？ | 4 个：`/db.sqlite`, `/searchdb.sqlite`, `/client-ops-db.sqlite`, `/visual_docs.sqlite` | `get-dbs` [db_worker.cljs:281](src/main/frontend/worker/db_worker.cljs:281)，visual-doc 见 `get-visual-doc-db` |
| Pool 和 DB 啥关系？ | Pool = VFS 容器 / 文件系统；DB = 里面的文件 | SQLite WASM OPFS SAH Pool VFS 文档 |
| DataScript 跟 SQLite 啥关系？ | DataScript 在内存里查询，持久化通过 `IStorage` 接口写到 `/db.sqlite` 的 `kvs` 表 | `new-sqlite-storage` [db_worker.cljs:162](src/main/frontend/worker/db_worker.cljs:162) |

---

## 三、关键代码路径

### 3.1 Pool 创建 / 获取

[db_worker.cljs:92](src/main/frontend/worker/db_worker.cljs):

```clojure
(defn- <get-opfs-pool
  [graph]
  (when-not @*publishing?
    (or (worker-state/get-opfs-pool graph)                           ;; 已有直接复用
        (p/let [^js pool (.installOpfsSAHPoolVfs
                          ^js @*sqlite
                          #js {:name (worker-util/get-pool-name graph)  ;; 按 graph 名字隔离
                               :initialCapacity 20})]
          (swap! *opfs-pools assoc graph pool)
          pool))))
```

### 3.2 Pool 名字生成

[worker_common/util.cljc:34](src/main/frontend/worker_common/util.cljc:34):

```clojure
(defn get-pool-name
  [graph-name]
  (str "logseq-pool-" (common-sqlite/sanitize-db-name graph-name)))
```

[deps/db/.../sqlite.cljs:19](deps/db/src/logseq/db/common/sqlite.cljs:19):

```clojure
(defn sanitize-db-name
  [db-name]
  (-> db-name
      (string/replace sqlite-util/db-version-prefix "")
      (string/replace "/" "_")
      (string/replace "\\" "_")
      (string/replace ":" "_")))                ;; Windows 兼容
```

SAH Pool VFS 在内部会再加一个前导点：`.logseq-pool-Demo/`。

### 3.3 同 Pool 下挂 4 个 DB

[db_worker.cljs:281](src/main/frontend/worker/db_worker.cljs:281):

```clojure
(defn- get-dbs
  [repo]
  (if @*publishing?
    ;; 发布站点模式：直接用内存模式
    (p/let [^object DB (.-DB ^object (.-oo1 ^object @*sqlite))
            db (new DB "/db.sqlite" "c")
            search-db (new DB "/search-db.sqlite" "c")]
      [db search-db])
    ;; 正常模式：同一个 SAH Pool 里开三个 DB
    (p/let [^js pool (<get-opfs-pool repo)
            capacity (.getCapacity pool)
            _ (when (zero? capacity) (.unpauseVfs pool))
            db            (new (.-OpfsSAHPoolDb pool) repo-path)                    ;; /db.sqlite
            search-db     (new (.-OpfsSAHPoolDb pool) (str "search" repo-path))     ;; /searchdb.sqlite
            client-ops-db (new (.-OpfsSAHPoolDb pool) (str "client-ops-" repo-path))] ;; /client-ops-db.sqlite
      [db search-db client-ops-db])))
```

第 4 个 `visual_docs.sqlite` 由 `<get-visual-doc-db` 延迟创建（首次访问 whiteboard / mind-map 时）。

### 3.4 新建 / 打开 graph 统一入口

[db_worker.cljs:327](src/main/frontend/worker/db_worker.cljs:327) `<create-or-open-db!`：
- 存在 → 直接开连接
- 不存在 → 创建 pool → 创建表 → 灌初始 schema (`sqlite-create-graph/build-db-initial-data`)

前端调用 [persist_db/browser.cljs:206](src/main/frontend/persist_db/browser.cljs:206)：
```clojure
(state/<invoke-db-worker :thread-api/create-or-open-db repo opts)
```

### 3.5 列出所有 graph

扫 OPFS root 目录下所有 `.logseq-pool-*` 开头的子目录 ([db_worker.cljs:420](src/main/frontend/worker/db_worker.cljs:420))：

```clojure
(p/let [db-dirs (filter (fn [^js file]
                          (string/starts-with? (.-name file) db-dir-prefix))
                        current-dir-dirs)]
  (p/all (map (fn [dir]
                (p/let [graph-name (-> (.-name dir)
                                       (string/replace-first ".logseq-pool-" "")
                                       (string/replace "+3A+" ":")
                                       (string/replace "++" "/"))]
                  {:name graph-name})) db-dirs)))
```

注意反向转换：`+3A+` → `:`，`++` → `/`（旧 URL-encode 风格，跟 `sanitize-db-name` 的 `_` 替换**不一致**，历史遗留）。

---

## 四、为什么不同浏览器能同名创建

### OPFS 的隔离边界（强 → 弱）

```
Browser 程序本身（Chrome / Firefox / Edge）
  │                                        ← OPFS 数据**完全独立**，各管各
  └── 用户 Profile（默认 / 工作 / 访客）
        │                                  ← OPFS 按 profile 隔离
        └── Origin（scheme + host + port）
              │                            ← 不同 origin = 不同 OPFS 根
              └── OPFS root
                    └── .logseq-pool-{name}
```

所以：

| 场景 | 能否看到同一个 graph |
|---|---|
| Chrome `localhost:3001` 和 Firefox `localhost:3001` | ❌ 看不到（浏览器层就隔离了） |
| Chrome 默认 profile 和 Chrome 访客 profile | ❌ 看不到（profile 隔离） |
| Chrome `localhost:3001` 和 Chrome `localhost:3002` | ❌ 看不到（端口不同 = 不同 origin） |
| Chrome `localhost:3001` 和 Chrome `127.0.0.1:3001` | ❌ 看不到（host 不同 = 不同 origin） |
| 同浏览器同 profile 同 origin 的两个 tab | ✅ 看同一份（单例 pool，多 tab 会有并发锁问题） |
| Electron 桌面版 | ⚠️ 用的是本地文件系统，**不走 OPFS**，路径由 Electron 的 user data dir 决定 |

### 所以"同名 graph"只是文件名巧合

Chrome 的 `.logseq-pool-Demo` 和 Firefox 的 `.logseq-pool-Demo` —— **两个完全不同的 SAH Pool，里面的 `/db.sqlite` 也是两个不同的文件**，数据互不可见。没有任何后端在协调"全局唯一名"。

---

## 五、Electron 桌面版的差异

Electron 模式下不使用 OPFS，而是通过 Node.js 直接读写文件系统：

| 平台 | Graph 存储位置 |
|---|---|
| macOS | `~/Library/Application Support/Logseq/graphs/<graph-name>/` |
| Windows | `C:\Users\<user>\AppData\Roaming\Logseq\graphs\<graph-name>\` |
| Linux | `~/.config/Logseq/graphs/<graph-name>/` |

路径拼接见 [deps/db/.../sqlite.cljs:27](deps/db/src/logseq/db/common/sqlite.cljs:27) `get-db-full-path`：
```clojure
(node-path/join graphs-dir db-name' "db.sqlite")
```

目录结构同 OPFS pool 内部布局（`db.sqlite` / `searchdb.sqlite` / `client-ops-db.sqlite` / `visual_docs.sqlite`）。

---

## 六、DataScript 和 SQLite 的关系（常见误解）

**DataScript ≠ SQLite**。

- **DataScript** 是**内存里的 Datalog 索引**。所有查询（`d/q`、`d/datoms`、`d/entity`）都走它，速度快。
- **SQLite** 只是 DataScript 的**持久化存储**。DataScript 实现了 `IStorage` 接口 ([db_worker.cljs:162](src/main/frontend/worker/db_worker.cljs:162) `new-sqlite-storage`)，把 (addr, content) 键值对写到 `/db.sqlite` 的 `kvs` 表。
- 启动时 `d/restore-conn storage` 把 SQLite 里的 kvs 行读回来重建 DataScript 索引。

`kvs` 表 schema：
```sql
CREATE TABLE kvs (
  addr     INTEGER PRIMARY KEY,
  content  TEXT,      -- transit 编码的 DataScript 节点
  addresses JSON      -- 子节点 addr 列表
);
```

所以你用 DevTools 打开 SQLite 看到的是**序列化后的 B-tree 节点**，不是可读的 block 数据。

---

## 七、排查清单

### 数据"丢了"？

1. **换浏览器了？** → 检查是否在 **相同 profile + 相同 origin** 访问
2. **换端口了？** → `localhost:3001` 和 `localhost:3002` 的 OPFS 是独立的
3. **清了浏览器数据？** → OPFS 归"Site Data"类，"Clear Browsing Data" 会删
4. **无痕窗口？** → OPFS 在无痕窗口关闭后直接清空

### 怎么查看 OPFS 实际内容？

**Chrome**：DevTools → Application → Storage → Origin Private File System
- 看到 `.logseq-pool-*` 目录就说明 graph 存在
- 但 SAH Pool 内部是不透明的二进制 handle，直接看不到 `/db.sqlite`

**Firefox**：about:storage-inspector（限制更多）

**程序内**：
```javascript
// 在 DB worker console（不是主线程！）
const root = await navigator.storage.getDirectory();
for await (const entry of root.values()) {
  console.log(entry.name);
}
```

### 想"迁移"graph 到另一个浏览器？

目前 **没有内置导出 OPFS graph 的 UI**。可用方案：
1. 导出 db.sqlite 文件 → `<export-db-file` ([db_worker.cljs:118](src/main/frontend/worker/db_worker.cljs:118)) 调用 `pool.exportFile(repo-path)` 拿到二进制
2. 在目标浏览器里 `<import-db` ([db_worker.cljs:126](src/main/frontend/worker/db_worker.cljs:126)) 调用 `pool.importDb(repo-path, data)`
3. UI 层入口暂未接通，需要 REPL 触发

---

## 八、与其他 skill 的关系

| 相关 skill | 覆盖面 |
|---|---|
| `db-sync-collaboration.md` | 说明为什么 **没有** 远端/租户（同步已移除） |
| `excalidraw-whiteboard.md` / `mind-map.md` | 同 pool 下的 `visual_docs.sqlite` sidecar 布局 |
| `docs/db-backend-debug-guide.md` | 构建命令、OPFS 入口的调试方法 |

**不要做**：

- ❌ 不要假设 "graph 名全局唯一" → 名字只对单个 OPFS 有意义
- ❌ 不要在前端假设能读到别人浏览器里的 graph → 跨浏览器就是跨世界
- ❌ 不要手动往 SAH Pool 目录里塞 SQLite 文件 → SAH handle 布局是库内部约定，直接写会破坏 pool
- ❌ 不要把 Electron 和 Web 的存储路径混用 → 完全两套实现
