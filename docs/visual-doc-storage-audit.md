# Visual-Doc 存储系统审计报告

> 审计日期: 2026-04-04
> 涵盖范围: 思维导图 + 白板的完整存储链路

## 一、架构总览

```
┌─ Layer 1: localStorage (草稿缓存) ────────────┐
│  Key: "{prefix}-{page-uuid}"                   │
│  Value: {version:1, saved-at, data: json-str}  │
│  LRU 淘汰: 每个 prefix 保留最近 5 条             │
│  写入频率: 白板 3s / 思维导图 on-change          │
└──────────────────┬──────────────────────────────┘
                   ↕
┌─ Layer 2: Worker SQLite Sidecar ───────────────┐
│  文件: /visual-doc.sqlite (OpfsSAHPoolDb)       │
│  visual_docs          — blob 主行 (权威数据源)   │
│  mind_map_nodes       — 派生索引 (≥256KB 才构建) │
│  whiteboard_elements  — 派生索引 (≥256KB 才构建) │
│  whiteboard_scene_meta — appState (scroll/zoom)  │
│  visual_doc_indexes   — 索引状态追踪             │
│  Schema version: 4                              │
└──────────────────┬──────────────────────────────┘
                   ↕
┌─ Layer 3: DataScript (主线程) ─────────────────┐
│  Page entity: title, tags, updated-at          │
│  不存储大 payload (保存成功后 retract)           │
└────────────────────────────────────────────────┘
```

**核心原则**: blob snapshot = 权威数据, normalized rows = 派生索引, 索引失败不影响保存。

## 二、关键常量

| 常量 | 值 | 位置 |
|------|---|------|
| `sidecar-path` | `/visual-doc.sqlite` | `worker/visual_doc.cljs` |
| `schema-version` | 4 | `worker/visual_doc.cljs` |
| `normalized-min-content-bytes` | 256 KB | `worker/visual_doc.cljs` |
| `cache-version` | 1 | `handler/visual_doc.cljs` |
| `lru-max-entries` | 5 | `handler/visual_doc.cljs` |
| 索引重建延迟 | 0ms (首次) / 1500ms (重试) | `worker/db_worker.cljs` |
| 白板草稿缓存间隔 | 3000 ms | `excalidraw/core.cljs` |
| 白板持久化间隔 | 9000 ms | `excalidraw/core.cljs` |

## 三、数据流

### 保存路径 (UI → SQLite)

```
Editor on-save-data(page-uuid, json-str)
  → handler/mind_map 或 handler/whiteboard
    → handler/visual-doc/<flush-doc!
      → Worker :thread-api/visual-doc-upsert
        → visual_doc/upsert-doc! → upsert-blob-doc! (写 blob 主行)
        → schedule-visual-doc-index-rebuild! (后台, 非阻塞)
      → 成功后: retract 大 payload, 更新 :block/updated-at
```

### 加载路径 (SQLite → UI)

```
Component did-mount
  → handler/<load-doc
    → Worker :thread-api/visual-doc-get (读 sidecar)
    → localStorage read (读草稿缓存)
    → choose-newer-source (三方比对, 选最新)
  → 传 initial-json + needs-initial-flush? 给编辑器
```

### 删除路径

```
1. visual-doc/<delete-doc! → 删 sidecar + localStorage
2. 删缩略图缓存 (思维导图: mind-map-thumb-{uuid})
3. 删 page manifest
```

## 四、已知问题与风险评估

### CRITICAL — 需关注但当前可接受

#### C1: `<flush-doc!` 保存半成功

**现象**: sidecar 写入成功 → DataScript retract 失败 → 数据不丢失但 DataScript 残留大 payload。

**影响**: 内存膨胀, 违反 manifest-only 原则。

**实际风险**: 低。DataScript 操作很少失败, 且下次保存会再次尝试 retract。

**建议**: 后续可加 retract 重试或启动时清理。

#### C2: 缓存淘汰可能删除正在编辑的文档

**现象**: `evict-lru-caches!` 纯按时间排序, 没有 "正在编辑" 保护。打开第 6 个文档时, 最旧的缓存被删。

**影响**: 丢失未保存的草稿 (仅限 localStorage 层, sidecar 数据不受影响)。

**实际风险**: 低。正常使用不会同时编辑 6 个以上文档。

**建议**: 后续可加 "pinned" 标记保护当前文档。

#### C3: 删除顺序不严谨

**现象**: `<delete-doc!` 先同步清 localStorage, 再异步删 sidecar。如果 sidecar 删除失败, 缓存已丢。

**影响**: 极端情况下删除不完整。

**实际风险**: 极低。用户意图就是删除, 即使 sidecar 残留也不影响功能。

**建议**: 后续可改为先删 sidecar 再清缓存。

### HIGH — 功能性风险

#### H1: `<get-visual-doc-db` 无错误处理

**现象**: OpfsSAHPoolDb 初始化失败时无 try-catch, 错误通过 promise 链传播, 导致静默失败。

**影响**: 整个 visual-doc 功能不可用, 但不会崩溃 (返回 nil)。

**实际风险**: 低。OpfsSAHPoolDb 初始化极少失败。

**建议**: 后续可加 try-catch + 用户提示。

#### H2: `run-in-transaction!` 无嵌套事务保护

