# graph-view — 图谱可视化

## When To Use

涉及图谱可视化（全局图谱、页面图谱、块图谱）、节点/边渲染、力导向布局、图谱过滤器、图谱搜索、Pixi.js / D3 / Graphology 等图形库时加载此文档。

**注意**：此处的"图谱"指的是页面/块关系的可视化网络图，不是 "graph" 数据库/repo 概念。

---

## 架构概览

```
┌─ DataScript (Worker 线程) ──────────────────────┐
│  ldb/get-pages-relation  → 页面间引用关系        │
│  ldb/get-all-tagged-pages → 页面标签关系          │
│  graph-view/build-graph  → 构建 nodes + links    │
│  :thread-api/build-graph → Worker API 入口       │
└────────────────┬─────────────────────────────────┘
                 ↓ (async via Worker postMessage)
┌─ 主线程 UI ────────────────────────────────────┐
│  page.cljs: global-graph / page-graph 组件      │
│  extensions/graph.cljs: graph-2d Rum 组件       │
│  extensions/graph/pixi.cljs: Pixi + D3 渲染引擎│
└─────────────────────────────────────────────────┘
```

---

## 核心文件

| 文件 | 职责 |
|------|------|
| `src/main/frontend/common/graph_view.cljs` | 图谱数据构建（nodes/links），三种模式：global、page、block |
| `src/main/frontend/extensions/graph/pixi.cljs` | Pixi.js + D3-force 渲染引擎，力导向物理模拟 |
| `src/main/frontend/extensions/graph.cljs` | `graph-2d` Rum 组件包装，节点高亮、点击处理 |
| `src/main/frontend/components/page.cljs` | 图谱页面组件（`global-graph`、`page-graph`、`graph-filters`） |
| `src/main/frontend/handler/graph.cljs` | 工具函数：n-hops 计算、metadata 持久化 |
| `src/main/frontend/extensions/graph.css` | 图谱容器样式（`#global-graph`、`#page-graph`） |
| `src/main/frontend/worker/db_worker.cljs` | Worker 端 `:thread-api/build-graph` API |

---

## 三种图谱模式

### 1. 全局图谱（Global Graph）

- **路由**：`/graph`（路由名 `:graph`）
- **入口组件**：`page/global-graph`
- **入口方式**：
  - 左侧栏 "Graph view" 导航项
  - 顶部 header 链接
  - 快捷键 `g g`（`:go/graph-view`）
- **数据构建**：`build-global-graph`
  1. `ldb/get-pages-relation` — 查询所有页面间的块引用关系
  2. `ldb/get-all-tagged-pages` — 查询所有标签关系
  3. 合并 relation + tagged-pages 为 links
  4. 过滤节点（journal、orphan、builtin、excluded、created-at）
  5. 构建 nodes（大小按链接数的立方根缩放）+ links

### 2. 页面图谱（Page Graph）

- **入口组件**：`page/page-graph`
- **显示位置**：右侧边栏 → "Page graph" 面板
- **数据构建**：`build-page-graph`
  1. 查找当前页面的 referenced pages（当前页面的块引用了哪些页面）
  2. 查找 mentioned pages（哪些页面的块引用了当前页面）
  3. 查找 tags（当前页面的标签）
  4. 计算 other-pages 之间的交叉引用链接
  5. 组装成以当前页面为中心的关系网

### 3. 块图谱（Block Graph）

- **数据构建**：`build-block-graph`
  1. 获取块的 `block/refs`（引用了谁）和 `block/_refs`（被谁引用）
  2. 将引用映射到所属页面
  3. 构建块与相关页面的关系网

---

## 渲染引擎

### 技术栈

| 库 | 版本 | 用途 |
|----|------|------|
| `pixi-graph-fork` | 0.2.0 | WebGL 图谱渲染（基于 Pixi.js 6） |
| `graphology` | 0.20.0 | 图数据结构管理（addNode、addEdge） |
| `d3-force` | 3.0.0 | 力导向物理模拟（弹簧、电荷、碰撞） |

### 渲染流程（`pixi.cljs`）

```
render!(state)
  → 清理旧实例 (destroy-instance!)
  → 构建 Graphology Graph (addNode / addEdge)
  → D3 layout! — 创建力模拟
  → 创建 PixiGraph 实例（WebGL canvas）
  → 注册拖拽监听器 (set-up-listeners!)
  → 绑定 simulation "tick" → 每帧更新节点/边位置
```

### D3 力模型参数

