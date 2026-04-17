# PDF 导出 (pdf-export)

Logseq 整页 / 整块 PDF 导出实现说明。**不要** 使用 jsPDF / html2pdf / html2canvas 生成 PDF —— 本项目统一走「克隆 DOM + blob 窗口 + 浏览器原生打印」方案。

---

## 一、整体架构

```
┌──────────────────────────────────────────────────────────┐
│ 用户入口                                                   │
│   • 页面菜单 "Export page"  (page_menu.cljs:115)          │
│     → shui/dialog-open! (export/export-blocks ...)        │
│   • 块选择后右键 Export    (components/content.cljs)      │
└──────────────┬───────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────┐
│ Export Dialog  (components/export.cljs:428+)              │
│   PDF | OPML | MARKDOWN | TEXT | PNG  按钮                │
│   PDF 按钮 on-click → (export-as-pdf! top-level-uuids)    │
└──────────────┬───────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────────┐
│ export-as-pdf!  (components/export.cljs:237-426)          │
│   1. collect-inline-css     — CSSOM 读全部样式            │
│   2. clone #main-content-container                        │
│   3. 删除打印无关 DOM（references、CM gutter…）             │
│   4. 修复 CodeMirror 5/6 换行 / 溢出 / clip-path           │
│   5. 拼装完整 <html> + 内联 CSS + <script>window.print()   │
│   6. Blob → URL.createObjectURL → window.open             │
│   7. 60s 后 revokeObjectURL                                │
└──────────────────────────────────────────────────────────┘
```

**核心思路**：把当前页面**已渲染**的 DOM 克隆出来，连同所有 CSS 一起塞进 blob 窗口；blob 窗口 onload 立刻调用 `window.print()`；用户选 "另存为 PDF"。

---

## 二、关键文件速查表

| 职责 | 文件 | 关键符号 |
|---|---|---|
| PDF 导出主函数 | [components/export.cljs](src/main/frontend/components/export.cljs) | `export-as-pdf!` (L237) |
| 内联 CSS 收集 | 同上 | `collect-inline-css` (L221) |
| Export 对话框 UI | 同上 | `export-blocks` (L428) |
| 页面导出入口 | [components/page_menu.cljs](src/main/frontend/components/page_menu.cljs:115) | `:title :export-page` |
| Sheet 打印专用表格 | [extensions/sheet/core.cljs](src/main/frontend/extensions/sheet/core.cljs) | `build-print-table-html` (L167) |
| Sheet 打印样式 | [common.css](src/main/frontend/common.css) | `@media print { .sheet-print-* }` (L302-375) |
| Sheet 共享 HTML 构建器 | [extensions/sheet/preview.cljs](src/main/frontend/extensions/sheet/preview.cljs) | `preview-table-html` |
| 图片导出 (PNG，非 PDF) | [components/export.cljs](src/main/frontend/components/export.cljs) | `html2canvas` 流程 (L190+) |

---

## 三、为什么用这套方案

| 尝试过的方案 | 为什么没用 |
|---|---|
| `jsPDF` | 纯 JS 排版引擎，公式 / CSS Grid / CodeMirror / CSS 变量统统拉胯 |
| `html2pdf.js` | 内部就是 html2canvas + jsPDF，文本变图片，搜索不到、体积爆炸 |
| `html2canvas` → PDF | 同上，文本栅格化；**我们只用它做 PNG 导出** |
| Electron `webContents.printToPDF` | Web 版用不了；两套路径维护成本高 |
| **浏览器原生打印** ✅ | 矢量文本、保留可搜索性、零依赖、CSS 原生支持、每个操作系统都有 "另存为 PDF" |

---

## 四、`export-as-pdf!` 流程详解

### 4.1 内联 CSS (`collect-inline-css`)

```clojure
(->> (array-seq js/document.styleSheets)
     (map (fn [^js sheet]
            (try
              (->> (array-seq (.-cssRules sheet))
                   (map #(.-cssText %))
                   (clojure.string/join "\n"))
              (catch :default _ ""))))  ;; 跨域表跳过
     (clojure.string/join "\n"))
```

**为什么内联而不是 `<link>`**：
- blob 窗口里 `<link>` 网络加载是异步的，可能 `window.print()` 先触发 → 样式缺失
- CSP 策略可能阻止 blob 窗口加载外部 CSS
- 内联后 CSS 变量 `--ls-*`、`:root {}` 全部随 DOM 一起过去

### 4.2 克隆并清洗 DOM

克隆 `#main-content-container` 整棵树，然后：

**删除节点**（`rm-sels`）：
- `.references-blocks-wrap` — 页面底部的「引用」区，对打印无意义
- `.sidebar-drop-indicator` — 拖拽指示器
- `.CodeMirror-gutters`, `.CodeMirror-linenumber` — CM5 行号列
- `.cm-gutters`, `.cm-layer`, `.cm-announced` — CM6 行号列 / 叠加层 / a11y 节点

