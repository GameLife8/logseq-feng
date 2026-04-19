# logseq-feng HTTP API Reference

> A self-contained reference for **external AI models**, automation scripts, and third-party integrations that want to talk to a running logseq-feng graph over HTTP / MCP.
>
> Everything a caller needs — endpoints, auth, payload shapes, visual-doc formats, SSE events, error semantics, and examples — is in this file. No prior familiarity with the Logseq internals is required.

---

## 1. Overview

logseq-feng embeds a local HTTP server in the Electron **main process**. It exposes three surfaces:

| Surface | Path prefix | Transport | Intended caller |
|---|---|---|---|
| Classic JSON-RPC-lite bridge | `POST /api` | JSON body | any HTTP client |
| Versioned REST | `/api/v1/*` | GET/POST/PUT + JSON | any HTTP client |
| Model Context Protocol | `/mcp` | Streamable HTTP (MCP) | MCP-aware AI clients |

All three share the same Bearer-token auth and the same underlying IPC bridge into the Renderer process, where the DataScript graph and the visual-doc sidecar (SQLite) live.

```
 External caller
      │ HTTP + Bearer token
      ▼
 Electron main (Fastify)  ──►  Renderer (DataScript + Worker SQLite)
```

**Defaults**

| Setting | Default | Override via |
|---|---|---|
| Host | `127.0.0.1` | Settings → HTTP API server |
| Port | `12315` | Settings → HTTP API server |
| Auth | Bearer tokens list | Settings → Authorization tokens |
| Autostart | off | Settings → HTTP API server |
| MCP route | off | Settings → HTTP API server |

All examples below assume `BASE=http://127.0.0.1:12315` and `TOKEN=<your-bearer-token>`.

---

## 2. Authentication

Every route except `GET /` requires:

```
Authorization: Bearer <token>
```

- Tokens are managed in the app via **Settings → HTTP API server → Authorization tokens**.
- Comparison is plain string equality against every configured token's raw string or `:value` field.
- Failure returns `HTTP 401` with an error body.
- CORS is `*`, but DNS-rebinding protection is enabled on `/mcp`. The loopback default keeps the surface off the network.

