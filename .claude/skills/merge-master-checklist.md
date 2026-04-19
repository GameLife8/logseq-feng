# merge-master-checklist

## Goal

Use this checklist when merging `master` back into the current feature branch.

**Important**: The db-sync / RTC collaboration system has been fully removed from this branch. If `master` introduces new sync-related code, it must be excluded or stubbed during merge.

## New Storage Rules To Preserve

- Whiteboard page = manifest only.
- Mind-map page = manifest only.
- Full whiteboard and mind-map payloads live in the worker sqlite sidecar.
- `visual_docs.content` blob snapshots are the authoritative durable payloads.
- `mind_map_nodes`, `whiteboard_elements`, and `whiteboard_scene_meta` are derived indexes, not the source of truth.
- Main-thread UI loads payloads through worker thread APIs and keeps them out of the main-thread DataScript replica.
- DataScript page writes should update only lightweight manifest metadata.
- Legacy payload attributes are fallback-only and should not become the source of truth again.
- Background index maintenance must not determine user-visible save success.

## Sync Removal Rules (New)

- `deps/db-sync/` has been deleted — do NOT re-introduce it from master.
- `src/main/frontend/worker/sync/` and `sync.cljs` are deleted.
- `src/main/frontend/handler/db_based/sync.cljs`, `rtc_flows.cljs`, `rtc_background_tasks.cljs` are deleted.
- `src/main/frontend/handler/events/rtc.cljs` is deleted.
- `src/main/frontend/components/rtc/` is deleted.
- `src/main/frontend/db/rtc/` is deleted.
- If master adds new requires for any of these namespaces, remove those requires during merge.
- If master adds new RTC state atoms in `state.cljs`, do not include them.
- `deps.edn` should NOT include `logseq/db-sync` dependency.

## Vector Embeddings Removal Rules (New — 2026-04-19)

- The `inference-worker` shadow-cljs build target has been removed entirely.
- `src/main/frontend/inference_worker/` directory has been deleted (inference_worker.cljs, state.cljs, text_embedding.cljs).
- `src/main/frontend/worker/embedding.cljs` has been deleted.
- `src/main/frontend/handler/db_based/vector_search_background_tasks.cljs` and `vector_search_flows.cljs` have been deleted.
- `src/main/frontend/components/vector_search/sidebar.cljs` has been deleted.
- `@huggingface/transformers` dependency has been removed from package.json.
- `:thread-api/set-infer-worker-proxy` thread-api and `worker-state/*infer-worker` atom have been removed.
- `:vector-search/sync-state` defmethod in `handler/events.cljs` has been removed.
- The `remove-old-embeddings-and-reset-new-updates!` function and `:logseq.property.embedding/hnsw-label-updated-at` tx handling in `worker/db_listener.cljs` have been removed.
- If master re-introduces any of these, remove during merge; yarn scripts must NOT build `inference-worker`.

## Upstream Commits We Deliberately Skip

| Upstream commit | Why we skip |
|---|---|
| `f8869978e1` "fix: insert block above" | Depends on upstream `insert-new-block!` refactor that adds `right-sibling` formal; our fork's signature is still `([_state block-value])`. The bug it fixes does not exist on our fork. |
| `9362dbc847` "fix: set date property value after creating journal page" | Our Chinese calendar in `components/property/value.cljs` already sequences `<create!` via `p/do!` and re-looks-up via `model/get-journal-page`, so the master bug is already not present. |
| `333f3e10b1` "remove vector embeddings" | Picked onto a dedicated cleanup branch; resolved conflicts by rejecting master's bundled RTC re-additions. Now landed — see Vector Embeddings Removal Rules above. |

## Cherry-Pick Surgery Patterns

When a master commit touches a file that also contains fork-only deletions (modify/delete conflicts):

1. Deleted-in-HEAD / modified-in-master: `git rm` the file (we intentionally removed it).
2. Modified-in-HEAD / deleted-in-master: `git rm` if we agree with the removal; keep-and-edit otherwise.
3. Content conflicts where master re-introduces removed namespaces as `:require` entries: drop both sides of the conflict block (the namespace truly does not exist).
4. If a master commit bundles unrelated RTC re-additions with the actual fix, split mentally: cherry-pick the fix hunk, drop the RTC hunk.

## Post-Merge Yarn Regeneration

After any commit that touches `package.json` (dependencies added/removed), run `yarn install` to regenerate `yarn.lock` so transitive deps are purged/added cleanly. The cherry-picked lockfile diff is not a reliable substitute.

## Search Markers Before Resolving Conflicts

- `VISUAL-DOC-SIDECAR`
- `:thread-api/visual-doc-get`
- `:thread-api/visual-doc-upsert`
- `:thread-api/visual-doc-delete`
- `mind_map_nodes`
- `whiteboard_elements`
- `whiteboard_scene_meta`
- `rtc` / `db-sync` / `sync.cljs` (should NOT exist after merge)

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
- `src/main/frontend/handler/events.cljs` (may re-add RTC event handlers from master)
- `src/main/frontend/components/header.cljs` (may re-add RTC indicator from master)
- `src/main/frontend/components/repo.cljs` (may re-add remote graph UI from master)

## Conflict Resolution Rules

1. Keep `master` changes for unrelated UI, routing, and bug fixes when they do not break the sidecar split.
2. Keep branch changes for worker sidecar APIs, manifest-only page writes, and delete ordering.
3. If both sides touched whiteboard or mind-map persistence, prefer the version that keeps payload JSON out of page entities.
4. If both sides touched sidecar schema code, preserve the normalized node/element tables as rebuildable derived indexes.
5. If both sides touched sidecar write paths, prefer the version that keeps blob snapshot writes authoritative and lets normalized rows lag or rebuild safely.
6. If both sides touched list pages, make sure gallery reads still work when payloads live only in sidecar storage.
7. If both sides touched save flows, preserve `await flush success -> navigate` behavior.
8. If both sides touched delete flows, preserve `delete sidecar -> clear cache -> delete page` ordering.
9. **If master re-introduces sync/RTC code**, remove the sync-specific parts and keep only unrelated changes from the same file.

## Post-Merge Checks

1. Compile `app` and `db-worker`.
2. Verify NO references to deleted sync namespaces (`frontend.worker.sync`, `frontend.handler.db-based.sync`, `frontend.handler.events.rtc`, `logseq.db-sync.*`).
3. Create a whiteboard, edit it, return, reopen it, and confirm the scene survives reload.
4. Delete a whiteboard and confirm refresh does not revive it.
5. Create a mind map, edit it, return, reopen it, and confirm the map survives reload.
6. Delete a mind map and confirm refresh does not revive it.
7. Check whiteboard gallery thumbnails and mind-map gallery cards.
8. Check that no new code writes full payload JSON back to `:block/whiteboard-canvas` or `:block/mind-map-data` as the primary store.
