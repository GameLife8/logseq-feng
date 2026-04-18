# univer-sheet

## When To Use

Use this note when working on the Univer spreadsheet feature, including the slash command entry, `{{sheet page-uuid}}` macro rendering, lazy-loaded sheet editor, webpack `univer-sheet` bundle, or sheet document persistence.

## Architecture Overview

```
┌─ localStorage (draft cache, every 3s) ───┐
│  Key: "sheet-data-{page-uuid}"           │
│  Value: {version:1, saved-at, data: json}│
│  LRU: keeps last 5 entries               │
└──────────────┬───────────────────────────┘
               ↕
┌─ Worker SQLite Sidecar (/visual-doc.sqlite) ──────┐
│  visual_docs — blob manifest row                   │
│  attr: :block/sheet-data                           │
└──────────────┬────────────────────────────────────┘
               ↕
┌─ DataScript (main thread) ────────────────┐
│  Page entity: title, tags, updated-at     │
│  NO large payload (retracted after save)  │
└───────────────────────────────────────────┘
```

Identical to the Excalidraw/whiteboard sidecar pattern. The sheet editor in `core.cljs` is modeled after `excalidraw/core.cljs` for dirty tracking, timers, and sync status.

### Read-only inline embed vs. full-page editor

**Inline embed** `{{sheet uuid}}` (rendered by `sheet-embed-card`) is **read-only** with an HTML table preview. No Univer instance is loaded for inline embeds — this prevents data loss from accidental navigation and keeps block rendering fast. The card has hover-reveal action buttons: **Refresh** (re-reads sidecar), **Edit** (navigates to full-page editor), **Delete** (removes the block).

**Full-page editor** at route `/sheet/:uuid` (rendered by `sheet-page`) mounts the full lazy Univer instance with all editing, dirty tracking, sync status, and print/export support.

Pattern mirrors `{{whiteboard uuid}}` / `{{mindmap uuid}}` — the inline embed is a preview card; real editing happens at the dedicated route.

## Main Files

| File | Role |
|------|------|
| `src/main/frontend/commands.cljs` | Slash command "Universheet" → `:editor/insert-sheet` |
| `src/main/frontend/components/editor.cljs` | `sheet-search` UI, macro insertion with retry logic |
| `src/main/frontend/components/sheet.cljs` | `sheet-embed-card` (read-only inline preview), `sheet-page` (full-page route), `all-sheets` (gallery), macro "sheet" |
| `src/main/frontend/handler/sheet.cljs` | Persistence + CRUD: `save-sheet-to-db!`, `<load-sheet-doc`, `<create-sheet!`, `<rename-sheet!`, `<delete-sheet!`, `redirect-to-sheet!`, Sheet class tag |
| `src/main/frontend/handler/visual_doc.cljs` | Shared sidecar helpers (cache, flush, load) |
| `src/main/frontend/extensions/sheet/core.cljs` | Univer runtime, dirty tracking, sync status UI |
| `src/main/js/univer-sheet-entry.js` | Webpack entry: JS factory + CSS injection |
| `webpack.config.js` | `univer-sheet` bundle config |
| `package.json` | `@univerjs/presets` + `@univerjs/preset-sheets-core` (^0.20.1) |
| `shadow-cljs.edn` | Lazy `:sheet` module |
| `resources/index.html` | Loads `./js/univer-sheet-bundle.js` |

## Univer Version & Package Architecture

**Current version: 0.20.1** (Preset mode)

Packages:
- `@univerjs/presets` — Core factory `createUniver()`, `LocaleType`, `mergeLocales`
- `@univerjs/preset-sheets-core` — `UniverSheetsCorePreset`, locale bundles, CSS
- `rxjs` — Required peer dependency

**NOT used** (legacy, removed): `@univerjs/core`, `@univerjs/design`, `@univerjs/engine-render`, `@univerjs/sheets`, `@univerjs/sheets-ui`, `@univerjs/ui`

## Runtime Contract

### JS Factory (`univer-sheet-entry.js`)

```javascript
// Exports to window.UniverSheet
export function createSheetInstance(containerEl, workbookData)
// Returns: { univer, univerAPI }
//   univer   — raw Univer instance (for .dispose())
//   univerAPI — FUniver facade (for getActiveWorkbook, onCommandExecuted, etc.)
```

Key behaviors:
- Calls `createUniver({ locale, locales, presets: [UniverSheetsCorePreset({container, footer:false})] })`
- Calls `univerAPI.createWorkbook(workbookData)` to mount the sheet
- Injects preset CSS via `<style>` tag (idempotent, checks by element ID)
- Injects override CSS to hide "保护" (Protection) toolbar button and stuck Radix tooltip

