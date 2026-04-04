# db-sync-collaboration

## When To Use

Use this note when working on Logseq multiplayer sync, collaboration consistency, presence, tx log handling, graph/view relationships that affect sync semantics, or when comparing current db-sync with the older RTC implementation.

This note is intentionally limited to the current repository's actual implementation. It does not describe future architecture ideas.

## Current Collaboration Model

- Current multiplayer is not Yjs-style text CRDT collaboration.
- The active implementation is graph-level DB sync backed by `deps/db-sync`.
- Each graph is routed to one Cloudflare Durable Object, which acts as the single serialization point for writes.
- The synchronized payload is DataScript transaction data, not character-level edits.
- Presence is block-level only via `editing-block-uuid`.

## Canonical Data Model

- The canonical local model is still a block/page database:
  - `:block/page`
  - `:block/parent`
  - `:block/order`
  - `:block/refs`
  - `:block/tags`
- Graph view is derived from the block/page database. It is not the write model.
- Linked references and unlinked references are computed views.
- `:block/page` is treated as an implicit reference in reference calculation.
- Older precomputed path-ref state was removed; the repo contains a migration that retracts `:block/path-refs`.

## Current Source Of Truth

- Client runtime:
  - `src/main/frontend/worker/sync.cljs`
  - `src/main/frontend/worker/sync/apply_txs.cljs`
  - `src/main/frontend/worker/sync/handle_message.cljs`
  - `src/main/frontend/worker/sync/client_op.cljs`
  - `src/main/frontend/worker/db_listener.cljs`
- Server runtime:
  - `deps/db-sync/src/logseq/db_sync/worker/dispatch.cljs`
  - `deps/db-sync/src/logseq/db_sync/worker.cljs`
  - `deps/db-sync/src/logseq/db_sync/worker/handler/sync.cljs`
  - `deps/db-sync/src/logseq/db_sync/worker/handler/ws.cljs`
  - `deps/db-sync/src/logseq/db_sync/storage.cljs`
  - `deps/db-sync/src/logseq/db_sync/checksum.cljs`
- Block/ref/view model:
  - `deps/db/src/logseq/db/frontend/schema.cljs`
  - `deps/db/src/logseq/db/frontend/malli_schema.cljs`
  - `deps/db/src/logseq/db/common/reference.cljs`
  - `deps/db/src/logseq/db/common/view.cljs`
  - `src/main/frontend/common/graph_view.cljs`
- Visual docs sidecar:
  - `src/main/frontend/worker/visual_doc.cljs`
  - `src/main/frontend/handler/visual_doc.cljs`

## Client Sync Flow

1. Local edits enter the DataScript database.
2. `db_listener.cljs` observes tx reports and forwards sync-eligible txs.
3. The client normalizes txs and persists:
   - pending txs
   - reverse txs
   - local sync metadata
4. The client only flushes pending txs when:
   - there is no inflight batch
   - `local-tx == remote-tx`
5. If remote txs arrive while local pending txs exist, the client does not naively append them. It:
   - reverses local pending txs
   - applies remote txs
   - rebases local pending txs
   - runs structural fix-up passes

## Server Sync Flow

1. The graph id is mapped to a single Durable Object.
2. The server validates `t-before` against the current graph `t`.
3. If stale, the batch is rejected with `reason = stale`.
4. If valid, the server:
   - transacts the tx entries
   - appends them to `tx_log`
   - advances `t`
   - updates checksum
   - broadcasts `changed`
5. Lagging clients then `pull` the missing tx log entries.

## Consistency And Convergence Invariants

- One graph maps to one Durable Object.
- One graph has one linear tx counter `t`.
- The client persists pending txs and reverse txs locally so restart/offline recovery can rebase safely.
- The client must catch up before it can upload new tx batches.
- Conflict handling favors convergence and structural safety over preserving both user intents.
- After rebase, fix-up code repairs problems such as:
  - cycles
  - broken page/parent consistency
  - sibling order issues
- Checksum is used as a convergence check after the client is caught up and has no pending or inflight txs.

## What Collaboration Currently Is Not

- Not character-level collaborative editing like Google Docs.
- Not a global graph database as the canonical write path.
- Not a page/subtree-sharded multi-authority system.
- Not a whiteboard/mind-map-first sync engine.

## Presence

- Presence currently communicates online users and block-level editing state.
- The protocol field of interest is `editing-block-uuid`.
- Presence does not imply text-level merge semantics.

## Snapshot And Bootstrap

- Bootstrap/import uses snapshot upload/download endpoints.
- During bootstrap, graph readiness is gated by `graph-ready-for-use?`.
- Pull and tx upload are blocked while the graph is not ready.

## Assets

- Assets are synchronized through a side channel, not the same tx log as block transactions.
- Asset transport is under `/assets/:graph-id/...`.
- Asset metadata and block transaction convergence are related but not fully atomic as one unified commit.

## E2EE

- The db-sync worker also handles E2EE user keys and per-graph AES key distribution.
- Current code paths include user RSA keys and graph AES key grant/access APIs.
- Treat encrypted-graph behavior and checksum behavior carefully; do not assume plain-graph checksum guarantees apply identically without re-reading the implementation.

## Visual Docs Status

- Whiteboard and mind-map content are stored in a local worker sqlite sidecar.
- The authoritative durable payload is the blob snapshot in `visual_docs.content`.
- The normalized tables are derived indexes:
  - `whiteboard_elements`
  - `whiteboard_scene_meta`
  - `mind_map_nodes`
- Derived tables may be rebuilt from the authoritative blob and should not be treated as the save-success boundary.
- Main page entities stay lightweight and should not become the primary store for full scene payloads.
- In this exploration pass, the verified storage path for visual docs is local sidecar persistence. Do not assume they are already end-to-end integrated into the same remote db-sync path without tracing that code separately.

## Historical Note: Older RTC Branches

- Older branches such as `origin/debug/add-tx-log` and `origin/enhance/change-property-type` use the legacy `src/main/frontend/worker/rtc/*` stack.
- That older stack was op-oriented:
  - infer semantic ops
  - queue pending ops
  - send `apply-ops`
- It does not use the current `deps/db-sync` server worker path.
- The current repository direction is the newer db-sync model, not the older RTC op-merge path.

## Practical Review Heuristics

- If a change touches tx ordering, read both client rebase code and server `t-before` validation.
- If a change affects linked refs or graph view, remember those are derived from the canonical block/page DB rather than being independent truth.
- If a change touches whiteboard or mind-map persistence, preserve the visual-doc sidecar model unless the task explicitly migrates it.
- If a change proposes CRDT semantics, verify whether it is actually introducing a new model rather than modifying the existing tx-log sync design.

## Useful Commands

- `bb dev:db-sync-start`
- `bb dev:db-sync-test`
- `cd deps/db-sync && npm run test`
- `cd deps/db-sync && npm run build:node-adapter`
- `cd deps/db-sync && npm run start:node-adapter`

## Short Summary

- Facts are stored as block/page entities plus explicit refs/tags.
- Collaboration synchronizes tx logs through a per-graph Durable Object.
- Convergence is achieved with pending queue persistence, reverse/rebase, structural repair, and checksum verification.
- Graph view and linked references are projections over the canonical DB.
- Whiteboard and mind-map persistence are currently a separate visual-doc sidecar path.