**CodeMirror 5 修复**：
- `.CodeMirror { clip-path: none; overflow: visible; height: auto }` —— **关键**：CM5 用 `clip-path: inset(0)` 裁剪溢出内容，即使 `overflow: visible` 也会被裁掉
- `.CodeMirror-scroll { overflow: visible; height: auto; margin-bottom: 0; padding-bottom: 0 }`
- `.CodeMirror-sizer { margin-left: 0; min-width: 0 }` —— 行号列删了，预留空间要清零
- `.CodeMirror-line, .CodeMirror-line>span { white-space: pre-wrap; word-break: break-word; overflow-wrap: anywhere }`

**CodeMirror 6 修复**：
- `.cm-scroller { display: block; overflow: visible; height: auto }`
- `.cm-content { white-space: pre-wrap; word-break: break-word; overflow-wrap: anywhere; min-width: 0; width: 100% }`

### 4.3 打印覆盖样式（放在内联 CSS **之后**，以最高优先级覆盖）

```css
:root {
  --ls-left-sidebar-width: 0px !important;
  --ls-right-sidebar-width: 0px !important;
  --ls-page-max-width: 860px !important;
}
#main-content-container {
  max-width: 860px !important;
  margin: 0 auto !important;
  padding: 1.5rem !important;
  border: none !important;
  box-shadow: none !important;
}
/* 隐藏交互 UI */
.block-control, .bullet-container, .open-block-ref-link,
.block-children-left-border, .ls-block-right-toolbar,
.cp__sidebar-help-btn { display: none !important }

@media print {
  /* 保留背景色和图片（浏览器默认打印时会剥除！） */
  * {
    -webkit-print-color-adjust: exact !important;
    color-adjust: exact !important;
    print-color-adjust: exact !important;
  }
}
```

### 4.4 资源 URL 修复

```clojure
(.replaceAll (.-outerHTML main-clone) "assets://" "file://")
```

Logseq 内部用 `assets://` 协议指向本地资源；blob 窗口不认识这个协议，必须换成 `file://`。

### 4.5 组装 + 触发打印

```clojure
(str "<!DOCTYPE html>"
     "<html class='" (.-className html-el) "'"
     (when (seq html-style) (str " style='" html-style "'"))  ;; 保留 :root 内联变量
     ">"
     "<head><meta charset='UTF-8'>" print-css "</head>"
     "<body>"
     "<div id='app-container'><div id='main-container'>" main-html "</div></div>"
     "<script>window.onload=function(){window.print();}</script>"
     "</body></html>")
```

然后：

```clojure
(let [blob (js/Blob. #js [full-html] #js {:type "text/html"})
      url  (js/URL.createObjectURL blob)]
  (js/window.open url "_blank")
  (js/setTimeout #(js/URL.revokeObjectURL url) 60000))  ;; 60s 足够用户选打印机
```

---

## 五、Sheet（Univer 电子表格）的特殊处理

Univer 用 Canvas 渲染，**无法** 被 DOM-clone 捕获。解决方案：**在 Sheet 组件里始终持有一份隐藏的 HTML 表格镜像**，打印时换它出来。

### 5.1 始终存在的镜像表格

[extensions/sheet/core.cljs:450](src/main/frontend/extensions/sheet/core.cljs)：
```clojure
[:div.sheet-print-container
 {:ref (fn [el] (reset! (::print-container state) el))}]
```

### 5.2 镜像内容同步

3 秒 cache timer 里：
```clojure
(when-let [el @(::print-container state)]
  (when-let [html (build-print-table-html json)]
    (set! (.-innerHTML el) html)))
```

以及 mount 时初始化 + did-update 响应数据变化。

### 5.3 构建表格 HTML

`build-print-table-html` ([core.cljs:167](src/main/frontend/extensions/sheet/core.cljs:167))：
- 读 `sheetOrder[0]` → `sheets[id].cellData`
- 按 `max-row` × `max-col` 铺表
- `<thead>` 画列头 A, B, C... + 行号（`col-letter` 函数做 Excel 风格转换）
- 每个 `<td>` 里取 `cell.v` 作为显示值

共享 HTML 构建器也存在于 [extensions/sheet/preview.cljs](src/main/frontend/extensions/sheet/preview.cljs)，被 inline embed card 复用（预览上限 200 行 × 50 列）。

### 5.4 CSS 切换（[common.css:302+](src/main/frontend/common.css)）

