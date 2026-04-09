# excalidraw-whiteboard

## When To Use

Use this note when working on Logseq whiteboard, Excalidraw integration, whiteboard gallery, linked blocks, or whiteboard persistence.

## Architecture Overview

```
┌─ localStorage (draft cache, every 3s) ───┐
│  Key: "whiteboard-data-{page-uuid}"       │
│  Value: {version:1, saved-at, data: json} │
│  LRU: keeps last 5 entries                │
└──────────────┬────────────────────────────┘
               ↕
┌─ Worker SQLite Sidecar (/visual-doc.sqlite) ──────┐
│  visual_docs            — blob manifest row        │
│  whiteboard_elements    — derived index (≥256KB)   │
│  whiteboard_scene_meta  — appState (scroll, zoom)  │
│  visual_doc_indexes     — index state tracker      │
└──────────────┬─────────────────────────────────────┘
               ↕
┌─ DataScript (main thread) ────────────────┐
│  Page entity: title, tags, updated-at     │
│  NO large payload (retracted after save)  │
└───────────────────────────────────────────┘
```

## Storage Model

- Whiteboard page = manifest only in DataScript.
- Full scene JSON lives in the worker SQLite sidecar (`visual_docs.content`).
- The authoritative durable payload is the blob snapshot — it determines save success.
- `whiteboard_elements` + `whiteboard_scene_meta` are non-authoritative derived tables, rebuilt in background for docs ≥ 256 KB.
- Derived index rebuild is non-blocking — failures do not break saves.
- Main-thread load: `frontend.handler.visual-doc/<load-doc`.
- Main-thread save: `frontend.handler.visual-doc/<flush-doc!`.
- `localStorage` is the draft cache layer with LRU eviction (5 entries).
- `:block/whiteboard-canvas` is legacy fallback only — retracted after sidecar save succeeds.

## Read And Save Flow

### Save Path (auto every 9s + explicit)
```
Excalidraw onChange → mark persist-dirty
  → Timer 3s: save-to-ls! → localStorage + *cached?=true
  → Timer 9s: persist! → on-save-data(page-uuid, json-str)
    → handler/whiteboard/save-canvas-to-db!
      → handler/visual-doc/<flush-doc! repo page-uuid :block/whiteboard-canvas json-str
        → Worker :thread-api/visual-doc-upsert
          → worker/visual-doc/upsert-doc! (blob write)
          → schedule-visual-doc-index-rebuild! (background)
        → Main: retract :block/whiteboard-canvas, update :block/updated-at
      → *persisted?=true
```

### Load Path
```
Component mount
  → handler/whiteboard/<load-canvas-doc page-uuid
    → handler/visual-doc/<load-doc repo page-uuid :block/whiteboard-canvas "whiteboard-data"
      → Worker :thread-api/visual-doc-get (reads sidecar)
      → localStorage read (draft cache)
      → choose-newer-source → winner
    → Pass initial-json + needs-initial-flush? to Excalidraw
```

### Delete Order
1. `visual-doc/<delete-doc!` — remove sidecar payload + localStorage cache
2. Delete the page manifest

## Sync Status UI

Bottom-right overlay (not in toolbar):
```
Draft: 草稿 已缓存 / 草稿 待缓存
Graph: 图谱 已保存 / 图谱 待保存
```

Tracked atoms: `*cached?`, `*persisted?`, `*cache-dirty?`, `*persist-dirty?`

## Key Constants

| Constant | Value | Location |
|----------|-------|----------|
| `sidecar-path` | `/visual-doc.sqlite` | `worker/visual_doc.cljs` |
| `schema-version` | 4 | `worker/visual_doc.cljs` |
| `normalized-min-content-bytes` | 256 KB | `worker/visual_doc.cljs` |
| `cache-version` | 1 | `handler/visual_doc.cljs` |
| `lru-max-entries` | 5 | `handler/visual_doc.cljs` |
| Draft cache interval | 3000 ms | `excalidraw/core.cljs` |
| DB flush interval | 9000 ms | `excalidraw/core.cljs` |
| Index rebuild delay | 0ms (first) / 1500ms (retry) | `worker/db_worker.cljs` |

## Whiteboard JSON Format

