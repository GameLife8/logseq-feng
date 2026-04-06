# 日程模块开发规范（Agenda / Schedule）

## 当前有效规则（2026-04 更新）

如果本文件后面的旧说明与本节冲突，以本节为准。

### 当前真正生效的入口

- 共享日程事件投影：`src/main/frontend/components/agenda.cljs`
  - `task->display-events`
  - `group-display-events-by-day`
  - `task-display-date-info`
- 看板当前入口：`kanban-view-projected`
  - `kanban-view-legacy` 仅保留作旧逻辑参考，不是当前页面入口
- 新建任务当前入口：`new-task-dialog-v2`
  - `new-task-btn` 已切到 `new-task-dialog-v2`
  - 旧的 `new-task-dialog` 不是当前 toolbar 使用的弹窗
- 日期任务默认状态补齐：`src/main/frontend/components/agenda_data.cljs`
  - `task-status-ident` 会把只有 `Scheduled/Deadline` 的任务按 `Todo` 处理

### 当前日历/周视图/右侧日看板规则

- `Scheduled` + `Deadline` 同一天：只显示 1 条 `Deadline`
- `Scheduled` + `Deadline` 不同天：显示 2 条
- 只有 `Scheduled`：显示 1 条 `Scheduled`
- 只有 `Deadline`：显示 1 条 `Deadline`
- 两者都没有：回退到 `created-at`
- 月视图、周视图、右侧日看板都走同一份 `display events` 数据
- 卡片底部时间跟随当前显示项，不再统一走旧的 `task-date-info`

### 当前看板规则

列顺序：

1. `积压中`
2. `计划中`
3. `截止日`
4. `逾期`
5. `已完成/已取消`

投影规则：

- `Done` / `Canceled` 只进 `已完成/已取消`
- `Deadline` 已过期：只进 `逾期`
- 手动把真实状态改成 `Backlog`：进 `积压中`
  - 如果还有未过期 `Deadline`，仍会同时进 `截止日`
- 只有 `Deadline`，或 `Scheduled + Deadline` 同一天：只进 `截止日`
- `Scheduled + Deadline` 不同天且 `Deadline` 未过期：同时进计划侧和截止侧
- 没有 `Deadline` 的开放任务：
  - 计划时间 = `Scheduled`，否则 `created-at`
  - 超过 7 天进 `积压中`
  - 否则进 `计划中`

### 当前新建任务规则

- toolbar 的“新建”只创建统一的 `Todo`
- 不再让用户在弹窗里选 `待办 / 计划 / 截止`
- 当前表单字段：
  - 标题
  - `Priority`
  - `Scheduled`
  - `Deadline`
  - 项目标签（可多选）
- `Scheduled` 必填
- `Deadline` 可选
- 创建时：
  - journal page 以 `Scheduled` 日期为准
  - 始终写入 `Status=Todo`
  - 再按表单写入 `Priority / Scheduled / Deadline / 项目标签`

### 当前月视图布局注意点

- 月格子里的任务标题必须保持单行省略
- 关键样式在月视图 cell 和事件标题上：
  - `:minWidth "0"`
  - `:overflow "hidden"`
  - `:textOverflow "ellipsis"`
  - `:whiteSpace "nowrap"`

### 修改建议

- 优先修改当前入口，不要默认去改遗留组件
- 如果改动的是日期展示语义，优先检查：
  - `task->display-events`
  - `group-display-events-by-day`
  - `task-display-date-info`
  - `task->kanban-items`
  - `new-task-dialog-v2`
  - `<create-task!`

## 关键文件

| 文件 | 职责 |
|------|------|
| `src/main/frontend/components/agenda.cljs` | 日程主视图（月/周/看板）、任务卡片、分类逻辑 |
| `src/main/frontend/commands.cljs` | 斜杠命令处理（/Scheduled /Deadline 等）|

---

## 数据模型

### 任务识别条件（DB 查询）
任务 = 满足以下任一条件的 block：
- `:logseq.property/status` 有值
- `:logseq.property/scheduled` 有值
- `:logseq.property/deadline` 有值

### 关键属性
```clojure
:logseq.property/status       ;; 枚举实体，:db/ident 为状态键
:logseq.property/scheduled    ;; 日期值（number ms 或 {:block/journal-day YYYYMMDD}）
:logseq.property/deadline     ;; 同上
:block/created-at             ;; 创建时间戳 ms
:block/page                   ;; 所在页面（:block/journal-day 表示日记页）
```

### 状态枚举 idents
```clojure
:logseq.property/status.todo
:logseq.property/status.doing
:logseq.property/status.in-review
:logseq.property/status.done
:logseq.property/status.canceled
;; nil → 无状态（"已取消"列）
```