Quick health-check:

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/events?limit=1"
```

---

## 3. Endpoint Catalog

### 3.1 `GET /`

Serves the browser-readable documentation page (`api_server.html`). No auth. Not intended for programmatic callers.

### 3.2 `POST /api` — JSON-RPC bridge

Legacy bridge that accepts any registered `logseq.*` method.

**Body**

```json
{ "method": "logseq.db.q", "args": ["(page-tags)"] }
```

**Method name rules**

- If `method` starts with `logseq.`, it's split on `.`. The second segment is the namespace (lower-cased), the last segment becomes the function name (auto-converted kebab→snake case).
  - `"logseq.db.q"` → `db@q`
  - `"logseq.cli.createWhiteboard"` → `cli@create_whiteboard`
- Any other value is passed through after trimming.

**Namespace whitelist**: `app`, `editor`, `db`, `cli`. Other namespaces are rejected.

**Response**

- `2xx` with JSON body on success.
- `500` with the body `{"error": "..."}` if the Renderer threw.

**Example**

```bash
curl -s -X POST "$BASE/api" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"method":"logseq.db.q","args":["(page-tags)"]}'
```

### 3.3 `/api/v1/*` — REST routes

All routes require Bearer auth. Responses are always JSON; bodies with an `error` key become `HTTP 500`.

| Method | Path | Backing method | Notes |
|---|---|---|---|
| GET  | `/api/v1/pages` | `cli@list_pages` | `?expand=true` for detail |
| GET  | `/api/v1/pages/:name` | `cli@get_page_data` | `:name` may be page title or uuid |
| GET  | `/api/v1/blocks/:uuid` | `editor@get_block` | `?includePage=true` adds owning page |
| GET  | `/api/v1/blocks/:uuid/tree` | `editor@get_block` | Always full nested children + page |
| POST | `/api/v1/upsert` | `cli@upsert_nodes` | Body `{operations, dryRun}`. Sole batch write path for blocks/pages/tags/properties |
| POST | `/api/v1/whiteboards` | `cli@create_whiteboard` | Body `{name}` → `{pageUuid, title, docType}` |
| GET  | `/api/v1/whiteboards/:uuid` | `cli@get_visual_doc` | Returns Excalidraw scene JSON |
| PUT  | `/api/v1/whiteboards/:uuid` | `cli@update_visual_doc` | Body `{json}` — **full overwrite** |
| POST | `/api/v1/sheets` | `cli@create_sheet` | Body `{name}` → `{pageUuid, title, docType}` |
| GET  | `/api/v1/sheets/:uuid` | `cli@get_visual_doc` | Returns Univer workbook JSON |
| PUT  | `/api/v1/sheets/:uuid` | `cli@update_visual_doc` | Body `{json}` — **full overwrite** |
| POST | `/api/v1/mind-maps` | `cli@create_mind_map` | Body `{name}` → `{pageUuid, title, docType}` |
| GET  | `/api/v1/mind-maps/:uuid` | `cli@get_visual_doc` | Returns mind-map tree JSON |
| PUT  | `/api/v1/mind-maps/:uuid` | `cli@update_visual_doc` | Body `{json}` — **full overwrite** |
| GET  | `/api/v1/events` | *(in-process)* | Snapshot of last ≤ 200 request events. `?limit=N` caps the slice |
| GET  | `/api/v1/events/stream` | *(SSE)* | `text/event-stream`. Emits `hello` once, `activity` per request, `:keepalive` every 20 s |

**`PUT` on a visual-doc is always a full overwrite of the scene JSON**, never a patch. Fetch → mutate → write the full result.

### 3.4 `/mcp` — Model Context Protocol

Enabled only when **Settings → Enable MCP** is on. Uses `StreamableHTTPServerTransport` from `@modelcontextprotocol/sdk`:

- `POST /mcp` — initialize or dispatch into an existing session (`mcp-session-id` header).
- `GET /mcp` — stream events for a session.
- `DELETE /mcp` — tear down a session.

Clients should use an MCP-aware SDK rather than hand-rolling the handshake. See §5 for the tool catalog the server registers on connect.

---

## 4. REST Bodies & Response Shapes

### 4.1 `POST /api/v1/upsert`

The **only** batch write path for DataScript entities (blocks, pages, tags, properties). Called at most once per logical user request.

**Request**

```json
{
  "operations": [
    {
      "operation": "add",
      "entityType": "page",
      "id": "temp-inbox",
      "data": { "title": "Inbox" }
    },
    {
      "operation": "add",
      "entityType": "block",
      "data": {
        "page-id": "temp-inbox",
        "title": "First todo"
      }
    }
  ],
  "dryRun": false
}
```

| Field | Required | Notes |
|---|---|---|
| `operation` | yes | `"add"` or `"edit"` |
| `entityType` | yes | `"block"`, `"page"`, `"tag"`, `"property"` |
| `id` | `edit`: required string uuid. `add`: optional temp string to reference later operations | |
| `data` | yes | Map with any of `title`, `page-id`, `tags`, `property-type`, `property-cardinality`, `property-classes`, `class-extends`, `class-properties` |
| `dryRun` | optional | `true` runs validation only, no commit |

Tips:
- Before creating a page/tag/property, check existence via `GET /api/v1/pages/:name` or MCP `listTags` / `listProperties`.
- Use temp string ids to chain dependent adds in one call.

### 4.2 Create visual-doc (`POST /api/v1/{whiteboards,sheets,mind-maps}`)

**Request**

```json
{ "name": "Project canvas" }
```

**Response**

```json
{ "pageUuid": "…uuid…", "title": "Project canvas", "docType": "whiteboard" }
```

On duplicate title (case-insensitive) the request fails with an error body.

### 4.3 Fetch visual-doc (`GET /api/v1/{whiteboards,sheets,mind-maps}/:uuid`)

**Response**

```json
{
  "pageUuid": "…",
  "docType": "whiteboard",
  "source": "db",
  "json": "{\"elements\":[...],\"appState\":{...},\"files\":{...}}",
  "needsFlush": false
}
```

- `json` is a **string**, not a parsed object. Parse before consumption.
- `source` is `"db"` if the durable sidecar has the authoritative copy, `"cache"` if the in-memory draft is newer.
- `needsFlush` indicates the draft has pending writes.

### 4.4 Update visual-doc (`PUT /api/v1/{whiteboards,sheets,mind-maps}/:uuid`)

**Request**

```json
{ "json": "{\"elements\":[], \"appState\":{}, \"files\":{}}" }
```

**Response**

```json
{
  "pageUuid": "…",
  "docType": "whiteboard",
  "updatedAt": 1713456789000,
  "writeToken": "…",
  "manifestStatus": "ok"
}
```

The `json` must be a complete payload — there is no patch semantics. Fetch first, mutate, write back.

---

## 5. MCP Tool Catalog

The MCP server registers these tools on `/mcp` connect. Each description below is what AI clients will see. Schemas are `zod/v3`.

### 5.1 Read tools

| Tool | Purpose | Key inputs |
|---|---|---|
| `listPages` | Enumerate pages | `expand?: boolean` |
| `getPage` | Fetch a page + its blocks (tag/property pages are also pages) | `pageName: string` |
| `getBlock` | Fetch one block (shallow child refs, optional owning page) | `uuid: string`, `includePage?: boolean` |
| `getBlockTree` | Fetch a block + full nested children subtree | `uuid: string` |
| `searchBlocks` | Substring search over block content | `searchTerm: string` |
| `listTags` | All tags | `expand?: boolean` |
| `listProperties` | All properties | `expand?: boolean` |

### 5.2 Write tool

| Tool | Purpose | Notes |
|---|---|---|
| `upsertNodes` | Batch create/edit blocks/pages/tags/properties | **Call at most once per user request.** Single write channel — never add sibling create tools. |

`upsertNodes` accepts the same `operations` array shape as `POST /api/v1/upsert`.

### 5.3 Visual-doc tools

`upsertNodes` **cannot** create or mutate whiteboard/sheet/mind-map payloads — their data lives in a worker-owned SQLite sidecar, not in DataScript. Use these tools instead:

| Tool | Purpose | Key inputs |
|---|---|---|
| `createWhiteboard` | Create an Excalidraw-backed page | `name: string` |
| `createSheet` | Create a Univer-workbook-backed page | `name: string` |
| `createMindMap` | Create a tree-backed mind-map page | `name: string` |
| `getVisualDoc` | Fetch the JSON payload of a visual-doc page | `pageUuid: string`, `docType: "whiteboard"|"sheet"|"mind-map"` |
| `updateVisualDoc` | Overwrite a visual-doc's JSON and flush to sidecar | `pageUuid`, `docType`, `json: string` (complete payload) |

A newly created visual-doc has a minimal empty scene. Use `updateVisualDoc` to set real content.

---

## 6. Visual-Doc Payload Formats

All payloads are JSON **strings** (the `json` field in requests / responses). Parse/serialize on your side.

### 6.1 Whiteboard — Excalidraw scene

```json
{
  "elements": [ /* Excalidraw element objects */ ],
  "appState": { "viewBackgroundColor": "#ffffff" },
  "files":    { /* image binary refs */ }
}
```

Minimum valid scene:
```json
{ "elements": [], "appState": { "viewBackgroundColor": "#ffffff" }, "files": {} }
```

### 6.2 Sheet — Univer workbook

```json
{
  "id": "workbook_1",
  "name": "Sheet",
  "sheetOrder": ["sheet-1"],
  "sheets": {
    "sheet-1": { /* Univer sheet object: cellData, rowCount, columnCount, ... */ }
  }
}
```

Start a fresh sheet by fetching the auto-seeded default with `getVisualDoc` first, then mutate.

### 6.3 Mind map — tree

```json
{
  "data":     { "text": "root" },
  "children": [
    { "data": { "text": "child 1" }, "children": [] },
    { "data": { "text": "child 2" }, "children": [] }
  ]
}
```

---

## 7. Activity Event Stream (SSE)

### 7.1 Snapshot — `GET /api/v1/events`

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/events?limit=50"
```