```json
{
  "elements": [
    { "id": "uuid", "type": "rectangle", "x": 0, "y": 0, ... }
  ],
  "appState": {
    "scrollX": 0, "scrollY": 0,
    "zoom": { "value": 1 }
  }
}
```

## Known Pitfalls

- **`rowMode: "object"` crashes on OpfsSAHPoolDb** in setTimeout contexts. All SELECT queries must use `exec-select` (callback + columnNames approach) instead.
- **`(int x)` / `(long x)` in CLJS** truncate to 32-bit signed integer via `(x | 0)`. Timestamps overflow. Always use `(js/Number x)`.
- **SQL bind values** must be JS primitives (string, number, null). Wrap with `(str x)`, `(js/Number x)`, `(some-> x str)`.
- **`.transaction` on OpfsSAHPoolDb** throws "Cannot convert object to primitive value". Use explicit `BEGIN`/`COMMIT`/`ROLLBACK` via `run-in-transaction!`.
- **`remote-function` in `thread_api.cljc`** transit-encodes errors even in `direct-pass?` mode — main thread receives transit string instead of rejected promise.
- **Excalidraw `onChange`** fires very frequently — only do lightweight work (flag dirty bits), defer heavy writes to timers.

## Props Interface (core.cljs)

```clojure
:page-uuid              — UUID string
:page-title             — display title
:initial-json           — preloaded scene JSON
:needs-initial-flush?   — boolean (cache newer than sidecar)
:on-save-data           — fn(page-uuid, json-str) → promise
:on-back                — fn() after save completes
:on-api-ready           — fn(api) for external hooks
:custom-fonts           — {:virgil :helvetica :cascadia} → paths
:render-tags            — fn() → React component
:on-rename              — fn(new-title)
:on-show-linked-blocks  — fn(element-id)
:on-selection-change    — fn(element-id | nil)
```

## Whiteboard Class Tag

- Whiteboard pages are tagged with `:logseq.class/Whiteboard` system class.
- This is a real system class (ident in `logseq.class/*` namespace), NOT a user-created class.
- In tag manager, Whiteboard appears in the "系统内置标签" section, not deletable.
- `<create-whiteboard!` applies both the system class tag AND a user tag named "Whiteboard" (dual-tag strategy for backward compatibility).
- Gallery query: `react/q` with `db-async/<get-tag-objects` on the `:logseq.class/Whiteboard` class id.
- Gallery filters out entities with `:db/ident` (class definitions) and `:logseq.property/deleted-at`.

## SVG Thumbnail Generation

`whiteboard-thumbnail` component in `whiteboard.cljs`:

1. Lazy-loads Excalidraw bundle via `ensure-excalidraw-loaded!`
2. Loads canvas JSON from sidecar via `whiteboard-handler/<load-canvas-doc`
3. Falls back to `load-canvas-from-db` (local draft cache)
4. Calls `ExcalidrawLib.exportToSvg({elements, appState, files})` to generate SVG
5. Sets `width="100%"`, `height="100%"`, `preserveAspectRatio="xMidYMid meet"`
6. Renders via `dangerouslySetInnerHTML`

Key: `exportToSvg` returns a Promise resolving to an SVG DOM element.

## Current Hotspots

- `src/main/frontend/worker/visual_doc.cljs` — SQLite sidecar ops (exec-select, upsert, index rebuild)
- `src/main/frontend/worker/db_worker.cljs` — Thread API handlers, index rebuild scheduling
- `src/main/frontend/handler/visual_doc.cljs` — Three-source reconciliation, cache, flush
- `src/main/frontend/handler/whiteboard.cljs` — Whiteboard specific handlers (CRUD, tag management)
- `src/main/frontend/components/whiteboard.cljs` — Gallery + editor mount + SVG thumbnails
- `src/main/frontend/extensions/excalidraw/core.cljs` — Excalidraw wrapper (timers, sync status, lifecycle)
- `src/main/frontend/components/tag_manager.cljs` — Whiteboard listed as system built-in tag

## Merge Notes

- Search for `VISUAL-DOC-SIDECAR` before resolving merge conflicts.
- Preserve sidecar thread APIs and manifest-only page writes.
- `blob snapshot = truth`, `normalized rows = derived index`.
- Do not reintroduce page-level scene JSON as the primary store.
- Do not use `rowMode: "object"` in any `.exec` call — use `exec-select` helper.
