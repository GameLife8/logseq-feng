# Agenda / Schedule Skill

Use this guide when changing the Logseq agenda module, including month view, week view, the right-side day panel, kanban projection, task creation, or slash-command date flows.

## Main Files

- `src/main/frontend/components/agenda.cljs`
  Main agenda UI, shared display-event projection, kanban projection, task cards, and task creation dialog.
- `src/main/frontend/components/agenda_data.cljs`
  Shared task loading, effective status resolution, and raw property-to-date normalization.
- `src/main/frontend/commands.cljs`
  Slash-command entry points for `/Scheduled` and `/Deadline`.

## Active Entry Points

Treat these as the real source of truth before changing agenda behavior.

### Task loading and status

- `<load-tasks`
- `task-status-ident`
- `task-active?`
- `prop->ms`
- `task-date-info`

### Shared calendar display projection

- `task->display-events`
- `group-display-events-by-day`
- `task-display-date-info`
- `task-card`
- `month-view`
- `week-view`
- `day-panel`

### Kanban projection

- `backlog-cutoff-ms`
- `build-kanban-item`
- `task->kanban-items`
- `kanban-view-projected`

### Task creation

- `<create-task!`
- `new-task-dialog-v2`
- `new-task-btn`
- `mini-date-picker`

### Slash commands

- `ensure-todo-status!`
- `handle-step :editor/set-scheduled`
- `handle-step :editor/set-deadline`

## Legacy Paths Still in the File

These still exist in `agenda.cljs`, but they are not the active path that powers the current UI.

- `kanban-view-legacy`

Do not update those first unless you are intentionally removing dead code.

## Task Model

An agenda task is any block that matches at least one of these conditions:

- Has `:logseq.property/status`
- Has `:logseq.property/scheduled`
- Has `:logseq.property/deadline`

Relevant fields:

```clojure
:block/uuid
:block/title
:block/created-at
:block/page
:block/tags
:logseq.property/status
:logseq.property/priority
:logseq.property/scheduled
:logseq.property/deadline
```

## Effective Status Rules

`task-status-ident` in `agenda_data.cljs` is the agenda status source of truth.

Current rules:

- Explicit `Status` wins.
- If a task has `Scheduled` or `Deadline` but no explicit `Status`, treat it as `Todo`.
- `Done` and `Canceled` are the only closed states for agenda filtering.

Current built-in status set used by agenda:

- `Backlog`
- `Todo`
- `Doing`
- `In Review`
- `Done`
- `Canceled`

Semantic meaning used by agenda:

- `Backlog`, `Todo`, `Doing`, and `In Review` are active states.
- `Done` and `Canceled` are closed states.

Important note:

- Agenda reads raw task entities from the DB worker.
- Built-in default property values are not always materialized on those raw pulls.
- That is why date-only tasks must be normalized in `task-status-ident` instead of assuming the DB pull already contains `Status=Todo`.

## Calendar Projection Model

Month view, week view, and the right-side day panel must use the same projected display-event model.

Do not treat a raw task as the final calendar unit. A task may render as one or two display events.

### Projection rules

- `Scheduled` and `Deadline` on the same day: render one `Deadline` event.
- `Scheduled` and `Deadline` on different days: render two events.
- Only `Scheduled`: render one `Scheduled` event.
- Only `Deadline`: render one `Deadline` event.
- Neither property exists: fall back to one `created-at` event.

### Same-day rule

Same-day comparison is based on local calendar day, not exact millisecond equality.

### Why this matters

- Week view must show one or two rows based on the projected events.
- Month view cell counts must match the same projection.
- The right-side day panel must show the same projected items for the selected day.

## Calendar Card Time Rules

The footer time shown under a task card must follow the projected event that produced that card.

Use `task-display-date-info` for card rendering in projected views.

Do not let the card footer fall back to a generic task-level date when the card actually represents a specific projected event.

Examples:

