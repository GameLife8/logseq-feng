---
name: publish-page
description: How "Publish page" works in the DB-version — Cloudflare Workers + Durable Object (SQLite) + R2 + Cognito-JWT. Contrast with the old static/Nginx export. Where to hook a comment feature.
---

# Publish Page (DB graph version)

## Important: this is NOT the old static export

The non-DB / file-based Logseq shipped an **"Export public pages"** feature that produced a self-contained folder of static HTML/JS/CSS you could serve from Nginx, GitHub Pages, etc. That pipeline does NOT exist in the DB-graph build.

The current Publish feature is an **online publishing service** hosted on Cloudflare:

- Pages are **uploaded** (not exported as files) to a Cloudflare Worker.
- Worker persists metadata in a **Durable Object with embedded SQLite**.
- Payload blob (transit) + image assets go to an **R2 bucket**.
- Published URLs are served by the same Worker via **SSR** at `https://{host}/page/{graph-uuid}/{page-uuid}` (or `/p/{short-id}`).
- Only the owner (Cognito JWT `sub` match) can delete / overwrite a published page.

If the user asks "how do I export my graph and host it on my own Nginx", the honest answer is: **not supported in the DB build**. Everything goes through `logseq.io` (prod) or `dev.logseq.io` / `staging.logseq.io`.

---

## Architecture at a glance

```
┌────────────────────────────┐           ┌──────────────────────────────────────────────┐
│   Logseq client (browser)  │           │          Cloudflare Worker (edge)           │
│                            │           │                                              │
│ handler/publish.cljs       │  POST     │  routes.cljs ──> handle-post-pages          │
│   publish-page!            │ /pages    │    verify JWT (Cognito)                      │
│     → <publish-data!       │ ────────► │    dedupe on content_hash                    │
│         worker-api-fn      │           │    put transit blob → R2                     │
│         build-publish      │           │    call Durable Object (per-page + index)    │
│         -page-payload      │           │                                              │
│     → <upload-custom-      │ POST      │  assets.cljs ──> handle-post-asset          │
│         publish-assets!    │ /assets   │    verify JWT                                │
│     → <upload-asset!       │ ────────► │    put binary → R2 (checksum-dedupe)        │
│         (image variants)   │           │                                              │
│     → <post-publish!       │           │                                              │
│         (JWT Bearer)       │           │  meta_store.cljs — SQLite schema:           │
│                            │           │    pages, page_refs, page_tags, page_blocks │
│ worker/publish.cljs        │           │                                              │
│   build-publish-page-      │           │  render.cljs — transit → HTML (SSR)         │
│   payload (collects refs,  │           │    inline-ast, block rendering,             │
│   tags, datoms, blocks,    │           │    KaTeX/CodeMirror bootstrap               │
│   search content)          │           │                                              │
│                            │ GET       │  /page/:graph/:page  → handle-page-html     │
│ open published URL  ◄────  │ /page/... │  /p/:short-id       → 302 → /page/...       │
│                            │           │  /tag/:name, /ref/:name, /u/:user, /graph/… │
└────────────────────────────┘           └──────────────────────────────────────────────┘
                                         ▲                                            
                                         │  static: /static/publish.css, publish.js   
                                         │  binding: PUBLISH_META_DO (DurableObject)  
                                         │  binding: PUBLISH_R2  (R2 bucket)          
                                         │  env:     COGNITO_*, R2_*, PUBLISH_BASE    
```

---

## Main files

