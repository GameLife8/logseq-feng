# 日程模块开发规范（Agenda / Schedule）

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
