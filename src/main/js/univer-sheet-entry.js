/**
 * Univer spreadsheet webpack entry point.
 * Bundled into static/js/univer-sheet-bundle.js and exposed as window.UniverSheet.
 *
 * The CSS imports are bundled as raw strings by webpack and injected into
 * <style> tags once so the sheet UI renders correctly inside Logseq.
 */

import designCss from '@univerjs/design/lib/index.css';
import uiCss from '@univerjs/ui/lib/index.css';
import sheetsUiCss from '@univerjs/sheets-ui/lib/index.css';

import DesignZhCN from '@univerjs/design/lib/es/locale/zh-CN';
import UIZhCN from '@univerjs/ui/lib/es/locale/zh-CN';
import SheetsZhCN from '@univerjs/sheets/lib/es/locale/zh-CN';
import SheetsUIZhCN from '@univerjs/sheets-ui/lib/es/locale/zh-CN';

const injectStyle = (id, cssText) => {
  if (typeof document === 'undefined' || !cssText || document.getElementById(id)) {
    return;
  }

  const style = document.createElement('style');
  style.id = id;
  style.textContent = cssText;
  document.head.appendChild(style);
};

injectStyle('univer-design-style', designCss);
injectStyle('univer-ui-style', uiCss);
injectStyle('univer-sheets-ui-style', sheetsUiCss);

export { Univer, UniverInstanceType, LocaleType } from '@univerjs/core';
export { defaultTheme } from '@univerjs/design';
export { UniverSheetsPlugin } from '@univerjs/sheets';
export { UniverRenderEnginePlugin } from '@univerjs/engine-render';
export { UniverSheetsUIPlugin } from '@univerjs/sheets-ui';
export { UniverUIPlugin } from '@univerjs/ui';
export { DesignZhCN, UIZhCN, SheetsZhCN, SheetsUIZhCN };