| File | Responsibility |
|---|---|
| [handler/publish.cljs](src/main/frontend/handler/publish.cljs) | Client orchestrator: JWT from Cognito, asset upload (image variants), POST `/pages`, DELETE `/pages/:g/:p`, mark page with `:logseq.property.publish/published-url`. |
| [worker/publish.cljs](src/main/frontend/worker/publish.cljs) | Inside the DataScript web worker: `build-publish-page-payload` — walks entities, expands embedded blocks via `:block/link`, normalizes datoms (rewrites `:block/page` for block-level publish), collects refs/tags/search content. |
| [deps/publish/src/logseq/publish/worker.cljs](deps/publish/src/logseq/publish/worker.cljs) | Cloudflare Worker entry: `worker.fetch → routes/handle-fetch`, `PublishMetaDO` Durable Object class. |
| [deps/publish/src/logseq/publish/routes.cljs](deps/publish/src/logseq/publish/routes.cljs) | All HTTP routing + per-request logic (auth, password gate, R2 read/write, DO RPC, ETag/304, CORS). |
| [deps/publish/src/logseq/publish/meta_store.cljs](deps/publish/src/logseq/publish/meta_store.cljs) | DO SQLite schema + CRUD for `pages` / `page_refs` / `page_tags` / `page_blocks`. All DO stubs share the same `do-fetch` — routes select which DO instance (per-page `graph:page` or shared `"index"`). |
| [deps/publish/src/logseq/publish/common.cljs](deps/publish/src/logseq/publish/common.cljs) | Utilities: CORS, JSON response, transit read, `parse-meta-header`, PBKDF2 `hash-password`/`verify-password`, **R2 S3v4 presign** (for transit fetch), `short-id-for-page` (SHA-256 of `graph:page`, first 10 chars base64url). |
| [deps/publish/src/logseq/publish/assets.cljs](deps/publish/src/logseq/publish/assets.cljs) | `handle-post-asset`: R2 put with checksum dedupe, content-type sniffing. |
| [deps/publish/src/logseq/publish/render.cljs](deps/publish/src/logseq/publish/render.cljs) | Hiccup-based SSR: home, graph list, page, tag, ref, password prompt, 404. Parses transit → datoms → entity map → renders blocks. |
| [deps/publish/src/logseq/publish/index.cljs](deps/publish/src/logseq/publish/index.cljs) | `page-refs-from-payload` / `page-tagged-nodes-from-payload` — flattens block graph into rows inserted into `page_refs` / `page_tags`. |
| [deps/publish/src/logseq/publish/model.cljs](deps/publish/src/logseq/publish/model.cljs) | `datoms->entities`, `entity->title` — reconstructs an entity map from the transit datom list. |
| [deps/publish/src/logseq/publish/publish.js](deps/publish/src/logseq/publish/publish.js) | Browser runtime served at `/static/publish.js`: block-toggle, theme, KaTeX render, CodeMirror mount, emoji, Tabler icon bootstrap. |
| [deps/publish/src/logseq/publish/publish.css](deps/publish/src/logseq/publish/publish.css) | Styles served at `/static/publish.css`. |
| [deps/publish/worker/wrangler.toml](deps/publish/worker/wrangler.toml) | Cloudflare config: routes, `PublishMetaDO` DO migration, R2 bucket names, Cognito env, 3 envs (dev / staging / prod at `logseq.io`). |
| [components/page_menu.cljs:21](src/main/frontend/components/page_menu.cljs:21) | `publish-page-dialog` — UI entry (password input, Publish button). |

---

## HTTP routes (Cloudflare Worker)

All served by one Worker. See `handle-fetch` in [routes.cljs:605](deps/publish/src/logseq/publish/routes.cljs:605).

| Method | Path | Purpose | Auth |
|---|---|---|---|
| `GET`  | `/` | SSR home page | none |
| `GET`  | `/static/publish.{css,js}` | Client runtime, `Cache-Control: immutable` | none |
| `GET`  | `/page/:g/:p` | SSR page HTML (reads transit from R2, renders, optional password gate) | none, page-password if set |
| `GET`  | `/p/:short-id` | 302 → `/page/:g/:p` | none |
| `GET`  | `/graph/:g[/json]` | SSR graph index / JSON list | none |
| `GET`  | `/tag/:name[/json]` | SSR by tag name (global) | none |
| `GET`  | `/ref/:name[/json]` | SSR by backlink target name | none |
| `GET`  | `/u/:username` | Pages owned by user | none |
| `GET`  | `/asset/:g/:file` | Streams image/video bytes from R2 | none |
| `GET`  | `/pages` | List all published pages (JSON) | none |
| `GET`  | `/pages/:g[/:p[/transit\|refs\|tagged_nodes]]` | Metadata / signed transit URL | page-password if set |
| `GET`  | `/search/:g?q=` | LIKE-search title + block_content | none, password-protected pages excluded |
| `POST` | `/pages` | **Publish / overwrite** a page | Cognito Bearer |
| `POST` | `/assets` | Upload an image / file to R2 | Cognito Bearer |
| `DELETE` | `/pages/:g/:p` | Unpublish a page | Cognito Bearer; `owner_sub` must match `claims.sub` |
| `DELETE` | `/pages/:g` | Drop a whole graph | same owner check |
| `OPTIONS` | `*` | CORS preflight | — |

---

## Durable Object schema

One DO class — `PublishMetaDO` — but two logical usages:

- **Per-page DO** — id = `idFromName("{graph}:{page}")`. Stores the metadata row for that page.
- **Shared index DO** — id = `idFromName("index")`. Stores `page_refs`, `page_tags`, `page_blocks` across the whole service + handles cross-graph listing / search.

Both DOs run the same `do-fetch` (see [meta_store.cljs:108](deps/publish/src/logseq/publish/meta_store.cljs:108)) and share the same SQLite schema (each DO has its own private SQLite file):