- If the card is the `Scheduled` event, show the `Scheduled` time.
- If the card is the `Deadline` event, show the `Deadline` time.
- If the card is the fallback event, show `created-at`.

If this rule breaks, the UI will show a card on one day while its footer time describes a different day.

## Month View Layout Guardrails

Long task titles must stay inside the current month cell.

Key style constraints in `month-view`:

- The day cell must allow shrinking.
- The day cell must hide overflow.
- The event title must be block-level and width-constrained.
- The event title must use ellipsis instead of expanding into the next day cell.

Current implementation depends on these styles:

- `:minWidth "0"`
- `:overflow "hidden"`
- `:display "block"`
- `:maxWidth "100%"`
- `:textOverflow "ellipsis"`
- `:whiteSpace "nowrap"`

If titles start bleeding into the next day, inspect the month cell and event-title style maps first.

## Scope and Project Filtering

Agenda filtering happens before calendar or kanban projection.

Current flow:

1. Load all agenda tasks.
2. Extract project tags.
3. Apply scope filtering.
4. Project the filtered tasks into display events or kanban items.

This means:

- Calendar and kanban items are display-layer projections.
- Project filtering still belongs to the original task.
- If a task matches a project filter, every projected item from that task should stay visible in that filtered scope.

Functions involved:

- `task-project-tags`
- `all-projects`
- `filter-tasks-by-scope`

## Kanban Projection Model

The active kanban implementation is `kanban-view-projected`, backed by `task->kanban-items`.

Do not use `classify-kanban` or `kanban-view-legacy` as the source of truth for new behavior.

### Current kanban column order

1. `Backlog`
2. `Planned`
3. `Deadline`
4. `Overdue`
5. `Done / Canceled`

### Current kanban rules

- `Done` and `Canceled` only appear in the closed column.
- Overdue deadline tasks only appear in `Overdue`.
- A manual `Backlog` status forces the task into `Backlog`.
- If a manual `Backlog` task also has a non-overdue `Deadline`, it still appears in `Deadline`.
- A task with `Deadline` but no `Scheduled` only appears in `Deadline` or `Overdue`.
- A task with `Scheduled` and `Deadline` on the same day behaves like a deadline-only task in kanban.
- A task with `Scheduled` and `Deadline` on different days appears on the plan side and the deadline side while the deadline is not overdue.
- Open tasks without `Deadline` use `Scheduled`, otherwise `created-at`, for the plan-side date.
- The backlog threshold is 7 days before today.

### Important distinction

Kanban items are display projections, not real status writes.

The UI may project an item into the `Backlog` column without mutating the block's true `Status` property.

## Task Creation Flow

The active creation flow is `new-task-dialog-v2` plus `<create-task!`.

The old Todo / Scheduled / Deadline type picker is not the active product model anymore.

### Current form fields

- Title
- Priority
- Scheduled
- Deadline
- Project tags

### Current creation rules

- `Scheduled` is required.
- `Deadline` is optional.
- `Priority` is optional.
- A newly created task is always written as `Status=Todo`.
- The journal page is chosen from `Scheduled`.
- After block creation, the flow writes `Priority`, `Scheduled`, `Deadline`, and selected project tags.

### Compatibility note

`<create-task!` still supports:

- The active map-style argument path used by `new-task-dialog-v2`
- A legacy arity used by older callers

If you change task creation, update the map-style path first and only preserve the legacy arity if an existing caller still needs it.

## Slash Command Rules

`/Scheduled` and `/Deadline` still come from `commands.cljs`, not from the agenda page.

Current behavior:

- `/Scheduled` ensures the current block has `Status=Todo` if no status exists.
- `/Deadline` ensures the current block has `Status=Todo` if no status exists.
- Then each command opens the matching property flow.

This means slash-created date tasks and agenda-created tasks are expected to converge on the same effective status behavior.

## Notification Rules

Agenda load notifications are derived from active tasks only.

