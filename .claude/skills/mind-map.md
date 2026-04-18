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
`<delete-mind-map!` in `handler/mind_map.cljs` calls `common-page-handler/<delete!` first, then in the success callback:
1. `visual-doc/clear-doc-cache!` — remove localStorage draft cache
2. `localStorage.removeItem` thumbnail key `mind-map-thumb-{uuid}`
3. `visual-doc/<delete-sidecar-doc!` — remove sidecar payload (best-effort, logs on failure)
4. User-facing notification

Guards: refuses when page is missing, has `:db/ident` (built-in), or already has `:logseq.property/deleted-at`.

## Key Constants

| Constant | Value | Location |
|----------|-------|----------|
| `sidecar-path` | `/visual-doc.sqlite` | `worker/visual_doc.cljs` |
| `schema-version` | 5 | `worker/visual_doc.cljs` (see `current-schema-version`) |
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

- **Legacy payload retract is best-effort**: `<flush-doc!` calls `[:db/retract page-id :block/mind-map-data]` after sidecar write; the retract now runs in its own inner `p/catch` so a failure never blocks the timestamp bump or bubbles up (`handler/visual_doc.cljs/<apply-manifest!`). Do not rely on the legacy attr being absent after save — it will be retracted on the next flush if it survived.
- **Thumbnail `localStorage.setItem` quota defense**: `save-thumbnail!` in `extensions/mind_map/core.cljs` wraps `.setItem` in a try/catch. On `QuotaExceededError` it evicts one older `mind-map-thumb-*` entry (via `evict-oldest-thumb!`) and retries once; if still failing it logs a warning and swallows. The 3s cache timer is protected from this class of exception.
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
- `<ensure-mindmap-hidden!` in `tag_manager.cljs` also enforces `:logseq.property/hide?` on mount.
- `get-all-mind-maps` (handler/mind_map.cljs) discovers pages via: class "MindMap" tagged `:logseq.class/Tag`, then `?b :block/tags ?class`, then `(missing? $ ?b :logseq.property/deleted-at)`. The legacy `#_(defn ...)` variants have been removed; the active implementation is the only one.
- Dirty tracking: `data_change` in `extensions/mind_map/core.cljs` stringifies the current `getData` snapshot and compares against `::last-persisted-json` before flipping `::persist-dirty?`. `::last-persisted-json` is seeded from the initial load and refreshed on every successful `<flush-doc!`. Opening a saved map and dragging without editing no longer pushes the sync-status UI into "pending" — only real data diffs do.

## Gallery and Thumbnails

- Gallery component: `all-mind-maps` in `components/mind_map.cljs`.
- Listing query: `mind-map-handler/get-all-mind-maps` queries by MindMap class tag from DataScript.
- Thumbnails stored as data URLs in localStorage: key `mind-map-thumb-{uuid}`.
- No SVG export like Excalidraw; uses `<img>` tag with cached data URL or icon placeholder.
- Gallery uses `react/q` with `db-async/<get-tag-objects` for reactive updates.

## Embed Cards

- `{{mindmap <page-uuid>}}` is rendered by `macro/register "mindmap"` in `components/mind_map.cljs`.
- The embed card root is marked non-editable (`.forbid-edit`) and stops `pointerdown` propagation so toolbar clicks do not reopen the raw macro text in the block editor.
- The embed preview reads the latest cached thumbnail from `mind-map-thumb-{uuid}` in localStorage and displays it inside the card shell.
- Toolbar actions are scoped to the embed surface:
  - Refresh reloads the cached preview thumbnail.
  - Edit opens the mind-map page.
  - Delete removes the embedding block only; it must not delete the mind-map page itself.

## Merge Notes

- Preserve the embed-card `pointerdown` guard; `click` handlers alone are too late to stop block editor activation.
- Search for `VISUAL-DOC-SIDECAR` before resolving merge conflicts.
- Preserve manifest-only page writes and worker sidecar reads.
- `blob snapshot = truth`, `normalized rows = derived index`.
- Do not move full document payload back onto `:block/mind-map-data`.
- Do not use `rowMode: "object"` in any `.exec` call – use `exec-select` helper.

## Review Guardrails

- Mind-map page entities are manifest rows only. Keep creation, rename, gallery queries, and delete flows aligned with that model instead of relying on legacy `:block/mind-map-data` assumptions.
- Treat sidecar and DataScript updates as two stores with no shared transaction boundary. Save failures after sidecar success and delete failures after sidecar cleanup both need explicit recovery semantics.
- Normal editor unmount should not silently downgrade durability to localStorage-only unless the page is actively being deleted. The localStorage cache is bounded by the shared 5-entry LRU and is not a safe long-term source of truth.
- Title-based class lookup (`"MindMap"`) is convenient but fragile. If the feature grows, prefer a stable ident or another explicit marker so gallery membership and duplicate-name checks use the same definition.