**现象**: 如果 `f()` 内部再次调用 `run-in-transaction!`, 内层 `BEGIN` 会失败。

**影响**: 索引重建时如果触发嵌套事务会抛异常。

**实际风险**: 极低。当前代码路径不存在嵌套调用。

**建议**: 后续可改用 SAVEPOINT 机制。

#### H3: 索引重建部分失败导致重试循环

**现象**: 事务内任一节点插入失败 → 回滚 → 索引状态被清除 → 下次 get-doc 触发重建 → 再次失败。

**影响**: 理论上可能无限循环, 但每次只重试一次 (attempt 0 → 1 → 放弃)。

**实际风险**: 低。索引失败不影响 blob 读取, get-doc 有 fallback。

**建议**: 可加失败计数器, 超过阈值停止重试。

### MEDIUM — 边界情况

#### M1: `flatten-mind-map` 深度递归栈溢出

**现象**: 纯递归遍历, JS 栈约支持 ~10000 层深度。

**影响**: 极深嵌套的思维导图 (>100 层单链) 会崩溃。

**实际风险**: 极低。正常思维导图 < 20 层。

**建议**: 后续可改为 BFS 迭代或加深度上限。

#### M2: `persist!` 无退避策略

**现象**: 白板每 9 秒重试保存, 网络断开时持续重试。

**影响**: 浪费资源, 但不丢失数据 (localStorage 仍有草稿)。

**实际风险**: 低。

**建议**: 后续可加指数退避 + 最大重试次数。

#### M3: cache-sidecar 时间竞争

**现象**: `choose-newer-source` 在 worker 查询和 localStorage 读取之间, 用户可能又保存了新数据。

**影响**: 极端情况下选错数据源。

**实际风险**: 极低。加载只在组件挂载时发生, 此时用户未开始编辑。

#### M4: 损坏的 JSON 静默变空对象

**现象**: `parse-json` 失败返回 nil → `hydrate-mind-map` / `hydrate-whiteboard` 用 `{}` 替代 → 节点/元素消失。

**影响**: 损坏数据不报错, 用户看到部分内容丢失。

**实际风险**: 极低。JSON 损坏概率很小, 且 blob 原文仍在 visual_docs.content。

#### M5: `content-size` 在无 TextEncoder 环境计算不准

**现象**: fallback 到 `(count str)`, 对多字节 UTF-8 不准确。

**影响**: 可能错误决定是否构建派生索引 (256KB 阈值判断偏差)。

**实际风险**: 极低。现代浏览器都有 TextEncoder。

## 五、数据规模承载评估

| 场景 | 承载能力 | 潜在瓶颈 |
|------|---------|----------|
| 思维导图 < 500 节点 | ✅ 无压力 | — |
| 思维导图 500 - 5000 节点 | ✅ 可以 | JSON 全量序列化耗时增加 |
| 思维导图 > 5000 节点 | ⚠️ 需实测 | 递归扁平化 + 全量 blob 写入 |
| 白板 < 200 元素 | ✅ 无压力 | — |
| 白板 200 - 2000 元素 | ✅ 可以 | 9s 全量 JSON 序列化 |
| 白板 > 2000 元素 | ⚠️ 需实测 | 全量序列化 + localStorage 写入 |
| 同时打开多文档切换 | ✅ 可以 | LRU 5 条限制足够 |

**结论**: 当前架构可支撑正常使用场景。对于超大文档 (5000+ 节点), 未来可考虑增量保存优化。

## 六、已修复的历史 Bug

以下问题在本轮审计中已修复:

| Bug | 根因 | 修复方式 |
|-----|------|---------|
| 白板/思维导图保存失败 | `.transaction` 在 OpfsSAHPoolDb 不可用 | 改用显式 `BEGIN`/`COMMIT`/`ROLLBACK` |
| SQL bind 值类型错误 | CLJS 集合对象传入 SQLite | 所有 bind 值加 `(str x)` / `(js/Number x)` 防御 |
| 时间戳 32 位截断 | `(int x)` 在 CLJS 做 `x \| 0` | 改用 `(js/Number x)` |
| 派生索引重建崩溃 | `rowMode: "object"` 在 OpfsSAHPoolDb setTimeout 中失败 | 改用 `exec-select` (callback + columnNames) |

## 七、文件索引

| 文件 | 职责 |
|------|------|
| `src/main/frontend/worker/visual_doc.cljs` | SQLite sidecar 操作 (exec-select, upsert, 索引重建) |
| `src/main/frontend/worker/db_worker.cljs` | Thread API handlers, 索引重建调度 |
| `src/main/frontend/handler/visual_doc.cljs` | 三源比对, 缓存层, flush 管道 |
| `src/main/frontend/handler/mind_map.cljs` | 思维导图特定 handler |
| `src/main/frontend/handler/whiteboard.cljs` | 白板特定 handler |
| `src/main/frontend/components/mind_map.cljs` | 思维导图画廊 + 编辑器挂载 |
| `src/main/frontend/extensions/mind_map/core.cljs` | 思维导图画布 UI (零 DB 依赖) |
| `src/main/frontend/extensions/excalidraw/core.cljs` | Excalidraw 包装器 (定时器, 同步状态, 生命周期) |
