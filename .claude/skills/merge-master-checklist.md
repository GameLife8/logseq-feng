# merge-master-checklist

## Goal

Use this checklist when merging `master` back into the whiteboard and mind-map branch.
The main risk now is no longer only frontend bundles. It is also the new manifest plus worker-side sidecar storage split.

## New Storage Rules To Preserve

- Whiteboard page = manifest only.
- Mind-map page = manifest only.
- Full whiteboard and mind-map payloads live in the worker sqlite sidecar.
- Mind maps are normalized into `mind_map_nodes`.
- Whiteboards are normalized into `whiteboard_elements` and `whiteboard_scene_meta`.
- Main-thread UI loads payloads through worker thread APIs and keeps them out of the main-thread DataScript replica.
- DataScript page writes should update only lightweight manifest metadata.
- Legacy payload attributes are fallback-only and should not become the source of truth again.

## Search Markers Before Resolving Conflicts

- `VISUAL-DOC-SIDECAR`
- `:thread-api/visual-doc-get`
- `:thread-api/visual-doc-upsert`
- `:thread-api/visual-doc-delete`
- `mind_map_nodes`
- `whiteboard_elements`
- `whiteboard_scene_meta`

## Highest-Risk Files

- `src/main/frontend/worker/visual_doc.cljs`
- `src/main/frontend/worker/db_worker.cljs`
- `src/main/frontend/worker/state.cljs`
- `src/main/frontend/handler/visual_doc.cljs`
- `src/main/frontend/handler/whiteboard.cljs`
- `src/main/frontend/handler/mind_map.cljs`
- `src/main/frontend/components/whiteboard.cljs`
- `src/main/frontend/components/mind_map.cljs`
- `src/main/frontend/extensions/excalidraw/core.cljs`
- `src/main/frontend/extensions/mind_map/core.cljs`

## Conflict Resolution Rules

1. Keep `master` changes for unrelated UI, routing, and bug fixes when they do not break the sidecar split.
2. Keep branch changes for worker sidecar APIs, manifest-only page writes, and delete ordering.
3. If both sides touched whiteboard or mind-map persistence, prefer the version that keeps payload JSON out of page entities.
4. If both sides touched sidecar schema code, preserve the normalized node/element tables and their read-side reconstruction logic.
5. If both sides touched list pages, make sure gallery reads still work when payloads live only in sidecar storage.
6. If both sides touched save flows, preserve `await flush success -> navigate` behavior.
7. If both sides touched delete flows, preserve `delete sidecar -> clear cache -> delete page` ordering.

## Post-Merge Checks

1. Compile `app` and `db-worker`.
2. Create a whiteboard, edit it, return, reopen it, and confirm the scene survives reload.
3. Delete a whiteboard and confirm refresh does not revive it.
4. Create a mind map, edit it, return, reopen it, and confirm the map survives reload.
5. Delete a mind map and confirm refresh does not revive it.
6. Check whiteboard gallery thumbnails and mind-map gallery cards.
7. Check that no new code writes full payload JSON back to `:block/whiteboard-canvas` or `:block/mind-map-data` as the primary store.
