# db-sync-collaboration (REMOVED)

## Status

**The multi-device sync / RTC collaboration system has been fully removed from this codebase.**

The removal was done to reduce code redundancy. A future AI-based sync replacement is planned.

## What Was Removed

- `src/main/frontend/worker/sync/` — 15 client sync files (apply_txs, assets, auth, crypto, transport, etc.)
- `src/main/frontend/worker/sync.cljs` — sync entry point
- `deps/db-sync/` — entire server-side sync package (~48 files, Cloudflare Durable Object model)
- `src/main/frontend/handler/db_based/sync.cljs` — RTC handler
- `src/main/frontend/handler/db_based/rtc_flows.cljs` — RTC flows
- `src/main/frontend/handler/db_based/rtc_background_tasks.cljs` — background tasks
- `src/main/frontend/handler/events/rtc.cljs` — RTC event handlers
- `src/main/frontend/components/rtc/indicator.cljs` — sync status indicator UI
- `src/main/frontend/db/rtc/debug_ui.cljs` — RTC debug panel
- All sync/RTC test files
- `docs/agent-guide/db-sync/` — sync protocol documentation

## Stubbed Functions

Some external-facing functions were stubbed (return nil/false) instead of deleted, to avoid breaking callers:

- `handler/e2ee.cljs` — `request-e2ee-password` returns nil, `decrypt-user-e2ee-private-key` returns rejected promise
- `handler/user.cljs` — `rtc-group?` returns false, `get-user-type` returns nil, `manager?` returns false
- `handler/assets.cljs` — `maybe-request-remote-asset-download!` is a no-op

## What Still Exists

### Canonical Data Model (unchanged)

- Block/page database: `:block/page`, `:block/parent`, `:block/order`, `:block/refs`, `:block/tags`
- Graph view is derived from the block/page DB (not an independent write model)
- Linked/unlinked references are computed views

### Key Files

- `deps/db/src/logseq/db/frontend/schema.cljs` — DB schema
- `deps/db/src/logseq/db/frontend/malli_schema.cljs` — validation schema
- `deps/db/src/logseq/db/common/reference.cljs` — reference computation
- `deps/db/src/logseq/db/common/view.cljs` — view queries
- `src/main/frontend/common/graph_view.cljs` — graph view

### Visual Docs (unchanged)

- Whiteboard and mind-map content stored in local worker SQLite sidecar
- `visual_docs.content` blob snapshots remain the authoritative durable payload
- Derived index tables (`whiteboard_elements`, `whiteboard_scene_meta`, `mind_map_nodes`) unchanged
- See `excalidraw-whiteboard.md` and `mind-map.md` for details

## Cleaned State Atoms

Removed from `state.cljs`:
- `:rtc/downloading-graph?`, `:rtc/downloading-graph-uuid`, `:rtc/uploading?`
- `:rtc/log`, `:rtc/state`, `:rtc/graphs`, `:rtc/remote-graphs`
- `:rtc/users-info`, `:rtc/current-page-users`
- `:feature/enable-sync?`

Removed from `worker/state.cljs`:
- `*db-sync-config`, `*db-sync-client`

Removed from `config.cljs`:
- `ENABLE-DB-SYNC-LOCAL`, `db-sync-local?`, `db-sync-ws-url`, `db-sync-http-base`

## Practical Notes

- All graphs are now local-only. No remote graph management in the repo UI.
- Asset sync is disabled; managed assets remain local.
- The settings collaboration tab has been removed.
- Header no longer shows RTC collaborator avatars or sync indicator.
- `deps.edn` no longer includes `logseq/db-sync` dependency.