Current behavior in `notify-on-load!`:

- Show an info notification for tasks whose `Scheduled` time starts today.
- Show a warning notification for active tasks whose `Deadline` is already overdue.

If notification behavior changes, make sure the active-task filter still respects the same closed-state semantics as the rest of agenda.

## Safe Change Workflow

When changing one area, inspect the related functions together instead of patching a single function in isolation.

### If changing effective status behavior

Inspect together:

- `task-status-ident`
- `task-active?`
- `task-card`
- `task->kanban-items`
- slash-command handlers in `commands.cljs`

### If changing calendar date selection or rendering

Inspect together:

- `prop->ms`
- `task-date-info`
- `task->display-events`
- `group-display-events-by-day`
- `task-display-date-info`
- `task-card`
- `month-view`
- `week-view`
- `day-panel`

### If changing kanban behavior

Inspect together:

- `task-status-ident`
- `task-active?`
- `backlog-cutoff-ms`
- `build-kanban-item`
- `task->kanban-items`
- `kanban-view-projected`

### If changing creation behavior

Inspect together:

- `new-task-dialog-v2`
- `mini-date-picker`
- `<create-task!`
- project-tag writing in `<create-task!`
- slash-command behavior if you want product consistency across creation paths

## Known Issues (Non-blocking)

### Calendar popover layout
- The `calendar-popover-style` in `new-task-dialog-v2` positions the mini-date-picker to the right of the form.
- Current: `bottom: 0` (bottom-aligned), `width: 360px` (matches form), `left: calc(100% + 12px)`.
- If the form layout changes, this absolute positioning may need adjustment.

### Non-atomic property writes in `<create-task!`
- Properties (status, priority, scheduled, deadline, tags) are set sequentially, not in a single transaction.
- If any step fails, the task may be partially configured. A `p/catch` now logs the error and returns nil, but cannot roll back already-written properties.
- In practice, individual property writes rarely fail.

## Common Failure Modes

Watch for these regressions after any agenda change.

- Date-only tasks show as no-status because status normalization was bypassed.
- Week view and month view disagree because one view still uses raw tasks instead of projected events.
- The right-side day panel disagrees with the month cell count because the panel is not reading the shared event map.
- Card footer time shows `Deadline` on a `Scheduled` card, or vice versa.
- `Scheduled` and `Deadline` on the same day accidentally render twice.
- Long month-cell titles overflow into the next day.
- Manual `Backlog` tasks lose their deadline-side visibility in kanban.
- Scope filtering works for the raw task list but not for the projected events.
- New-task creation writes the block to the wrong journal page because `Scheduled` is not used as the page anchor.

## Verification Checklist

After agenda changes, verify these behaviors manually:

- A task with only `Scheduled` appears once at `Scheduled`.
- A task with only `Deadline` appears once at `Deadline`.
- A task with `Scheduled` and `Deadline` on the same day appears once as `Deadline`.
- A task with `Scheduled` and `Deadline` on different days appears twice in calendar views.
- A date-less todo falls back to `created-at` in projected calendar views.
- A date-only task without explicit status still behaves as `Todo` in agenda.
- Long titles in month view do not bleed into the next day.
- Kanban follows the current `Backlog / Planned / Deadline / Overdue / Done / Canceled` projection rules.
- The creation dialog always creates a Todo and requires `Scheduled`.
- Slash-created `Scheduled` and `Deadline` tasks still end up with effective Todo status.

## Focused Test Commands

Use these focused tests before broader runs:

```powershell
bb dev:test -v frontend.components.agenda-data-test
bb dev:test -v frontend.components.agenda-test
```

If behavior changed in slash-command status bootstrapping, also inspect `commands.cljs` manually even if no dedicated agenda test fails.

## Quick Rule Summary

Remember these three agenda invariants:

1. Normalize status first.
2. Filter tasks before projection.
3. Render projected items, not raw tasks, in shared calendar and kanban views.

