# http-api-server

## When To Use

Use this note when working on the local HTTP API server, MCP endpoint, external AI/automation integration, Bearer token auth, or when exposing new Logseq capabilities to outside clients.

## TL;DR

Logseq-feng already ships a local HTTP server. It is **Electron-only** (main process). It bridges external HTTP/MCP clients to the Renderer (where DataScript lives) via Electron IPC.

```
┌── External client (AI / curl / MCP client) ──┐
│       http://127.0.0.1:12315/{api,mcp}        │
└─────────────────────┬─────────────────────────┘
                      │ Bearer <token>
                      ▼
┌── Electron main process (Fastify) ───────────┐
│  src/electron/electron/server.cljs            │
│   - /        → api_server.html (docs, no auth)│
│   - /api     → JSON-RPC style bridge          │
│   - /mcp     → Streamable HTTP MCP transport  │
└─────────────────────┬─────────────────────────┘
                      │ send-to-renderer :invokeLogseqAPI
                      │ ipcMain.handleOnce (::sync! <sid>)
                      ▼
┌── Renderer process ──────────────────────────┐
│  logseq.* API surface (logseq.api.db, ...)    │
│  DataScript, worker SQLite sidecars           │
└───────────────────────────────────────────────┘
```

## Running In Development

The server lives in the **Electron main** compile target. Pure `shadow-cljs watch app` + browser cannot run it.

| Dev mode | Server available? |
|---|---|
| `yarn watch` + browser only | No |
| `yarn watch` + Electron main watch + Electron shell | Yes |
| Packaged `.exe` / `.dmg` | Yes |

To test REST/MCP end-to-end:
1. Run the renderer build (`yarn watch` or project equivalent).
2. Run the Electron main build that compiles `src/electron/**`.
3. Launch Electron.
4. Open Settings → "HTTP API server", add a token, optionally enable MCP, start the server.
5. Default listen: `127.0.0.1:12315` (overridable via `:server/host` / `:server/port`).

MCP tool handlers in `deps/cli/src/logseq/cli/common/mcp/server.cljs` are plain ClojureScript and can be unit-tested without Electron.

## Config Keys

Stored through `electron.configs/set-item!`:

| Key | Default | Notes |
|---|---|---|
| `:server/host` | `127.0.0.1` | Bind host |
| `:server/port` | `12315` | Bind port |
| `:server/tokens` | `nil` | Vector of `{:name :value}` or plain strings |
| `:server/autostart` | `nil` | Start on app boot |
| `:server/mcp-enabled?` | `nil` | Register `/mcp` route on start |

State atom `electron.server/*state` is mirrored to Renderer via `utils/send-to-renderer :syncAPIServerState`.

## Routes

### `GET /`

Serves `docs/api_server.html` with `${HOST}` / `${PORT}` template replacement. Bypasses auth (see `api-pre-handler!`).

### `POST /api`

JSON-RPC-lite bridge. Body:
```json
{ "method": "logseq.db.q", "args": ["(page-tags)"] }
```

Method resolution (`resolve-real-api-method`):
- Strings starting with `"logseq."` are split on `.` — the second segment is the API namespace (lowercased), the last segment becomes the method. Example: `"logseq.db.q"` → `db@q` in snake case.
- Other method names pass through after `trim`.

Invocation flow:
1. `invoke-logseq-api!` generates a monotonic sync id (`*cid`).
2. Sends `:invokeLogseqAPI {:syncId :method :args}` to the main window.
3. Waits for one-shot reply on `::sync!<sid>` via `ipcMain.handleOnce`.
4. Response body with an `:error` field produces HTTP 500 plus console error.

### `/api/v1/*` — versioned REST routes

Registered in `register-v1-routes!` (same Fastify instance, same Bearer pre-handler). All return JSON; error bodies with an `:error` key become HTTP 500 via `send-rest-result!`.

| Route | Method invoked | Notes |
|---|---|---|
| `GET  /api/v1/pages` | `cli@list_pages` | Query `?expand=true` toggles detail |
| `GET  /api/v1/pages/:name` | `cli@get_page_data` | `:name` is page name or uuid |
| `GET  /api/v1/blocks/:uuid` | `block@get_block` | Query `?includePage=true` |
| `GET  /api/v1/blocks/:uuid/tree` | `block@get_block` | Always includes page + full child subtree |
| `POST /api/v1/upsert` | `cli@upsert_nodes` | Body `{operations, dryRun}` — sole REST write path |

