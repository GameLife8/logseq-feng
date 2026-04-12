# asset-upload-management

## When To Use

Use this note when working on asset upload, slash commands `/upload an asset` and `/Insert an asset`, drag/drop or paste file insertion, the `#Asset` page, asset block rendering, or external-file reference behavior.

## Main Files

- `src/main/frontend/commands.cljs`
- `src/main/frontend/components/editor.cljs`
- `src/main/frontend/components/container.cljs`
- `src/main/frontend/handler/paste.cljs`
- `src/main/frontend/handler/editor.cljs`
- `src/main/frontend/handler/assets.cljs`
- `src/main/frontend/components/block.cljs`
- `src/main/frontend/components/objects.cljs`
- `src/main/frontend/components/page.cljs`
- `src/main/frontend/components/views.cljs`
- `src/main/frontend/components/assets.cljs`
- `deps/db/src/logseq/db/frontend/class.cljs`
- `deps/db/src/logseq/db/frontend/property.cljs`
- ~~`src/main/frontend/worker/sync/asset_db_listener.cljs`~~ (REMOVED — sync code deleted)
- ~~`src/main/frontend/worker/sync/assets.cljs`~~ (REMOVED — sync code deleted)
- ~~`src/main/frontend/worker/sync/download.cljs`~~ (REMOVED — sync code deleted)

## Mental Model

- There are now two distinct asset insertion modes.
- `Upload an asset` is a managed asset flow:
  - copy the binary into the graph `assets/` directory
  - create a `:logseq.class/Asset` entity in DB
  - participate in checksum dedupe and db-sync
- `Insert an asset` is a lightweight external reference flow:
  - open a desktop file picker
  - insert a plain `file:` link into the current block
  - do not copy the file into graph `assets/`
  - do not create any Asset DB entity
- The `#Asset` sidebar page only shows managed Asset entities. Lightweight external file links do not appear there.

## Managed Asset Entity Shape

Managed asset blocks are created in `frontend.handler.editor/new-asset-block` with:

- `:block/title`
- `:block/uuid`
- `:logseq.property.asset/type`
- `:logseq.property.asset/size`
- `:logseq.property.asset/checksum`
- `:logseq.property.asset/external-url`
- `:block/tags #{(:db/id (db/entity :logseq.class/Asset))}`

Related built-in properties are defined in `deps/db/src/logseq/db/frontend/property.cljs`.

## Entry Points

### 1. Slash command `/upload an asset`

Flow:

```clojure
/upload an asset
  -> commands.cljs "Upload an asset"
  -> [:editor/click-hidden-file-input :id]
  -> components/editor.cljs #upload-file
  -> editor-handler/upload-asset!
```

This is the managed upload flow. It copies files into graph storage and creates Asset entities.

### 2. Slash command `/Insert an asset`

Flow:

```clojure
/Insert an asset
  -> commands.cljs "Insert an asset"
  -> clear current slash command text
  -> electron showOpenDialog openFile
  -> insert plain file link into current block
```

Rules:

- desktop / Electron only
- browse disk only
- no Asset entity
- no checksum dedupe
- no sync upload
- deleting this reference only edits block content and never deletes the original file

### 3. Drag and drop into the editor area

Flow:

```clojure
main-content-container drop
  -> components/container.cljs
  -> editor-handler/upload-asset!
```

### 4. Paste files from clipboard

Flow:

```clojure
paste
  -> handler/paste.cljs paste-file-if-exists
  -> editor-handler/upload-asset!
```

### 5. Add files from the `#Asset` page

Flow:

```clojure
Asset page "+" / New row
  -> components/objects.cljs add-new-object!
  -> filepicker/picker
  -> editor-handler/upload-asset! nil files :markdown ...
```

This is still the managed upload flow.

### 6. Create or edit external Asset records

This is separate from `/Insert an asset`.

Flow:

```clojure
asset external-url property click
  -> state/pub-event! [:asset/dialog-edit-external-url ...]
  -> components/assets.cljs edit-external-url-content
  -> editor-handler/db-based-save-assets! repo [{:title title :src src}]
```

