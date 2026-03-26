(ns frontend.components.mind-map
  "思维导图组件：画廊页 + 独立编辑器。

   架构：
     frontend.extensions.mind-map.core – simple-mind-map 画布封装（懒加载模块）
     frontend.handler.mind-map         – DB 读写（多文档支持）
     frontend.components.mind-map      – 画廊 UI + 路由入口"
  (:require [clojure.string :as str]
            [datascript.core :as d]
            [frontend.db :as db]
            [frontend.db-mixins :as db-mixins]
            [frontend.db.async :as db-async]
            [frontend.db.react :as react]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.mind-map :as mind-map-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.route :as route-handler]
            [frontend.search :as search]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]
            [rum.core :as rum]
            [shadow.lazy :as lazy]))

;; ── 懒加载 mind-map 模块 ─────────────────────────────────────────────────────

#_:clj-kondo/ignore
(def ^:private lazy-mind-map
  (lazy/loadable frontend.extensions.mind-map.core/editor))

(defonce ^:private *mind-map-loaded? (atom false))

(defn- ensure-mind-map-loaded! [on-done]
  (if @*mind-map-loaded?
    (on-done)
    (lazy/load lazy-mind-map
               (fn []
                 (reset! *mind-map-loaded? true)
                 (on-done)))))

;; ── 思维导图编辑器页 ──────────────────────────────────────────────────────────

