# excalidraw-whiteboard — Excalidraw / 白板开发速查

## Recent Save Semantics

- Whiteboard data now loads worker-first before the editor mounts.
- Draft cache and graph persistence are tracked separately:
  `localStorage` is the fast draft layer and the graph DB is the durable layer.
- The back action must wait for the DB flush promise to resolve successfully
  before navigating away.
- Component unmount should only keep the latest local draft cache. It must not
  perform another DB write, otherwise a page being deleted can be written back.
- The toolbar status should reflect serialized scene equality instead of every
  raw `onChange` callback, so selection-only changes should not keep the badge
  stuck in a pending state.
- Whiteboard gallery data should exclude built-in/class entities. Only real
  whiteboard pages should be shown or offered for deletion.

加载条件：操作白板、Excalidraw、excalidraw-config 设置时加载。

---

## 关键文件

| 文件 | 作用 |
|------|------|
| `src/main/frontend/components/whiteboard.cljs` | 白板页面 UI、linked-blocks 面板、tags 工具栏（主 bundle） |
| `src/main/frontend/extensions/excalidraw/core.cljs` | Excalidraw React 包装器（**lazy bundle**，需 shadow.lazy 加载） |
| `src/main/frontend/extensions/excalidraw/api.cljs` | 元素构建器 + customData CRUD（主 bundle） |
| `src/main/frontend/handler/excalidraw_config.cljs` | 读写用户设置（embed-whitelist、自定义字体路径等） |
| `src/main/frontend/handler/whiteboard.cljs` | 白板页面创建/删除/重命名、tags 操作 |

---

## excalidraw-editor Props（core.cljs）

```clojure
{:page-uuid               "uuid-str"     ; 白板 page UUID
 :page-title              "标题"
 :on-back                 (fn [])        ; 保存后返回列表
 :initial-json            "{\"elements\":[],...}" ; worker-first 预加载结果
 :on-save-data            (fn [uuid json]) ; 写 DB
 :on-api-ready            (fn [api])     ; 拿到 ExcalidrawImperativeAPI
 :on-show-linked-blocks   (fn [el-id])   ; 打开元素关联块面板
 :on-selection-change     (fn [el-id])   ; 选中单元素时回调（nil = 没选）
 :render-tags             (fn [])        ; 渲染 Rum tags-bar 组件
 :on-rename               (fn [title])  ; 重命名白板
 :validate-embeddable     false|true|(fn [url]→bool) ; iframe 白名单
 :custom-fonts            {:virgil "" :helvetica "" :cascadia ""}} ; 字体路径
```

**重要**：Excalidraw 是 `React.memo` 函数组件，不能用 `:ref`，必须用 `:excalidrawAPI` prop 拿 API。

---

## 数据持久化策略

```
localStorage (key: "whiteboard-data-{uuid}")  ←  {:version 1 :saved-at ms :data json-string}
              ↓  on-back / will-unmount 时强制写
Logseq DB  (:block/whiteboard-canvas JSON 字符串)  ←  via on-save-data callback
```

加载优先级：`worker-first DB pull` 与 `localStorage wrapper` 比较时间戳，父组件拿到最终结果后再挂载 Excalidraw。

保存语义：
- `localStorage` 草稿缓存按 3s 节奏写入
- DB 落库按更慢的节奏单独处理；UI 需要区分 `Draft cached` 和 `Graph saved`

---

## 元素 customData 结构

```js
{
  // 新格式（当前）
  linkedBlockIds: ["uuid1", "uuid2"],   // 引用已有 Logseq 块
  noteBlockIds:   ["uuid3"],            // 在白板 page 下创建的备注块

  // 旧格式（向后兼容）
  type:     "logseq-block",
  blockId:  "uuid-str",
  blockTitle: "...",
  pageTitle:  "..."
}
```

### api.cljs 主要函数

```clojure
(ex-api/get-linked-block-ids el)           ; => ["uuid" ...]（兼容新旧格式）
(ex-api/get-note-block-ids   el)           ; => ["uuid" ...]
(ex-api/add-linked-block!    api elem-id uuid-str)   ; mutate scene
(ex-api/remove-linked-block! api elem-id uuid-str)
(ex-api/add-note-block!      api elem-id uuid-str)
(ex-api/remove-note-block!   api elem-id uuid-str)
(ex-api/get-element-by-id    api elem-id)  ; => JS element | nil
(ex-api/get-selected-element-id api)       ; => id-str | nil
```

---

## Excalidraw 配置（excalidraw-config 页面）

```
存储位置：  page title = "excalidraw-config"
           tag        = #ConfigPage（Class 实体，:class? true 创建）
           属性       = :block/excalidraw-config  (JSON 字符串)

注意：页面名不能含 "/"，否则 DB 图谱的 namespace 解析会出错。
```

### 配置 map 结构

```clojure
{:embed-whitelist     ""      ; 换行分隔的域名列表，"*" = 允许所有
 :font-path-virgil    ""      ; TTF/OTF/WOFF2 绝对路径 或 file:// URL
 :font-path-helvetica ""
 :font-path-cascadia  ""}
```

### 读写 API

```clojure
;; 同步读（仅在主线程 DataScript 副本已同步后有效）
(ex-cfg/get-config)  ; => config-map（不存在时返回 default-config）

;; 异步读（走 worker DB，推荐）
(p/let [cfg (ex-cfg/<get-config)]
  (prn cfg))

;; 写（自动创建页面和 Class tag）
(ex-cfg/save-config! {:embed-whitelist "youtube.com\nexample.com"
                       :font-path-virgil "/usr/share/fonts/MyFont.ttf"})

;; validateEmbeddable prop 构建
(ex-cfg/make-validate-embeddable whitelist-str)
; => false | true | (fn [url] → bool)
```

---

## Class Tag 创建模式

在 DB 图谱中，`:block/tags` 必须指向有效的 Class 实体（有 `:logseq.class/Tag` 标记）。

```clojure
;; 查找 Class 实体
(first (d/q '[:find [?e ...]
              :in $ ?t
              :where [?e :block/title ?t]
                     [?e :block/tags ?tag]
                     [?tag :db/ident :logseq.class/Tag]]
            database "MyClassName"))

;; 若不存在则创建（:class? true 生成 :db/ident 在 user.class namespace）
(common-page-handler/<create! "MyClassName" {:redirect? false :class? true})

;; 打标签到页面
(db/transact! repo
              [{:db/id (:db/id page) :block/tags #{(:db/id tag-entity)}}]
              {:outliner-op :save-block})
```

**注意**：`[:find [?e ...]]` 返回直接值向量，用 `first`，不能用 `ffirst`。

---

## lazy 模块加载模式

```clojure
;; 1. 定义 loadable（模块级别，只定义一次）
(def ^:private lazy-excalidraw
  (lazy/loadable frontend.extensions.excalidraw.core/editor))

;; 2. 加载后执行
(defonce *loaded? (atom false))
(defn ensure-loaded! [on-done]
  (if @*loaded?
    (on-done)
    (lazy/load lazy-excalidraw (fn [] (reset! *loaded? true) (on-done)))))

;; 3. 渲染时
(when @*loaded?
  (ui/lazy-loaded lazy-excalidraw props))
```

---

## 白板 page 标识

白板 page 通过 `:block/whiteboard-canvas` 属性识别：
```clojure
(d/q '[:find (pull ?b [...])
       :where [?b :block/whiteboard-canvas _]] database)
```

思维导图 page 通过 `:block/mind-map-data` 属性识别（同理）。