Implementation detail: these routes invoke pre-resolved snake-case methods (e.g. `"cli@list_pages"`) directly rather than going through `resolve-real-api-method`. If you add new v1 handlers, stay on this shorter form to avoid redundant string parsing.

### `POST /mcp` / `GET /mcp` / `DELETE /mcp`

Registered only when `:server/mcp-enabled?` is truthy. Implementation in `deps/cli/src/logseq/cli/common/mcp/server.cljs`:
- Uses `@modelcontextprotocol/sdk/server/mcp.js` + `StreamableHTTPServerTransport`.
- `enableDnsRebindingProtection: true`, `allowedHosts: ["<host>:<port>"]`.
- POST initiates a session or delegates to an existing session (via `mcp-session-id` header).
- GET streams events for the session; DELETE tears it down.

The MCP server is fed an `api-fn` that wraps `invoke-logseq-api!` with `resolve-real-api-method`.

## Authentication

`api-pre-handler!` runs before every route:
- URL `"/"` skips the token check.
- All other routes require `Authorization: Bearer <token>`.
- `validate-auth-token` compares the stripped token against every `:server/tokens` entry, matching either the raw string or the `:value` field.
- Failures return HTTP 401 with the raw `js/Error`.

## Registered MCP Tools (schema v0.1)

Defined in `deps/cli/src/logseq/cli/common/mcp/server.cljs` under `api-tools`:

