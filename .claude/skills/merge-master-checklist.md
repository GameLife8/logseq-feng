# logseq-feng 合并 master 操作手册

> 本手册由 2026-03-30 的一次合并事故总结而来。
> 合并 `bdc931041`（feature ← master）因 index.html 被 master 覆盖，
> 导致 Excalidraw/MindMap 全部失效，排查耗时数小时。

---

## 一、合并前必读

### 1.1 本项目的特殊资源文件

本项目在 master 之上新增了如下**自定义 bundle**，master 并不知晓这些文件：

| 文件 | 说明 | 构建方式 |
|------|------|---------|
| `resources/js/excalidraw-bundle.js` | Excalidraw webpack bundle | `yarn build:excalidraw` |
| `resources/js/mind-map-bundle.js` | simple-mind-map webpack bundle | `yarn build:mind-map` |
| `resources/css/index.css` | Excalidraw 自带 CSS | gulpfile 从 `@excalidraw/excalidraw/dist/prod/index.css` 复制 |

**这三个文件在 master 的 `index.html` 里不存在，每次合并后必须手动核对。**

### 1.2 gulpfile 与 index.html 的对应关系

`gulpfile.js` 控制哪些文件被复制到 `resources/js/` 和 `resources/css/`。
`resources/index.html` 的 `<script>` / `<link>` 必须与 gulpfile 保持一致。

```
# 检查 gulpfile 生成哪些 JS/CSS 文件：
grep -E "marked|excalidraw|mind.map|index\.css" gulpfile.js

# 检查 index.html 引用了什么：
grep -E "<script|<link" resources/index.html
```

---

## 二、合并后立即执行的核查清单

### Step 1：核查所有 HTML 入口文件

```bash
# 找出所有 HTML 入口文件
find resources -name "*.html"
# → resources/index.html
# → resources/marketplace.html
# → resources/mobile/index.html
```

对每个 HTML 文件执行：

```bash
# 与 feature 分支合并前的版本对比
git show <feature-branch-merge-base>:<html-file> | diff - <html-file>
```

#### 必须核查的引用项

| 引用 | 正确值（本项目） | master 错误值 | 验证方法 |
|------|----------------|--------------|---------|
| Excalidraw CSS | `./css/index.css` | 无此行 | `grep index.css resources/index.html` |
| Excalidraw bundle | `./js/excalidraw-bundle.js` | 无此行 | `grep excalidraw resources/index.html` |
| MindMap bundle | `./js/mind-map-bundle.js` | 无此行 | `grep mind-map resources/index.html` |
| marked 库 | `./js/marked.min.js` | `marked.umd.js` | `grep marked resources/*.html resources/mobile/*.html` |

**修复模板（任何 HTML 缺失时）：**

```html
<!-- CSS 区域（<head> 内） -->
<link href="./css/index.css" rel="stylesheet" type="text/css">

<!-- JS 区域（</body> 前） -->
<script defer src="./js/excalidraw-bundle.js"></script>
<script defer src="./js/mind-map-bundle.js"></script>
```

---

### Step 2：核查 shadow-cljs.edn 构建目标

```bash
git show <feature-merge-base>:shadow-cljs.edn | diff - shadow-cljs.edn
```

确认以下构建目标仍然存在：
- `:excalidraw` 构建目标
- `:mind-map` 构建目标

---

### Step 3：核查 gulpfile.js

```bash
git show <feature-merge-base>:gulpfile.js | diff - gulpfile.js
```

确认以下 copy 任务未被删除：
```js
'node_modules/@excalidraw/excalidraw/dist/prod/index.css'  // Excalidraw CSS
'node_modules/marked/marked.min.js'                         // marked 库
```

---

### Step 4：功能文件完整性检查

```bash
# feature 分支新增的所有文件，确认仍然存在
ls src/main/frontend/extensions/excalidraw/
ls src/main/frontend/extensions/mind_map/
ls src/main/frontend/components/whiteboard.cljs
ls src/main/frontend/components/mind_map.cljs
ls src/main/frontend/handler/whiteboard.cljs
ls src/main/frontend/handler/excalidraw-config.cljs
ls src/main/frontend/handler/mind_map.cljs
```

