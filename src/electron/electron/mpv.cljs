(ns electron.mpv
  "MPV 进程管理与 JSON IPC 通信。
   架构：
   1. 用户在设置中配置 mpv 可执行文件路径（不打包进应用）
   2. 启动时以 --idle --input-ipc-server=<socket> 运行 mpv
   3. 通过 Unix socket / Named pipe 用 JSON 行协议与 mpv 通信
   4. 前端通过 ipcMain.handle 'mpv-control' channel 调用本模块"
  (:require ["child_process" :as cp]
            ["fs" :as fs]
            ["net" :as net]
            ["os" :as os]
            ["path" :as node-path]
            [clojure.string :as string]
            [electron.logger :as logger]
            [promesa.core :as p]))

;; ── 状态 ──────────────────────────────────────────────────────────────────────

(defonce ^:private *state
  (atom {:proc       nil    ; ChildProcess
         :socket     nil    ; net.Socket（连接到 mpv socket）
         :sock-path  nil    ; socket 文件路径
         :buf        ""     ; 接收缓冲（逐行解析 JSON）
         :req-id     0      ; 自增请求 ID
         :pending    {}     ; {req-id -> promise resolve fn}
         :props      {:playing? false
                      :paused?  false
                      :path     nil
                      :duration 0
                      :position 0
                      :volume   80}}))

;; ── Socket 路径 ───────────────────────────────────────────────────────────────

(defn- sock-path []
  (if (= (.-platform js/process) "win32")
    "\\\\.\\pipe\\mpv-logseq"
    (node-path/join (.tmpdir os) "mpv-logseq.sock")))

;; ── JSON 行读取 ───────────────────────────────────────────────────────────────

(defn- handle-mpv-event! [line]
  (try
    (let [obj (js->clj (js/JSON.parse line) :keywordize-keys true)]
      (cond
        ;; 异步属性查询响应
        (:request_id obj)
        (let [resolve-fn (get (:pending @*state) (:request_id obj))]
          (when resolve-fn
            (swap! *state update :pending dissoc (:request_id obj))
            (resolve-fn (:data obj))))

        ;; 属性变更事件（mpv --observe-property）
        (= (:event obj) "property-change")
        (let [name (:name obj)
              val  (:data obj)]
          (cond
            (= name "time-pos")   (swap! *state assoc-in [:props :position] (or val 0))
            (= name "duration")   (swap! *state assoc-in [:props :duration] (or val 0))
            (= name "pause")      (do (swap! *state assoc-in [:props :paused?]  (boolean val))
                                      (swap! *state assoc-in [:props :playing?] (not (boolean val))))
            (= name "path")       (swap! *state assoc-in [:props :path] val)
            (= name "ao-volume")  (swap! *state assoc-in [:props :volume] (int (or val 80)))
            (= name "idle-active")(when val
                                    (swap! *state assoc-in [:props :playing?] false)
                                    (swap! *state assoc-in [:props :paused?]  false))))

        ;; 文件加载完成
        (= (:event obj) "playback-restart")
        (swap! *state assoc-in [:props :playing?] true)))
    (catch :default _)))

(defn- on-socket-data! [^js data]
  (let [new-buf  (str (:buf @*state) (.toString data))
        lines    (string/split new-buf "\n")
        complete (butlast lines)
        rest-buf (last lines)]
    (swap! *state assoc :buf rest-buf)
    (doseq [line complete]
      (when (seq (string/trim line))
        (handle-mpv-event! line)))))

;; ── MPV 命令发送 ──────────────────────────────────────────────────────────────

(defn- send-cmd!
  "向 mpv socket 发送 JSON 命令，返回 Promise。"
  [cmd-vec & [opts]]
  (let [socket (:socket @*state)]
    (if-not socket
      (p/rejected (js/Error. "mpv socket 未连接"))
      (if (:no-reply opts)
        ;; 不需要返回值的命令（如 loadfile, pause 等）
        (let [msg (str (js/JSON.stringify (clj->js {:command cmd-vec})) "\n")]
          (.write socket msg)
          (p/resolved nil))
        ;; 需要返回值的命令（get_property 等）
        (let [req-id (inc (:req-id @*state))
              msg    (str (js/JSON.stringify
                           (clj->js {:command    cmd-vec
                                     :request_id req-id}))
                          "\n")]
          (swap! *state assoc :req-id req-id)
          (p/create
           (fn [resolve _reject]
             (swap! *state assoc-in [:pending req-id] resolve)
             (.write socket msg)
             ;; 超时兜底（500ms）
             (js/setTimeout #(when (get-in @*state [:pending req-id])
                               (swap! *state update :pending dissoc req-id)
                               (resolve nil))
                            500))))))))

;; ── 连接到 socket ─────────────────────────────────────────────────────────────

