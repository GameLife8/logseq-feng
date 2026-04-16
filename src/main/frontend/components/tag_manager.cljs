(ns frontend.components.tag-manager
  "标签管理页面 – 统计、查看和删除用户标签；系统内置标签只读。"
  (:require [frontend.db :as db]
            [frontend.db.async :as db-async]
            [frontend.handler.page :as page-handler]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [promesa.core :as p]
            [rum.core :as rum]))

;; ── 系统内置标签定义（禁止删除）─────────────────────────────────────────────

(def ^:private system-class-idents
  "所有系统内置 class ident，这些对应的标签禁止删除。"
  [:logseq.class/Journal
   :logseq.class/Task
   :logseq.class/Whiteboard
   :logseq.class/Asset
   :logseq.class/Tag
   :logseq.class/Page
   :logseq.class/Property
   :logseq.class/Root
   :logseq.class/Query
   :logseq.class/Cards
   :logseq.class/Card
   :logseq.class/Code-block
   :logseq.class/Quote-block
   :logseq.class/Math-block
   :logseq.class/Pdf-annotation
   :logseq.class/Template])

(def ^:private system-class-ident-set
  (set system-class-idents))

(def ^:private system-tag-display
  "系统标签显示名称映射"
  {:logseq.class/Journal     "Journal（日记）"
   :logseq.class/Task        "Task（任务）"
   :logseq.class/Whiteboard  "Whiteboard（白板）"
   :logseq.class/Asset       "Asset（资产）"
   :logseq.class/Tag         "Tag（标签类型）"
   :logseq.class/Page        "Page（页面）"
   :logseq.class/Property    "Property（属性）"
   :logseq.class/Root        "Root（根标签）"
   :logseq.class/Query       "Query（查询）"
   :logseq.class/Cards       "Cards（记忆卡片集）"
   :logseq.class/Card        "Card（记忆卡片）"
   :logseq.class/Code-block  "Code（代码块）"
   :logseq.class/Quote-block "Quote（引用块）"
   :logseq.class/Math-block  "Math（数学块）"
   :logseq.class/Pdf-annotation "PDF Annotation（PDF 标注）"
   :logseq.class/Template    "Template（模板）"})