---

### Step 5：ClojureScript 括号平衡验证（重要！）

合并后如果有 `.cljs` 文件冲突，必须验证括号平衡：

```python
# 保存为 check_brackets.py 运行
import sys
def check(path):
    with open(path) as f: content = f.read()
    p = b = 0
    for ch in content:
        if ch == '(': p += 1
        elif ch == ')': p -= 1
        elif ch == '[': b += 1
        elif ch == ']': b -= 1
    print(f"{path}: paren={p} bracket={b}", "OK" if p==0 and b==0 else "ERROR!")

for f in sys.argv[1:]: check(f)
```

```bash
python3 check_brackets.py \
  src/main/frontend/components/whiteboard.cljs \
  src/main/frontend/extensions/excalidraw/core.cljs \
  src/main/frontend/extensions/mind_map/core.cljs
```

---

## 三、已知的合并冲突规律

| 文件 | master 的变化 | 本项目保留策略 |
|------|--------------|--------------|
| `resources/index.html` | 升级 JS/CSS 引用、删除未知 bundle | **保留 feature 分支的 bundle 引用 + index.css，接受 master 的其他更新** |
| `resources/marketplace.html` | `marked.min.js` → `marked.umd.js` | **保留 marked.min.js**（gulpfile 只生成 min 版） |
| `resources/mobile/index.html` | 同上 | **保留 marked.min.js** |
| `packages/ui/package.json` | 依赖升级 | 接受 master，若用 `rm -rf` 改为 `rimraf`（Windows 兼容） |
| `shadow-cljs.edn` | 构建目标调整 | 确认 excalidraw/mind-map 目标未被删除 |
| `tailwind.config.js` | 移除 safelist 项 | 接受 master |
| `postcss.config.js` | 移除 postcss 插件 | 接受 master |

---

## 四、合并操作步骤（标准流程）

```bash
# 1. 在合并前记录 feature 分支最后一个 commit hash
FEATURE_TIP=$(git rev-parse HEAD)

# 2. 执行合并
git fetch origin master
git merge origin/master --allow-unrelated-histories

# 3. 解决冲突后，执行核查清单
#    重点：index.html / marketplace.html / mobile/index.html

# 4. 对比 HTML 文件
git show $FEATURE_TIP:resources/index.html | diff - resources/index.html
git show $FEATURE_TIP:resources/marketplace.html | diff - resources/marketplace.html
git show $FEATURE_TIP:resources/mobile/index.html | diff - resources/mobile/index.html

# 5. 验证括号平衡
python3 check_brackets.py src/main/frontend/components/whiteboard.cljs \
  src/main/frontend/extensions/excalidraw/core.cljs \
  src/main/frontend/extensions/mind_map/core.cljs

# 6. 功能验证：打开白板和思维导图，确认正常渲染
```

---

## 五、症状 → 根因 速查表

| 症状 | 根因 | 修复 |
|------|------|------|
| 白板画布只显示巨大工具栏图标，布局崩坏 | `index.css` 未加载（Excalidraw 失去内部 CSS） | 在 index.html 恢复 `<link href="./css/index.css">` |
| 白板/思维导图入口点击无反应，控制台 `ExcalidrawLib is undefined` | `excalidraw-bundle.js` 或 `mind-map-bundle.js` 未加载 | 在 index.html 恢复对应 `<script>` 标签 |
| Markdown 渲染异常，控制台 404 marked | `marked.umd.js` 404（gulpfile 只生成 min） | 改回 `marked.min.js` |
| `Cannot infer target type` 编译警告 | JS 互操作缺少 `^js` 类型提示 | 添加 `^js` hint：`(.-prop ^js obj)` |
| 组件渲染异常 / 奇怪报错 | `.cljs` 文件括号不平衡 | 用 check_brackets.py 定位并修复 |
