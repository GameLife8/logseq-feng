# logseq-dev — Logseq 开发辅助 Skill

当用户在本项目（logseq-feng）中进行功能开发、调试或代码审查时，
自动加载本 skill，避免每次重新读取源文件。

## 触发条件

以下任意情况激活本 skill：
- 用户提到「思维导图」「mind map」「mind_map」
- 用户要操作 Logseq DB / block / page / sidebar
- 用户在 `src/main/frontend/extensions/mind_map/` 或 `src/main/frontend/components/mind_map.cljs` 中工作
- 用户问「怎么创建块」「怎么打开侧边栏」等 Logseq 内部 API 问题

## 相关 Skill 文件

- `.claude/skills/excalidraw-whiteboard.md` — Excalidraw/白板 Props、customData、配置存储、lazy 模块加载
- `.claude/skills/logseq-patterns.md` — DataScript 查询、async DB、IPC、PDF 导出、路由、Worker 架构、常见陷阱

---

## 关键文件速查

| 文件 | 作用 |
|------|------|
| `src/main/frontend/extensions/mind_map/core.cljs` | 思维导图画布核心（`mind-map-editor` 组件，纯 UI，不依赖 Logseq DB） |
| `src/main/frontend/components/mind_map.cljs` | 思维导图路由入口，桥接 DB ↔ core（持有 on-open-block / on-open-note-block / on-search-blocks） |
| `src/main/frontend/handler/mind_map.cljs` | 思维导图数据持久化（save/load/create/delete/rename） |
| `src/main/frontend/state.cljs` | 全局状态，侧边栏 API，pub-event! |
| `src/main/frontend/db.cljs` | 主查询 API（entity / get-page / transact! / new-block-id） |
| `src/main/frontend/handler/editor.cljs` | `api-insert-new-block!` 公共块创建 API |
| `docs/logseq-db-api.md` | **完整 DB API 参考手册（必读）** |

---

## 思维导图组件接口（core.cljs Props）

```clojure
{:map-id             "page-uuid-str"    ; localStorage 和 DB key
 :map-title          "标题"
 :on-back            (fn [])            ; 返回画廊
 :on-load-data       (fn [map-id])      ; => JSON-string | nil
 :on-save-data       (fn [map-id json]) ; 保存到 DB
 :on-open-block      (fn [uuid-str])    ; 在右侧边栏打开块
 :on-open-note-block (fn [uuid-str])    ; 备注：空串=新建块，非空=打开已有块
                                        ; 返回 Promise<new-uuid-str | nil>
 :on-search-blocks   (fn [q])           ; 搜索块，返回 Promise<[{:block-id :block-title :page-title}]>
}
```

---

## Node 数据字段（SimpleMindMap node data）

```js
{
  "text":            "节点文字",
  "noteBlockId":     "uuid-str",   // 关联备注 block 的 UUID
  "linkedBlocksJson":"[{\"blockId\":\"uuid\",\"blockTitle\":\"t\",\"pageTitle\":\"p\"}]"
}
```

---

## 常用 Logseq API 速查

```clojure
;; 1. 查找实体
(db/entity [:block/uuid (uuid "uuid-str")])
(db/get-page "page-title-or-uuid")

;; 2. 创建块（在页面末尾）
(p/let [block (editor-handler/api-insert-new-block!
               "" {:page (uuid page-uuid-str)
                   :end? true :edit-block? false
                   :container-id :unknown-container})]
  (str (:block/uuid block)))

;; 3. 在右侧边栏打开
(state/sidebar-add-block! (state/get-current-repo) (:db/id entity) :block)

;; 4. 保存自定义属性到 page（不经 outliner）
(db/transact! repo
              [{:db/id (:db/id page) :my/prop "value" :block/updated-at (.now js/Date)}]
              {:outliner-op :save-block})

;; 5. 搜索块
(search/block-search repo q {:built-in? false :enable-snippet? false})
;; => Promise<[{:block/uuid :block/title :block/page ...}]>
```

---

## 重要约束

1. **`sidebar-add-block!` 必须传 `(:db/id entity)` 整数**，不能传 UUID 字符串。
2. **`api-insert-new-block!` 的 `:page` 参数传 Clojure UUID 对象**（`(uuid str)`），不是字符串。
3. `outliner-op/*` 函数**必须在 `ui-outliner-tx/transact!` 宏内**调用。
4. 移动端 `sidebar-add-block!` 会静默忽略（`sm-breakpoint?` 检查）。
5. 块的 `:block/order` **不可手动修改**，由 outliner 自动管理。
6. `api-insert-new-block!` 返回 **Promise**，必须用 `p/let` 等待。

---

## 提交规范

分支：`claude/analyze-test-db-iffvr-b79hV`

提交信息格式：
```
<模块>: <简短描述>

<详细说明（可选）>

https://claude.ai/code/session_01TmkTwtPcnZYa8zykQ7SwsM
```