### CLJS Editor (`extensions/sheet/core.cljs`)

```clojure
;; Access the JS factory:
(def ^:private createSheetInstance (.-createSheetInstance js/UniverSheet))

;; Create instance:
(let [^js result (createSheetInstance container-el workbook-data)]
  {:univer (.-univer result)    ;; for dispose
   :api    (.-univerAPI result)}) ;; FUniver facade

;; Snapshot (save):
(.save (.getActiveWorkbook univerAPI))  ;; returns IWorkbookData JS object

;; Listen for edits:
(.onCommandExecuted univerAPI callback) ;; returns IDisposable
```

**Critical**: Always use `univerAPI` (FUniver facade), never the raw `univer` instance. The raw instance does NOT have `getActiveWorkbook`, `onCommandExecuted`, etc.

## Dirty Tracking & Persistence (Excalidraw Pattern)

Rum locals in the `editor` component:

| Atom | Purpose |
|------|---------|
| `::univer` | Raw Univer instance (for `.dispose()`) |
| `::univer-api` | FUniver facade |
| `::cache-dirty?` | Needs localStorage write |
| `::persist-dirty?` | Needs sidecar flush |
| `::cached?` | Last cache write succeeded (drives UI) |
| `::persisted?` | Last sidecar write succeeded (drives UI) |
| `::last-cached-json` | Baseline for cache diff |
| `::last-persisted-json` | Baseline for sidecar diff |
| `::command-listener` | IDisposable from `onCommandExecuted` |
| `::cache-timer-id` | 3s `setInterval` for localStorage |
| `::flush-timer-id` | 9s `setInterval` for sidecar |
| `::pagehide-handler` | Window pagehide listener |
| `::visibility-handler` | Document visibilitychange listener |

### Save Flow

```
onCommandExecuted fires → snapshot→json → compare with baselines
  → set *cache-dirty? and/or *persist-dirty?

Timer 3s: if *cache-dirty?
  → save-doc-cache!(page-uuid, json) → localStorage
  → reset *cached?=true, *cache-dirty?=false

Timer 9s: if *persist-dirty?
  → persist!() → on-save-data(page-uuid, json)
    → handler/sheet/save-sheet-to-db!
      → visual-doc/<flush-doc! repo page-uuid :block/sheet-data json
    → reset *persisted?=true, *persist-dirty?=false

pagehide / visibilitychange="hidden"
  → persist!() (immediate flush)

will-unmount
  → clear timers, remove event listeners, dispose command listener
  → final save-doc-cache! + on-save-data
  → destroy-univer-instance! (.dispose univer)
```

### Load Flow

```
Component mount
  → handler/sheet/<load-sheet-doc page-uuid
    → visual-doc/<load-doc repo page-uuid :block/sheet-data "sheet-data"
      → Worker sidecar read + localStorage read
      → choose-newer-source → winner
    → Pass initial-json + needs-initial-flush? to editor
  → did-mount: json→workbook-data → createSheetInstance → mount
  → If needs-initial-flush?: immediate on-save-data call
```

## Sync Status UI

Bottom-right overlay (matches Excalidraw pattern):

```
Draft: 草稿 已缓存 / 草稿 待保存
Graph: 图谱 已保存 / 图谱 待保存
```

