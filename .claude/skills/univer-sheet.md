# univer-sheet

## When To Use

Use this note when working on the Univer spreadsheet feature, including the slash command entry, `{{sheet page-uuid}}` macro rendering, lazy-loaded sheet editor, webpack `univer-sheet` bundle, or sheet document persistence.

## Main Files

- `src/main/frontend/commands.cljs`
- `src/main/frontend/components/editor.cljs`
- `src/main/frontend/components/sheet.cljs`
- `src/main/frontend/handler/sheet.cljs`
- `src/main/frontend/handler/visual_doc.cljs`
- `src/main/frontend/extensions/sheet/core.cljs`
- `src/main/frontend/routes.cljs`
- `src/main/frontend/common.css`
- `src/main/js/univer-sheet-entry.js`
- `package.json`
- `shadow-cljs.edn`
- `webpack.config.js`
- `resources/index.html`

## Mental Model

- A sheet is a normal page entity plus a sidecar document payload.
- The block content only stores a macro reference: `{{sheet page-uuid}}`.
- The full workbook JSON does not live in the main DataScript page body.
- Durable data is written through the shared visual-doc sidecar helper with attr `:block/sheet-data`.
- Draft data is cached in `localStorage` under prefix `sheet-data`.
- The editor runtime is loaded lazily from the shadow-cljs `:sheet` module and depends on the webpack bundle exposing `window.UniverSheet`.

## User Flow

### Create and insert

```clojure
slash command "Universheet"
  -> [:editor/insert-sheet]
  -> state action :sheet-search
  -> components/editor.cljs sheet-search
  -> handler/sheet.cljs <create-sheet!
  -> insert {{sheet page-uuid}} into current block
```

`sheet-search` currently only creates a new sheet. It does not list existing sheets.

### Render

```clojure
routes.cljs requires frontend.components.sheet
  -> side-effect registers macro "sheet"
  -> {{sheet page-uuid}}
  -> components/sheet.cljs sheet-inline-editor
  -> lazy load frontend.extensions.sheet.core/editor
```

### Persist

```clojure
extensions/sheet/core.cljs
  -> autosave to localStorage every 3s
  -> flush sidecar every 9s through on-save-data
  -> final save on unmount
```

## Persistence Contract

`frontend.handler.sheet`

- `sheet-attr` is `:block/sheet-data`
- `save-sheet-to-db!` delegates to `visual-doc/<flush-doc!`
- `<load-sheet-doc` delegates to `visual-doc/<load-doc`
- `<create-sheet!` creates a page, seeds an empty workbook JSON, writes cache, then flushes the sidecar

Important:

- Do not store the full workbook JSON back onto the page entity as the long-term source of truth.
- Reuse `visual-doc/<load-doc` and `visual-doc/<flush-doc!` instead of inventing a sheet-only persistence path.

## Runtime Contract

`frontend.extensions.sheet.core`

- Reads constructors and plugins from `js/UniverSheet`
- Mounts into a container ref after `requestAnimationFrame`
- Creates a workbook via `(.createUnit univer (.-UNIVER_SHEET UniverInstanceType) workbook-data)`
- Needs locale bundles and CSS to be available from the webpack bundle

`src/main/js/univer-sheet-entry.js`

- Exports the Univer constructors/plugins to `window.UniverSheet`
- Injects Univer CSS into `<style>` tags once
- Exports merged zh-CN locale pieces used by the lazy CLJS editor

If the sheet opens blank or crashes at startup, check this file first.

## Bundling Requirements

- `package.json` must include the Univer packages and `rxjs`
- `webpack.config.js` must define the `univer-sheet` bundle target
- `package.json` scripts must include `--config-name univer-sheet`
- `resources/index.html` must load `./js/univer-sheet-bundle.js`
- `shadow-cljs.edn` must include the lazy `:sheet` module

If one of these is missing, the macro may render but the editor runtime will not boot.

## Styling

- Shared embed shell styles live in `common.css`
- Univer internal CSS is injected from the webpack bundle entry file
- `.sheet-editor-area` and `.sheet-univer-container` must keep a real height, otherwise Univer mounts into a zero-height box

## Known Pitfalls

- The most common compile failure is a broken Rum lifecycle map in `extensions/sheet/core.cljs`.
- The most common runtime failure is missing `window.UniverSheet` exports or missing locale/CSS injection in `univer-sheet-entry.js`.
- `sheet-search` is create-only right now; adding “open existing sheet” later belongs in `components/editor.cljs` and should still insert the same `{{sheet uuid}}` macro.
- `#Asset` logic is unrelated. Sheets use the visual-doc sidecar path, not the asset upload pipeline.

## Merge Notes

- Keep the split clear:
  - `components/sheet.cljs` handles macro registration and DB bridge
  - `handler/sheet.cljs` handles persistence
  - `extensions/sheet/core.cljs` handles Univer runtime only
- When debugging startup, verify all three layers:
  - slash command / macro path
  - sidecar load/save path
  - webpack bundle + global exports