```sql
CREATE TABLE pages (
  page_uuid TEXT NOT NULL,
  page_title TEXT,
  page_tags TEXT,                -- JSON array string
  graph_uuid TEXT NOT NULL,
  schema_version TEXT,
  block_count INTEGER,
  content_hash TEXT NOT NULL,    -- SHA-256 of transit body, used as R2 key + ETag
  content_length INTEGER,
  r2_key TEXT NOT NULL,          -- "publish/{graph}/{hash}.transit"
  owner_sub TEXT,                -- Cognito user sub — auth owner
  owner_username TEXT,
  created_at INTEGER,
  updated_at INTEGER,
  short_id TEXT,                 -- 10-char base64url of sha256(graph:page)
  password_hash TEXT,            -- pbkdf2$sha256$90000$salt$digest, nullable
  PRIMARY KEY (graph_uuid, page_uuid)
);

CREATE TABLE page_refs (
  graph_uuid, target_page_uuid, target_page_title, target_page_name,
  source_page_uuid, source_page_title, source_block_uuid,
  source_block_content, source_block_format, updated_at,
  PRIMARY KEY (graph_uuid, target_page_uuid, source_block_uuid)
);

CREATE TABLE page_tags (
  graph_uuid, tag_page_uuid, tag_title, source_page_uuid, source_page_title,
  source_block_uuid, source_block_content, source_block_format, updated_at,
  PRIMARY KEY (graph_uuid, tag_page_uuid, source_block_uuid)
);

CREATE TABLE page_blocks (       -- search index
  graph_uuid, page_uuid, block_uuid, block_content, updated_at,
  PRIMARY KEY (graph_uuid, block_uuid)
);
```

Schema migrations are imperative in `init-schema!` — it runs on every DO request (no-op after the first), drops the old `pages` table if legacy columns `page_id`/`graph` are detected, and additively adds columns via `ALTER TABLE`.

---

## Publish payload flow (POST `/pages`)

1. **Client** — `publish-page!` ([handler/publish.cljs](src/main/frontend/handler/publish.cljs)):
   - Calls worker RPC `:thread-api/build-publish-page-payload` → returns `{:payload <clj> :meta {:content_hash ...} :asset-plans ...}`.
   - Encodes payload to **transit+json** string, computes SHA-256 content hash.
   - Uploads custom `publish.css` / `publish.js` (per graph) via `<upload-custom-publish-assets!`.
   - Uploads each referenced image as a **raw** + two **variants** (`1024`, `1600` long edge) — all checksum-dedupe'd.
   - POSTs transit body to `/pages` with `Authorization: Bearer <Cognito JWT>`, `x-publish-meta: <JSON>`, optional `page-password` in the transit payload.
