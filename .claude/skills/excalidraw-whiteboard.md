# excalidraw-whiteboard

## When To Use

Use this note when working on Logseq whiteboard, Excalidraw integration, whiteboard gallery behavior, linked blocks, or whiteboard persistence.

## Storage Model

- Whiteboard page = manifest only.
- Page entities should stay lightweight: title, tags, updated-at, and other small metadata.
- Full scene JSON lives in the worker sqlite sidecar implemented in `src/main/frontend/worker/visual_doc.cljs`.
- The authoritative durable payload is the blob snapshot stored in `visual_docs.content`.
- `whiteboard_elements` and `whiteboard_scene_meta` are non-authoritative derived tables.
- Derived tables may be rebuilt from the blob snapshot and must not determine save success.
- Main-thread code must load payloads through `frontend.handler.visual-doc/<load-doc`.
- Main-thread code must save payloads through `frontend.handler.visual-doc/<flush-doc!`.
- `localStorage` is only the draft cache layer.
- `:block/whiteboard-canvas` is legacy fallback only during migration. It is not the source of truth anymore.

## Read And Save Flow

1. Route resolves the page manifest from DataScript.
2. Parent component loads the whiteboard payload worker-first through `whiteboard-handler/<load-canvas-doc`.
3. Editor mounts with `:initial-json` from sidecar, cache, or legacy fallback.
4. Draft cache writes every few seconds into `localStorage`.
5. Durable flush writes sidecar content and only touches manifest metadata in DataScript.
6. The authoritative save path should depend only on the sidecar blob snapshot succeeding.
7. Worker-side load may reconstruct Excalidraw JSON from normalized rows when those rows match the latest blob version.
8. If normalized rows are stale or missing, read paths should fall back to the blob snapshot.
9. Successful sidecar flush retracts the legacy `:block/whiteboard-canvas` page payload.
10. Back navigation must await flush success before leaving.

## Delete Order

1. Delete sidecar payload through `frontend.handler.visual-doc/<delete-doc!`.
2. Clear local draft cache.
3. Delete the page manifest.

## Current Hotspots

- `src/main/frontend/worker/visual_doc.cljs`
- `src/main/frontend/worker/db_worker.cljs`
- `src/main/frontend/handler/visual_doc.cljs`
- `src/main/frontend/handler/whiteboard.cljs`
- `src/main/frontend/components/whiteboard.cljs`
- `src/main/frontend/extensions/excalidraw/core.cljs`

## Merge Notes

- Search for `VISUAL-DOC-SIDECAR` before resolving merge conflicts.
- Prefer preserving sidecar thread APIs and manifest-only page writes.
- Prefer `blob snapshot = truth`, `normalized rows = derived index`.
- Do not reintroduce page-level scene JSON as the primary store.