Colors: blue (#3b82f6) = good, amber (#f59e0b) = pending

Requires `rum/reactive` mixin on the `editor` component for `rum/react` to read atoms.

## Props Interface (editor component)

```clojure
:sheet-id             — page UUID string (required)
:sheet-title          — display title
:initial-json         — initial workbook JSON string (may be nil)
:needs-initial-flush? — when true, flush initial-json to sidecar on mount
:on-save-data         — (fn [page-uuid json-str]) called to persist data
```

**Important**: The prop key is `:sheet-id`, not `:page-uuid`. The `did-update` handler syncs this to `*current-page-uuid` atom.

## Workbook Data Format

```json
{
  "id": "workbook_1",
  "name": "Sheet Title",
  "sheetOrder": ["sheet_1"],
  "sheets": {
    "sheet_1": {
      "id": "sheet_1",
      "name": "Sheet1",
      "rowCount": 50,
      "columnCount": 20,
      "cellData": {}
    }
  }
}
```

This IWorkbookData schema is stable across Univer versions (0.6.x → 0.20.x). Old saved data loads correctly in new versions.

## User Flow

### Create and Insert (slash command)

```
slash command "Universheet"
  → [:editor/insert-sheet]
  → state action :sheet-search
  → components/editor.cljs sheet-search
  → handler/sheet.cljs <create-sheet! name {:redirect? false}
  → insert-macro-and-close! inserts {{sheet page-uuid}} into current block
  → sheet-handler/redirect-to-sheet! navigates to /sheet/:uuid
```

Order matters: create page → insert macro → THEN redirect. Redirecting before the insert would unmount the editor mid-write and lose the macro. `insert-macro-and-close!` has retry logic (3 frames) to handle editor re-renders during page creation.

### Render inline `{{sheet uuid}}`

```
routes.cljs requires frontend.components.sheet
  → side-effect registers macro "sheet"
  → {{sheet page-uuid}}
  → components/sheet.cljs sheet-embed-card
  → <load-sheet-doc → JSON → build-preview-table-html → HTML table
  → NO lazy Univer loading (preview is pure HTML)
```

Action buttons (hover-reveal toolbar):
- **Refresh** — re-runs `<load-sheet-doc` and rebuilds preview HTML
- **Edit** — `sheet-handler/redirect-to-sheet!` → `/sheet/:uuid` (full Univer editor)
- **Delete** — `editor-handler/delete-block-aux!` removes the block

### Full-page editor `/sheet/:uuid`

```
route :sheet → components/sheet.cljs sheet-page
  → <load-sheet-doc → initial-json
  → lazy load frontend.extensions.sheet.core/editor
  → full Univer instance with dirty tracking + sync status
```

The `sheet-page` component is the ONLY place that mounts a live Univer instance. `margin-less-pages?` in `container.cljs` gives it the full right-side viewport.

## Bundling Requirements

- `package.json` must include `@univerjs/presets`, `@univerjs/preset-sheets-core`, and `rxjs`
- `webpack.config.js` must define `univer-sheet` config with:
  - React/ReactDOM as externals
  - CSS as `asset/source` (raw string import)
  - `vue: false` alias (Univer internal references)
  - `publicPath: '/static/js/'` (for async chunk loading)
  - Output as `window.UniverSheet` library
- `package.json` scripts must include `--config-name univer-sheet`
- `resources/index.html` must load `./js/univer-sheet-bundle.js`
- `shadow-cljs.edn` must include the lazy `:sheet` module

**Note**: Univer 0.20.x produces multiple chunk files (`*.univer-sheet-bundle.js`) via code splitting. The `publicPath` setting ensures chunks load correctly at runtime.

## CSS & Styling

- Univer preset CSS is imported as raw string in `univer-sheet-entry.js` and injected via `<style id="univer-preset-sheets-core">`
- Override CSS hides the "保护" (Protection) button: `button:has(.univerjs-icon-protect-icon)` and stuck Radix tooltip: `body > [role="tooltip"].univer-bg-gray-700`

## Review Guardrails

- Never interpolate raw cell text into HTML preview or print strings. Escape cell values first, or build DOM nodes with `textContent`, before using `dangerouslySetInnerHTML` / `innerHTML`.
- Keep preview/print rendering bounded. Sparse workbooks can have a single populated cell far down/right; rendering `0..max-row` by `0..max-col` without caps can freeze the UI.
- When cache wins over sidecar on load, the initial flush must also update the local persisted baseline/status atoms. Otherwise the editor stays in a fake "pending" state until the next user edit.
- Prefer the sheet editor's current "final unmount flush to sidecar" model over the whiteboard/mind-map local-cache-only fallback when durability matters.
- `.sheet-editor-wrapper` and `.sheet-univer-container` must keep `min-height: 400px`, otherwise Univer mounts into a zero-height box
- Shared embed shell styles live in `common.css`

## Known Pitfalls

- **`getActiveUnitForType` does not exist** on the raw `Univer` class. Always use the `univerAPI` (FUniver facade) returned by `createUniver()`. The facade provides `getActiveWorkbook()`, `onCommandExecuted()`, etc.
- **`rum/react` requires `rum/reactive`** mixin. If the `editor` component reads atoms via `rum/react` without the mixin, it throws at runtime.
- **Prop name is `:sheet-id`** not `:page-uuid`. Mismatched prop name in `did-update` causes `*current-page-uuid` to reset to nil → sidecar writes fail silently (guard clause returns false).
- **`onCommandExecuted` callback** fires on every command (including internal renders). Only do lightweight work (compare snapshots, set dirty flags). Never do IO in the callback.
- **Radix tooltips** from Univer toolbar can get "stuck" as detached DOM nodes on `<body>`. The CSS override in `univer-sheet-entry.js` hides them.
- **Webpack chunk loading**: Univer 0.20.x uses dynamic imports internally. If `publicPath` is wrong, chunk 404s will cause silent failures.
- **Browser cache**: After rebuilding the webpack bundle, a hard refresh (Ctrl+Shift+R) may be needed to pick up changes.
- **Cell edit commit on snapshot**: `snapshot->json` must call `commit-cell-edit!` (which calls `FWorkbook.endEditing(true)`) BEFORE `workbook.save()`. Otherwise, the value in the cell being actively edited is not yet in the workbook model and will be lost on navigation.
- **Preview HTML generation is centralized** in `frontend.extensions.sheet.preview/build-table-html`, which escapes cell values via `escape-html` before injecting them into markup consumed by `dangerouslySetInnerHTML`. Both the inline read-only embed (`components/sheet.cljs`) and the print/PDF hidden table (`extensions/sheet/core.cljs`) call this shared helper. Do not reintroduce a components-layer duplicate that interpolates raw cell text.
- **Inline embed has NO Univer instance**. Do not try to read live workbook data from an inline embed — it only has the sidecar JSON snapshot. For live data, open the full-page editor.
- **Slash command redirect order**: Must be `<create-sheet! ... {:redirect? false}` → `insert-macro-and-close!` → `redirect-to-sheet!`. Redirecting before macro insertion unmounts the editor and loses the macro.
- **Route change to a new `:sheet-id` triggers a full React remount**, not an in-place prop swap. The `sheet-page` callsite in `components/sheet.cljs` wraps the lazy editor in `rum/with-key` keyed by `page-uuid`, so React unmounts the old Univer workbook and mounts a fresh one for the new page. The editor's `:did-update` remains defensive: if the `:sheet-id` prop changes without an unmount, it flushes the active workbook snapshot to the OLD page-uuid before swapping save-fn/uuid atoms, preventing cross-page content contamination.
- **Cache-before-flush on create**: `<create-sheet!` calls `visual-doc/save-doc-cache!` with the initial workbook JSON **before** `<flush-doc!` resolves. If sidecar write then fails, the localStorage cache is the only copy and is subject to 5-entry LRU eviction. The load path will still find the cache (newer than empty sidecar), but durability is dependent on no other doc taking the LRU slot.
- **Name uniqueness check is racy by design**: `sheet-name-exists?` queries the in-memory DataScript replica only; two near-simultaneous `<create-sheet!` calls can both pass the check and create duplicate-named pages. A `;; NOTE:` comment inside `<create-sheet!` documents this. Duplicate titles do NOT corrupt data — each page has a distinct `:block/uuid` and its own sidecar row; the constraint is best-effort UX only.
- **Preview HTML escapes cell values**: `sheet-preview/build-table-html` passes each cell through `escape-html` before concatenating into markup. Both the inline embed and the print/PDF path share this helper. Do not bypass it when rendering untrusted sheet content.
- **Inline embed is unmount-guarded**: `sheet-embed-card` sets a `::mounted?` Rum-local in `:did-mount` and clears it in `:will-unmount`; both the initial load and the refresh button gate state resets with `(when @(::mounted? state) ...)`. Prevents "cannot update unmounted component" warnings when many embeds scroll in and out of view.
- **Legacy payload retract is best-effort**: `<flush-doc!` calls `[:db/retract page-id :block/sheet-data]` after sidecar write; the retract now runs in its own inner `p/catch` so a failure never blocks the timestamp bump or bubbles up (`handler/visual_doc.cljs/<apply-manifest!`). Do not rely on the legacy attr being absent after save — it will be retracted on the next flush if it survived.

## Persistence Contract

`frontend.handler.sheet`

- `sheet-attr` is `:block/sheet-data`
- `save-sheet-to-db!` delegates to `visual-doc/<flush-doc!`
- `<load-sheet-doc` delegates to `visual-doc/<load-doc`
- `<create-sheet!` creates a page, seeds an empty workbook JSON, writes cache, then flushes sidecar

Rules:
- Do not store the full workbook JSON on the page entity as primary store
- Reuse `visual-doc/<load-doc` and `visual-doc/<flush-doc!` — never invent a sheet-only persistence path

## Merge Notes

- Keep the split clear:
  - `components/sheet.cljs` — macro registration and DB bridge
  - `handler/sheet.cljs` — persistence (CRUD)
  - `extensions/sheet/core.cljs` — Univer runtime only (zero DB dependency)
  - `univer-sheet-entry.js` — all Univer JS imports and factory (zero CLJS knowledge)
- When debugging startup, verify all three layers:
  1. Slash command / macro path
  2. Sidecar load/save path
  3. Webpack bundle + `window.UniverSheet` exports
- The sheet editor follows the same patterns as `excalidraw/core.cljs`. When modifying one, check if the other needs the same change.