;; 虚拟内置标签：非 logseq.class/* 但应视为系统内置、禁止删除的用户创建标签
(def ^:private virtual-builtin-titles
  "用户动态创建但应视为内置的标签名称集合"
  #{"MindMap" "Sheet"})

(def ^:private virtual-builtin-display
  "虚拟内置标签显示名称"
  {"MindMap" "MindMap（思维导图）"
   "Sheet"   "Sheet（表格）"})

;; ── 数据加载 ─────────────────────────────────────────────────────────────────

(defn- <load-all-tag-entities
  "加载所有带 :logseq.class/Tag 标签的实体（含系统和用户），pull :db/ident 用于客户端区分。"
  [repo]
  (db-async/<q repo {:transact-db? false}
               '[:find [(pull ?page [:db/id :db/ident :block/uuid :block/title]) ...]
                 :where
                 [?tag-class :db/ident :logseq.class/Tag]
                 [?page :block/tags ?tag-class]]))

(defn- <load-tag-ref-counts
  "返回所有 Tag 类标签的引用计数 {db-id count}：
   统计有多少 block 的 :block/tags 包含该标签（排除标签页面本身）。"
  [repo]
  (p/let [rows (db-async/<q repo {:transact-db? false}
                             '[:find ?tag-id (count ?block)
                               :where
                               [?tag-class :db/ident :logseq.class/Tag]
                               [?tag-id :block/tags ?tag-class]
                               [?block :block/tags ?tag-id]
                               [(not= ?block ?tag-id)]
                               [?block :block/uuid _]])]
    (into {} rows)))

(defn- <load-system-tag-counts
  "返回系统标签的引用计数 {ident count}"
  [repo]
  (p/let [rows (db-async/<q repo {:transact-db? false}
                             '[:find ?ident (count ?block)
                               :where
                               [?class :db/ident ?ident]
                               [(namespace ?ident) ?ns]
                               [(= ?ns "logseq.class")]
                               [?block :block/tags ?class]
                               [?block :block/uuid _]])]
    (into {} rows)))

(defn- <ensure-mindmap-hidden!
  "确保 MindMap 类标签设置了 :logseq.property/hide? 使其不显示在 All Pages。"
  [all-tags]
  (doseq [t all-tags]
    (when (virtual-builtin-titles (:block/title t))
      (let [ent (db/entity (:db/id t))]
        (when (and ent (not (:logseq.property/hide? ent)))
          (db/transact! [{:db/id (:db/id t) :logseq.property/hide? true}]))))))

;; ── UI 组件 ──────────────────────────────────────────────────────────────────

(defn- section-title [text]
  [:div {:style {:fontSize "13px" :fontWeight "700" :letterSpacing "0.05em"
                 :textTransform "uppercase" :opacity "0.45"
                 :marginBottom "10px" :marginTop "4px"}}
   text])

(defn- count-badge [n]
  [:span {:style {:display "inline-flex" :alignItems "center" :justifyContent "center"
                  :minWidth "22px" :height "20px" :borderRadius "10px"
                  :fontSize "11px" :fontWeight "600" :padding "0 6px"
                  :background (if (pos? n) "#6366f1" "var(--lx-gray-05,#e5e7eb)")
                  :color (if (pos? n) "#fff" "var(--lx-gray-09,#6b7280)")}}
   n])

(defn- tag-row-system
  [{:keys [ident display-name count]}]
  [:div {:key   (str ident)
         :style {:display "flex" :alignItems "center" :gap "10px"
                 :padding "8px 12px" :borderRadius "8px"
                 :background "var(--lx-gray-02,#f9fafb)"
                 :border "1px solid var(--lx-gray-04,#f1f5f9)"
                 :marginBottom "5px"}}
   [:span {:style {:fontSize "14px" :opacity "0.4" :flexShrink "0"}} "🔒"]
   [:span {:style {:fontSize "13px" :fontWeight "500" :flex "1"}} display-name]
   (count-badge (or count 0))
   [:span {:style {:fontSize "11px" :opacity "0.35" :marginLeft "4px"}} "系统内置"]])

(defn- tag-row-user
  [{:keys [tag on-delete]}]
  (let [{:keys [block/title block/uuid]} tag]
    [:div {:key   (str uuid)
           :style {:display "flex" :alignItems "center" :gap "10px"
                   :padding "8px 12px" :borderRadius "8px"
                   :background "var(--lx-gray-01,#fff)"
                   :border "1px solid var(--lx-gray-05,#e5e7eb)"
                   :marginBottom "5px"}}
     [:span {:style {:fontSize "14px" :color "#6366f1" :flexShrink "0" :fontWeight "700"}} "#"]
     [:span {:style {:fontSize "13px" :fontWeight "500" :flex "1"
                     :overflow "hidden" :textOverflow "ellipsis" :whiteSpace "nowrap"}}
      (or title "(无名称)")]
     (count-badge (or (:ref-count tag) 0))
     [:button
      {:on-click #(on-delete uuid title)
       :title "删除此标签"
       :style {:padding "2px 8px" :borderRadius "5px"
               :border "1px solid #fca5a5"
               :background "#fff1f2" :color "#ef4444"
               :fontSize "12px" :cursor "pointer"
               :flexShrink "0"
               :transition "all 0.1s"}}
      "删除"]]))

;; ── 主页面 ───────────────────────────────────────────────────────────────────

(rum/defcs tag-manager-page
  "标签管理页面 – 统计系统标签和用户标签，支持删除用户标签。"
  < rum/reactive
  (rum/local nil  ::user-tags)       ;; [{:db/id :block/uuid :block/title :ref-count}]
  (rum/local nil  ::vb-tags)         ;; [{:db/id :block/title :ref-count}] 虚拟内置标签
  (rum/local nil  ::sys-counts)      ;; {ident count}
  (rum/local false ::loading?)
  (rum/local nil  ::filter-text)     ;; 搜索关键词
  (rum/local nil  ::confirm-delete)  ;; {:uuid :title} 待确认删除的标签
  {:did-mount
   (fn [state]
     (let [*user-tags (::user-tags state)
           *vb-tags   (::vb-tags state)
           *sys       (::sys-counts state)
           *loading   (::loading? state)]
       (reset! *loading true)
       (when-let [repo (state/get-current-repo)]
         (p/let [all-tags    (<load-all-tag-entities repo)
                 ref-counts  (<load-tag-ref-counts repo)
                 sys-counts  (<load-system-tag-counts repo)
                 ;; 客户端分类：
                 ;; - :db/ident 在 system-class-ident-set 中 → 系统标签（已由独立查询计数）
                 ;; - :block/title 在 virtual-builtin-titles 中 → 虚拟内置标签
                 ;; - 其余 → 用户标签
                 user-only   (->> all-tags
                                  (remove #(system-class-ident-set (:db/ident %)))
                                  (remove #(virtual-builtin-titles (:block/title %)))
                                  (map (fn [t] (assoc t :ref-count (get ref-counts (:db/id t) 0))))
                                  (sort-by #(str (:block/title %))))
                 vb-only     (->> all-tags
                                  (filter #(virtual-builtin-titles (:block/title %)))
                                  (remove #(system-class-ident-set (:db/ident %)))
                                  (map (fn [t] (assoc t :ref-count (get ref-counts (:db/id t) 0)))))]
           (<ensure-mindmap-hidden! all-tags)
           (reset! *user-tags user-only)
           (reset! *vb-tags vb-only)
           (reset! *sys sys-counts)
           (reset! *loading false))))
     state)}
  [state]
  (let [*user-tags  (::user-tags state)
        *vb-tags    (::vb-tags state)
        *sys-counts (::sys-counts state)
        *loading    (::loading? state)
        *filter     (::filter-text state)
        *confirm    (::confirm-delete state)
        user-tags   (rum/react *user-tags)
        vb-tags     (rum/react *vb-tags)
        sys-counts  (rum/react *sys-counts)
        loading?    (rum/react *loading)
        filter-text (rum/react *filter)
        confirm-del (rum/react *confirm)

        ;; 过滤用户标签
        visible-tags (if (seq filter-text)
                       (filter #(some-> (:block/title %)
                                        (.toLowerCase)
                                        (.includes (.toLowerCase filter-text)))
                               (or user-tags []))
                       (or user-tags []))

        ;; 删除处理
        do-delete!  (fn [uuid title]
                      (reset! *confirm {:uuid uuid :title title}))
        confirm-ok! (fn []
                      (when-let [{:keys [uuid]} @*confirm]
                        (page-handler/<delete!
                         uuid
                         (fn []
                           (reset! *user-tags
                                   (remove #(= (:block/uuid %) uuid) @*user-tags))
                           (reset! *confirm nil)))))
        cancel-del! #(reset! *confirm nil)]

    [:div.tag-manager-page
     {:style {:display "flex" :flexDirection "column" :height "100%"
              :background "var(--lx-gray-01,#fff)" :overflow "hidden"}}

     ;; ── 顶部标题栏 ─────────────────────────────────────────────────────────
     [:div {:style {:display "flex" :alignItems "center" :gap "8px"
                    :padding "12px 16px 12px"
                    :borderBottom "1px solid var(--lx-gray-05,#e5e7eb)"
                    :flexShrink "0"}}
      (ui/icon "tags" {:size 18 :class "opacity-70"})
      [:h1 {:style {:fontSize "16px" :fontWeight "700" :margin 0 :flex "1"}} "标签管理"]
      (when (and user-tags (not loading?))
        [:span {:style {:fontSize "12px" :opacity "0.5"}}
         (str (count user-tags) " 个用户标签")])]

     ;; ── 确认删除弹窗 ───────────────────────────────────────────────────────
     (when confirm-del
       [:div {:style {:position "fixed" :inset "0" :zIndex "999"
                      :display "flex" :alignItems "center" :justifyContent "center"
                      :background "rgba(0,0,0,0.35)"}}
        [:div {:style {:background "#fff" :borderRadius "12px"
                       :padding "24px 28px" :maxWidth "360px" :width "90%"
                       :boxShadow "0 20px 60px rgba(0,0,0,0.15)"}}
         [:div {:style {:fontSize "15px" :fontWeight "700" :marginBottom "8px"}} "删除标签"]
         [:div {:style {:fontSize "13px" :opacity "0.7" :marginBottom "20px"}}
          (str "确认删除标签 「" (:title confirm-del) "」？删除后引用该标签的块将失去此标签，操作不可恢复。")]
         [:div {:style {:display "flex" :gap "10px" :justifyContent "flex-end"}}
          [:button {:on-click cancel-del!
                    :style {:padding "6px 16px" :borderRadius "7px"
                            :border "1px solid var(--lx-gray-06,#e5e7eb)"
                            :background "var(--lx-gray-03,#f3f4f6)"
                            :fontSize "13px" :cursor "pointer"}}
           "取消"]
          [:button {:on-click confirm-ok!
                    :style {:padding "6px 16px" :borderRadius "7px"
                            :border "none" :background "#ef4444" :color "#fff"
                            :fontSize "13px" :cursor "pointer" :fontWeight "600"}}
           "确认删除"]]]])

     ;; ── 主内容区（可滚动）─────────────────────────────────────────────────
     [:div {:style {:flex "1" :overflowY "auto" :padding "16px"}}

      (when loading?
        [:div {:style {:textAlign "center" :padding "40px 0" :opacity "0.5" :fontSize "14px"}}
         "正在加载标签数据…"])

      (when (and (not loading?) user-tags)
        [:<>
         ;; ── 用户标签 ──────────────────────────────────────────────────────
         (section-title (str "用户标签（" (count user-tags) "）"))

         [:div {:style {:marginBottom "12px"}}
          [:input {:type "text"
                   :placeholder "搜索标签名称…"
                   :value (or filter-text "")
                   :on-change #(reset! *filter (.. % -target -value))
                   :style {:width "100%" :padding "6px 10px"
                           :border "1px solid var(--lx-gray-05,#e5e7eb)"
                           :borderRadius "7px" :fontSize "13px"
                           :background "var(--lx-gray-02,#f9fafb)"
                           :outline "none"
                           :boxSizing "border-box"}}]]

         (if (seq visible-tags)
           (for [t visible-tags]
             (tag-row-user {:tag t :on-delete do-delete!}))
           [:div {:style {:fontSize "13px" :opacity "0.4" :textAlign "center"
                          :padding "20px 0"}}
            (if (seq filter-text) "无匹配标签" "暂无用户标签")])

         ;; ── 系统内置标签 ──────────────────────────────────────────────────
         [:div {:style {:marginTop "28px"}}
          (section-title "系统内置标签（只读）")]
         (for [ident system-class-idents
               :let [cnt (get sys-counts ident 0)]]
           (tag-row-system
            {:ident        ident
             :display-name (get system-tag-display ident (name ident))
             :count        cnt}))
         ;; 虚拟内置标签（如 MindMap）
         (for [t (sort-by :block/title vb-tags)]
           (tag-row-system
            {:ident        (str "virtual-" (:block/title t))
             :display-name (get virtual-builtin-display (:block/title t) (:block/title t))
             :count        (:ref-count t 0)}))])]]))