Returns a JSON array of the most recent request events (up to 200 retained in memory). Each event:

```json
{
  "id": 42,
  "ts": "2026-04-19T02:11:34.123Z",
  "kind": "rest",
  "method": "cli@list_pages",
  "route": "/api/v1/pages",
  "status": "ok",
  "duration-ms": 17,
  "args-summary": "[{\"expand\":true}]"
}
```

- `kind` ∈ `"rest"` | `"api"` (legacy `/api`) | `"mcp"`.
- `status` is `"ok"` or `"error"`; error entries also include `"error": "message"`.

### 7.2 Live stream — `GET /api/v1/events/stream`

`text/event-stream`. Emits:

- `event: hello` once on connect — includes the event id of the last known entry.
- `event: activity` per subsequent request.
- A `:keepalive` comment every 20 s so proxies don't idle-disconnect.

**Example consumer**

```bash
curl -N -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/events/stream"
```

**Event shape**

```
event: activity
id: 43
data: {"id":43,"ts":"2026-04-19T02:11:34.500Z","kind":"rest","method":"editor@get_block","route":"/api/v1/blocks/:uuid","status":"ok","duration-ms":9}
```

Use SSE event ids with `Last-Event-ID` on reconnect for gap detection.

---

## 8. Error Semantics

| Surface | Shape |
|---|---|
| HTTP 401 | Missing/wrong Bearer token |
| HTTP 500 + `{"error": "..."}` | Renderer threw, or `/api` handler returned a body with an `error` key |
| HTTP 500 + message text | Fastify-level failure (schema parse, timeout, etc.) |
| `2xx` with `{"error": "..."}` | Only possible on `/api` legacy bridge in rare paths — callers should **still** check `error` on 2xx bodies |
| MCP JSON-RPC error `code -32000` | "Bad Request: No valid session ID provided" on misrouted `/mcp` |

