# Visual Doc Storage Scaling Guide

## Purpose

This document defines a practical storage strategy for Logseq visual documents,
including whiteboards and mind maps, as document size and edit frequency grow.

It is based on one core principle:

- `blob` snapshot remains the authoritative durable data.
- normalized tables are derived indexes or acceleration structures.
- patch logs are optional write-optimization layers, not the source of truth.

This guide is meant to keep the save path stable while still allowing future
performance work for large and very large visual documents.

## Current State

Current visual document persistence is split into two layers:

- Durable store:
  `visual-doc.sqlite` in the worker-side sqlite sidecar.
- Draft cache:
  browser or Electron `localStorage`.

Current key code locations:

- Main-thread loader and flusher:
  `src/main/frontend/handler/visual_doc.cljs`
- Whiteboard handler:
  `src/main/frontend/handler/whiteboard.cljs`
- Mind-map handler:
  `src/main/frontend/handler/mind_map.cljs`
- Worker thread API:
  `src/main/frontend/worker/db_worker.cljs`
- Worker storage implementation:
  `src/main/frontend/worker/visual_doc.cljs`

Current durable write behavior:

- `visual_docs.content` stores the full document JSON as a blob string.
- `localStorage` stores recent draft cache entries for crash recovery and fast
  resume.
- normalized tables such as `mind_map_nodes`, `whiteboard_elements`, and
  `whiteboard_scene_meta` exist in schema, but they should be treated as
  non-authoritative optimization layers.

## Problem

Using one large JSON blob is the most reliable save format, but document size
eventually creates pressure in several places:

- every save requires full `JSON.stringify`
- every durable write rewrites the full payload
- every open requires full `JSON.parse`
- save latency becomes user-visible on back navigation and periodic flushes
- large documents are harder to search, inspect, diff, and optimize incrementally

The key tradeoff is:

- blob is best for correctness and rollback
- normalized storage is best for queryability and partial computation
- patch logs are best for very high-frequency edits

## Design Principle

Do not make the most complex storage representation the critical save path.

For whiteboards and mind maps:

- the user-facing save path must prefer the most stable format
- optimization layers may fail independently without causing save failure
- any future background indexing must be rebuildable from the authoritative blob

In short:

- authoritative data = blob snapshot
- derived index = normalized rows
- write optimization = patch log plus periodic checkpoint

## Three-Tier Strategy

### Tier 1: Small To Medium Documents

Recommended mode:

- direct blob only

Storage model:

- authoritative snapshot in `visual_docs.content`
- draft cache in `localStorage`

When to use:

- document size is small enough that full serialize and full parse are cheap
- editor save latency is not user-visible
- no strong need for per-node or per-element search

Why:

- simplest implementation
- lowest correctness risk
- easiest recovery and rollback

Suggested initial range:

- up to about `256 KB` serialized JSON
- or up to about `500` logical nodes or elements

These thresholds are intentionally conservative and should be validated with
telemetry before being treated as final.

### Tier 2: Large Documents

Recommended mode:

- blob snapshot plus normalized derived index

Storage model:

- authoritative snapshot in `visual_docs.content`
- derived rows in:
  - `mind_map_nodes`
  - `whiteboard_elements`
  - `whiteboard_scene_meta`

When to use:

- full blob save is still acceptable
- but gallery, search, statistics, or partial reads become expensive

Why:

- keeps the save path stable
- allows faster read-side operations for selected use cases
- derived rows can be rebuilt from blob after corruption or schema changes

Typical uses for normalized indexes:

- gallery metadata
- thumbnail generation
- text extraction for search
- element or node counts
- future merge diagnostics

Important rule:

- durable save success must not depend on normalized write success

Recommended flow:

1. save blob snapshot successfully
2. mark document saved in UI
3. schedule normalized rebuild in background or idle time
4. if normalized build fails, log and retry later

Suggested initial range:

- about `256 KB` to `2 MB`
- or about `500` to `5000` logical nodes or elements

### Tier 3: Huge And High-Frequency Documents

Recommended mode:

- blob checkpoint plus patch log plus normalized derived index

Storage model:

- authoritative durable checkpoint in blob form
- append-only or bounded patch log for recent edits
- normalized rows for read optimization

When to use:

- documents are large enough that rewriting the full blob too often is costly
- editing cadence is high enough that users can feel periodic full writes

Why:

- patch log reduces durable write pressure during active editing
- checkpoint keeps recovery simple
- normalized rows still support partial reads and indexing

Recommended flow:

1. editor emits compact patches during active editing
2. worker appends patches to patch log
3. worker periodically compacts patches into a new blob checkpoint
4. normalized index is rebuilt or incrementally updated from checkpoint plus
   patches
