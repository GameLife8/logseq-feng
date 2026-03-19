# Logseq DB 后端调试手册

> 适用版本：DB 版 Logseq（master 分支）
> 更新日期：2026-03-19

---

## 目录

- [环境版本要求](#一环境版本要求)
- [架构概览](#二架构概览)
- [构建命令速查](#三构建命令速查)
- [环境变量参数](#四环境变量参数--closure-defines)
- [开发模式调试](#五开发模式调试)
- [DB Worker 调试](#六db-worker-调试)
- [常见构建问题](#七常见构建问题)
- [DB Sync 本地调试](#八db-sync-本地调试)
- [测试运行](#九测试运行)

---

## 一、环境版本要求

### 运行时

| 工具 | 要求版本 | 说明 |
|------|----------|------|
| **Node.js** | `>= 22.20.0` | 见 `package.json engines` |
| **Java (JDK)** | `11` | 运行 Clojure 编译器 |
| **Clojure CLI** | `1.11.1.1413` | `clojure` 命令行工具 |
| **Yarn** | `1.x` | 包管理器（经典版） |

### 主要依赖版本

| 依赖 | 版本 | 用途 |
|------|------|------|
| `shadow-cljs` | `2.28.23` | ClojureScript 编译器 |
| `@sqlite.org/sqlite-wasm` | `^3.50.3-build1` | 浏览器端 SQLite WASM |
| `comlink` | `^4.4.1` | Web Worker 通信层 |
| `react` | `18.3.1` | UI 框架 |
| `webpack` | `^5.98.0` | JS 打包（db-worker bundle） |
| `tailwindcss` | `3.3.5` | CSS 框架 |
| `datascript` | logseq fork | 内存数据库 / Datalog 查询 |
| `malli` | `0.16.1` | 数据 Schema 验证 |
| `gulp` | `^4.0.2` | 静态资源构建任务 |

---

## 二、架构概览

```
浏览器主线程 (:app target)
│   frontend.core/init
│   └─ React / Rum UI 组件
│   └─ DataScript (内存查询缓存)
│
├── postMessage (Comlink)
│
Web Worker (:db-worker target)
│   frontend.worker.db-worker/init
│   └─ SQLite WASM (@sqlite.org/sqlite-wasm)  ← 数据持久化层
│   └─ DataScript IStorage 实现              ← 查询加速层
│   └─ RTC Sync (deps/db-sync)               ← 多端同步
│
Web Worker (:inference-worker target)
    frontend.inference-worker/init
    └─ AI 推理 / Embedding 计算
```

### DB Worker 双重构建流程

```
ClojureScript 编译 (:db-worker)
    ↓  shadow-cljs (ESM 外部索引模式)
target/db-worker.js
    ↓  webpack 打包 (含 @sqlite.org/sqlite-wasm)
static/js/db-worker-bundle.js
    ↓  shadow-cljs 在 Worker 头部 prepend
importScripts('db-worker-bundle.js');   ← 加载 SQLite WASM
static/js/db-worker.js                  ← 最终 Worker 产物
```

---

## 三、构建命令速查

### 开发模式

```bash
# 安装所有依赖
yarn

# 【最常用】启动浏览器 DB App 开发服务器
yarn app-watch
# 等价于同时运行：
#   gulp watch            → 复制静态资源到 static/（含 sqlite3.wasm）
#   clojure ... watch app db-worker inference-worker  → ClojureScript 热重载
#   npx webpack --watch --config-name app             → 打包 db-worker-bundle.js

# 访问开发服务器
# http://localhost:3001
```

```bash
# 启动完整开发环境（含 Electron + Mobile）
yarn watch

# 仅启动 App（不含 Electron）
yarn app-watch

# 启动 Electron 桌面开发
yarn electron-watch
# 等所有 Build Completed 后，另开终端：
yarn dev-electron-app
```

### 生产构建

```bash
# 构建 Web App（DB 版本）
yarn release-app
# 等价于：
#   gulp:build → 复制所有静态资源
#   clojure ... release app db-worker inference-worker
#   npx webpack build --mode production --config-name app
# 输出目录：static/

# 构建 Electron 桌面版
yarn release-electron
# 输出目录：static/out/

# 构建移动端
yarn release-mobile
```

### 单独编译某个 target

```bash
# 只编译 db-worker（调试构建问题时用）
clojure -M:cljs compile db-worker

# 只编译主应用
clojure -M:cljs compile app

# 同时编译 app + db-worker + inference-worker
clojure -M:cljs compile app db-worker inference-worker

# 生产 release + 开启 source-map 调试
clojure -M:cljs release app db-worker inference-worker --debug
```

### Webpack 单独命令

```bash
# 开发模式监听（打包 db-worker-bundle.js）
npx webpack --watch --config-name app

# 生产模式打包
npx webpack build --mode production --config-name app

# 查看 bundle 分析报告
yarn report
# 输出：report.html
```

---

## 四、环境变量参数 / Closure Defines

### 构建时环境变量

通过 `shadow-cljs.edn` 中 `:closure-defines` 读取的环境变量：

| 环境变量 | 类型 | 默认值 | 说明 |
|----------|------|--------|------|
| `ENABLE_PLUGINS` | bool | `true` | 启用插件系统 |
| `ENABLE_FILE_SYNC_PRODUCTION` | bool | `true` | 使用生产 File Sync 服务器 |
| `ENABLE_RTC_SYNC_PRODUCTION` | bool | `true` | 使用生产 RTC Sync 服务器 |
| `ENABLE_DB_SYNC_LOCAL` | bool | `false` | 使用本地 DB Sync 服务（开发调试用） |
| `LOGSEQ_SENTRY_DSN` | string | - | Sentry 错误追踪 DSN |
| `LOGSEQ_POSTHOG_TOKEN` | string | - | PostHog 埋点 Token |
| `LOGSEQ_REVISION` | string | `"dev"` | 版本标识（由 git-revision-hook 自动设置） |

### 使用示例

```bash
# 启用本地 DB Sync 进行调试
ENABLE_DB_SYNC_LOCAL=true yarn app-watch

# 禁用插件（减少干扰）
ENABLE_PLUGINS=false yarn app-watch

# 同时设置多个参数
ENABLE_DB_SYNC_LOCAL=true ENABLE_PLUGINS=false yarn app-watch

# 生产构建并注入 Sentry
LOGSEQ_SENTRY_DSN=https://xxx@sentry.io/123 yarn release-app
```

### 运行时 Closure Defines（仅 shadow-cljs 内部）

```clojure
;; 在 shadow-cljs.edn 中 --config-merge 覆盖
;; 开发版 release（含调试信息）
clojure -M:cljs release app db-worker inference-worker \
  --config-merge "{:closure-defines {frontend.config/DEV-RELEASE true}}"
```

---

## 五、开发模式调试

### nREPL 连接

`yarn app-watch` 启动后会在 `8701` 端口开启 nREPL 服务。

**VSCode + Calva:**
1. `Cmd+Shift+P` → `Calva: Connect to a Running REPL Server in the Project`
2. 选择 `logseq` → `shadow-cljs` → `:app` → `localhost:8701`

**Emacs + Cider:**
```
M-x cider-connect → localhost:8701
```

### 切换到 DB Worker REPL 上下文

```clojure
;; 在 CLJ nREPL 中执行：
(shadow.user/worker-repl)

;; 或手动指定 runtime-id（在 http://localhost:9630/runtimes 查询）
(shadow/nrepl-select :app {:runtime-id <id>})
```

### 浏览器 DevTools

打开 `http://localhost:3001`，在 DevTools 中：

```javascript
// 查看 DB Worker 状态
// Application → Storage → IndexedDB → logseq-...

// 在 Console 中访问全局状态（cljs 开发模式）
logseq.api.show_msg("hello")
```

### shadow-cljs 管理界面

```
http://localhost:9630        ← 查看所有 build target 状态
http://localhost:9630/runtimes  ← 查看所有运行时（含 Worker runtime-id）
http://localhost:9630/builds    ← 查看构建日志
```

---

## 六、DB Worker 调试

### 关键源码位置

```
src/main/frontend/worker/
├── db_worker.cljs          ← Worker 主入口 (frontend.worker.db-worker/init)
├── db/
│   ├── fix.cljs            ← DB 数据修复
│   ├── migrate.cljs        ← DB Schema 迁移
│   └── validate.cljs       ← 数据校验
├── pipeline.cljs           ← 数据写入管道
├── search.cljs             ← 全文搜索
├── embedding.cljs          ← 向量 embedding
├── undo_redo.cljs          ← 撤销/重做
└── sync/
    ├── client_op.cljs      ← RTC 客户端操作队列
    └── crypt.cljs          ← 端对端加密

deps/db/src/logseq/db/sqlite/
├── build.cljs              ← 图数据构建
├── create_graph.cljs       ← 新图初始化
├── export.cljs             ← 导入/导出
├── gc.cljs                 ← 垃圾回收
└── util.cljs               ← SQLite 工具函数
```

### Worker 通信调试

DB Worker 使用 [Comlink](https://github.com/GoogleChromeLabs/comlink) 通过 `postMessage` 与主线程通信。

在 DevTools → Sources 中可以看到 `db-worker.js` 的 source map（开发模式下自动启用）。

### SQLite WASM 文件位置

```bash
# 构建后 SQLite WASM 文件
static/js/sqlite3.wasm

# 来源
node_modules/@sqlite.org/sqlite-wasm/sqlite-wasm/jswasm/sqlite3.wasm
```

### 查看 SQLite 数据

在浏览器 DevTools → Application → Storage → Origin Private File System (OPFS) 可以查看 SQLite 数据库文件。

---

## 七、常见构建问题

### 1. `clojure: command not found`

```bash
# 安装 Clojure CLI
curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
chmod +x linux-install.sh
sudo ./linux-install.sh
```

### 2. `Execution error: -M:cljs (No such file or directory)`

Clojure 版本错误。请卸载当前版本，安装 Clojure CLI `1.11.1.1413`：
```bash
clojure --version  # 应显示 1.11.1.1413
```

### 3. `db-worker-bundle.js` 未生成

webpack 未启动或未完成。检查：
```bash
# 确保 webpack 任务在运行
npx webpack --watch --config-name app

# 检查 target/ 目录是否有中间文件
ls target/db-worker.js
```

### 4. Worker 连接失败（`No available JS runtime`）

先打开浏览器 `http://localhost:3001`，再连接 nREPL。Worker 必须在浏览器中运行后才能连接。

### 5. SQLite WASM 加载失败

确认 `static/js/sqlite3.wasm` 文件存在：
```bash
ls static/js/sqlite3.wasm

# 如果不存在，手动触发 gulp 资源同步
yarn gulp:build
```

### 6. 构建后 DB 图不显示

检查浏览器控制台是否有 OPFS 权限错误。DB 版本需要 [Origin Private File System](https://developer.mozilla.org/en-US/docs/Web/API/File_System_API/Origin_private_file_system) 支持（Chrome 102+，Firefox 111+）。

---

## 八、DB Sync 本地调试

### 方式一：Cloudflare Worker 本地适配器

```bash
cd deps/db-sync
yarn install
yarn release

# 初始化本地 D1 数据库（只需一次）
cd worker && wrangler d1 migrations apply DB --local && cd -

# 启动本地 Sync 服务（默认 http://localhost:8787）
yarn dev
```

### 方式二：Node.js 本地适配器（自托管）

```bash
cd deps/db-sync
yarn install

# 设置环境变量并构建
DB_SYNC_PORT=8787 \
COGNITO_ISSUER=https://cognito-idp.us-east-2.amazonaws.com/us-east-2_kAqZcxIeM \
COGNITO_CLIENT_ID=1qi1uijg8b6ra70nejvbptis0q \
COGNITO_JWKS_URL=https://cognito-idp.us-east-2.amazonaws.com/us-east-2_kAqZcxIeM/.well-known/jwks.json \
yarn build:node-adapter

# 启动服务
DB_SYNC_PORT=8787 \
COGNITO_ISSUER=https://cognito-idp.us-east-2.amazonaws.com/us-east-2_kAqZcxIeM \
COGNITO_CLIENT_ID=1qi1uijg8b6ra70nejvbptis0q \
COGNITO_JWKS_URL=https://cognito-idp.us-east-2.amazonaws.com/us-east-2_kAqZcxIeM/.well-known/jwks.json \
yarn start:node-adapter

# 可选：指定数据目录（默认 data/db-sync）
DB_SYNC_DATA_DIR=/your/path yarn start:node-adapter
```

### 启动 App 并连接本地 Sync

```bash
# 必须设置 ENABLE_DB_SYNC_LOCAL=true 才能连接本地 Sync 服务
ENABLE_DB_SYNC_LOCAL=true yarn app-watch
```

---

## 九、测试运行

### 编译测试

```bash
# 编译测试文件
clojure -M:test compile test

# 编译无 Worker 测试（跳过 db-sync worker 相关）
yarn cljs:test-no-worker
```

### 运行测试

```bash
# 运行所有测试（需先编译）
yarn cljs:run-test

# 运行无 Worker 测试
yarn cljs:run-test-no-worker

# 运行特定 DB 查询测试
DB_QUERY_TYPE=basic node static/tests.js -r frontend.db.query-dsl-test

# 跳过 fix-me 标记的测试
node static/tests.js -r "^(?!logseq.db-sync.).*" -e fix-me
```

### Malli Schema 代码生成

```bash
# 生成 Malli Kondo 配置
clojure -M:cljs run shadow.cljs.build-report app db-worker inference-worker report.html
```

---

## 附录：build target 对照表

| Target | 入口函数 | 输出文件 | 说明 |
|--------|----------|----------|------|
| `:app` | `frontend.core/init` | `static/js/main.js` | 主应用 UI |
| `:db-worker` | `frontend.worker.db-worker/init` | `static/js/db-worker.js` | DB Worker（SQLite） |
| `:inference-worker` | `frontend.inference-worker/init` | `static/js/inference-worker.js` | AI 推理 Worker |
| `:electron` | `electron.core/main` | `static/electron.js` | Electron 主进程 |
| `:mobile` | `mobile.core/init` | `static/mobile/js/main.js` | 移动端 |
| `:publishing` | `frontend.publishing/init` | `static/js/publishing/main.js` | 发布页 |
| `:test` | `frontend.test...` | `static/tests.js` | 测试套件 |