If `:src` is a string URL/path, `new-asset-block` creates metadata only and skips local binary write, but it still creates an Asset DB entity. Do not confuse this with the lightweight file-link insertion flow.

## Core Managed Upload Pipeline

Shared upload logic lives in `frontend.handler.editor`.

### `upload-asset!`

- Thin wrapper around `db-upload-assets!`
- Used by slash upload, drag/drop, paste, and Asset page picker

### `db-upload-assets!`

- Clears/inserts command state in the editor
- Calls `db-based-save-assets!`
- Resets upload UI atoms when finished

### `db-based-save-assets!`

Responsibilities:

1. Ensure the graph `assets/` directory exists
2. Resolve insertion target
3. Build asset blocks with `new-asset-block`
4. Insert them with `outliner-op/insert-blocks!`
5. Reuse the first empty target block when possible

### `new-asset-block`

Responsibilities:

1. Normalize file vs external URL input
2. Infer extension
3. Reject files above 100 MB
4. Compute checksum
5. Check duplicates with `db-async/<get-asset-with-checksum`
6. Write local file for real uploads
7. Return the DB entity map

Important behaviors:

- duplicate upload is blocked by checksum
- local files are renamed to `assets/<uuid>.<ext>`
- remote/external Asset records skip binary write but still enter the Asset class

## External File Link Rendering

`src/main/frontend/components/block.cljs`

- plain `file:` links are rendered by the normal link pipeline, not by `asset-cp`
- Electron opens them with `js/window.apis.openPath`
- they are lightweight references attached only to block text
- they do not get Asset actions, checksum metadata, or sync state

Because `/Insert an asset` inserts normal block links, its deletion semantics are simple: remove the link text only.

## `#Asset` Page Logic

## Sidebar Entry

`src/main/frontend/components/left_sidebar.cljs`

- Sidebar nav includes `:tag/assets`
- That key maps to `:logseq.class/Asset`
- Clicking `Assets` routes to the built-in Asset class page

This means the page is a normal class page route, not a custom hardcoded `/assets` screen.

## Page Rendering

```clojure
page.cljs -> objects/class-objects -> views/view
```

The visible Asset management page is mostly generic class-object UI, not `components/assets.cljs`.

## Column Construction

`src/main/frontend/components/objects.cljs`

Asset class gets special table treatment:

- start from generic class properties
- remove checksum column
- inject custom `File` column using `state/get-component :block/asset-cp`
- keep tags column

This page only manages real Asset entities. Plain external file links inserted by `/Insert an asset` are out of scope for this page.

## Asset Sync Logic (REMOVED)

~~For db-sync graphs, only managed Asset entities participate in upload/download lifecycle.~~

The remote asset sync system has been removed along with the entire db-sync module. The following files no longer exist:
- `asset_db_listener.cljs` — deleted
- `sync/assets.cljs` — deleted
- `sync/download.cljs` — deleted

`handler/assets.cljs` → `maybe-request-remote-asset-download!` is now a no-op.

Managed Asset entities still work locally (upload, checksum dedupe, `#Asset` page), but they no longer sync to remote.

## Known Pitfalls

- `components/assets.cljs` is not the main `#Asset` page body.
- The Asset page is a generic class page; many changes belong in `page.cljs`, `objects.cljs`, or `views.cljs`.
- Do not route `/Insert an asset` through `db-based-save-assets!` or `new-asset-block`; that would silently turn a plain link into a managed Asset record.
- Do not assume `#Asset` should show every file link in the graph. It only shows Asset entities.
- `Upload an asset` and `Insert an asset` have intentionally different delete semantics.
- Remote asset sync has been removed; managed assets are local-only now.

## Merge Notes

- Preserve the shared managed-upload pipeline across slash upload, paste, drag/drop, and Asset page picker.
- Keep `/Insert an asset` lightweight: browse disk, insert link, stop there.
- When changing Asset page UI, verify whether the change belongs to class views, saved views, or `asset-cp`.
