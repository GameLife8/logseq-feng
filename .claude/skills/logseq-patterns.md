# logseq-patterns — Logseq 通用开发模式速查

加载条件：涉及 PDF 导出、DataScript 查询、async DB、IPC、路由、Worker 时加载。

---

## 文件速查（常用）

| 文件 | 作用 |
|------|------|
| `src/main/frontend/db.cljs` | 主查询 API（entity / get-page / transact! / get-db） |
| `src/main/frontend/db/async.cljs` | `<pull` / `<q`（走 worker DB，避免主线程副本延迟） |
| `src/main/frontend/state.cljs` | 全局状态、侧边栏、pub-event!、get-current-repo |
| `src/main/frontend/handler/common/page.cljs` | `<create!`（创建页面） |
| `src/main/frontend/handler/route.cljs` | `redirect!` / `navigate-to-page!` |
| `src/main/frontend/components/export.cljs` | PDF 导出（DOM 克隆 + 打印） |
| `src/main/electron/ipc.cljs` | Electron IPC 调用封装 |
| `resources/js/preload.js` | Electron contextBridge 定义（**不要**直接修改生产版本） |

---

## DataScript 查询模式

### 同步查询（主线程，可能有副本延迟）

```clojure
;; 查找实体
(db/entity [:block/uuid (uuid "uuid-str")])
(db/get-page "page-title-or-name")  ; => entity map | nil
(db/get-db)                          ; => DataScript conn（用于 d/q）

;; 直接 Datalog 查询
(d/q '[:find (pull ?b [:db/id :block/title :block/uuid])
       :where [?b :some/attr _]]
     (db/get-db))

;; [:find [?e ...]] 返回直接值向量 → 用 first（不是 ffirst）
(first (d/q '[:find [?e ...]
              :where [?e :block/title "foo"]]
            (db/get-db)))
```

### 异步查询（走 worker，推荐用于配置/page 属性）

```clojure
;; db-async/<pull — 拉取单实体（比主线程副本更及时）
(p/let [result (db-async/<pull repo
                               '[:db/id :block/name :block/title :my/attr]
                               [:block/name "page-title"])]
  ;; result 可能是 nil（page 不存在）
  (:my/attr result))

;; db-async/<q — 任意 Datalog 查询
(p/let [results (db-async/<q repo
                              '[:find (pull ?b [:db/id :block/title])
                                :where [?b :block/tags ?t]
                                       [?t :db/ident :logseq.class/Tag]]
                              {:transact-db? false})]
  (map first results))
```

### 写入 DB（不走 Outliner，直接 transact 到 page 实体）

```clojure
(db/transact! repo
              [{:db/id            (:db/id page-entity)
                :my/custom-attr   "value"
                :block/updated-at (.now js/Date)}]
              {:outliner-op :save-block})
```

---

## 页面创建模式

```clojure
;; 普通页面
(p/let [page (common-page-handler/<create! "页面标题"
                                           {:redirect? false})]
  (:db/id page))

;; Class 实体（可用于 :block/tags）
(common-page-handler/<create! "MyClass" {:redirect? false :class? true})
```

---

## 路由跳转

```clojure
(route-handler/redirect! {:to :whiteboard :path-params {:name page-uuid-str}})
(route-handler/redirect! {:to :page       :path-params {:name page-title}})

;; 返回上一页
(js/window.history.back)
```

---

## IPC（Electron 专用）

```clojure
;; 在 renderer 进程调用 main 进程 handler
(p/let [result (ipc/invoke "channel-name" arg1 arg2)]
  result)

;; 底层 window.apis 直接调用
(js/window.apis.invoke "channel-name" (clj->js [arg1 arg2]))
(js/window.apis.doAction (clj->js [:action-key arg]))
```

**preload.js 位置**：
- 源文件：`resources/js/preload.js`（开发时修改这里）
- 构建产物：`static/js/preload.js`（由 gulp syncResourceFile 任务复制）
- 修改 preload 后必须**重启 Electron**（不支持热更新）

---

## PDF 导出模式（export.cljs）

核心思路：克隆 DOM → 修改克隆 → 新窗口打印。

