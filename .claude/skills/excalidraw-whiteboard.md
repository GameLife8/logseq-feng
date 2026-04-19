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

### Create Path
```
<create-whiteboard! name
  → common-page-handler/<create!
  → ensure hidden user-defined `Whiteboard` class tag (`:class? true`, `:logseq.property/hide? true`)
  → tag the page with that class entity
  → visual-doc/save-doc-cache! (localStorage seed with initial-canvas-json)
  → visual-doc/<flush-doc! (sidecar seed; failure logged, does not fail create)
  → redirect (if opts :redirect? true)
```

### Delete Order
`<delete-whiteboard!` in `handler/whiteboard.cljs` calls `common-page-handler/<delete!` first, then in the success callback:
1. `visual-doc/clear-doc-cache!` — remove localStorage draft cache
2. `visual-doc/<delete-sidecar-doc!` — remove sidecar payload (best-effort, logs on failure)
3. User-facing notification

Guards: refuses when page is missing, has `:db/ident` (built-in), or already has `:logseq.property/deleted-at`. On failure, `:error-handler` callback shows a notification.

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
| `schema-version` | 5 | `worker/visual_doc.cljs` (see `current-schema-version`) |
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
- **`<create-whiteboard!` seeds an initial empty scene** into the localStorage draft cache AND the sidecar on create (see `initial-canvas-json` in `handler/whiteboard.cljs`). Navigating away before first edit still yields a valid empty scene from both layers — the load path no longer needs to tolerate an empty sidecar + empty cache for freshly created pages. Sidecar seed failure is logged and swallowed; page creation still succeeds.
- **`get-all-whiteboards` queries the hidden user-defined `Whiteboard` class directly**. New whiteboards no longer rely on `:logseq.class/Whiteboard` or dual-tag matching, which avoids `ldb/page?` misclassification in All Pages.
- **Tag add and remove share the same outliner write path**: both `add-tag-to-page!` and `remove-tag-from-page!` go through `db-property-handler` (`set-block-property!` and `delete-property-value!` respectively). Outliner middleware and tx metadata now fire symmetrically on add and remove.
- **Legacy payload retract is best-effort**: `<flush-doc!` calls `[:db/retract page-id :block/whiteboard-canvas]` after sidecar write; the retract now runs in its own inner `p/catch` so a failure never blocks the timestamp bump or bubbles up (`handler/visual_doc.cljs/<apply-manifest!`). Do not rely on the legacy attr being absent after save — it will be retracted on the next flush if it survived.

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

- Whiteboard pages are tagged with a hidden user-created `Whiteboard` class entity (`:class? true`, user-class ident namespace).
- This keeps the page itself classified as a normal Page while still making whiteboards queryable as a group.
- In tag manager, Whiteboard appears as a virtual built-in tag, not a real `logseq.class/*` system tag.
- `<create-whiteboard!` ensures the hidden class exists, marks it `:logseq.property/hide? true`, then tags the page with that class.
- Gallery query: `react/q` with `db-async/<get-tag-objects` on the hidden Whiteboard class id returned by `get-whiteboard-class-tag`.
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

## Embed Cards

- `{{whiteboard <page-uuid>}}` is rendered by `macro/register "whiteboard"` in `components/whiteboard.cljs`.
- The embed card root is marked non-editable (`.forbid-edit`) and stops `pointerdown` propagation so toolbar clicks do not reopen the raw macro text in the block editor.
- The embed preview uses the same sidecar-first load path as gallery thumbnails: sidecar JSON first, then local draft cache fallback, then `ExcalidrawLib.exportToSvg`.
- Toolbar actions are scoped to the embed surface:
  - Refresh regenerates the preview SVG.
  - Edit opens the whiteboard page.
  - Delete removes the embedding block only; it must not delete the whiteboard page itself.

## Current Hotspots

- `src/main/frontend/worker/visual_doc.cljs` — SQLite sidecar ops (exec-select, upsert, index rebuild)
- `src/main/frontend/worker/db_worker.cljs` — Thread API handlers, index rebuild scheduling
- `src/main/frontend/handler/visual_doc.cljs` — Three-source reconciliation, cache, flush
- `src/main/frontend/handler/whiteboard.cljs` — Whiteboard specific handlers (CRUD, tag management)
- `src/main/frontend/components/whiteboard.cljs` — Gallery + editor mount + SVG thumbnails
- `src/main/frontend/extensions/excalidraw/core.cljs` — Excalidraw wrapper (timers, sync status, lifecycle)
- `src/main/frontend/components/tag_manager.cljs` — Whiteboard listed as a virtual built-in tag

## Merge Notes

- Preserve the embed-card `pointerdown` guard; `click` handlers alone are too late to stop block editor activation.
- Search for `VISUAL-DOC-SIDECAR` before resolving merge conflicts.
- Preserve sidecar thread APIs and manifest-only page writes.
- `blob snapshot = truth`, `normalized rows = derived index`.
- Do not reintroduce page-level scene JSON as the primary store.
- Do not use `rowMode: "object"` in any `.exec` call – use `exec-select` helper.

## Review Guardrails

- Persist the full Excalidraw scene, not just `elements` plus a tiny `appState` subset. If image/file elements are supported, the serialized payload must include the file map as well or round-trips will drop those assets.
- Treat sidecar save/delete plus DataScript manifest updates as a split transaction. Avoid sidecar-first destructive flows unless you also have a rollback or retry strategy.
- For destructive deletes, prefer "page tombstone/delete succeeds first, then sidecar cleanup" over "sidecar cleanup first". Orphaned blobs are easier to repair than user-visible pages whose durable content has already been removed.
- Normal route changes should not rely on the 5-entry localStorage LRU as the only durable copy. If unmount skips the sidecar flush, make that conditional on an explicit deleting state rather than the default path.