Typical call pattern:

```bash
resp=$(curl -s -w '\n%{http_code}' ...)
body=$(echo "$resp" | head -n -1)
code=$(echo "$resp" | tail -n 1)
[[ "$code" == "200" ]] || { echo "HTTP $code: $body"; exit 1; }
echo "$body" | jq -e '.error // empty' >/dev/null && { echo "error body: $body"; exit 1; }
```

---

## 9. Worked Examples

### 9.1 Create a page and add two blocks in one call

```bash
curl -s -X POST "$BASE/api/v1/upsert" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "operations": [
      {"operation":"add","entityType":"page","id":"tmp-inbox",
       "data":{"title":"Inbox"}},
      {"operation":"add","entityType":"block",
       "data":{"page-id":"tmp-inbox","title":"First task"}},
      {"operation":"add","entityType":"block",
       "data":{"page-id":"tmp-inbox","title":"Second task"}}
    ]
  }'
```

### 9.2 Create a whiteboard and seed an Excalidraw scene

```bash
# 1. Create the page
resp=$(curl -s -X POST "$BASE/api/v1/whiteboards" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Architecture diagram"}')
uuid=$(echo "$resp" | jq -r .pageUuid)

# 2. Write a scene
curl -s -X PUT "$BASE/api/v1/whiteboards/$uuid" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"json":"{\"elements\":[{\"type\":\"rectangle\",\"x\":100,\"y\":100,\"width\":200,\"height\":80,\"strokeColor\":\"#000\"}],\"appState\":{\"viewBackgroundColor\":\"#fff\"},\"files\":{}}"}'
```

### 9.3 Read a block's full subtree

```bash
uuid="119268a6-704f-4e9e-8c34-36dfc6133729"
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/blocks/$uuid/tree"
```

