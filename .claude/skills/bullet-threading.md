# Bullet Threading Skill

## When To Use

Use this guide when modifying the built-in bullet threading feature, nested block connector styling, or the Settings → 更多功能 (features tab) controls that configure it.

## Architecture

The old `logseq-plugin-bullet-threading` plugin was a thin remote CSS loader. In this repository the feature is built in locally instead:

- `src/main/frontend/components/block.css`
  - Owns the connector-line rendering.
  - Anchors the threading pseudo-elements to the current block DOM, not the legacy plugin selectors.
- `src/main/frontend/components/container.cljs`
  - Adds the `ls-bullet-threading-enabled` root class.
  - Injects CSS variables for width and active color.
- `src/main/frontend/state.cljs`
  - Reads the repo config values and applies defaults.
- `src/main/frontend/handler/config.cljs`
  - Persists settings back into `logseq/config.edn`.
- `src/main/frontend/components/settings.cljs`
  - Exposes the controls on the "更多功能" (features) tab of the Settings modal. NOT in the Appearance modal.
- `deps/common/resources/templates/config.edn`
  - Documents the repo config keys.

## Config Keys

```clojure
:ui/bullet-threading? false
:ui/bullet-threading-width "1px"
:ui/bullet-threading-color ""
```

- `:ui/bullet-threading?`
  - Enables the feature.
- `:ui/bullet-threading-width`
  - Accepts a CSS length string such as `"1px"` or `"2px"`.
- `:ui/bullet-threading-color`
  - Optional CSS color for the active thread highlight. Blank means follow the theme.

## DOM Anchors

Do not port the legacy plugin selectors directly. They depended on older `.items-center` / `.items-baseline` wrappers.

Current stable anchors:

- Nested elbow connector:
  - `.ls-block .ls-block > .block-main-container > .block-control-wrap::before`
- Parent-to-children vertical segment:
  - `.ls-block[haschild="true"] > .block-main-container::after`
- Sibling continuation segment:
  - `.block-children > .ls-block::before`
- Root feature gate:
  - `main.theme-container-inner.ls-bullet-threading-enabled`

## Current Behavior

- Only enabled when `ls-bullet-threading-enabled` is present on the app container.
- **Focus-only visibility.** Connector lines are transparent by default (`--ls-block-bullet-threading-color: transparent`). They only become visible on the `:focus-within` ancestor chain of the block that contains the caret.
- When the caret leaves, the chain fades back to transparent — the page looks like threading is off.
- The active (visible) color follows the theme unless `:ui/bullet-threading-color` overrides it.
- The focus chain lights up via three selectors: nested elbow `::before`, parent stub `::after`, and `.block-children:focus-within` border-left.
- Page title blocks are excluded via `data-page-title` so the page header does not render fake bullet threads.
- Doc mode explicitly disables the threading pseudo-elements.

## Guardrails

- Keep this feature CSS-only. It should not depend on DataScript, SQLite, or any worker round-trip.
- Avoid reintroducing CDN or runtime remote stylesheet loading.
- Prefer selectors rooted in `block-main-container`, `block-control-wrap`, and `block-children`; those are the current stable structure.
- If the block DOM changes, update this skill and the selectors together.
- The current implementation intentionally ignores mobile-only layout variants.