| 力 | 作用 | 默认值 |
|----|------|--------|
| `forceLink` | 连线弹簧力（距离约束） | distance = 70 |
| `forceManyBody` | 电荷力（节点互斥/吸引） | strength = -600, range = 600 |
| `forceCollide` | 碰撞避免（节点不重叠） | radius = 26, iterations = 2 |
| `forceX` / `forceY` | 向原点拉回的弱引力 | strength = 0.02 |
| `forceCenter` | 图谱居中 | — |
| `velocityDecay` | 速度衰减（摩擦力） | 0.5 |

---

## 过滤器系统（`graph-filters`）

全局图谱右上角有四个可折叠面板：

### Nodes 节点过滤

| 选项 | atom | 配置键 | 默认值 |
|------|------|--------|--------|
| Enable journals | `*journal?` | `:journal?` | false |
| Orphan pages | `*orphan-pages?` | `:orphan-pages?` | true |
| Built-in pages | `*builtin-pages?` | `:builtin-pages?` | false |
| Excluded pages | `*excluded-pages?` | `:excluded-pages?` | true |
| Created before (slider) | `*created-at-filter` | `:created-at-filter` | nil |
| N hops (shift+click 选中后出现) | `*n-hops` | — | nil |

- "Excluded pages" 过滤器检查页面的 `:logseq.property/exclude-from-graph-view` 属性

### Search 搜索过滤

- 打开命令面板（search mode = `:graph`），搜索页面名
- 搜索结果存储在 `state/:search/graph-filters` 向量中
- 多个搜索条件叠加过滤（regex 匹配节点 label）
- `filter-graph-nodes` 函数负责在前端过滤

### Forces 力参数调节

| 参数 | atom | 默认值 | 范围 |
|------|------|--------|------|
| Link Distance | `*link-dist` | 70 | 10–180 |
| Charge Strength | `*charge-strength` | -600 | -1000–1000 |
| Charge Range | `*charge-range` | 600 | 500–4000 |
| Pause simulation | `pixi/*simulation-paused?` | false | — |

### Export 导出

- 将当前 canvas 导出为 PNG（`canvasToImage`）

---

## 节点样式

### 大小计算

```clojure
(int (* 8 (max 1.0 (js/Math.cbrt n))))
;; n = 该页面的链接数（入+出）
;; 最小 8px，按立方根缩放
```

### 颜色规则

| 条件 | 浅色主题 | 深色主题 |
|------|----------|----------|
| 普通页面 | `#999` | `#93a1a1` |
| 当前页面 | `#045591` | `#ffffff` |
| 标签页面（有 tag 关系） | `green` | `orange` |
| 父命名空间 | 使用 hash 着色 | 使用 hash 着色 |

### 悬停样式

- 节点：accent-color（默认 `#6366F1`）
- 边：`#A5B4FC`
- 邻居节点也高亮（accent-color + 边框）

---

## 交互行为

### 节点点击

- **普通点击** → 跳转到该页面（`redirect-to-page!`）
- **Shift+点击** → 选中节点，添加到 `*focus-nodes`，高亮邻居
  - 选中后出现 "N hops" 滑块，控制显示几跳内的节点
  - `graph-handler/n-hops` 递归搜索 N 跳内的节点集

### 节点拖拽

- `nodeMousedown` → 启动拖拽，重启模拟（alphaTarget 0.3）
- `nodeMousemove` → 实时更新节点位置
- `nodeMouseup` → 结束拖拽，停止模拟加速
- 2 秒后自动归零 alphaTarget（模拟趋于稳定）

### 判断拖拽 vs 点击

- 记录 `mousedown` 位置，`mouseup` 时对比偏移量
- 偏移 ≤ 5px 视为点击，> 5px 视为拖拽

---

## 配置持久化

图谱设置存储在图谱配置文件中：

```clojure
;; 节点过滤设置
:graph/settings {:journal? false
                 :orphan-pages? true
                 :builtin-pages? false
                 :excluded-pages? true
                 :created-at-filter nil}

;; 力参数设置
:graph/forcesettings {:link-dist 70
                      :charge-strength -600
                      :charge-range 600}
```

读取：`state/graph-settings` 和 `state/graph-forcesettings`

写入：`config-handler/set-config!`（持久化到 logseq/config.edn）

图谱 metadata（最后一次查看等）：`handler/graph.cljs` → `settle-metadata-to-local!`（localStorage）

---

## 数据流完整路径

### 全局图谱

