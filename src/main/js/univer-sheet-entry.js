/**
 * Univer spreadsheet webpack entry point.
 * Bundled into static/js/univer-sheet-bundle.js and exposed as window.UniverSheet.
 *
 * Uses the Univer Preset architecture (@univerjs/presets + @univerjs/preset-sheets-core).
 * Exports a factory function `createSheetInstance` that hides all Univer internals
 * so ClojureScript only needs to call one function and use the returned univerAPI facade.
 */

import { UniverSheetsCorePreset } from '@univerjs/preset-sheets-core';
import UniverPresetSheetsCoreZhCN from '@univerjs/preset-sheets-core/locales/zh-CN';
import { createUniver, LocaleType, mergeLocales } from '@univerjs/presets';
import presetCSS from '@univerjs/preset-sheets-core/lib/index.css';

// ── Inject CSS once ─────────────────────────────────────────────────────────
const injectStyle = (id, cssText) => {
  if (typeof document === 'undefined' || !cssText || document.getElementById(id)) {
    return;
  }
  const style = document.createElement('style');
  style.id = id;
  style.textContent = cssText;
  document.head.appendChild(style);
};

injectStyle('univer-preset-sheets-core', presetCSS);

// Hide Univer protection/permission button — not needed in embedded Logseq context.
// 1. The protect icon button in the toolbar overflow panel
// 2. The stuck tooltip ("保护") that Radix renders on <body> at (0,0)
injectStyle('univer-logseq-overrides', `
  button:has(.univerjs-icon-protect-icon),
  a:has(.univerjs-icon-protect-icon) {
    display: none !important;
  }
  body > [role="tooltip"].univer-bg-gray-700 {
    display: none !important;
  }
`);

// ── Factory: creates a Univer sheet instance ────────────────────────────────
/**
 * @param {HTMLElement} containerEl  — the DOM element to mount the sheet into
 * @param {object}      workbookData — IWorkbookData JSON object (parsed, not string)
 * @returns {{ univer: object, univerAPI: object }}
 */
export function createSheetInstance(containerEl, workbookData) {
  const { univer, univerAPI } = createUniver({
    locale: LocaleType.ZH_CN,
    locales: {
      [LocaleType.ZH_CN]: mergeLocales(UniverPresetSheetsCoreZhCN),
    },
    presets: [
      UniverSheetsCorePreset({
        container: containerEl,
        footer: false,
      }),
    ],
  });

  univerAPI.createWorkbook(workbookData);

  return { univer, univerAPI };
}

export { LocaleType };