(rum/defcs mind-map-page
  "单个思维导图编辑器。route-match 提供 :path-params {:name <page-uuid>}。"
  < rum/reactive
  (rum/local false ::loaded?)
  {:did-mount
   (fn [state]
     (ensure-mind-map-loaded!
      (fn [] (reset! (::loaded? state) true)))
     state)}
  [state route-match]
  (let [loaded?   (rum/react (::loaded? state))
        page-uuid (get-in route-match [:path-params :name])
        page      (when (and page-uuid (util/uuid-string? page-uuid))
                    (db/entity [:block/uuid (uuid page-uuid)]))
        map-title (or (:block/title page) "思维导图")]
    (cond
      (not page)
      [:div.flex.flex-col.items-center.justify-center.gap-4
       {:style {:height "80vh"}}
       (ui/icon "hierarchy" {:size 48 :class "opacity-30"})
       [:div.text-sm.opacity-60 "思维导图未找到"]
       (shui/button
        {:on-click #(route-handler/redirect! {:to :all-mind-maps})}
        "返回列表")]

      (not loaded?)
      [:div.mind-map-page
       {:style {:width "100%" :height "100%" :display "flex" :flexDirection "column"}}
       [:div {:style {:display "flex" :alignItems "center" :justifyContent "center"
                      :flex "1" :color "var(--ls-secondary-text-color,#666)"}}
        "加载思维导图中…"]]

      :else
      [:div.mind-map-page
       {:style {:width "100%" :height "100%" :display "flex" :flexDirection "column"}}
       (@lazy-mind-map
        {:map-id       page-uuid
         :map-title    map-title
         :on-back      (fn [] (route-handler/redirect! {:to :all-mind-maps}))
         :on-load-data mind-map-handler/load-mind-map-from-db
         :on-save-data mind-map-handler/save-mind-map-to-db!
         ;; Open a linked block (by UUID string) in the right sidebar.
         ;; Uses db-async/<get-block to ensure the block is loaded into the
         ;; main-thread DataScript before looking it up (same pattern as whiteboard).
         :on-open-block (fn [uuid-str]
                          (when (seq uuid-str)
                            (let [uid (try (uuid uuid-str) (catch :default e (js/console.error "[mind-map] invalid uuid:" uuid-str e) nil))]
                              (when uid
                                (p/let [_ (db-async/<get-block (state/get-current-repo) uid :children? false)]
                                  (editor-handler/open-block-in-sidebar! uid))))))
         ;; Create a new note block under the mind-map page.
         ;; Returns a Promise resolving to the new block's UUID string.
         :on-add-note-block
         (fn []
           (js/console.log "[mind-map] on-add-note-block called, page-uuid:" page-uuid)
           (let [repo (state/get-current-repo)]
             (p/let [result (editor-handler/api-insert-new-block!
                             ""
                             {:page         (uuid page-uuid)
                              :edit-block?  false
                              :end?         true
                              :container-id :unknown-container})]
               (js/console.log "[mind-map] on-add-note-block result:" (clj->js result))
               (when result
                 (state/sidebar-add-block! repo (:db/id result) :block)
                 (str (:block/uuid result))))))
         ;; Search blocks: returns a JS Promise resolving to a plain-data vector
         :on-search-blocks (fn [q]
                             (when (seq q)
                               (let [res-p (search/block-search
                                            (state/get-current-repo) q
                                            {:built-in? false :enable-snippet? false})]
                                 (when res-p
                                   (.then res-p
                                          (fn [res]
                                            (mapv (fn [block]
                                                    {:block-id    (str (:block/uuid block))
                                                     :block-title (or (:block/title block) "(无标题)")
                                                     :page-title  (some-> (db/entity (:db/id (:block/page block)))
                                                                          :block/title)})
                                                  (take 25 (or res [])))))))))})])))

;; ── 思维导图画廊页 ────────────────────────────────────────────────────────────

(rum/defcs all-mind-maps
  "画廊页：列出所有思维导图，支持新建、重命名、删除。"
  < rum/reactive db-mixins/query
  (rum/local false ::creating?)
  (rum/local "" ::new-name)
  (rum/local nil ::editing-uuid)
  (rum/local "" ::rename-val)
  [state]
  (let [*creating?    (::creating? state)
        *new-name     (::new-name state)
        *editing-uuid (::editing-uuid state)
        *rename-val   (::rename-val state)
        creating?     (rum/react *creating?)
        new-name      (rum/react *new-name)
        editing-uuid  (rum/react *editing-uuid)
        rename-val    (rum/react *rename-val)
        repo          (state/get-current-repo)

        ;; 查找 MindMap class 实体 ID（用于建立响应式订阅）
        mm-class-id   (when-let [db (db/get-db)]
                        (ffirst (d/q '[:find [?e ...]
                                       :where [?e :block/title "MindMap"]
                                              [?e :block/tags ?t]
                                              [?t :db/ident :logseq.class/Tag]]
                                     db)))
        ;; 订阅 MindMap class 对象变更（删除/新增思维导图时 :block/tags 变化会触发）
        ;; 返回 result atom；若 class 尚不存在则退化到直接读本地 DB。
        mm-atom       (when (and repo mm-class-id)
                        (react/q repo [:frontend.worker.react/objects mm-class-id]
                                 {:query-fn (fn [_db _] (mind-map-handler/get-all-mind-maps))}
                                 nil))
        mind-maps     (or (some-> mm-atom rum/react)
                          (mind-map-handler/get-all-mind-maps))

        do-create!
        (fn []
          (let [trimmed (clojure.string/trim new-name)]
            (when (seq trimmed)
              (mind-map-handler/<create-mind-map! trimmed)
              (reset! *creating? false)
              (reset! *new-name ""))))

        do-rename!
        (fn []
          (let [trimmed (clojure.string/trim rename-val)]
            (when (and (seq trimmed) editing-uuid)
              (mind-map-handler/<rename-mind-map! editing-uuid trimmed)
              (reset! *editing-uuid nil)
              (reset! *rename-val ""))))

        cancel-rename!
        (fn [] (reset! *editing-uuid nil) (reset! *rename-val ""))]

    [:div.all-mind-maps-page
     {:style {:padding "24px 28px" :maxWidth "1000px" :margin "0 auto"}}

     ;; ── 标题栏 ──────────────────────────────────────────────────────────────
     [:div.flex.items-center.gap-3.mb-6
      (ui/icon "hierarchy" {:size 22 :class "opacity-70"})
      [:h1.text-xl.font-bold.flex-1 "思维导图"]
      (if creating?
        [:div.flex.items-center.gap-2
         [:input
          {:type        "text"
           :auto-focus  true
           :placeholder "思维导图名称…"
           :value       new-name
           :style       {:fontSize "13px" :padding "5px 10px"
                         :borderRadius "6px"
                         :border "1px solid var(--lx-gray-07, #d1d5db)"
                         :outline "none" :width "200px"}
           :on-change   #(reset! *new-name (.. % -target -value))
           :on-key-down (fn [^js e]
                          (case (.-key e)
                            "Enter"  (do-create!)
                            "Escape" (do (reset! *creating? false)
                                         (reset! *new-name ""))
                            nil))}]
         (shui/button {:size :sm :on-click do-create!} "创建")
         (shui/button {:size :sm :variant :ghost
                       :on-click #(do (reset! *creating? false) (reset! *new-name ""))}
                      "取消")]
        (shui/button
         {:size :sm
          :on-click (fn [] (reset! *creating? true) (reset! *new-name ""))}
         (ui/icon "plus" {:size 14 :class "mr-1"})
         "新建思维导图"))]

     ;; ── 卡片列表 ────────────────────────────────────────────────────────────
     (if (seq mind-maps)
       [:div.mm-gallery-grid
        {:style {:display             "grid"
                 :gridTemplateColumns "repeat(3, 1fr)"
                 :gap                 "16px"}}
        (doall
         (for [mm   mind-maps
               :let [mm-uuid  (str (:block/uuid mm))
                     mm-title (or (:block/title mm) "未命名")
                     renaming? (= editing-uuid mm-uuid)
                     thumb    (.getItem js/localStorage (str "mind-map-thumb-" mm-uuid))]]
           [:div.mm-gallery-card
            {:key            (str "mmcard-" mm-uuid)
             :style          {:border       "1px solid var(--lx-gray-05, #e5e7eb)"
                              :borderRadius "10px"
                              :overflow     "hidden"
                              :cursor       "pointer"
                              :transition   "box-shadow 0.15s, transform 0.15s"
                              :background   "var(--lx-gray-01, #fff)"}
             :on-mouse-enter #(let [^js s (.. % -currentTarget -style)
                                    ^js as (some-> % .-currentTarget
                                                   (.querySelector ".mm-card-actions") .-style)]
                                (set! (.-boxShadow s) "0 4px 16px rgba(0,0,0,0.12)")
                                (set! (.-transform s) "translateY(-2px)")
                                (when as (set! (.-opacity as) "1")))
             :on-mouse-leave #(let [^js s (.. % -currentTarget -style)
                                    ^js as (some-> % .-currentTarget
                                                   (.querySelector ".mm-card-actions") .-style)]
                                (set! (.-boxShadow s) "none")
                                (set! (.-transform s) "none")
                                (when as (set! (.-opacity as) "0")))
             :on-click       (fn [^js e]
                               (when-not (some-> (.. e -target)
                                                 (.closest ".mm-card-actions, .mm-rename-input"))
                                 (mind-map-handler/redirect-to-mind-map! mm-uuid)))}

            ;; 缩略图区：有缩略图时显示 SVG 预览，否则显示占位图标。
            ;; 使用 <img> 而非内联 SVG，绕过 Logseq common.css 中
            ;;   svg { pointer-events: none }  的全局规则。
            [:div.mm-thumbnail-area
             {:style {:width        "100%"
                      :height       "130px"
                      :background   "var(--lx-gray-02, #f9fafb)"
                      :borderRadius "8px 8px 0 0"
                      :overflow     "hidden"
                      :display      "flex"
                      :alignItems   "center"
                      :justifyContent "center"}}
             (if thumb
               [:img {:src   thumb
                      :style {:width         "100%"
                              :height        "100%"
                              :objectFit     "contain"
                              :pointerEvents "none"
                              :display       "block"
                              :padding       "8px"}}]
               [:div.opacity-20 (ui/icon "hierarchy" {:size 40})])]

            ;; 底部：标题 + 操作按钮
            [:div.flex.items-center.gap-2.px-3.py-2
             {:style {:borderTop "1px solid var(--lx-gray-05, #e5e7eb)"}}
             (ui/icon "hierarchy" {:size 13 :class "opacity-50 shrink-0"})
             (if renaming?
               [:input.mm-rename-input
                {:type        "text"
                 :auto-focus  true
                 :value       rename-val
                 :style       {:flex "1" :fontSize "13px"
                               :padding "1px 4px" :borderRadius "4px"
                               :border "1px solid var(--lx-gray-07,#d1d5db)"
                               :outline "none" :minWidth 0}
                 :on-change   #(reset! *rename-val (.. % -target -value))
                 :on-blur     (fn [] (js/setTimeout cancel-rename! 150))
                 :on-key-down (fn [^js e]
                                (case (.-key e)
                                  "Enter"  (do-rename!)
                                  "Escape" (cancel-rename!)
                                  nil))}]
               [:span.text-sm.font-medium.truncate {:style {:flex "1"}} mm-title])
             [:div.mm-card-actions
              {:style {:display "flex" :gap "2px" :opacity "0"
                       :transition "opacity 0.15s"}}
              [:button
               {:title    "重命名"
                :on-click (fn [^js e]
                            (.stopPropagation e)
                            (reset! *editing-uuid mm-uuid)
                            (reset! *rename-val mm-title))
                :style    {:background "none" :border "none" :cursor "pointer"
                           :padding "2px 4px" :border-radius "4px"
                           :font-size "12px" :opacity "0.6"
                           :color "var(--lx-gray-11,#374151)"}}
               (ui/icon "pencil" {:size 13})]
              [:button
               {:title    "删除"
                :on-click (fn [^js e]
                            (.stopPropagation e)
                            (let [uid (keyword (str (random-uuid)))]
                              (notification/show!
                               [:div
                                [:div {:style {:font-weight "600" :margin-bottom "6px"}}
                                 (str "删除「" mm-title "」？")]
                                [:div {:style {:font-size "12px" :opacity "0.7" :margin-bottom "12px"}}
                                 "此操作不可撤销。"]
                                [:div {:style {:display "flex" :gap "8px"}}
                                 [:button {:on-click (fn []
                                                       (notification/clear! uid)
                                                       (mind-map-handler/<delete-mind-map! mm-uuid))
                                           :style {:padding "4px 12px" :border-radius "6px"
                                                   :border "none" :background "#ef4444" :color "#fff"
                                                   :font-size "12px" :cursor "pointer" :font-weight "600"}}
                                  "确认删除"]
                                 [:button {:on-click #(notification/clear! uid)
                                           :style {:padding "4px 12px" :border-radius "6px"
                                                   :border "1px solid var(--lx-gray-06,#e5e7eb)"
                                                   :background "var(--lx-gray-03,#f3f4f6)"
                                                   :font-size "12px" :cursor "pointer"}}
                                  "取消"]]]
                               :warning false uid nil nil)))
                :style    {:background "none" :border "none" :cursor "pointer"
                           :padding "2px 4px" :border-radius "4px"
                           :font-size "12px" :color "#ef4444" :opacity "0.6"}}
               (ui/icon "trash" {:size 13})]]]]))]

       ;; 空状态
       [:div.flex.flex-col.items-center.justify-center.gap-4
        {:style {:paddingTop "80px"}}
        (ui/icon "hierarchy" {:size 56 :class "opacity-20"})
        [:div.text-sm.opacity-50 "还没有思维导图，点击「新建思维导图」开始"]])]))