(defn- connect-socket! []
  (p/create
   (fn [resolve reject]
     (let [path   (:sock-path @*state)
           sock   (.createConnection net path)]
       (.on sock "connect"
            (fn []
              (logger/info "[mpv] socket 已连接" path)
              (swap! *state assoc :socket sock)
              ;; 订阅关键属性变更事件
              (doseq [prop ["pause" "time-pos" "duration" "path" "ao-volume" "idle-active"]]
                (send-cmd! ["observe_property" 1 prop] {:no-reply true}))
              (resolve true)))
       (.on sock "data" on-socket-data!)
       (.on sock "error"
            (fn [e]
              (logger/warn "[mpv] socket 错误" (.-message e))
              (swap! *state assoc :socket nil)
              (reject e)))
       (.on sock "close"
            (fn []
              (logger/info "[mpv] socket 关闭")
              (swap! *state assoc :socket nil)))))))

;; ── 启动 MPV ─────────────────────────────────────────────────────────────────

(defn start! [mpv-path volume]
  (if-let [proc (:proc @*state)]
    ;; 已经运行中
    (if (.exitCode proc)
      (do (swap! *state assoc :proc nil :socket nil)
          (start! mpv-path volume))
      (p/resolved #js {:ok true}))
    ;; 启动新进程
    (do
      (let [sp (sock-path)
            _  (when (and (not= (.-platform js/process) "win32")
                          (fs/existsSync sp))
                 (fs/unlinkSync sp))
            _  (swap! *state assoc :sock-path sp)
            proc (cp/spawn mpv-path
                           (clj->js ["--idle"
                                     "--no-video"
                                     (str "--input-ipc-server=" sp)
                                     (str "--volume=" volume)
                                     "--force-window=no"])
                           (clj->js {:detached false}))]
        (swap! *state assoc :proc proc)
        (.on proc "error"
             (fn [e]
               (swap! *state assoc :proc nil :socket nil)
               (logger/error "[mpv] 进程错误" (.-message e))))
        (.on proc "exit"
             (fn [code]
               (logger/info "[mpv] 进程退出，code=" code)
               (swap! *state assoc :proc nil :socket nil)))
        ;; 等待 socket 出现（最多 5s，每 200ms 尝试一次）
        (p/create
         (fn [resolve reject]
           (let [attempts (atom 0)
                 try-connect!
                 (fn try-connect! []
                   (if (> @attempts 25)
                     (reject (js/Error. "等待 mpv socket 超时"))
                     (if (fs/existsSync sp)
                       (-> (connect-socket!)
                           (.then #(resolve #js {:ok true}))
                           (.catch (fn [_]
                                     (swap! attempts inc)
                                     (js/setTimeout try-connect! 200))))
                       (do (swap! attempts inc)
                           (js/setTimeout try-connect! 200)))))]
             (js/setTimeout try-connect! 300))))))))

;; ── 公开 API（通过 ipcMain.handle 'mpv-control' 调用）────────────────────────

(defn kill! []
  (when-let [proc (:proc @*state)]
    (.kill proc))
  (when-let [sock (:socket @*state)]
    (.destroy sock))
  (swap! *state assoc :proc nil :socket nil))

(defn play! [path]
  (send-cmd! ["loadfile" path] {:no-reply true}))

(defn pause! []
  (send-cmd! ["set_property" "pause" true] {:no-reply true}))

(defn resume! []
  (send-cmd! ["set_property" "pause" false] {:no-reply true}))

(defn seek! [pos]
  (send-cmd! ["seek" pos "absolute"] {:no-reply true}))

(defn set-volume! [v]
  (send-cmd! ["set_property" "ao-volume" v] {:no-reply true}))

(defn status []
  (:props @*state))

(defn list-music! [folder]
  "递归扫描文件夹及所有子文件夹中的音乐文件。"
  (let [exts #{".mp3" ".flac" ".ogg" ".wav" ".aac" ".m4a" ".opus" ".wma"}
        results (atom [])]
    (letfn [(scan! [dir]
              (try
                (let [entries (js->clj (.readdirSync fs dir (clj->js {:withFileTypes true})))]
                  (doseq [entry entries]
                    (let [name  (.-name entry)
                          full  (node-path/join dir name)]
                      (cond
                        ;; 跳过隐藏文件/文件夹
                        (string/starts-with? name ".") nil
                        (.isDirectory entry) (scan! full)
                        (.isFile entry)
                        (when (exts (string/lower-case
                                     (subs name (max 0 (or (string/last-index-of name ".") 0)))))
                          (swap! results conj {:path full :name name}))))))
                (catch :default e
                  (logger/warn "[mpv] scan error in" dir ":" (.-message e)))))]
      (scan! folder)
      (p/resolved (clj->js (sort-by :path @results))))))