### 9.4 Live-tail API activity

```bash
curl -N -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/events/stream" \
  | grep --line-buffered '^data: '
```

### 9.5 Update a mind-map

```bash
# Fetch current payload
uuid="..."
curr=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/mind-maps/$uuid")
old_json=$(echo "$curr" | jq -r .json)

# Mutate (add a child), then write back
new_json=$(echo "$old_json" | jq '.children += [{"data":{"text":"new idea"},"children":[]}]' -c)
curl -s -X PUT "$BASE/api/v1/mind-maps/$uuid" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"json\":$(jq -Rn --arg j "$new_json" '$j')}"
```

---

## 10. Conventions & Pitfalls

- **Visual-doc creates cannot be done via `upsertNodes`** — the sidecar payload is outside DataScript. Use the `create*` REST routes / MCP tools.
- **`PUT` on visual-docs is always a full overwrite.** There is no patch — fetch first, mutate, write back the complete payload.
- **`upsertNodes` is a single write channel.** Never chain multiple calls to implement what could be one batch; MCP clients should limit it to once per user turn.
- **Namespace whitelist.** `/api` only accepts `app`, `editor`, `db`, `cli`. Other namespaces return `MethodNotExist: …`.
- **Block method name is `editor@get_block`.** Not `block@get_block` — there is no `block` namespace.
- **Bearer comparison is plain string equality.** Tokens travel unencrypted on loopback; bind `0.0.0.0` at your own timing-attack risk.
- **Visual-doc duplicate names are rejected case-insensitively** by each create tool. Rename or pick a new title.
- **`json` fields everywhere are strings**, not parsed objects. Serialize/parse on your side.
- **Restart is needed when toggling MCP.** The `/mcp` route is registered at server start.
- **IPC reply is one-shot.** If the Renderer crashes while a request is in flight, the request eventually times out at Fastify's 42 s `requestTimeout`.

---

## 11. Listing the Visual-Doc Pages

The three visual-doc kinds are modeled as ordinary pages tagged with a hidden user-class:

| Kind | Class entity title |
|---|---|
| Whiteboard | `Whiteboard` |
| Sheet | `Sheet` |
| Mind map | `MindMap` |

To find them via HTTP:

```bash
# Using upsertNodes' getter cousins is not available; use listPages then filter.
curl -s -H "Authorization: Bearer $TOKEN" \
  "$BASE/api/v1/pages?expand=true" \
  | jq '.[] | select(.tags[]? | .title == "Whiteboard")'
```

MCP clients can do the same with `listPages` + client-side filter, or use `searchBlocks` for text within them.

---

## 12. Related References

Internal engineering notes (in-repo):

- `.claude/skills/http-api-server.md` — server internals, IPC, main-process code paths
- `.claude/skills/excalidraw-whiteboard.md` — whiteboard data model & sidecar details
- `.claude/skills/univer-sheet.md` — sheet data model
- `.claude/skills/mind-map.md` — mind-map data model
- `.claude/skills/storage-model.md` — shared visual-doc sidecar schema
- `.claude/skills/tag-manager.md` — tag classification (used by visual-doc class tags)

External:

- Model Context Protocol spec — <https://modelcontextprotocol.io/>
- Excalidraw scene schema — <https://github.com/excalidraw/excalidraw/blob/master/src/data/types.ts>
- Univer workbook schema — <https://univer.ai/>

---

## 13. Version Notes

- API v1 was introduced alongside the activity event bus and SSE stream.
- The whiteboard class model was recently aligned with sheets and mind maps: whiteboards now use a hidden user-defined `Whiteboard` class tag (same pattern as `Sheet` / `MindMap`), replacing the built-in `:logseq.class/Whiteboard`. **This change is transparent to HTTP / MCP callers** — all tool signatures, REST bodies, and response shapes are unchanged. The only visible consequence is that `listPages` filters should match by tag **title** (`"Whiteboard"`), not by a specific built-in ident.