```css
/* 默认隐藏镜像表格 */
.sheet-print-container, .sheet-print-table { display: none; }

@media print {
  .sheet-univer-container { display: none !important; }  /* 藏 Canvas */
  .sheet-sync-status { display: none !important; }       /* 藏同步指示器 */
  .sheet-print-container { display: block !important; }
  .sheet-print-table { display: table !important; }
  .sheet-print-table thead th {
    background: #f0f1f4 !important;
    -webkit-print-color-adjust: exact;  /* 列头灰色必须保留 */
  }
  .sheet-print-table tr { page-break-inside: avoid; }  /* 行不跨页 */
}
```

---

## 六、坑位清单（踩过的坑）

| # | 坑 | 解决 |
|---|---|---|
| 1 | blob 窗口的 `<link rel=stylesheet>` 异步，`window.print()` 先触发 → 没样式 | 通过 CSSOM 全量内联 CSS |
| 2 | 跨域 stylesheet `cssRules` 会抛 SecurityError | `try/catch` 跳过 |
| 3 | CM5 用 `clip-path: inset(0)` 裁剪，`overflow:visible` 也没用 | DOM 层显式 `el.style.clipPath = "none"` |
| 4 | 删掉 `.CodeMirror-gutters` 后 `.CodeMirror-sizer` 左边多出空白 | `margin-left: 0; min-width: 0` |
| 5 | 代码块在窄页 break-all 把单词拦腰劈开 | 用 `word-break: break-word; overflow-wrap: anywhere` |
| 6 | 浏览器打印默认剥除背景色 | `print-color-adjust: exact` + `@media print` 内重复一次 |
| 7 | `assets://` 协议在 blob 窗口加载不出 | 字符串替换 `assets:// → file://` |
| 8 | Sheet Canvas 无法克隆 | 始终持一份 `.sheet-print-table` 镜像，@media print 切换显隐 |
| 9 | CSS 变量只在 `<html style>` 上（Logseq 动态设置主题色时）丢失 | 拷贝 `html-el.style.cssText` 到 blob `<html style>` |
| 10 | 侧边栏宽度变量 `--ls-left-sidebar-width` 导致内容区偏移 | `:root { --ls-*-sidebar-width: 0px !important }` |

---

## 七、新增需要打印的动态内容时的检查清单

如果你要让一个新类型的块（比如白板、思维导图）在 PDF 里正确呈现：

1. **它是 Canvas / WebGL 吗？**
   - 是 → 参考 Sheet 方案：渲染一份隐藏 HTML / SVG 镜像
   - 否 → 继续

2. **它有 `clip-path` / `overflow:hidden` 吗？**
   - 有 → 在 `export-as-pdf!` 的 DOM 清洗步骤里加 `clip-path:none; overflow:visible; height:auto`

3. **它依赖滚动容器才能看全内容吗？**
   - 是 → scroll 容器设 `overflow:visible; height:auto`

4. **它有交互 UI（控制按钮、指示器）吗？**
   - 是 → 在 print-css 的 `display:none` 列表里加选择器

5. **它有背景色 / 图片需要保留吗？**
   - 是 → 确认 `print-color-adjust:exact` 生效（已全局开启，一般不用再加）

6. **它用 Shadow DOM 吗？**
   - ⚠️ **注意**：`cloneNode(true)` **不穿透** Shadow DOM，内部节点不会被克隆。没通用方案，需要组件自己暴露 light DOM 镜像。

---

## 八、调试技巧

```javascript
// 在 blob 窗口里按 Ctrl+P 打开打印预览前，先按 Ctrl+Shift+I 开 DevTools
// 看实际渲染效果。blob 窗口的 console 和主窗口是分开的。

// 如果 PDF 里某个元素消失，先检查：
// 1. DevTools → Elements → 该元素是否在 DOM 中
// 2. DevTools → 切换 Rendering → Emulate CSS media type: print
// 3. 查 @media print 的样式是否被 !important 盖掉
```

在 `export-as-pdf!` 里已经埋了一些 `console.log`：
- `[pdf] CodeMirror (CM5) editors found: N`
- `[pdf] CM5 .CodeMirror-scroll found: N`
- `[pdf] CM6 .cm-scroller found: N`
- `[pdf] removing N .sel`

主窗口 Console 能看到，blob 窗口看不到（因为 clone 发生在主窗口）。

---

## 九、不要做的事

- ❌ 不要引入 `jsPDF` / `html2pdf.js` —— 理由见第二节
- ❌ 不要用 `html2canvas` 做 PDF —— 文本会变成栅格图，失去可搜索性
- ❌ 不要在 `export-as-pdf!` 里 `await` 网络请求 —— blob 窗口的 `onload` 不会等你
- ❌ 不要把 `window.print()` 写在主窗口 —— 会把 Logseq 本体连同侧栏一起打印
- ❌ 不要改 `assets://` 到 `http://` —— `file://` 才是 Electron 的本地资源映射
- ❌ 不要删 `60000` 这个 timeout —— 打印对话框没关用户就 revoke URL 了会炸
