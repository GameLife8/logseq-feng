# mind-map

## When To Use

Use this note when working on the mind-map editor, mind-map gallery, linked blocks, note blocks, or mind-map persistence.

## Storage Model

- Mind-map page = manifest only.
- Page entities should keep lightweight metadata only.
- Full mind-map JSON lives in the worker sqlite sidecar implemented in `src/main/frontend/worker/visual_doc.cljs`.
- The authoritative sidecar format is now normalized node storage in `mind_map_nodes`.
- Main-thread code must load payloads through `frontend.handler.visual-doc/<load-doc`.
- Main-thread code must save payloads through `frontend.handler.visual-doc/<flush-doc!`.
- `localStorage` is the draft cache layer.
- `:block/mind-map-data` is legacy fallback only during migration.

## Read And Save Flow

1. Route resolves the page manifest from DataScript.
2. Parent component loads payload worker-first through `mind-map-handler/<load-mind-map-doc`.
3. Editor mounts with `:initial-json` from sidecar, cache, or legacy fallback.
4. Draft cache writes into `localStorage`.
5. Durable flush writes sidecar content and updates only manifest metadata.
6. Worker-side save normalizes the tree into `mind_map_nodes` rows keyed by `node_id`, with `parent_id` and `child_order`.
7. Worker-side load reconstructs the JSON tree from normalized node rows.
8. Older blob sidecar records are auto-migrated into node rows on first read.
9. Successful sidecar flush retracts the legacy `:block/mind-map-data` payload.
10. Back navigation must await flush success before leaving.

## Create And Delete Rules

- New mind maps should create the page manifest first, then write the initial JSON into sidecar storage.
- New code must not place the initial document JSON onto the page entity.
- Delete order:
  1. delete sidecar payload,
  2. clear draft cache and thumbnail cache,
  3. delete the page manifest.

## Current Hotspots

- `src/main/frontend/worker/visual_doc.cljs`
- `src/main/frontend/worker/db_worker.cljs`
- `src/main/frontend/handler/visual_doc.cljs`
- `src/main/frontend/handler/mind_map.cljs`
- `src/main/frontend/components/mind_map.cljs`
- `src/main/frontend/extensions/mind_map/core.cljs`

## Merge Notes

- Search for `VISUAL-DOC-SIDECAR` before resolving merge conflicts.
- Preserve manifest-only page writes and worker sidecar reads.
- Do not move the full document payload back onto `:block/mind-map-data` except as a temporary fallback during migration.
