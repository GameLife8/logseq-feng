# Logseq DB API 开发参考手册

> 面向本项目二次开发者（包括 AI 辅助编程），汇总所有数据库操作接口、调用方式与注意事项。
> 每次新会话直接阅读本文档，无需重新扫描代码。

---

## 目录

1. [核心架构](#1-核心架构)
2. [Block 实体结构](#2-block-实体结构)
3. [主要 namespace 对照表](#3-主要-namespace-对照表)
4. [frontend.db — 主查询 API](#4-frontenddb--主查询-api)
5. [frontend.state — 全局状态 API](#5-frontendstate--全局状态-api)
6. [frontend.handler.editor — 块编辑 API](#6-frontendhandlereditor--块编辑-api)
7. [frontend.handler.common.page — 页面 API](#7-frontendhandlercommonpage--页面-api)
8. [frontend.modules.outliner.op — outliner 操作](#8-frontendmodulesoutlinerop--outliner-操作)
9. [transact! 调用链路全图](#9-transact-调用链路全图)
10. [右侧边栏 API](#10-右侧边栏-api)
11. [常见操作速查](#11-常见操作速查)
12. [注意事项 & 易错点](#12-注意事项--易错点)

---

## 1 核心架构

```
DataScript (内存图数据库)
    ↑
ldb/transact!  (deps/db/src/logseq/db.cljs)
    ↑
conn/transact!  (frontend/db/conn.cljs) — 直接写入，仅测试/低层使用
    ↑
ui-outliner-tx/transact!  (outliner/ui.cljc) — 宏，所有业务代码入口
    ↑
db/transact!  (frontend/db.cljs) — 路由层（根据 publishing? 切换实现）
    ↑
outliner-op/insert-blocks! / save-block! 等  (outliner/op.cljs)
    ↑
editor-handler/api-insert-new-block!  (handler/editor.cljs) — 公共 API
```

**原则：业务代码只调用 `editor-handler/api-insert-new-block!` 或 `outliner-op/*`（在 `ui-outliner-tx/transact!` 内），不要直接操作 `conn/transact!`。**

---

## 2 Block 实体结构

```clojure
{:db/id             42          ; DataScript 内部 ID（整数）
 :block/uuid        #uuid "..." ; 全局唯一标识，外部操作用这个
 :block/title       "块内容"    ; 文本内容（Markdown）
 :block/page        {:db/id 10} ; 所属页面（ref → page 实体）
 :block/parent      {:db/id 41} ; 父块（ref，页面节点的父是页面本身）
 :block/order       "aaa"       ; 分数索引排序字段（字符串，勿手动修改）
 :block/refs        #{...}      ; 被引用的块/页面集合（cardinality many）
 :block/tags        #{...}      ; 标签集合（cardinality many）
 :block/created-at  1704067200000
 :block/updated-at  1704067200000
 :block/collapsed?  false
 ;; 仅页面实体有：
 :block/name        "page name" ; 小写，用于查找
 :block/journal-day 20240101    ; 日志页 YYYYMMDD
 ;; 反向查找（不存储，DataScript 自动推导）
 :block/_parent     [{:db/id 43} ...] ; 所有直接子块
 :block/_page       [{:db/id 43} ...] ; 页面上所有块}
```

---

## 3 主要 namespace 对照表

| 用途 | Namespace | 别名 |
|------|-----------|------|
| 实体查询、事务入口 | `frontend.db` | `db` |
| DB 连接管理 | `frontend.db.conn` | `conn` |
| 全局状态 | `frontend.state` | `state` |
| 块编辑（公共 API） | `frontend.handler.editor` | `editor-handler` |
| 页面创建/删除 | `frontend.handler.common.page` | `common-page-handler` |
| Outliner 操作构建器 | `frontend.modules.outliner.op` | `outliner-op` |
| Outliner 事务宏 | `frontend.modules.outliner.ui` | `ui-outliner-tx` |
| 底层 DataScript | `datascript.core` | `d` |
| Logseq DB 工具 | `logseq.db` | `ldb` |

---

## 4 frontend.db — 主查询 API

```clojure
;; 按 eid / uuid 向量查实体
(db/entity eid)                      ; eid 可以是 :db/id 整数、[:block/uuid uuid]
(db/entity repo eid)

;; DataScript pull
(db/pull eid)
(db/pull selector eid)               ; selector 如 '[*]' 或 '[:block/title :block/uuid]'
(db/pull-many eids)
(db/pull-many selector eids)

;; 获取页面（按标题、UUID 字符串、UUID 对象均可）
(db/get-page page-name-or-uuid)

;; 获取当前 repo 的 DataScript DB 快照
(db/get-db)                          ; deref'd
(db/get-db repo)
(db/get-db repo false)               ; false = 返回 atom（不 deref）

;; 子块查询
(db/has-children? block-uuid)
(db/get-block-immediate-children repo block-uuid)   ; 直接子块
(db/get-block-and-children repo block-uuid opts)    ; 含所有后代

;; 排序
(db/sort-by-order blocks)            ; 按 :block/order 排序

;; 新 UUID
(db/new-block-id)                    ; => #uuid "..."

;; 事务（路由层，见第9节）
(db/transact! tx-data)
(db/transact! repo tx-data)
(db/transact! repo tx-data tx-meta)
```

### DataScript 直接查询（高级）

```clojure
(d/q '[:find ?uuid
       :where [?b :block/title ?t]
              [?b :block/uuid ?uuid]
              [(clojure.string/includes? ?t "关键词")]]
     (db/get-db))
```

---

## 5 frontend.state — 全局状态 API

```clojure
;; 当前 repo
(state/get-current-repo)             ; => "logseq_local_xxx" 字符串

;; 右侧边栏
(state/sidebar-add-block! repo db-id block-type)
;; block-type: :block :page :contents :shortcut-settings
;; db-id: (:db/id entity) 整数
;; 自动打开侧边栏并滚动到顶

(state/open-right-sidebar!)
(state/hide-right-sidebar!)
(state/sidebar-remove-block! idx)    ; idx: 0-based 或 block-id 字符串
(state/clear-sidebar-blocks!)

;; 编辑状态
(state/set-block-component-editing-mode! true/false)
;; 调用时机：组件 focusin/focusout，防止 Logseq 快捷键拦截

;; 事件发布
(state/pub-event! [:notification/show {:content "msg" :status :success}])
(state/pub-event! [:notification/show {:content "msg" :status :warning}])

;; UI 状态订阅（在 rum/reactive 组件里）
(state/sub :ui/theme)                ; "dark" | "light"
(state/sub :ui/sidebar-open?)
```

---

## 6 frontend.handler.editor — 块编辑 API

### `api-insert-new-block!` （最常用）

```clojure
(editor-handler/api-insert-new-block!
  content        ; 字符串，块的初始文本内容（可为 ""）
  {:page          page-uuid-or-title  ; 插入到页面（提供 page 或 block-uuid 二选一）
   :block-uuid    block-uuid          ; 插入到某块的相对位置
   :sibling?      false               ; true = 插为兄弟；false = 插为子块（默认 false）
   :before?       false               ; 在目标块之前插入
   :start?        false               ; 插到目标块的第一个子块位置
   :end?          true                ; 插到目标块的最后子块位置（推荐用这个在页面末尾追加）
   :edit-block?   false               ; 是否自动聚焦编辑（默认 true，若不想打断 UI 传 false）
   :custom-uuid   some-uuid           ; 自定义 UUID（可选）
   :container-id  :unknown-container  ; 非编辑器上下文时传此值
   :properties    {}                  ; 块属性（可选）
   :other-attrs   {}})                ; 额外 DataScript 属性（可选）
;; 返回 Promise，resolve 值为新建 block 实体（含 :db/id :block/uuid）
```

**常用调用示例：**

```clojure
;; 在页面末尾追加一个空块（如备注块）
(p/let [result (editor-handler/api-insert-new-block!
                ""
                {:page         (uuid page-uuid-str)
                 :end?         true
                 :edit-block?  false
                 :container-id :unknown-container})]
  (when result
    (let [new-uuid (str (:block/uuid result))]
      (state/sidebar-add-block! repo (:db/id result) :block)
      new-uuid)))
```

### `open-link-in-sidebar!`

```clojure
(editor-handler/open-link-in-sidebar!)
;; 把光标所在的 [[页面]] 或 ((块)) 引用在侧边栏打开
```

---

## 7 frontend.handler.common.page — 页面 API

```clojure
;; 创建页面（异步，返回 Promise）
(common-page-handler/<create! title)
(common-page-handler/<create! title {:redirect?     false   ; 不跳转（默认 true）
                                      :today-journal? false
                                      :class?         false})
;; resolve 值为新建 page 实体

;; 删除页面
(common-page-handler/<delete! page-uuid callback-fn)
```

---

## 8 frontend.modules.outliner.op — outliner 操作

**必须在 `(ui-outliner-tx/transact! opts body)` 宏内调用。**

```clojure
(ui-outliner-tx/transact!
  {:outliner-op :insert-blocks}      ; tx-meta，用于历史记录
  (outliner-op/insert-blocks! blocks target-block {:sibling? false :keep-uuid? true}))

;; 常用操作
(outliner-op/save-block! block)
(outliner-op/delete-blocks! blocks opts)
(outliner-op/move-blocks! blocks target opts)
(outliner-op/indent-outdent-blocks! blocks indent? opts)

;; 属性操作
(outliner-op/upsert-property! block-id prop-id prop-value {:tx-meta ...})
(outliner-op/set-block-property! block-id prop-uuid value)
(outliner-op/remove-block-property! block-id prop-id)
```

---

## 9 transact! 调用链路全图

```
你的代码
 │
 ├─ 简单场景：api-insert-new-block!
 │    └─ 内部自动处理，返回 Promise
 │
 └─ 复杂/低层场景：
      ui-outliner-tx/transact! [opts]    ← 宏，创建 op 收集上下文
        ├─ outliner-op/insert-blocks!    ← 把操作推入 *outliner-ops*
        ├─ outliner-op/save-block!
        └─ ...
          └─ apply-outliner-ops          ← 处理收集到的 ops
               └─ db worker / conn/transact!
                    └─ ldb/transact!
                         └─ DataScript d/transact!
```

**直接调用 `db/transact!` 的场景（只存储自定义属性，不经过 outliner）：**

```clojure
(db/transact! repo
              [{:db/id             (:db/id page)
                :block/mind-map-data json-str
                :block/updated-at   (.now js/Date)}]
              {:outliner-op :save-block})
```

---

## 10 右侧边栏 API

```clojure
;; 在侧边栏打开一个块
(let [repo  (state/get-current-repo)
      block (db/entity [:block/uuid (uuid uuid-str)])]
  (state/sidebar-add-block!
    repo
    (:db/id block)
    (if (:block/page block) :block :page)))
;; 注意：必须用 :db/id（整数），不能用 UUID

;; 在侧边栏打开一个页面
(state/sidebar-add-block! repo (:db/id page-entity) :page)

;; 关闭侧边栏中某一项（按索引）
(state/sidebar-remove-block! 0)

;; 清空侧边栏
(state/clear-sidebar-blocks!)
```

---

## 11 常见操作速查

### 查找块/页面

```clojure
;; 通过 UUID 字符串找实体
(db/entity [:block/uuid (uuid "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")])

;; 通过页面标题找
(db/get-page "我的页面")

;; DataScript 原生查询
(d/q '[:find (pull ?b [:db/id :block/uuid :block/title])
       :where [?b :block/title "目标内容"]]
     (db/get-db))
```

### 创建块并在侧边栏打开

```clojure
(p/let [block (editor-handler/api-insert-new-block!
               "初始内容"
               {:page (uuid page-uuid-str) :end? true :edit-block? false
                :container-id :unknown-container})]
  (when block
    (state/sidebar-add-block! (state/get-current-repo) (:db/id block) :block)))
```

### 自定义属性存储（无 outliner）

```clojure
;; 在 page/block 上挂任意属性（ad-hoc，无需 schema 注册）
(db/transact! (state/get-current-repo)
              [{:db/id            (:db/id entity)
                :my/custom-data   "json-string-or-value"
                :block/updated-at (.now js/Date)}]
              {:outliner-op :save-block})
```

### 监听 DB 变化（组件内）

```clojure
(rum/defcs my-component
  < rum/reactive
  [state]
  (let [blocks (state/sub :some/key)]   ; rum/reactive 自动刷新
    ...))
```

---

## 12 注意事项 & 易错点

| 场景 | 错误做法 | 正确做法 |
|------|---------|---------|
| outliner 操作上下文 | 在 `ui-outliner-tx` 外调用 `outliner-op/insert-blocks!` | 必须包裹在 `ui-outliner-tx/transact!` 宏内 |
| sidebar-add-block! 参数 | 传 UUID 字符串 | 必须传 `(:db/id entity)` 整数 |
| DB ID | 缓存 `(:db/id entity)` 跨事务使用 | 每次重新从 `db/entity` 读取最新 `:db/id` |
| 新块 UUID | 手写 UUID | 用 `(db/new-block-id)` 生成 |
| page 参数 | 传标题字符串 | 传 Clojure UUID 对象（`(uuid str)`） |
| edit-block? | 不传（默认 true） | 非编辑器上下文传 `:edit-block? false` |
| 移动端 | 直接调 sidebar-add-block! | 先检查 `(util/sm-breakpoint?)` —— 移动端该函数会忽略请求 |
| publishing 模式 | 直接 outliner 写入 | publishing? 下只允许特定操作 |
| block/order | 手动设置排序字段 | 永远不要手动修改，由 outliner 管理 |
| 异步 api-insert-new-block! | 直接用返回值 | 必须用 `p/let` 等 promesa 原语等待 Promise |
