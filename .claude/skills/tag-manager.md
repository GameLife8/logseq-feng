# Tag Manager Skill

## When To Use

Use this guide when modifying the tag management page, tag classification, or tag visibility rules.

## Main File

- `src/main/frontend/components/tag_manager.cljs`

## Architecture

### Tag Classification (Three Categories)

1. **System tags** — `:db/ident` in `logseq.class/*` namespace (e.g. `:logseq.class/Whiteboard`)
   - Defined in `system-class-idents` vector (16 entries)
   - Filtered via `system-class-ident-set` (the set version)
   - Display names in `system-tag-display` map
   - Shown with lock icon, "系统内置" label, no delete button
   - Counts from separate `<load-system-tag-counts` query

2. **Virtual builtin tags** — User-created classes treated as system tags
   - Defined in `virtual-builtin-titles` set (currently `#{"MindMap"}`)
   - Display names in `virtual-builtin-display` map
   - Shown in system section after real system tags
   - NOT deletable
   - Counts from the shared `<load-tag-ref-counts` query

3. **User tags** — Everything else tagged with `:logseq.class/Tag`
   - Shown with `#` icon and red delete button
   - Deletable with confirmation dialog

### System Class Idents (Full List)

```clojure
[:logseq.class/Journal :logseq.class/Task :logseq.class/Whiteboard
 :logseq.class/Asset :logseq.class/Tag :logseq.class/Page
 :logseq.class/Property :logseq.class/Root :logseq.class/Query
 :logseq.class/Cards :logseq.class/Card :logseq.class/Code-block
 :logseq.class/Quote-block :logseq.class/Math-block
 :logseq.class/Pdf-annotation :logseq.class/Template]
```

### Key Filtering Logic

All tag entities are loaded in ONE query (`<load-all-tag-entities`), then split client-side:

```clojure
;; User tags: remove system idents AND virtual builtins
user-only (->> all-tags
               (remove #(system-class-ident-set (:db/ident %)))
               (remove #(virtual-builtin-titles (:block/title %)))
               ...)

;; Virtual builtins: match by title, exclude system idents
vb-only   (->> all-tags
               (filter #(virtual-builtin-titles (:block/title %)))
               (remove #(system-class-ident-set (:db/ident %)))
               ...)
```

**Critical**: Do NOT filter by `:db/ident` presence. ALL class entities (including user-created ones) have `:db/ident`. User classes get idents like `:user.class/Foo-XxxXx`, not `logseq.class/*`.

### Hiding from All Pages

Virtual builtin tags (MindMap) are hidden from All Pages via `:logseq.property/hide? true`, set by `<ensure-mindmap-hidden!` in the tag manager's `did-mount`.

The `get-exclude-page-ids` function in `deps/db/src/logseq/db/common/view.cljs` checks this property to exclude pages from the All Pages view.

## Data Loading

| Function | Returns | Purpose |
|----------|---------|---------|
| `<load-all-tag-entities` | `[{:db/id :db/ident :block/uuid :block/title} ...]` | All entities tagged with `:logseq.class/Tag` |
| `<load-tag-ref-counts` | `{db-id count}` | How many blocks reference each tag |
| `<load-system-tag-counts` | `{ident count}` | How many blocks reference each `logseq.class/*` tag |

## Adding a New System Tag

To add a new system-level tag (with `logseq.class/*` ident):

1. Add the ident keyword to `system-class-idents` vector
2. Add display name to `system-tag-display` map

## Adding a New Virtual Builtin Tag

To make a user-created class act as a system tag:

1. Add its title to `virtual-builtin-titles`
2. Add display name to `virtual-builtin-display`
3. Ensure it has `:logseq.property/hide? true` (add to `<ensure-mindmap-hidden!` or its creation function)

## UI Components

| Component | Purpose |
|-----------|---------|
| `tag-row-system` | Renders a system/virtual-builtin tag row (lock icon, no delete) |
| `tag-row-user` | Renders a user tag row (# icon, delete button) |
| `section-title` | Section header (uppercase, faded) |
| `count-badge` | Reference count pill (purple if > 0, gray if 0) |
| `tag-manager-page` | Main page component with search, sections, delete confirmation |

## Known Pitfalls

- `db-async/<q` returns pull results via worker thread with transit serialization. Keywords round-trip correctly.
- The `not` clause in DataScript queries via `db-async/<q` may not work reliably. Always prefer client-side filtering.
- `db/transact!` goes through the outliner pipeline, not direct DataScript transact. For setting properties like `:logseq.property/hide?`, this is the correct approach.
- ALL class entities have `:db/ident` — filtering by `(not (:db/ident %))` removes ALL tags including user-created ones. Always use `system-class-ident-set` to distinguish system from user.
