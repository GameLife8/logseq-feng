(ns frontend.components.export
  (:require ["/frontend/utils" :as utils]
            [cljs-time.core :as t]
            [cljs.pprint :as pprint]
            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.db :as db]
            [frontend.handler.block :as block-handler]
            [frontend.handler.db-based.export :as db-export-handler]
            [frontend.handler.export :as export]
            [frontend.handler.export.html :as export-html]
            [frontend.handler.export.opml :as export-opml]
            [frontend.handler.export.text :as export-text]
            [frontend.handler.notification :as notification]
            [frontend.image :as image]
            [frontend.mobile.util :as mobile-util]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [logseq.db :as ldb]
            [logseq.db.sqlite.export :as sqlite-export]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]
            [rum.core :as rum]))

(rum/defcs auto-backup < rum/reactive
  {:init (fn [state]
           (assoc state ::folder (atom (ldb/get-key-value (db/get-db) :logseq.kv/graph-backup-folder))))}
  [state]
  (let [*backup-folder (::folder state)
        backup-folder (rum/react *backup-folder)
        repo (state/get-current-repo)]
    [:div.flex.flex-col.gap-4
     [:div.font-medium.opacity-50
      "Schedule backup"]
     (if (utils/nfsSupported)
       [:<>
        (if backup-folder
          [:div.flex.flex-row.items-center.gap-1.text-sm
           [:div.opacity-50 (str "Backup folder:")]
           backup-folder
           (shui/button
            {:variant :ghost
             :class "!px-1 !py-1"
             :title "Change backup folder"
             :on-click (fn []
                         (p/do!
                          (db/transact! [[:db/retractEntity :logseq.kv/graph-backup-folder]])
                          (reset! *backup-folder nil)))
             :size :sm}
            (ui/icon "edit"))]
          (shui/button
           {:variant :default
            :on-click (fn []
                        (p/let [[folder-name _handle] (export/choose-backup-folder repo)]
                          (reset! *backup-folder folder-name)))}
           "Set backup folder first"))
        [:div.opacity-50.text-sm
         "Backup will be created every hour."]

        (when backup-folder
          (shui/button
           {:variant :default
            :on-click (fn []
                        (->
                         (p/let [result (export/backup-db-graph repo)]
                           (case result
                             true
                             (notification/show! "Backup successful!" :success)
                             :graph-not-changed
                             (notification/show! "Graph has not been updated since last export." :success)
                             nil)
                           (export/auto-db-backup! repo))
                         (p/catch (fn [error]
                                    (println "Failed to backup.")
                                    (js/console.error error)))))}
           "Backup now"))]
       [:div
        [:span "Your browser doesn't support "]
        [:a
         {:href "https://developer.chrome.com/docs/capabilities/web-apis/file-system-access"
          :target "_blank"}
         "The File System Access API"]
        [:span ", please switch to a Chromium-based browser."]])]))