5. on startup, recovery replays the latest checkpoint plus unapplied patches

Important rule:

- patch log is not the source of truth forever
- patch logs must be compacted into checkpoints regularly

Suggested initial range:

- above about `2 MB`
- or above about `5000` logical nodes or elements
- or documents with sustained high-frequency edits that make full-blob flushes
  visibly expensive

## Recommended Data Roles

### Blob Snapshot

Role:

- authoritative durable representation

Properties:

- simple to persist
- simple to recover
- easiest rollback story
- easiest compatibility story across schema versions

Recommended fields in `visual_docs`:

- `page_uuid`
- `doc_type`
- `content`
- `updated_at`
- `schema_version`
- `storage_format`
- optional future fields:
  - `content_size`
  - `content_hash`
  - `checkpoint_seq`

### Normalized Derived Index

Role:

- query acceleration and partial read support

Properties:

- rebuildable from blob
- may lag temporarily behind latest blob
- should never block user-visible save success

Recommended guarantees:

- index rows carry blob version or hash lineage
- read paths verify freshness before trusting index-only results
- stale indexes fall back to blob

### Patch Log

Role:

- reduce full-blob rewrite frequency for very large documents

Properties:

- transient optimization layer
- bounded size
- regularly compacted
- replayable against last checkpoint

Recommended future fields:

- `page_uuid`
- `patch_seq`
- `base_checkpoint_seq`
- `patch_json`
- `created_at`

## Read Path

Recommended order of operations:

1. load authoritative blob snapshot from sidecar
2. compare with draft cache timestamp from `localStorage`
3. if cache is newer, seed editor from cache and mark `needs-flush?`
4. for editor boot, prefer blob-compatible reconstruction
5. for gallery or query workloads, use normalized rows only when they are known
   to match the latest blob version
6. if normalized rows are stale or missing, fall back to blob and optionally
   trigger background reindex

## Write Path

### Tier 1 And Tier 2

Recommended save flow:

1. serialize full JSON
2. write authoritative blob snapshot
3. update manifest metadata such as `:block/updated-at`
4. update draft cache state
5. schedule optional background normalized rebuild

This keeps back navigation and periodic autosave dependent only on the stable
blob write.

### Tier 3

Recommended save flow:

1. keep draft cache updated locally
2. append patch entries for active editing
3. checkpoint to authoritative blob on interval, idle period, or explicit save
4. compact patch log after successful checkpoint
5. update normalized derived index asynchronously

## Close, Crash, And Recovery Semantics

The system should preserve these guarantees:

- if the UI shows graph saved, a valid blob checkpoint exists
- if the UI shows only draft cached, `localStorage` is allowed to be newer than
  durable sidecar state
- after crash or forced close, startup compares sidecar blob and local draft
  cache and chooses the newer valid source
- normalized rows may be missing without blocking recovery
- patch logs, if introduced later, must replay cleanly or be safely discarded
  after fallback to last valid checkpoint

## Threshold Strategy

Thresholds should not be hardcoded blindly. Start with telemetry and revise.

Suggested metrics:

- serialized blob size
- `JSON.stringify` duration
- blob write duration
- `JSON.parse` duration
- editor mount time from saved state
- background normalize duration
- patch replay duration

Suggested first-pass activation logic:

- Tier 1:
  default mode for all docs
- Tier 2:
  enable when blob size or node count crosses threshold for repeated saves
- Tier 3:
  enable only after telemetry shows Tier 2 still produces user-visible latency

## Rollout Plan

### Phase 1

- keep blob as the only authoritative save path
- keep `localStorage` as draft cache
- add metrics for size and save latency

### Phase 2

- reintroduce normalized tables as derived indexes only
- make rebuild background and retryable
- gate read-side use on blob version or hash checks

### Phase 3

- add optional patch log for very large and high-frequency documents
- add checkpoint compaction
- keep explicit fallback to last valid blob snapshot

## What To Avoid

Do not:

- make normalized row writes the only durable save path
- block back navigation on background index maintenance
- treat normalized rows as fresher than the blob without version checks
- let patch log grow without compaction
- require multiple storage representations to succeed before reporting save
  success

## Summary

The preferred long-term architecture is:

- small to medium docs:
  blob only
- large docs:
  blob plus normalized derived index
- huge and high-frequency docs:
  blob checkpoint plus patch log plus normalized derived index

The important architectural decision is not the number of layers. It is the
ownership of truth:

- blob owns correctness
- normalized rows own acceleration
- patch log owns write amortization

That separation keeps visual document persistence robust while still leaving a
clear path to scale.