| Tool | API method invoked | Purpose |
|---|---|---|
| `listPages` | `logseq.cli.listPages` | Enumerate pages |
| `getPage` | `logseq.cli.getPageData` | Fetch page + blocks |
| `getBlock` | `logseq.block.getBlock` | Fetch one block (shallow children refs, optional owning page) |
| `getBlockTree` | `logseq.block.getBlock` | Fetch a block with full nested children subtree |
| `searchBlocks` | `logseq.app.search` | Substring search |
| `listTags` | `logseq.cli.listTags` | All tags |
| `listProperties` | `logseq.cli.listProperties` | All properties |
| `upsertNodes` | `logseq.cli.upsertNodes` | Batch create/edit blocks/pages/tags/properties — single write channel |
| `createWhiteboard` | `logseq.cli.createWhiteboard` | Create a whiteboard page (sidecar-backed; `upsertNodes` can't do this) |
| `createSheet` | `logseq.cli.createSheet` | Create a sheet page (sidecar-backed) |
| `createMindMap` | `logseq.cli.createMindMap` | Create a mind-map page (sidecar-backed) |
| `getVisualDoc` | `logseq.cli.getVisualDoc` | Read whiteboard/sheet/mind-map JSON payload from sidecar |
| `updateVisualDoc` | `logseq.cli.updateVisualDoc` | Overwrite whiteboard/sheet/mind-map JSON and flush to sidecar |

### Write-path convention

`upsertNodes` is the **only** batch write channel and the MCP description pins it to "at most once per user request". Do not add sibling write-path tools (`createPage`, `createBlock`, etc.) that would split the write surface; model new write flows as additional `entityType` values or data keys inside `upsertNodes`.

`upsertNodes` is the one write-path tool. It expects a `:operations` array of `{:operation :entityType :id :data}` objects. Temporary string ids link newly-created entities to their dependents within a single call.

CORS: `origin: "*"` via `@fastify/cors`. The DNS rebinding protection on the MCP transport is the main network-level safeguard alongside the bearer token.

## Adding A New MCP Tool

1. Add an `api-<name>` handler in `deps/cli/src/logseq/cli/common/mcp/server.cljs` that calls `call-api-fn` with a `"logseq.*"` method plus already-JS args.
2. Register in the `api-tools` map: `:<toolKey> {:fn api-<name> :config #js {:title :description :inputSchema}}`.
3. Use `zod/v3` (NOT v4 — see the header comment in `mcp/server.cljs`) for the `inputSchema`.
4. Ensure the backing `logseq.*` method exists in `src/main/logseq/api/*` or in the Renderer's API registration; otherwise `resolve-real-api-method` returns a string the Renderer cannot dispatch.

Example (conceptual, not committed):
```clojure
(defn- api-get-block
  [call-api-fn args]
  (call-api-fn "logseq.editor.getBlock" [(aget args "uuid")]))

;; in api-tools
:getBlock
{:fn api-get-block
 :config #js {:title "Get Block"
              :description "Fetch a single block by uuid"
              :inputSchema #js {:uuid (-> (z/string) (.describe "Block uuid"))}}}
```

## Adding A New REST Route

Prefer a versioned `/api/v1/*` prefix so the legacy `POST /api` dispatcher stays untouched.

1. Add route handlers inside `start!` in `src/electron/electron/server.cljs` — they receive raw Fastify `req`/`rep`.
2. Use `invoke-logseq-api!` for anything that needs DataScript; it already handles the IPC round-trip.
3. Do **not** call worker functions directly from the main process — the Renderer owns the worker thread. Proxy through the Renderer.
4. Respect the pre-handler: the Bearer token guard is already applied to every path except `/`. If you add an intentionally public route, either prefix it with `/` semantics handled by `api-pre-handler!` or split the auth hook.

## Invoking `logseq.*` Methods From HTTP

Available method namespaces live under `src/main/logseq/` — e.g. `logseq.api.db/q`, `logseq.api.db/datascript_query`, `logseq.api.db/custom_query`, `logseq.api.db/set_file_content`, `logseq.api.db/get_file_content`, plus `logseq.sdk.*` and the CLI/editor methods exposed to the Renderer.

Call shape:
- HTTP body method: `"logseq.<ns>.<fn>"` (kebab-to-snake is handled for the trailing segment; the namespace segment is lower-cased).
- `args` is always a JS array; individual arg encoding matches whatever the underlying fn expects (strings stay strings; functions are generally not supported across the IPC boundary).

Write paths:
- `set_file_content` only accepts paths in `#{"logseq/custom.js" "logseq/custom.css" "logseq/publish.js" "logseq/publish.css"}` — attempts outside that set throw.
- Block/page mutations must go through `logseq.cli.upsertNodes` (batch) or the equivalent SDK methods so outliner middleware runs.

## Known Pitfalls

- **Electron-only** — there is no web-mode fallback. Any design that assumes `window.fetch` to `127.0.0.1:12315` from a browser dev session will silently fail unless Electron is running.
- **`api-pre-handler!` only whitelists `"/"`** — any future "public" endpoint must update the conditional or move to a different Fastify plugin scope.
- **Error shape on `/api`** — a successful promise resolution whose body contains an `error` key becomes HTTP 500 with the body passed through. Callers must check for `error` on 2xx bodies too, since `/api` treats any non-throw as success otherwise.
- **MCP route registration is start-time** — toggling `:server/mcp-enabled?` requires restarting the server (call `do-server! :restart`).
- **Bearer token comparison is string equality** — there is no constant-time compare. The host is normally `127.0.0.1`, but if you ever bind `0.0.0.0`, you inherit timing-attack exposure.
- **CORS is `*`** — combined with Bearer in a header, this is safe for local dev but means any page the user visits can probe the server; the DNS rebinding protection on `/mcp` and the loopback default are what keep this sane.
- **`resolve-real-api-method` is string-based** — typos silently produce invalid snake-case method names that the Renderer rejects. Test new endpoints with a real call, not just a unit test on the dispatcher.
- **IPC `handleOnce`** — each `/api` call registers exactly one listener keyed by `sid`. If the Renderer never replies, the promise never resolves; Fastify's 42 s `requestTimeout` kicks in and the listener is leaked. Watch main-process memory when stress-testing a broken method name.
- **Electron window atom** — `*win` is set in `setup!` from the first window. If the main window is closed and a new one opened, existing server instances keep the stale reference. The IPC reply path may target a dead BrowserWindow.
- **MCP transport sessions are in-memory** — `transports` is a `defonce` atom in `cli-common-mcp-server`; restarting the server leaves old session ids invalid, clients must re-initialize.
- **Zod v4 breakage** — pin to `zod/v3` in the MCP schema code (see the required-by-the-SDK comment at the top of `mcp/server.cljs`).

## Key Hotspots

- `src/electron/electron/server.cljs` — Fastify setup, auth pre-handler, `/api` bridge, MCP wiring.
- `deps/cli/src/logseq/cli/common/mcp/server.cljs` — MCP tool registry, Streamable HTTP handlers.
- `src/main/logseq/api/db.cljs` — example API methods exposed via the bridge (`q`, `datascript_query`, `custom_query`, file I/O).
- `src/main/frontend/components/settings.cljs` — "HTTP API server" settings pane (search for `HTTP API server`).
- `src/electron/electron/configs.cljs` — where `server/*` keys persist.

## Related Skills

- `.claude/skills/excalidraw-whiteboard.md` — whiteboard data model, useful when wiring MCP tools that create whiteboards.
- `.claude/skills/publish-page.md` — cloud publish (separate service; do not confuse with this local server).
- `.claude/skills/storage-model.md` — visual-doc sidecar shape, needed for any tool that touches whiteboards / sheets / mind maps.