---

## 日期处理函数

```clojure
;; scheduled/deadline 属性值 → 毫秒
(defn- prop->ms [v]
  (cond
    (number? v) v
    (map? v) (journal-day->ms (:block/journal-day v))
    :else nil))

;; 任务日期信息（优先级：deadline > scheduled > journal > created）
;; 有 deadline → source=:deadline → 显示 ⏰
;; 仅 scheduled → source=:scheduled → 显示 📅
(task-date-info task) ;; → {:ms 毫秒 :source :deadline|:scheduled|:journal|:created}
```

**重要**：`deadline` 优先于 `scheduled`。有 deadline 时图标显示 ⏰，排序和看板归类也以 deadline 为准。

---

## 图标规则

```clojure
(def date-source-label
  {:scheduled "📅" :deadline "⏰" :journal "📓" :created "🕐"})
```

- 有 `deadline` 属性 → 一律显示 ⏰（无论是否同时有 scheduled）
- 仅有 `scheduled` → 显示 📅

---

## 看板视图（kanban-view）

### 列定义（5列，无"今天"列）

| 列名 | 颜色 | 包含内容 |
|------|------|---------|
| 计划中 | #6366f1 | 有 scheduled（无 deadline）的活跃任务 |
| 截止日 | #ef4444 | 有 deadline 且未过期的活跃任务 |
| 逾期   | #dc2626 | deadline 已过期 或 无日期的活跃任务 |
| 已完成 | #10b981 | done + canceled 状态的任务 |
| 已取消 | #94a3b8 | **nil status（无状态）的任务** |

> **关键**：无状态（nil status）的任务不进入活跃列，专门放到"已取消"列。
> 勾选"已取消"设置的是 `:logseq.property/status.canceled`，与"无状态"（nil）是不同的值。

### 分类函数 classify-kanban
```clojure
(defn- classify-kanban [task today-end]
  (let [s-ms (prop->ms (:logseq.property/scheduled task))
        d-ms (prop->ms (:logseq.property/deadline task))]
    (cond
      (and d-ms (> d-ms today-end)) :deadline    ;; deadline 未过期
      d-ms                          :overdue      ;; deadline 已过期
      s-ms                          :scheduled    ;; 仅 scheduled
      :else                         :no-date)))   ;; 无日期 → 逾期兜底
```

**Deadline 优先原则**：任务同时有 scheduled 和 deadline 时，以 deadline 归类（进"截止日"），不进"计划中"。

---

## 斜杠命令 /Scheduled /Deadline

文件：`commands.cljs`

```clojure
;; /Scheduled 或 /Deadline 时，若块无状态则自动设为 Todo
(defn- ensure-todo-status! []
  (when-let [block (state/get-edit-block)]
    (when (nil? (get-in block [:logseq.property/status :db/ident]))
      (db-property-handler/batch-set-property-closed-value!
       [(:block/uuid block)] :logseq.property/status "Todo"))))
```

**行为**：使用 `/Scheduled` 或 `/Deadline` 插入日期时，若块当前无状态，自动补充 Todo 状态。

---

## 常见修改场景

### 1. 新增看板列
在 `kanban-view` 中修改 `groups` 分拣逻辑并添加 `(kanban-column ...)` 调用。

### 2. 修改状态枚举
使用 `db-property-handler/batch-set-property-closed-value!`：
```clojure
(db-property-handler/batch-set-property-closed-value!
 [block-uuid] :logseq.property/status "Todo") ;; "Todo"/"Doing"/"Done"/"Canceled" 等
```

### 3. 添加新的日期排序
`sort-by` + `prop->ms` 组合：
```clojure
(sort-by #(prop->ms (:logseq.property/deadline %)) tasks)
```

### 4. 已取消 vs 无状态
- **已取消**（Canceled）= `:logseq.property/status.canceled`，任务有明确的取消状态
- **无状态**（nil）= `(nil? (task-status-ident task))`，任务没有设置任何状态
- 两者都在"已取消"列显示，但语义不同

---

## 注意事项

1. `task-active?` 函数只过滤 done/canceled 状态，nil-status 任务也返回 true。
   若要排除 nil-status，需额外 `(filter #(some? (task-status-ident %)) tasks)`。

2. 月视图/周视图使用 `task-date-info` 中的 ms 进行分组，deadline 优先意味着有 deadline 的任务会按 deadline 日期显示在对应日期格中。

3. 通知逻辑 `notify-on-load!` 独立于分类逻辑，直接读取 scheduled/deadline 属性，不受 `classify-kanban` 影响。
