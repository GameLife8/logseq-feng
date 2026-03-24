# logseq-feng 开发规范（Claude Code 自动加载）

## 分支规则

**永远在指定 feature 分支上开发，不要推到 main/master。**
当前活跃分支：`claude/analyze-test-db-iffvr-b79hV`

## 必读参考文档

- `docs/logseq-db-api.md` — Logseq DB / Block / Sidebar 完整 API 手册
- `.claude/skills/logseq-dev.md` — 思维导图模块接口速查 + 常用 API

**每次涉及 DB / block / sidebar / 思维导图 操作，先读上述文档，不要靠猜测。**

## 项目结构（思维导图相关）

```
src/main/frontend/
  extensions/mind_map/core.cljs   — 画布 UI（纯组件，不直接访问 Logseq DB）
  components/mind_map.cljs        — 路由入口 + DB 桥接层
  handler/mind_map.cljs           — 数据持久化（save/load/create/delete）
  db.cljs                         — 主查询 API
  state.cljs                      — 全局状态 + 侧边栏 API
  handler/editor.cljs             — api-insert-new-block!（创建块的公共 API）
```

## 架构原则

- `core.cljs` 通过 Props 回调与外部通信（`on-open-block` / `on-open-note-block` / `on-search-blocks`）
- 所有 DB 操作在 `mind_map.cljs` 中通过回调实现，`core.cljs` 零 DB 依赖
- 存储自定义属性用 `db/transact!`（直接写 page 实体属性）
- 创建块用 `editor-handler/api-insert-new-block!`（返回 Promise）
- 侧边栏打开用 `state/sidebar-add-block! repo db-id :block`（注意必须传 `:db/id` 整数）

## 提交 / 推送

```bash
git push -u origin claude/analyze-test-db-iffvr-b79hV
```

提交信息末尾附会话链接：
`https://claude.ai/code/session_01TmkTwtPcnZYa8zykQ7SwsM`