2. **Worker** — `handle-post-pages` ([routes.cljs:59](deps/publish/src/logseq/publish/routes.cljs:59)):
   - `authorization/verify-jwt` (Cognito JWKS, pool `us-east-1_dtagLnju8`).
   - Parses meta from `x-publish-meta` header (falls back to transit body's `:meta` key).
   - Validates `content_hash`, `graph`, `page_uuid`.
   - `PUBLISH_R2.head "publish/{g}/{hash}.transit"` — skips put if blob already exists.
   - If `page-password` present → `hash-password` (PBKDF2-SHA256, 90 000 iter, 16-byte salt, base64url-packed).
   - RPC to **per-page DO** (POST) — upserts `pages` row, replaces `page_blocks` for this page.
   - RPC to **index DO** (POST) — replaces `page_refs`/`page_tags` for this page.
   - Returns `{page_uuid, graph_uuid, r2_key, short_id, short_url: "/p/:short-id", updated_at}`.
3. **Client** — marks the page entity with `:logseq.property.publish/published-url` so the UI can show "Published" status.

---

## Read / view flow (GET `/page/:g/:p`)

`handle-page-html` ([routes.cljs:518](deps/publish/src/logseq/publish/routes.cljs:518)):

1. Look up metadata in per-page DO. If no row, fall back to `"index"` DO `page_tags` — if the UUID matches a tag, render tag-aggregation page.
2. `check-page-password` — PBKDF2 verify against `password_hash` (header `x-publish-password` or `?password=`).
3. ETag check (`content_hash`) → 304.
4. Fetch transit blob from R2.
5. Parallel fetch refs + tagged_nodes from index DO.
6. `render-page-html transit page-uuid refs tagged-nodes` → Hiccup → HTML string.
7. Response headers: `cache-control: public, max-age=300, must-revalidate`, `etag`, full CORS.

---

## Auth model

- **Who can publish/delete** — any signed-in Cognito user (AWS Cognito pool `us-east-1_dtagLnju8`). JWT verified with JWKS.
- **Who owns a page** — `owner_sub` = `claims.sub` from the first POST. Subsequent POSTs to the same `(graph, page)` overwrite metadata but the owner field stays pinned (currently UPSERT overwrites it — ⚠️ see pitfalls).
- **Who can delete** — only requests where `claims.sub == owner_sub` pass ([routes.cljs:466](deps/publish/src/logseq/publish/routes.cljs:466)).
- **Who can read** — the world, unless the page has a `password_hash`, in which case the HTML page renders a password prompt ([render-password-html](deps/publish/src/logseq/publish/render.cljs)) and API endpoints return 401.
- **Asset access** — R2 assets at `/asset/:g/:file` are publicly readable (no auth, long `immutable` cache). Signed URLs (S3v4 presign, 5-minute TTL) are only used by the `/pages/:g/:p/transit` endpoint for direct R2 fetches.

---

## Client UI entry

[components/page_menu.cljs:21](src/main/frontend/components/page_menu.cljs:21) — `publish-page-dialog` with optional password input.
The page menu item is gated by `(not config/publishing?)` — i.e. hidden **inside** the published site itself.

---

## What the old static export did (for reference)

- Bundled the whole graph into a single HTML file with embedded JS, or into a folder of HTML/CSS/JS.
- You could drop it on any static host (Nginx, GitHub Pages, Netlify).
- No server, no auth, no per-page control, no search index, no backlinks aggregation — everything was client-rendered from a giant JSON.

**The DB build has none of that.** Publish now requires Logseq's Cloudflare account.

---

## Known pitfalls

1. **No self-host story.** All routes are hard-wired in `wrangler.toml` to `publish.logseq.io` / `dev.logseq.io` / `staging.logseq.io`. You can't point the client at your own worker without code changes + your own Cognito pool.
2. **`owner_sub` is UPSERT-overwritten** on every POST in [meta_store.cljs:143](deps/publish/src/logseq/publish/meta_store.cljs:143) — `ON CONFLICT ... DO UPDATE SET owner_sub=excluded.owner_sub`. Combined with the pre-UPSERT check in `handle-delete-page`, this is fine for delete-auth, but any signed-in user who knows a `(graph, page)` pair can in theory re-POST and take ownership. The POST path has no owner check. **Treat this as a known edge case** when planning features that rely on `owner_sub`.
3. **Same DO class, two roles.** Every `(graph:page)` DO runs `init-schema!` and ends up with empty `page_refs`/`page_tags`/`page_blocks` tables. Those tables are only actually populated inside the `"index"` DO. Not a bug, but surprising.
4. **Short IDs aren't dedupe-safe.** `short-id-for-page` is the first 10 chars of base64url SHA-256 — collisions are astronomically unlikely but not guaranteed. `/short/:id` returns the first match.
5. **Transit fallback.** `read-transit-safe` tries `ldb/read-transit-str` first, then a fallback reader that coerces `datascript/Entity` → `identity`, `error` → `ex-info` — so the Worker can still read payloads produced by older clients.
6. **Hiccup SSR is Hiccups-runtime**, not reagent. The `render.cljs` namespace is huge (1600 lines) and has its own mldoc/inline AST handling. Touching block rendering requires understanding `gp-mldoc` + the block hierarchy in `model.cljs`.
7. **`publish.js` fetches from esm.sh on every page load.** No local bundle; KaTeX, CodeMirror, emoji-mart all load from esm.sh CDN at render time.

---

## If you're touching the publish module

- Client payload shape changed? Bump `schema_version` in `worker/publish.cljs` and handle the old version in `render.cljs` / `model.cljs`.
- DO schema changed? Add an `ALTER TABLE` branch inside `init-schema!` (don't rely on `CREATE TABLE IF NOT EXISTS` alone — existing DOs won't re-run it).
- R2 layout: transit blobs live at `publish/{graph}/{content-hash}.transit`; assets at `publish/assets/{graph}/{uuid}.{ext}`. Reuse the content-hash filename — it's the dedupe key AND the ETag.
- When adding a new route, remember to add it to the `cond` in `handle-fetch` AND the CORS `allow-methods` list if it's not GET/POST/DELETE.
- Any `fetch` to a DO uses the fake `https://publish/...` URL — it's not a real URL, it's just the DO protocol.

---

## Quick sanity checks

```
deps/publish/worker/scripts/dev_test.sh      # hits local wrangler dev
deps/publish/worker/scripts/clear_dev_state.sh  # wipes dev DO + R2
```

`wrangler dev` runs against the `dev` environment; `wrangler deploy --env staging` or `--env prod` ships to staging/prod.