```
global-graph (Rum 组件, 响应式)
  → graph-aux (hooks/use-effect)
    → state/<invoke-db-worker :thread-api/build-graph {:type :global ...}
      → Worker: graph-view/build-graph → build-global-graph
        → ldb/get-pages-relation + ldb/get-all-tagged-pages
        → build-nodes + build-links + normalize-page-name
      → 返回 {:nodes [...] :links [...] :all-pages {...}}
    → filter-graph-nodes（前端搜索过滤）
    → global-graph-inner
      → graph/graph-2d (Rum 组件)
        → pixi/render! (did-update)
          → Graphology Graph + D3 simulation + PixiGraph
```

### 页面图谱

```
page-graph (Rum 组件)
  → page-graph-aux (hooks/use-effect)
    → state/<invoke-db-worker :thread-api/build-graph {:type :page :block/uuid uuid}
      → Worker: graph-view/build-graph → build-page-graph
    → page-graph-inner
      → graph/graph-2d
```

---

## 节点过滤逻辑

### 全局图谱过滤（`build-global-graph` 内，Worker 端）

1. `created-at-filter` — 只保留创建时间 ≤ 阈值的页面
2. `(not journal?)` — 移除日记页面
3. `(not excluded-pages?)` — 移除设置了 `exclude-from-graph-view` 的页面
4. `(not builtin-pages?)` — 移除内置页面（来自 `sqlite-create-graph/built-in-pages-names`）
5. `(not orphan-pages?)` — 只保留有链接的页面
6. `remove-uuids-and-files!` — 移除 UUID 节点和资产文件节点

### 搜索过滤（`filter-graph-nodes`，主线程端）

- 对每个 search-filter 生成正则表达式（case-insensitive）
- 节点 label 匹配任意一个 filter 就保留

---

## 页面属性

- `:logseq.property/exclude-from-graph-view` — checkbox 属性，设为 true 则该页面从全局图谱中排除
- 定义在 `deps/db/src/logseq/db/frontend/property.cljs`

---

## 右侧边栏集成

- `right_sidebar.cljs` 中 `:page-graph` 条目
- 用户点击 "Page graph" → 渲染 `page/page-graph` 组件
- 固定宽高 600×600
- 带有 "Show journals" 开关

---

## 快捷键

| 快捷键 | 命令 | 功能 |
|--------|------|------|
| `g g` | `:go/graph-view` | 跳转到全局图谱页面 |

---

## 容器样式要点

- `:graph` 路由在 `container.cljs` 的 `margin-less-pages?` 中，无左右边距（全屏）
- `#global-graph` / `#page-graph`：`min-height: 100%`、`overflow: hidden`、`z-index: 4`
- `.graph` div：高度 500px，在 `#main-content-container` 内为 `calc(100vh - 100px)`

---

## 已知问题和待办

1. **命名空间（Namespace）图谱**：代码中有 `namespaces` 变量但始终为空数组 `[]`，注释 `FIXME: Implement for DB graphs`。
2. **块图谱的 tags**：`build-block-graph` 中注释 `FIXME: get block tags`，当前未获取块标签。
3. **节点颜色硬编码**：注释 `FIXME: Put it into CSS`，当前在代码中硬编码颜色值。
4. **重复链接**：`pixi.cljs` 中注释 `#3331 (@zhaohui0923) seems caused by duplicated links. Why distinct doesn't work?`。

---

## 常见陷阱

1. **`graph-2d` 的 `should-update`** 只对比 `nodes`、`links`、`dark?`、`link-dist`、`charge-strength`、`charge-range` 六个 key，其他 props 变化不触发重渲染。
2. **节点 ID 是字符串化的 `:db/id`**（`(str (:db/id p))`），不是 UUID。点击回跳时需要 `js/parseInt` 再 `db/entity`。
3. **图谱数据构建在 Worker 线程**，通过 `state/<invoke-db-worker` 异步获取，不能同步访问。
4. **`*graph-instance` 是全局单例**，同一时间只有一个图谱实例。切换时必须先 `destroy-instance!`。
5. **搜索模式**：全局图谱打开时，search mode 设为 `:graph`，离开时重置为 nil。不重置会影响块选择操作栏。

---

## 依赖关系图

```
page.cljs (global-graph / page-graph)
  ├─ extensions/graph.cljs (graph-2d, on-click-handler)
  │   └─ extensions/graph/pixi.cljs (render!, layout!, PixiGraph)
  │       ├─ pixi-graph-fork (WebGL 渲染)
  │       ├─ graphology (图数据结构)
  │       └─ d3-force (物理模拟)
  ├─ handler/graph.cljs (n-hops, metadata)
  └─ common/graph_view.cljs (build-graph, Worker 端)
      └─ logseq.db (ldb queries)
```