(rum/defc export
  []
  (when-let [current-repo (state/get-current-repo)]
    [:div.export
     [:h1.title.mb-8 (t :export)]

     [:div.flex.flex-col.gap-4.ml-1
      [:div
       [:a.font-medium {:on-click #(export/export-repo-as-sqlite-db! current-repo)}
        (t :export-sqlite-db)]
       [:p.text-sm.opacity-70.mb-0 "Primary way to backup graph's content to a single .sqlite file."]]
      [:div
       [:a.font-medium {:on-click #(export/export-repo-as-zip! current-repo)}
        (t :export-zip)]
       [:p.text-sm.opacity-70.mb-0 "Primary way to backup graph's content and assets to a .zip file."]]

      (when-not (util/mobile?)
        [:div
         [:a.font-medium {:on-click #(db-export-handler/export-repo-as-db-edn! current-repo)}
          (t :export-db-edn)]
         [:p.text-sm.opacity-70.mb-0 "Exports to a readable and editable .edn file. Don't rely on this as a primary backup."]])
      (when-not (mobile-util/native-platform?)
        [:div
         [:a.font-medium {:on-click #(export-text/export-repo-as-markdown! current-repo)}
          (t :export-markdown)]])

      (when (util/electron?)
        [:div
         [:a.font-medium {:on-click #(export/download-repo-as-html! current-repo)}
          (t :export-public-pages)]])

      [:div
       [:a.font-medium {:on-click #(export/export-repo-as-debug-transit! current-repo)}
        "Export debug transit file"]
       [:p.text-sm.opacity-70.mb-0 "Exports to a .transit file to send to us for debugging. Any sensitive data will be removed in the exported file."]]

      (if (util/electron?)
        [:div
         [:hr]
         [:div "Hourly backups are enabled for this graph, "
          [:a.ml-1 {:on-click (fn []
                                (let [path (config/get-electron-backup-dir (state/get-current-repo))]
                                  (js/window.apis.openPath path)))}
           "open backups folder for this graph"]]]
        (when (and util/web-platform?
                   (not (util/mobile?)))
          [:div
           [:hr]
           (auto-backup)]))]]))

(def *export-block-type (atom :text))

(def text-indent-style-options [{:label "dashes"
                                 :selected false}
                                {:label "spaces"
                                 :selected false}
                                {:label "no-indent"
                                 :selected false}])

(defn- export-helper
  [top-level-ids]
  (let [current-repo (state/get-current-repo)
        text-indent-style (state/get-export-block-text-indent-style)
        text-remove-options (set (state/get-export-block-text-remove-options))
        text-other-options (state/get-export-block-text-other-options)
        tp @*export-block-type]
    (case tp
      :text (export-text/export-blocks-as-markdown
             current-repo top-level-ids
             {:indent-style text-indent-style :remove-options text-remove-options :other-options text-other-options})
      :opml (export-opml/export-blocks-as-opml
             current-repo top-level-ids {:remove-options text-remove-options :other-options text-other-options})
      :html (export-html/export-blocks-as-html
             current-repo top-level-ids {:remove-options text-remove-options :other-options text-other-options})
      "")))

(defn- <export-edn-helper
  [root-block-uuids-or-page-uuid export-type]
  (let [export-args (case export-type
                      :page
                      {:page-id [:block/uuid (first root-block-uuids-or-page-uuid)]}
                      :block
                      {:block-id [:block/uuid (first root-block-uuids-or-page-uuid)]}
                      :selected-nodes
                      {:node-ids (mapv #(vector :block/uuid %) root-block-uuids-or-page-uuid)}
                      {})]
    (p/let [export-edn (state/<invoke-db-worker :thread-api/export-edn
                                                (state/get-current-repo)
                                                (merge {:export-type export-type} export-args))]
      ;; Don't validate :block for now b/c it requires more setup
      (if (#{:page :selected-nodes} export-type)
        (if-let [error (:error (sqlite-export/validate-export export-edn))]
          (do
            (js/console.log "Invalid export EDN:")
            (pprint/pprint export-edn)
            {:export-edn-error error})
          export-edn)
        export-edn))))

(defn- get-image-blob
  [block-uuids-or-page-name {:keys [transparent-bg? x y width height zoom]} callback]
  (let [top-block-id (if (coll? block-uuids-or-page-name) (first block-uuids-or-page-name) block-uuids-or-page-name)
        style (js/window.getComputedStyle js/document.body)
        background (when-not transparent-bg? (.getPropertyValue style "--ls-primary-background-color"))
        page? (and (uuid? top-block-id) (db/page? (db/entity [:block/uuid top-block-id])))
        selector (if page?
                   "#main-content-container"
                   (str "[blockid='" top-block-id "']"))
        container  (js/document.querySelector selector)
        scale (if page? (/ 1 (or zoom 1)) 1)
        options #js {:allowTaint true
                     :useCORS true
                     :backgroundColor (or background "transparent")
                     :x (or (/ x scale) 0)
                     :y (or (/ y scale) 0)
                     :width (when width (/ width scale))
                     :height (when height (/ height scale))
                     :scrollX 0
                     :scrollY 0
                     :scale scale
                     :windowHeight (when page?
                                     (.-scrollHeight container))}]
    (-> (js/html2canvas container options)
        (.then (fn [canvas] (.toBlob canvas (fn [blob]
                                              (when blob
                                                (let [img (js/document.getElementById "export-preview")
                                                      img-url (image/create-object-url blob)]
                                                  (set! (.-src img) img-url)
                                                  (callback blob)))) "image/png"))))))

(defn- get-top-level-uuids
  [selection-ids]
  (->> (block-handler/get-top-level-blocks (map #(db/entity [:block/uuid %]) selection-ids))
       (map :block/uuid)))

(defn- collect-inline-css
  "读取当前页面所有已加载样式表的 CSS 文本（通过 CSSOM，无需网络请求）。
   用于在打印窗口中内联所有样式，避免 blob 窗口加载外部 stylesheet 时的
   时序问题（stylesheet 未加载完就触发 window.print()）或 CSP 限制。"
  []
  (->> (array-seq js/document.styleSheets)
       (map (fn [^js sheet]
              (try
                (->> (array-seq (.-cssRules sheet))
                     (map #(.-cssText %))
                     (clojure.string/join "\n"))
                (catch :default _
                  ;; 跨域 sheet 无法访问 cssRules，跳过
                  ""))))
       (clojure.string/join "\n")))

(defn- export-as-pdf!
  "克隆当前页面实际渲染的 DOM，在新窗口触发打印对话框保存为 PDF。

   核心改进（相对于 link-stylesheet 方案）：
   1. 通过 CSSOM 内联全部 CSS（collect-inline-css），保证 CSS 变量、代码块
      背景色等在 blob 窗口中完整可用，不依赖外部文件加载。
   2. 在克隆前从 DOM 中移除「关联引用」等打印无关区域。
   3. 注入 print-color-adjust:exact，让浏览器保留背景色/图片。
   4. 用 break-word 代替 break-all，代码行仅在词边界换行。"
  [_top-level-uuids]
  (let [doc        js/document
        html-el    (.-documentElement doc)

        ;; ── 内联 CSS（CSSOM 读取，无网络依赖）───────────────────────────
        all-css    (collect-inline-css)

        ;; ── 克隆主内容区域 ──────────────────────────────────────────────
        main-el    (.getElementById doc "main-content-container")
        main-clone (when main-el (.cloneNode main-el true))

        ;; 移除不需要打印的区域（关联引用、侧边栏提示等）
        _          (when main-clone
                     (doseq [sel [".references-blocks-wrap"  ; 「关联引用」区块
                                  ".sidebar-drop-indicator"]]
                       (doseq [^js el (array-seq (.querySelectorAll main-clone sel))]
                         (some-> (.-parentNode el) (.removeChild el)))))

        main-html  (if main-clone
                     (.replaceAll (.-outerHTML main-clone) "assets://" "file://")
                     "")

        ;; ── 打印专用覆盖样式 ────────────────────────────────────────────
        ;; 放在内联 CSS *之后*，以最高优先级覆盖布局变量和交互元素
        print-css  (str "<style>"
                        all-css  ; 全量应用 app 样式（含 CSS 变量 / :root 块）
                        ;; 重置侧边栏宽度变量，防止布局塌陷
                        ":root{"
                        "--ls-left-sidebar-width:0px!important;"
                        "--ls-right-sidebar-width:0px!important;"
                        "--ls-page-max-width:860px!important;"
                        "}"
                        "html,body{writing-mode:horizontal-tb!important;"
                        "margin:0;padding:0;background:#fff}"
                        "#app-container{display:block!important}"
                        "#main-container{display:block!important;"
                        "width:100%!important;max-width:100%!important}"
                        "#main-content-container{display:block!important;"
                        "width:100%!important;max-width:860px!important;"
                        "margin:0 auto!important;padding:1.5rem!important;"
                        "border:none!important;border-radius:0!important;"
                        "box-shadow:none!important}"
                        ;; 代码块：仅修正溢出和换行，颜色由内联 CSS 变量决定
                        ".CodeMirror,.cm-editor{"
                        "overflow:visible!important;"
                        "break-inside:avoid!important}"
                        ".CodeMirror-scroll,.cm-scroller{"
                        "overflow:visible!important}"
                        ".CodeMirror-line,.cm-line{"
                        "white-space:pre-wrap!important;"
                        "word-break:break-word!important;"   ; break-word 而非 break-all
                        "overflow-wrap:break-word!important}"
                        ;; 隐藏交互 UI
                        ".block-control,.bullet-container,.open-block-ref-link,"
                        ".block-children-left-border,.ls-block-right-toolbar,"
                        ".cp__sidebar-help-btn{display:none!important}"
                        "@media print{"
                        ;; 保留背景色和图片（浏览器默认打印时会剥除）
                        "*{-webkit-print-color-adjust:exact!important;"
                        "color-adjust:exact!important;"
                        "print-color-adjust:exact!important}"
                        ".CodeMirror,.cm-editor{overflow:visible!important;"
                        "break-inside:avoid!important}"
                        ".CodeMirror-line,.cm-line{white-space:pre-wrap!important;"
                        "word-break:break-word!important}"
                        "}"
                        "</style>")

        ;; 保留 html 元素上动态设置的 CSS 变量（--ls-* 等内联 style）
        html-style (.. html-el -style -cssText)
        full-html  (str "<!DOCTYPE html>"
                        "<html class='" (.-className html-el) "'"
                        (when (seq html-style) (str " style='" html-style "'"))
                        ">"
                        "<head><meta charset='UTF-8'>"
                        ;; 不再需要 head-clone（stylesheet 链接）：CSS 已全量内联
                        print-css
                        "</head><body>"
                        "<div id='app-container'><div id='main-container'>"
                        main-html
                        "</div></div>"
                        "<script>window.onload=function(){window.print();}</script>"
                        "</body></html>")
        blob       (js/Blob. #js [full-html] #js {:type "text/html"})
        url        (js/URL.createObjectURL blob)]
    (js/window.open url "_blank")
    (js/setTimeout #(js/URL.revokeObjectURL url) 60000)))

(rum/defcs ^:large-vars/cleanup-todo
  export-blocks < rum/static
  (rum/local false ::copied?)
  (rum/local nil ::text-remove-options)
  (rum/local nil ::text-indent-style)
  (rum/local nil ::text-other-options)
  (rum/local nil ::content)
  {:will-mount (fn [state]
                 (let [top-level-uuids (get-top-level-uuids (first (:rum/args state)))]
                   (reset! *export-block-type :opml)
                   (if (= @*export-block-type :png)
                     (do (reset! (::content state) nil)
                         (get-image-blob top-level-uuids
                                         (merge (second (:rum/args state)) {:transparent-bg? false})
                                         (fn [blob] (reset! (::content state) blob))))
                     (reset! (::content state) (export-helper top-level-uuids)))
                   (reset! (::text-remove-options state) (set (state/get-export-block-text-remove-options)))
                   (reset! (::text-indent-style state) (state/get-export-block-text-indent-style))
                   (reset! (::text-other-options state) (state/get-export-block-text-other-options))
                   (assoc state ::top-level-uuids top-level-uuids)))}
  [state _selection-ids {:keys [export-type] :as options}]
  (let [top-level-uuids (::top-level-uuids state)
        tp @*export-block-type
        *text-other-options (::text-other-options state)
        *text-remove-options (::text-remove-options state)
        *text-indent-style (::text-indent-style state)
        *copied? (::copied? state)
        *content (::content state)]
    [:div.export.resize
     {:class "-m-5"}
     [:div.p-6
      [:div.flex.pb-3
       (ui/button "PDF"
                  :class "mr-4 w-20"
                  :on-click #(do (reset! *export-block-type :pdf)
                                 (reset! *content nil)
                                 (export-as-pdf! top-level-uuids)))
       (ui/button "OPML"
                  :class "mr-4 w-20"
                  :on-click #(do (reset! *export-block-type :opml)
                                 (reset! *content (export-helper top-level-uuids))))
       ;; HTML 导出已移除（内容过于简陋）
       ;; TODO: Remove if this is no longer used after whiteboard removal
       (when-not (seq? top-level-uuids)
         (ui/button "PNG"
                    :class "mr-4 w-20"
                    :on-click #(do (reset! *export-block-type :png)
                                   (reset! *content nil)
                                   (get-image-blob top-level-uuids (merge options {:transparent-bg? false}) (fn [blob] (reset! *content blob))))))
       (ui/button "EDN"
                  :class "w-20"
                  :on-click #(do (reset! *export-block-type :edn)
                                 (p/let [result (<export-edn-helper top-level-uuids export-type)
                                         pull-data (with-out-str (pprint/pprint result))]
                                   (if (:export-edn-error result)
                                     (notification/show! (:export-edn-error result) :error)
                                     (reset! *content pull-data)))))]
      (cond
        (= :png tp)
        [:div.flex.items-center.justify-center.relative
         (when (not @*content) [:div.absolute (ui/loading "")])
         [:img {:alt "export preview" :id "export-preview" :class "my-4" :style {:visibility (when (not @*content) "hidden")}}]]

        (= :pdf tp)
        [:div.flex.items-center.justify-center.h-24.opacity-60.text-sm
         "已在新窗口生成打印预览，请在打印对话框中选择「保存为 PDF」"]

        :else
        [:textarea.overflow-y-auto.h-96 {:value @*content :read-only true}])

      (if (= :png tp)
        [:div.flex.items-center
         [:div (t :export-transparent-background)]
         (ui/checkbox {:class "mr-2 ml-4"
                       :on-change (fn [e]
                                    (reset! *content nil)
                                    (get-image-blob top-level-uuids (merge options {:transparent-bg? e.currentTarget.checked}) (fn [blob] (reset! *content blob))))})]
        (let [options (->> text-indent-style-options
                           (mapv (fn [opt]
                                   (if (= @*text-indent-style (:label opt))
                                     (assoc opt :selected true)
                                     opt))))]
          [:div [:div.flex.items-center
                 [:label.mr-4
                  {:style {:visibility (if (= :text tp) "visible" "hidden")}}
                  "Indentation style:"]
                 [:select.block.my-2.text-lg.rounded.border.py-0.px-1
                  {:style {:visibility (if (= :text tp) "visible" "hidden")}
                   :on-change (fn [e]
                                (let [value (util/evalue e)]
                                  (state/set-export-block-text-indent-style! value)
                                  (reset! *text-indent-style value)
                                  (reset! *content (export-helper top-level-uuids))))}
                  (for [{:keys [label value selected]} options]
                    [:option (cond->
                              {:key label
                               :value (or value label)}
                               selected
                               (assoc :selected selected))
                     label])]]
           [:div.flex.items-center
            (ui/checkbox {:class "mr-2"
                          :style {:visibility (if (#{:text :html :opml} tp) "visible" "hidden")}
                          :checked (contains? @*text-remove-options :page-ref)
                          :on-change (fn [e]
                                       (state/update-export-block-text-remove-options! e :page-ref)
                                       (reset! *text-remove-options (state/get-export-block-text-remove-options))
                                       (reset! *content (export-helper top-level-uuids)))})
            [:div {:style {:visibility (if (#{:text :html :opml} tp) "visible" "hidden")}}
             "[[页面]] → 纯文本"]

            (ui/checkbox {:class "mr-2 ml-4"
                          :style {:visibility (if (#{:text :html :opml} tp) "visible" "hidden")}
                          :checked (contains? @*text-remove-options :emphasis)
                          :on-change (fn [e]
                                       (state/update-export-block-text-remove-options! e :emphasis)
                                       (reset! *text-remove-options (state/get-export-block-text-remove-options))
                                       (reset! *content (export-helper top-level-uuids)))})

            [:div {:style {:visibility (if (#{:text :html :opml} tp) "visible" "hidden")}}
             "去除格式标记"]

            (ui/checkbox {:class "mr-2 ml-4"
                          :style {:visibility (if (#{:text :html :opml} tp) "visible" "hidden")}
                          :checked (contains? @*text-remove-options :tag)
                          :on-change (fn [e]
                                       (state/update-export-block-text-remove-options! e :tag)
                                       (reset! *text-remove-options (state/get-export-block-text-remove-options))
                                       (reset! *content (export-helper top-level-uuids)))})

            [:div {:style {:visibility (if (#{:text :html :opml} tp) "visible" "hidden")}}
             "去除 #标签"]]

           [:div.flex.items-center
            (ui/checkbox {:class "mr-2"
                          :style {:visibility (if (#{:text} tp) "visible" "hidden")}
                          :checked (boolean (:newline-after-block @*text-other-options))
                          :on-change (fn [e]
                                       (state/update-export-block-text-other-options!
                                        :newline-after-block (boolean (util/echecked? e)))
                                       (reset! *text-other-options (state/get-export-block-text-other-options))
                                       (reset! *content (export-helper top-level-uuids)))})
            [:div {:style {:visibility (if (#{:text} tp) "visible" "hidden")}}
             "newline after block"]

            (ui/checkbox {:class "mr-2 ml-4"
                          :style {:visibility (if (#{:text} tp) "visible" "hidden")}
                          :checked (contains? @*text-remove-options :property)
                          :on-change (fn [e]
                                       (state/update-export-block-text-remove-options! e :property)
                                       (reset! *text-remove-options (state/get-export-block-text-remove-options))
                                       (reset! *content (export-helper top-level-uuids)))})
            [:div {:style {:visibility (if (#{:text} tp) "visible" "hidden")}}
             "remove properties"]]

           [:div.flex.items-center
            (ui/checkbox {:class "mr-2"
                          :style {:visibility (if (#{:text :html :opml} tp) "visible" "hidden")}
                          :checked (boolean (:open-blocks-only @*text-other-options))
                          :on-change (fn [e]
                                       (state/update-export-block-text-other-options!
                                        :open-blocks-only (boolean (util/echecked? e)))
                                       (reset! *text-other-options (state/get-export-block-text-other-options))
                                       (reset! *content (export-helper top-level-uuids)))})
            [:div {:style {:visibility (if (#{:text :html :opml} tp) "visible" "hidden")}}
             "仅导出展开的块"]]

           [:div.flex.items-center
            [:label.mr-2 {:style {:visibility (if (#{:text :html :opml} tp) "visible" "hidden")}}
             "层级 <="]
            [:select.block.my-2.text-lg.rounded.border.px-2.py-0
             {:style {:visibility (if (#{:text :html :opml} tp) "visible" "hidden")}
              :value (or (:keep-only-level<=N @*text-other-options) :all)
              :on-change (fn [e]
                           (let [value (util/evalue e)
                                 level (if (= "all" value) :all (util/safe-parse-int value))]
                             (state/update-export-block-text-other-options! :keep-only-level<=N level)
                             (reset! *text-other-options (state/get-export-block-text-other-options))
                             (reset! *content (export-helper top-level-uuids))))}
             (for [n (cons "all" (range 1 10))]
               [:option {:key n :value n} n])]]]))

      (when (and @*content (not= :pdf tp))
        [:div.mt-4.flex.flex-row.gap-2
         (ui/button (if @*copied? (t :export-copied-to-clipboard) (t :export-copy-to-clipboard))
                    :class "mr-4"
                    :on-click (fn []
                                (if (= tp :png)
                                  (js/navigator.clipboard.write [(js/ClipboardItem. #js {"image/png" @*content})])
                                  (util/copy-to-clipboard! @*content :html (when (= tp :html) @*content)))
                                (reset! *copied? true)))
         (ui/button (t :export-save-to-file)
                    :on-click #(let [file-name (if (uuid? top-level-uuids)
                                                 (-> (db/get-page top-level-uuids)
                                                     (util/get-page-title))
                                                 (t/now))]
                                 (utils/saveToFile (js/Blob. [@*content]) (str "logseq_" file-name) (if (= tp :text) "txt" (name tp)))))])]]))
