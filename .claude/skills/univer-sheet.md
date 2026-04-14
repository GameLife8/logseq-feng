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

## Main Files

| File | Role |
|------|------|
| `src/main/frontend/commands.cljs` | Slash command "Universheet" → `:editor/insert-sheet` |
| `src/main/frontend/components/editor.cljs` | `sheet-search` UI, macro insertion with retry logic |
| `src/main/frontend/components/sheet.cljs` | Macro registration "sheet", DB bridge, lazy loader |
| `src/main/frontend/handler/sheet.cljs` | Persistence: `save-sheet-to-db!`, `<load-sheet-doc`, `<create-sheet!` |
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

### Create and Insert

```
slash command "Universheet"
  → [:editor/insert-sheet]
  → state action :sheet-search
  → components/editor.cljs sheet-search
  → handler/sheet.cljs <create-sheet!
  → insert {{sheet page-uuid}} into current block
```

`sheet-search` currently only creates a new sheet. `insert-macro-and-close!` has retry logic (3 frames) to handle editor re-renders during page creation.

### Render

```
routes.cljs requires frontend.components.sheet
  → side-effect registers macro "sheet"
  → {{sheet page-uuid}}
  → components/sheet.cljs sheet-inline-editor
  → lazy load frontend.extensions.sheet.core/editor
```

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
