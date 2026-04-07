# mind-map

## When To Use

Use this note when working on the mind-map editor, mind-map gallery, linked blocks, note blocks, or mind-map persistence.

## Architecture Overview

```
┌─ localStorage (draft cache, 3s interval) ─┐
│  Key: "mind-map-data-{page-uuid}"          │
│  Value: {version:1, saved-at, data: json}  │
│  LRU: keeps last 5 entries                 │
└──────────────┬─────────────────────────────┘
               ↕
┌─ Worker SQLite Sidecar (/visual-doc.sqlite) ─┐
│  visual_docs        — blob manifest row       │
│  mind_map_nodes     — derived index (≥256KB)  │
│  visual_doc_indexes — index state tracker     │
└──────────────┬────────────────────────────────┘
               ↕
┌─ DataScript (main thread) ────────────────┐
│  Page entity: title, tags, updated-at     │
│  NO large payload (retracted after save)  │
└───────────────────────────────────────────┘
```

## Storage Model

- Mind-map page = manifest only in DataScript.
- Full mind-map JSON lives in the worker SQLite sidecar (`visual_docs.content`).
- The authoritative durable payload is the blob snapshot — it determines save success.
- `mind_map_nodes` is a non-authoritative derived index, rebuilt in background for docs ≥ 256 KB.
- Derived index rebuild is non-blocking — failures do not break saves.
- Main-thread load: `frontend.handler.visual-doc/<load-doc`.
- Main-thread save: `frontend.handler.visual-doc/<flush-doc!`.
- `localStorage` is the draft cache layer with LRU eviction (5 entries).
- `:block/mind-map-data` is legacy fallback only — retracted after sidecar save succeeds.

## Read And Save Flow

### Save Path
```
Editor on-save-data(page-uuid, json-str)
  → handler/mind-map/save-mind-map-to-db!
    → handler/visual-doc/<flush-doc! repo page-uuid :block/mind-map-data json-str
      → Worker :thread-api/visual-doc-upsert
        → worker/visual-doc/upsert-doc! (blob write)
        → schedule-visual-doc-index-rebuild! (background, non-blocking)
      → Main: retract :block/mind-map-data, update :block/updated-at
```

### Load Path
```
Component did-mount
  → handler/mind-map/<load-mind-map-doc page-uuid
    → handler/visual-doc/<load-doc repo page-uuid :block/mind-map-data "mind-map-data"
      → Worker :thread-api/visual-doc-get (reads sidecar)
      → localStorage read (draft cache)
      → choose-newer-source → winner
    → Pass initial-json + needs-initial-flush? to editor
```

### Create Path
```
<create-mind-map! name
  → create page entity + MindMap class tag
  → save-doc-cache! (localStorage)
  → <flush-doc! (sidecar)
  → redirect to editor
```

### Delete Order
1. `visual-doc/<delete-doc!` — remove sidecar payload + localStorage cache
2. Remove thumbnail cache: `mind-map-thumb-{uuid}`
3. Delete the page manifest

## Key Constants

| Constant | Value | Location |
|----------|-------|----------|
| `sidecar-path` | `/visual-doc.sqlite` | `worker/visual_doc.cljs` |
| `schema-version` | 4 | `worker/visual_doc.cljs` |
| `normalized-min-content-bytes` | 256 KB | `worker/visual_doc.cljs` |
| `cache-version` | 1 | `handler/visual_doc.cljs` |
| `lru-max-entries` | 5 | `handler/visual_doc.cljs` |
| Index rebuild delay | 0ms (first) / 1500ms (retry) | `worker/db_worker.cljs` |

## Mind-Map JSON Format

```json
{
  "data": { "text": "Root text", "uid": "uuid-string", ... },
  "children": [
    { "data": { "text": "Child", "uid": "..." }, "children": [...] }
  ]
}
```

## Known Pitfalls

- **`rowMode: "object"` crashes on OpfsSAHPoolDb** in setTimeout contexts. All SELECT queries must use `exec-select` (callback + columnNames approach) instead.
- **`(int x)` / `(long x)` in CLJS** truncate to 32-bit signed integer via `(x | 0)`. Timestamps overflow. Always use `(js/Number x)`.
- **SQL bind values** must be JS primitives (string, number, null). Wrap with `(str x)`, `(js/Number x)`, `(some-> x str)`.
- **`.transaction` on OpfsSAHPoolDb** throws "Cannot convert object to primitive value". Use explicit `BEGIN`/`COMMIT`/`ROLLBACK` via `run-in-transaction!`.
- **`remote-function` in `thread_api.cljc`** transit-encodes errors even in `direct-pass?` mode — main thread receives transit string instead of rejected promise.

## Props Interface (core.cljs)

```clojure
:map-id               — page-uuid string
:map-title            — page title
:initial-json         — loaded JSON
:needs-initial-flush? — boolean (cache was newer)
:on-save-data         — fn(page-uuid, json-str) → save to sidecar
:on-open-block        — fn(uuid-str) → open in sidebar
:on-add-note-block    — fn() → returns promise(uuid-str)
:note-block-title-fn  — fn(uid-str) → lookup title
:on-search-blocks     — fn(q) → returns promise(results)
```

## Current Hotspots

- `src/main/frontend/worker/visual_doc.cljs` — SQLite sidecar ops (exec-select, upsert, index rebuild)
- `src/main/frontend/worker/db_worker.cljs` — Thread API handlers, index rebuild scheduling
- `src/main/frontend/handler/visual_doc.cljs` — Three-source reconciliation, cache, flush
- `src/main/frontend/handler/mind_map.cljs` — Mind-map specific handlers
- `src/main/frontend/components/mind_map.cljs` — Gallery + editor mount
- `src/main/frontend/extensions/mind_map/core.cljs` — Canvas UI (zero DB dependency)

## MindMap Class Tag

- MindMap pages are tagged with a class entity titled "MindMap".
- This class is created by `<ensure-mindmap-class-tag!` in `handler/mind_map.cljs`.
- It gets a `:db/ident` like `:user.class/MindMap-XxxXx` (NOT `logseq.class/*`).
- The class entity has `:logseq.property/hide? true` so it's excluded from All Pages.
- In tag manager, MindMap is treated as a "virtual builtin" — shown in the system section, not deletable.
- The `virtual-builtin-titles` set in `tag_manager.cljs` controls which user-created classes are treated as builtin.

## Merge Notes

- Search for `VISUAL-DOC-SIDECAR` before resolving merge conflicts.
- Preserve manifest-only page writes and worker sidecar reads.
- `blob snapshot = truth`, `normalized rows = derived index`.
- Do not move full document payload back onto `:block/mind-map-data`.
- Do not use `rowMode: "object"` in any `.exec` call — use `exec-select` helper.