```clojure
;; 1. 内联所有 CSS（CSSOM，无网络请求）
(defn collect-inline-css []
  (->> (array-seq js/document.styleSheets)
       (map (fn [^js sheet]
              (try (->> (array-seq (.-cssRules sheet))
                        (map #(.-cssText %))
                        (clojure.string/join "\n"))
                   (catch :default _ ""))))
       (clojure.string/join "\n")))

;; 2. 克隆主内容区
(let [main-el    (.getElementById js/document "main-content-container")
      main-clone (.cloneNode main-el true)]

  ;; 3. 移除不需要打印的元素
  (doseq [sel [".references-blocks-wrap" ".CodeMirror-gutters" ".cm-gutters"]]
    (doseq [^js el (array-seq (.querySelectorAll main-clone sel))]
      (some-> (.-parentNode el) (.removeChild el))))

  ;; 4. 修复 CM5 代码块截断（根本原因：clip-path:inset(0px)）
  (doseq [^js el (array-seq (.querySelectorAll main-clone ".CodeMirror"))]
    (set! (.. el -style -clipPath) "none")
    (set! (.. el -style -overflow) "visible")
    (set! (.. el -style -height) "auto"))

  ;; 5. 修复行号列移除后的左边距
  (doseq [^js el (array-seq (.querySelectorAll main-clone ".CodeMirror-sizer"))]
    (set! (.. el -style -marginLeft) "0")
    (set! (.. el -style -minWidth) "0")))

;; 6. 打开新窗口，写入 HTML，调用 print()
```

---

## Worker DB 架构要点

- DataScript 运行在独立 Web Worker 线程（`src/main/frontend/worker/db_worker.cljs`）
- 主线程持有**懒副本**（`frontend.db`），通过 postMessage 同步
- 副本可能有延迟 → 对配置页等重要数据用 `db-async/<pull` 走 worker 直接查询
- 响应式查询缓存：`src/main/frontend/db/react.cljs`（`get-affected-queries-keys` 精确失效）
- 事务后钩子：`src/main/frontend/worker/pipeline.cljs`（rebuild refs、更新 FTS 等）

---

## 全局状态常用 API

```clojure
(state/get-current-repo)             ; => "graph-path" string
(state/sub :ui/theme)                ; 响应式读取 "light"|"dark"
(state/sidebar-add-block! repo db-id :block)  ; 右侧边栏打开块（必须传 :db/id 整数）
(state/pub-event! [:some/event args]) ; 发布事件
```

---

## 搜索块

```clojure
;; 全文搜索（走 SQLite FTS5）
(p/let [results (search/block-search repo q {:built-in? false :enable-snippet? false})]
  ;; results: [{:block/uuid :block/title :block/page ...}]
  results)
```

---

## 路由定义（routes.cljs）

新增路由示例：
```clojure
{:name :my-feature
 :path "/my-feature/:id"
 :component #'components.my-feature/page}
```

路由名在 `container.cljs` 的 `margin-less-pages?` 中排除侧边距（全屏页面用）：
```clojure
(def ^:private margin-less-pages?
  #{:whiteboard :mind-map :my-feature})
```

---

## 常见陷阱

1. **`ffirst` vs `first`**：`[:find [?e ...]]` 已展开向量，用 `first`；`[:find ?e ...]` 返回向量的向量，用 `ffirst`。
2. **`sidebar-add-block!`** 必须传 `(:db/id entity)` 整数，不是 UUID 字符串。
3. **`api-insert-new-block!`** 的 `:page` 参数传 Clojure UUID 对象 `(uuid "str")`，不是字符串，返回 Promise。
4. **页面名不能含 `"/"`**（DB 图谱会做 namespace 分割），用连字符代替。
5. **outliner 操作必须在 `ui-outliner-tx/transact!` 宏内**，直接 `d/transact!` 不走 Outliner 逻辑。
6. **preload.js 修改后必须重启 Electron**，shadow-cljs 热更新不覆盖 preload。
7. **contextBridge 的方法是不可枚举的**，`Object.keys(window.apis)` 看不到任何方法，`window.apis.invoke` 可以直接调用但 ClojureScript 的 `(.-invoke apis)` 可能返回 `undefined`——使用 `(js/window.apis.invoke ...)` 字面量调用。
