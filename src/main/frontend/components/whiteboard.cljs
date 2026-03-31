(ns frontend.components.whiteboard
  "Whiteboard page component backed by Excalidraw.

   Architecture:
     frontend.extensions.excalidraw.api  – element builders + customData CRUD (main bundle)
     frontend.extensions.excalidraw.core – Excalidraw React wrapper (lazy bundle)
     frontend.components.whiteboard      – page UI / linked-blocks panel (main bundle)

   Element block model:
     Each Excalidraw element can carry linked blocks (references to existing Logseq
     blocks) and note blocks (new blocks created under the whiteboard page).  Both are
     stored as UUID arrays in element.customData.linkedBlockIds / .noteBlockIds and
     persisted as part of the canvas JSON."
  (:require [clojure.string :as string]
            [frontend.db :as db]
            [frontend.db-mixins :as db-mixins]
            [frontend.db.async :as db-async]
            [frontend.db.react :as react]
            [frontend.extensions.excalidraw.api :as ex-api]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.excalidraw-config :as ex-cfg]
            [frontend.handler.notification :as notification]
            [frontend.handler.route :as route-handler]
            [frontend.handler.whiteboard :as whiteboard-handler]
            [frontend.search :as search]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [logseq.shui.ui :as shui]
            [promesa.core :as p]
            [rum.core :as rum]
            [shadow.lazy :as lazy]))

;; ── lazy-load Excalidraw module ───────────────────────────────────────────────

#_:clj-kondo/ignore
(def ^:private lazy-excalidraw
  (lazy/loadable frontend.extensions.excalidraw.core/editor))

(defonce ^:private *excalidraw-loaded? (atom false))

(defn- ensure-excalidraw-loaded! [on-done]
  (if @*excalidraw-loaded?
    (on-done)
    (lazy/load lazy-excalidraw
               (fn []
                 (reset! *excalidraw-loaded? true)
                 (on-done)))))

;; ── tags bar (floating, real Logseq block/tags) ─────────────────────────────

(rum/defcs tags-bar
  "Tags dropdown button in the whiteboard toolbar.
   Click to open a panel showing selected tags and a search to add/remove tags."
  < rum/reactive
  (rum/local false ::open?)
  (rum/local "" ::tag-input)
  (rum/local [] ::tag-results)
  [state page-uuid page-entity]
  (let [*open?   (::open? state)
        *input   (::tag-input state)
        *results (::tag-results state)
        open?    (rum/react *open?)
        _        (rum/react *input)
        results  (rum/react *results)
        tags     (whiteboard-handler/get-page-user-tags page-entity)
        cnt      (count tags)
        close!   (fn []
                   (reset! *open? false)
                   (reset! *input "")
                   (reset! *results []))]
    [:div {:style {:position "relative" :display "inline-block"}}
     ;; ── Trigger button ──────────────────────────────────────────────────────
     [:button
      {:on-click #(if open? (close!) (do (reset! *open? true)
                                         (reset! *results (whiteboard-handler/search-tags ""))))
       :style    {:display       "flex"
                  :align-items   "center"
                  :gap           "5px"
                  :padding       "5px 10px"
                  :background    (if open? "var(--lx-gray-04,#e5e7eb)" "var(--lx-gray-03,#f3f4f6)")
                  :color         "var(--lx-gray-12,#111)"
                  :border        "1px solid var(--lx-gray-06,#e5e7eb)"
                  :border-radius "6px"
                  :cursor        "pointer"
                  :font-size     "13px"
                  :white-space   "nowrap"}}
      "🏷"
      (if (pos? cnt) (str "标签 (" cnt ")") "+ 标签")]

     ;; ── Dropdown panel ──────────────────────────────────────────────────────
     (when open?
       [:div {:style {:position      "absolute"
                      :top           "calc(100% + 6px)"
                      :right         0
                      :background    "var(--lx-gray-01, #fff)"
                      :border        "1px solid var(--lx-gray-06,#e5e7eb)"
                      :border-radius "10px"
                      :box-shadow    "0 6px 20px rgba(0,0,0,0.15)"
                      :z-index       3000
                      :min-width     "220px"
                      :padding       "10px 10px 8px"}}

        ;; Header
        [:div {:style {:display         "flex"
                       :justify-content "space-between"
                       :align-items     "center"
                       :margin-bottom   "8px"}}
         [:span {:style {:font-size "12px" :font-weight "600" :opacity "0.6"}} "标签"]
         [:button {:on-click close!
                   :style {:background "none" :border "none" :cursor "pointer"
                           :opacity "0.4" :font-size "16px" :line-height "1" :padding "0 2px"}}
          "×"]]

        ;; Already-selected tags
        (if (seq tags)
          [:div {:style {:margin-bottom "6px"}}
           (for [tag tags]
             [:div {:key   (str "sel-" (:db/id tag))
                    :style {:display         "flex"
                            :align-items     "center"
                            :justify-content "space-between"
                            :padding         "4px 8px"
                            :border-radius   "6px"
                            :background      "#eef2ff"
                            :margin-bottom   "3px"}}
              [:span {:style {:font-size "12px" :color "#4f46e5"}} (str "#" (:block/title tag))]
              [:button {:on-click (fn [^js e]
                                    (.stopPropagation e)
                                    (whiteboard-handler/remove-tag-from-page! (uuid page-uuid) tag))
                        :style {:background "none" :border "none" :cursor "pointer"
                                :opacity "0.5" :font-size "12px" :padding "0 2px"
                                :line-height "1"}}
               "×"]])]
          [:div {:style {:font-size "12px" :opacity "0.4" :text-align "center"
                         :padding "4px 0 6px"}}
           "暂无标签"])

        ;; Divider
        [:hr {:style {:border "none" :border-top "1px solid var(--lx-gray-05,#e5e7eb)"
                      :margin "6px 0"}}]

        ;; Search input
        [:input {:type        "text"
                 :placeholder "搜索标签…"
                 :value       @*input
                 :style       {:display       "block"
                               :width         "100%"
                               :padding       "5px 8px"
                               :border-radius "6px"
                               :border        "1px solid var(--lx-gray-07,#d1d5db)"
                               :outline       "none"
                               :font-size     "12px"
                               :box-sizing    "border-box"}
                 :on-change   (fn [^js e]
                                (let [q (.. e -target -value)]
                                  (reset! *input q)
                                  (reset! *results (whiteboard-handler/search-tags q))))
                 :on-focus    #(reset! *results (whiteboard-handler/search-tags ""))
                 :on-blur     (fn [] (js/setTimeout close! 200))
                 :on-key-down (fn [^js e]
                                (when (= "Escape" (.-key e)) (close!)))}]

        ;; Candidates list (all tags, already-selected shown with ✓)
        (when (seq results)
          [:div {:style {:max-height "160px" :overflow-y "auto" :margin-top "4px"}}
           (for [tag-entity results
                 :let [already? (some #(= (:db/id %) (:db/id tag-entity)) tags)]]
             [:div {:key          (str "res-" (:db/id tag-entity))
                    :style        {:display         "flex"
                                   :align-items     "center"
                                   :justify-content "space-between"
                                   :padding         "5px 8px"
                                   :border-radius   "6px"
                                   :cursor          "pointer"
                                   :background      (if already? "var(--lx-gray-03,#f3f4f6)" "transparent")
                                   :margin-bottom   "2px"}
                    :on-mouse-enter #(when-not already?
                                       (set! (.. % -currentTarget -style -background)
                                             "var(--lx-gray-04,#f3f4f6)"))
                    :on-mouse-leave #(when-not already?
                                       (set! (.. % -currentTarget -style -background) "transparent"))
                    :on-mouse-down  (fn [^js e]
                                      (.preventDefault e)
                                      (if already?
                                        (whiteboard-handler/remove-tag-from-page! (uuid page-uuid) tag-entity)
                                        (do (whiteboard-handler/add-tag-to-page! (uuid page-uuid) tag-entity)
                                            (reset! *input "")
                                            (reset! *results (whiteboard-handler/search-tags "")))))}
              [:span {:style {:font-size "12px"}} (str "#" (:block/title tag-entity))]
              (when already?
                [:span {:style {:font-size "10px" :color "#6366f1" :opacity "0.8"}} "✓"])])])
        ])]))


;; ── linked-blocks panel ─────────────────────────────────────────────────────
;;
;; Floating right-side panel that shows linked blocks and note blocks for a
;; selected canvas element.  Opened by clicking "🔗 链接块" in the toolbar.
;;
;; Data flow:
;;   1. "🔗" click in core.cljs toolbar → onShowLinkedBlocks(el-id) callback
;;   2. whiteboard-page sets *linked-panel-el-id → panel mounts
;;   3. did-mount reads linkedBlockIds / noteBlockIds from element's customData
;;   4. Add/remove operations call ex-api mutators (which do api.updateScene)
;;      AND update the panel's local state atoms so the UI stays in sync.

(rum/defcs linked-blocks-panel
  "Right-side panel for managing linked blocks and notes of a selected canvas element."
  < rum/reactive
  (rum/local [] ::linked-ids)     ; linked block UUID strings
  (rum/local [] ::note-ids)       ; note block UUID strings
  (rum/local false ::show-search?) ; show inline block-search
  (rum/local "" ::search-q)
  (rum/local [] ::search-res)
  (rum/local false ::searching?)
  (rum/local {} ::linked-aliases)  ; uid → custom alias string for linked blocks
  (rum/local {} ::note-aliases)    ; uid → custom alias string for note blocks
  (rum/local nil ::editing-uid)    ; uid currently being renamed (nil = none)
  (rum/local :linked ::editing-type) ; :linked or :note
  (rum/local "" ::editing-val)     ; current text in rename input
  {:did-mount
   (fn [state]
     (let [{:keys [api el-id]} (-> state :rum/args first)
           el (ex-api/get-element-by-id api el-id)]
       (js/console.log "[wb-panel] did-mount el-id:" el-id "found?" (boolean el))
       (reset! (::linked-ids state)     (ex-api/get-linked-block-ids el))
       (reset! (::note-ids state)       (ex-api/get-note-block-ids el))
       (reset! (::linked-aliases state) (or (ex-api/get-block-aliases el) {}))
       (reset! (::note-aliases state)   (or (ex-api/get-note-aliases el) {})))
     state)}
  [state {:keys [api el-id page-uuid on-close on-open-block on-add-note-block]}]
  (let [*linked-ids    (::linked-ids state)
        *note-ids      (::note-ids state)
        *show-search?  (::show-search? state)
        *search-q      (::search-q state)
        *search-res    (::search-res state)
        *searching?    (::searching? state)
        *linked-ali    (::linked-aliases state)
        *note-ali      (::note-aliases state)
        *editing-uid   (::editing-uid state)
        *editing-type  (::editing-type state)
        *editing-val   (::editing-val state)
        linked-ids     (rum/react *linked-ids)
        note-ids       (rum/react *note-ids)
        show-search?   (rum/react *show-search?)
        linked-aliases (rum/react *linked-ali)
        note-aliases   (rum/react *note-ali)
        editing-uid    (rum/react *editing-uid)
        editing-type   (rum/react *editing-type)

        ;; 截断长文本至 20 字符
        truncate      (fn [s] (if (> (count s) 20) (str (subs s 0 20) "…") s))

        ;; Look up block title from DataScript; truncate long titles to 20 chars
        title-for-uid (fn [uid-str]
                        (let [uid (try (uuid uid-str) (catch :default _ nil))
                              e   (when uid (db/entity [:block/uuid uid]))
                              raw (or (:block/title e)
                                      (str "(块 " (subs uid-str 0 (min 8 (count uid-str))) "…)"))]
                          (truncate raw)))

        ;; 显示名称：自定义别名优先，否则查 DB 标题
        display-linked (fn [uid] (truncate (or (get linked-aliases uid) (title-for-uid uid))))
        display-note   (fn [uid] (truncate (or (get note-aliases uid) (title-for-uid uid))))

        ;; 开始编辑（单击名称）
        start-edit!  (fn [uid type cur-display]
                       (reset! *editing-uid uid)
                       (reset! *editing-type type)
                       (reset! *editing-val cur-display))

        ;; 保存别名
        commit-edit! (fn []
                       (let [uid  @*editing-uid
                             type @*editing-type
                             val  (clojure.string/trim @*editing-val)]
                         (when (seq uid)
                           (if (seq val)
                             (do (if (= type :linked)
                                   (do (swap! *linked-ali assoc uid val)
                                       (ex-api/set-block-alias! api el-id uid val))
                                   (do (swap! *note-ali assoc uid val)
                                       (ex-api/set-note-alias! api el-id uid val))))
                             ;; 清空 → 恢复 DB 标题
                             (if (= type :linked)
                               (do (swap! *linked-ali dissoc uid)
                                   (ex-api/set-block-alias! api el-id uid ""))
                               (do (swap! *note-ali dissoc uid)
                                   (ex-api/set-note-alias! api el-id uid "")))))
                         (reset! *editing-uid nil)))

        cancel-edit! (fn [] (reset! *editing-uid nil))

        close-search! (fn []
                        (reset! *show-search? false)
                        (reset! *search-q "")
                        (reset! *search-res []))

        do-add-linked! (fn [block-uuid-str]
                         (js/console.log "[wb-panel] add-linked" block-uuid-str)
                         (ex-api/add-linked-block! api el-id block-uuid-str)
                         (swap! *linked-ids #(if (some #{block-uuid-str} %) %
                                                 (conj % block-uuid-str)))
                         (close-search!))

        do-remove-linked! (fn [uid]
                            (js/console.log "[wb-panel] remove-linked" uid)
                            (ex-api/remove-linked-block! api el-id uid)
                            (reset! *linked-ids (filterv #(not= % uid) linked-ids)))

        do-remove-note! (fn [uid]
                          (js/console.log "[wb-panel] remove-note" uid)
                          (ex-api/remove-note-block! api el-id uid)
                          (reset! *note-ids (filterv #(not= % uid) note-ids)))

        open-block!   (fn [uid-str]
                        (js/console.log "[wb-panel] open-block" uid-str)
                        (on-open-block uid-str))

        row-style     {:display      "flex" :alignItems "center" :gap "6px"
                       :padding      "5px 7px" :borderRadius "6px"
                       :background   "var(--lx-gray-03,#f3f4f6)" :marginBottom "4px"}

        section-label {:fontSize "11px" :opacity "0.5" :marginBottom "6px"
                       :fontWeight "600" :textTransform "uppercase" :letterSpacing "0.05em"}

        icon-btn-style {:background "none" :border "none" :cursor "pointer"
                        :fontSize "13px" :opacity "0.6" :padding "1px 3px"
                        :flexShrink "0"}

        ;; 渲染单行（名称可内联编辑），依赖 row-style / icon-btn-style，须放在其后
        block-row    (fn [uid type on-open on-remove]
                       (let [display  (if (= type :linked) (display-linked uid) (display-note uid))
                             editing? (and (= editing-uid uid) (= editing-type type))]
                         [:div {:key uid :style row-style}
                          (if editing?
                            [:input {:auto-focus   true
                                     :value        (rum/react *editing-val)
                                     :placeholder  "自定义名称（空白=恢复默认）"
                                     :on-change    #(reset! *editing-val (.. % -target -value))
                                     :on-blur      commit-edit!
                                     :on-key-down  (fn [^js e]
                                                     (case (.-key e)
                                                       "Enter"  (commit-edit!)
                                                       "Escape" (cancel-edit!)
                                                       nil))
                                     :style {:flex "1" :fontSize "12px" :padding "2px 4px"
                                             :borderRadius "4px" :outline "none"
                                             :border "1px solid var(--lx-gray-07,#d1d5db)"}}]
                            [:span {:title    "点击编辑名称"
                                    :on-click #(start-edit! uid type display)
                                    :style    {:flex "1" :fontSize "12px"
                                               :overflow "hidden" :textOverflow "ellipsis"
                                               :whiteSpace "nowrap" :cursor "text"}}
                             display])
                          [:button {:title "在侧边栏打开"
                                    :on-click (fn [^js e] (.stopPropagation e) (on-open uid))
                                    :style icon-btn-style}
                           "↗"]
                          [:button {:title "移除关联（不删除原块）"
                                    :on-click (fn [^js e] (.stopPropagation e) (on-remove uid))
                                    :style (assoc icon-btn-style :color "#ef4444")}
                           "×"]]))]

    [:div.wb-linked-panel
     {:style {:position "absolute" :top "58px" :right "12px"
              :width "290px"
              :background "var(--lx-gray-01,#fff)"
              :border "1px solid var(--lx-gray-06,#e5e7eb)"
              :borderRadius "10px"
              :boxShadow "0 6px 24px rgba(0,0,0,0.15)"
              :zIndex 1000 :overflowY "auto" :maxHeight "80vh"
              :padding "14px"}}

     ;; ── Header ──────────────────────────────────────────────────────────────
     [:div {:style {:display "flex" :justifyContent "space-between"
                    :alignItems "center" :marginBottom "12px"}}
      [:span {:style {:fontWeight "600" :fontSize "13px"}} "🔗 关联块"]
      [:button {:on-click on-close
                :style {:background "none" :border "none" :cursor "pointer"
                        :opacity "0.5" :fontSize "16px" :padding "0 2px"}}
       "×"]]

     ;; ── Linked blocks ────────────────────────────────────────────────────────
     [:div {:style {:marginBottom "10px"}}
      [:div {:style section-label} "关联块"]
      (if (seq linked-ids)
        (doall
         (for [uid linked-ids]
           (block-row uid :linked open-block! do-remove-linked!)))
        [:div {:style {:fontSize "12px" :opacity "0.4" :padding "4px 0"}} "暂无关联块"])

      ;; Inline block search or add button
      (if show-search?
        [:div {:style {:marginTop "8px"}}
         [:div {:style {:display "flex" :gap "4px" :marginBottom "6px"}}
          [:input {:type        "text"
                   :auto-focus  true
                   :placeholder "搜索块名称…"
                   :value       @*search-q
                   :style       {:flex "1" :fontSize "12px" :padding "5px 8px"
                                 :borderRadius "6px"
                                 :border "1px solid var(--lx-gray-07,#d1d5db)"
                                 :outline "none"}
                   :on-change   (fn [^js e]
                                  (let [q (.. e -target -value)]
                                    (reset! *search-q q)
                                    (if (string/blank? q)
                                      (reset! *search-res [])
                                      (do (reset! *searching? true)
                                          (p/let [res (search/block-search
                                                       (state/get-current-repo) q
                                                       {:built-in? false :enable-snippet? false})]
                                            (reset! *search-res (vec (take 15 (or res []))))
                                            (reset! *searching? false))))))
                   :on-key-down (fn [^js e]
                                  (when (= "Escape" (.-key e)) (close-search!)))}]
          [:button {:on-click close-search!
                    :style {:background "none" :border "none" :cursor "pointer"
                            :opacity "0.5" :fontSize "14px"}}
           "×"]]
         (when @*searching?
           [:div {:style {:fontSize "12px" :opacity "0.5" :padding "2px 0"}} "搜索中…"])
         (when (seq @*search-res)
           [:div {:style {:maxHeight "200px" :overflowY "auto"}}
            (doall
             (for [block @*search-res
                   :let [title (or (:block/title block) "(无标题)")
                         buid  (str (:block/uuid block))
                         page  (some-> (db/entity (:db/id (:block/page block))) :block/title)]]
               [:div {:key   buid
                      :style {:padding "5px 8px" :cursor "pointer" :borderRadius "5px"
                              :fontSize "12px" :marginBottom "2px"}
                      :on-mouse-enter #(set! (.. % -currentTarget -style -background)
                                             "var(--lx-gray-04,#f3f4f6)")
                      :on-mouse-leave #(set! (.. % -currentTarget -style -background) "transparent")
                      :on-click       (fn [] (do-add-linked! buid))}
                [:div {:style {:fontWeight "500" :overflow "hidden"
                               :textOverflow "ellipsis" :whiteSpace "nowrap"}} title]
                (when page
                  [:div {:style {:opacity "0.5" :fontSize "11px" :marginTop "1px"}} page])]))])]
        [:button {:on-click #(reset! *show-search? true)
                  :style {:marginTop "6px" :background "none" :border "none"
                          :cursor "pointer" :fontSize "12px" :color "#6366f1"
                          :padding "2px 0" :opacity "0.8"}}
         "+ 添加关联块"])]

     [:hr {:style {:border "none" :borderTop "1px solid var(--lx-gray-05,#e5e7eb)"
                   :margin "10px 0"}}]

     ;; ── Note blocks ──────────────────────────────────────────────────────────
     [:div
      [:div {:style section-label} "备注块"]
      (if (seq note-ids)
        (doall
         (for [uid note-ids]
           (block-row uid :note open-block! do-remove-note!)))
        [:div {:style {:fontSize "12px" :opacity "0.4" :padding "4px 0"}} "暂无备注块"])
      [:button
       {:on-click (fn []
                    (js/console.log "[wb-panel] add-note-block for el:" el-id "page:" page-uuid)
                    (p/let [uid (on-add-note-block)]
                      (when uid
                        (js/console.log "[wb-panel] note block created uid:" uid)
                        (ex-api/add-note-block! api el-id uid)
                        (swap! *note-ids conj uid))))
        :style {:marginTop "6px" :background "none" :border "none"
                :cursor "pointer" :fontSize "12px" :color "#6366f1"
                :padding "2px 0" :opacity "0.8"}}
       "+ 新增备注块"]]]))

;; ── canvas ────────────────────────────────────────────────────────────────────

(rum/defcs whiteboard-canvas
  "Renders the lazy-loaded Excalidraw component."
  < rum/reactive
  (rum/local false ::loaded?)
  {:did-mount
   (fn [state]
     (ensure-excalidraw-loaded!
      (fn [] (reset! (::loaded? state) true)))
     state)}
  [state {:keys [page-uuid page-title on-back on-api-ready
                 on-show-linked-blocks on-selection-change render-tags on-rename
                 validate-embeddable custom-fonts initial-json]}]
  (let [loaded? (rum/react (::loaded? state))]
    [:div.wb-canvas {:style {:width "100%" :height "100%"}}
     (if loaded?
       (@lazy-excalidraw
        {:page-uuid              page-uuid
         :page-title             page-title
         :on-back                on-back
         :on-api-ready           on-api-ready
         :on-show-linked-blocks  on-show-linked-blocks
         :on-selection-change    on-selection-change
         :on-rename              on-rename
         :render-tags            render-tags
         ;; Config-driven props (from excalidraw settings panel)
         :validate-embeddable    validate-embeddable
         :custom-fonts           custom-fonts
         ;; DB persistence callbacks (main bundle → lazy bundle boundary)
         :initial-json           initial-json
         :on-save-data           whiteboard-handler/save-canvas-to-db!})
       [:div.flex.items-center.justify-center.h-full
        [:div.text-sm.opacity-60 "正在加载白板编辑器…"]])]))

;; ── full whiteboard page ──────────────────────────────────────────────────────

(rum/defcs whiteboard-page
  "Full-page whiteboard editor.
   Canvas fills the entire viewport; a collapsible toolbar sits top-right.
   Selecting any element reveals the '🔗 链接块' toolbar button; clicking it
   opens a right-side panel to manage linked blocks and note blocks for that element."
  < rum/reactive
  (rum/local nil ::canvas-api)
  (rum/local nil ::linked-panel-el-id)  ; element ID whose panel is open, or nil
  (rum/local nil ::ex-config)           ; async-loaded excalidraw config (nil = not yet loaded)
  (rum/local {:loaded? false
              :json nil
              :needs-flush? false
              :source :empty}
             ::initial-doc)
  {:did-mount
   (fn [state]
     ;; Async-load the Excalidraw config from the worker DB.  The synchronous
     ;; get-config falls back to default when the logseq/excalidraw page isn't in
     ;; the main-thread DataScript replica yet (lazy-DB).  Once the async load
     ;; resolves, the atom update triggers a re-render with the correct config.
     (let [page-uuid (some-> state :rum/args first :page-entity :block/uuid str)]
       (p/let [cfg      (ex-cfg/<get-config)
               doc-info (whiteboard-handler/<load-canvas-doc page-uuid)]
         (reset! (::ex-config state) cfg)
         (reset! (::initial-doc state)
                 (merge {:loaded? true} doc-info))))
     state)}
  [state {:keys [page-entity]}]
  (let [*canvas-api        (::canvas-api state)
        *linked-panel-el-id (::linked-panel-el-id state)
        *ex-config-atom    (::ex-config state)
        *initial-doc       (::initial-doc state)
        linked-panel-el-id (rum/react *linked-panel-el-id)
        {:keys [loaded? json]} (rum/react *initial-doc)
        page-uuid          (str (:block/uuid page-entity))
        page-title         (or (:block/title page-entity) "Untitled Whiteboard")
        repo               (state/get-current-repo)

        ;; Use async-loaded config when ready; fall back to synchronous read
        ;; (which may return default-config on first load before async completes).
        ;; The rum/react subscription ensures a re-render when async load finishes.
        ex-config          (or (rum/react *ex-config-atom) (ex-cfg/get-config))
        ;; Derive validate-embed from the raw whitelist string, not calling
        ;; make-validate-embeddable in every render.  Use the raw string as the
        ;; stable value passed to core.cljs which converts it on did-mount.
        embed-whitelist    (:embed-whitelist ex-config)
        validate-embed     (ex-cfg/make-validate-embeddable embed-whitelist)
        custom-fonts       {:virgil    (:font-path-virgil ex-config)
                            :helvetica (:font-path-helvetica ex-config)
                            :cascadia  (:font-path-cascadia ex-config)}

        on-back (fn []
                  (notification/show! "白板已保存" :success)
                  (route-handler/redirect! {:to :all-whiteboards}))

        ;; Open a block (by UUID string) in the right sidebar.
        ;; Uses db-async/<get-block to ensure the block entity is loaded into the
        ;; main-thread DataScript replica before calling open-block-in-sidebar!
        ;; (same lazy-DB pattern as the whiteboard and mind-map editors).
        open-block-in-sidebar!
        (fn [uid-str]
          (js/console.log "[wb] open-block-in-sidebar!" uid-str)
          (when (seq uid-str)
            (let [uid (try (uuid uid-str) (catch :default e
                             (js/console.error "[wb] invalid uuid:" uid-str e) nil))]
              (when uid
                (p/let [_ (db-async/<get-block repo uid :children? false)]
                  (whiteboard-handler/open-block-in-sidebar! uid-str))))))

        ;; Create a new note block under this whiteboard page.
        ;; Returns a Promise resolving to the new block's UUID string.
        add-note-block!
        (fn []
          (js/console.log "[wb] add-note-block! page:" page-uuid)
          (p/let [result (editor-handler/api-insert-new-block!
                          ""
                          {:page         (uuid page-uuid)
                           :edit-block?  false
                           :end?         true
                           :container-id :unknown-container})]
            (js/console.log "[wb] note block result:" (clj->js result))
            (when result
              (state/sidebar-add-block! repo (:db/id result) :block)
              (str (:block/uuid result)))))]

    [:div.whiteboard-page
     {:style {:position "relative" :width "100%" :height "100%"}}

     ;; Canvas fills entire viewport
     [:div {:style {:position "absolute" :inset 0 :overflow "hidden"}}
      (if loaded?
        (whiteboard-canvas
       {:page-uuid             page-uuid
        :page-title            page-title
        :on-back               on-back
        :on-api-ready          (fn [api]
                                 (js/console.log "[wb] API ready")
                                 (reset! *canvas-api api))
        ;; When user clicks 🔗 in toolbar: open panel for that element
        :on-show-linked-blocks (fn [el-id]
                                 (reset! *linked-panel-el-id el-id))
        ;; When selection is cleared (deselect all): close the panel
        :on-selection-change   (fn [el-id]
                                 (when (nil? el-id)
                                   (reset! *linked-panel-el-id nil)))
        :on-rename             (fn [new-title]
                                 (whiteboard-handler/<rename-whiteboard! page-uuid new-title))
        :render-tags           (fn [] (tags-bar page-uuid page-entity))
        :validate-embeddable   validate-embed
        :custom-fonts          custom-fonts
        :initial-json          json})
        [:div.flex.items-center.justify-center.h-full
         [:div.text-sm.opacity-60 "Loading whiteboard data..."]])]

     ;; Linked-blocks panel (shown when linked-panel-el-id is set)
     (when (and loaded? linked-panel-el-id @*canvas-api)
       (linked-blocks-panel
        {:api              @*canvas-api
         :el-id            linked-panel-el-id
         :page-uuid        page-uuid
         :on-close         (fn [] (reset! *linked-panel-el-id nil))
         :on-open-block    open-block-in-sidebar!
         :on-add-note-block add-note-block!}))]))

;; ── whiteboard gallery ────────────────────────────────────────────────────────

(rum/defcs whiteboard-thumbnail
  "Renders an SVG thumbnail for a whiteboard from localStorage.
   Falls back to an icon placeholder if ExcalidrawLib is not loaded or data absent."
  < rum/reactive
  (rum/local nil ::svg-html)
  {:did-mount
   (fn [state]
     (let [page-uuid (-> state :rum/args first)
           *svg      (::svg-html state)
           gen-svg!  (fn []
                       (let [raw (whiteboard-handler/load-canvas-from-db page-uuid)]
                         (js/console.log "[wb] thumbnail" page-uuid "raw canvas len:" (some-> raw count))
                         (if-not raw
                           (js/console.log "[wb] thumbnail: no canvas data, using placeholder")
                           (try
                             (let [data     (js/JSON.parse raw)
                                   elements (.-elements data)]
                               (js/console.log "[wb] thumbnail elements count:"
                                               (if elements (.-length elements) "nil"))
                               (if (and elements
                                        (pos? (.-length elements))
                                        (exists? js/ExcalidrawLib)
                                        (.-exportToSvg js/ExcalidrawLib))
                                 (-> (js/ExcalidrawLib.exportToSvg
                                      #js {:elements elements
                                           :appState #js {:exportWithDarkMode false
                                                          :exportBackground   true
                                                          :backgroundColor    "#ffffff"}
                                           :files #js {}})
                                     (.then (fn [^js svg]
                                              (.setAttribute svg "width" "100%")
                                              (.setAttribute svg "height" "100%")
                                              (.setAttribute svg "preserveAspectRatio" "xMidYMid meet")
                                              (reset! *svg (.-outerHTML svg))))
                                     (.catch (fn [err]
                                               (js/console.warn "[wb] SVG thumbnail export failed" err))))
                                 (js/console.log "[wb] thumbnail: empty elements or no ExcalidrawLib")))
                             (catch :default err
                               (js/console.warn "[wb] Thumbnail parse error" err))))))]
       ;; Ensure the Excalidraw bundle (which exposes ExcalidrawLib) is loaded
       ;; before attempting SVG export. On first visit the bundle isn't loaded yet.
       (ensure-excalidraw-loaded! gen-svg!))
     state)}
  [state _page-uuid]
  (let [svg-html (rum/react (::svg-html state))]
    [:div.wb-thumbnail-area
     {:style {:width         "100%"
              :height        "130px"
              :background    "var(--lx-gray-02, #f9fafb)"
              :borderRadius  "8px 8px 0 0"
              :overflow      "hidden"
              :display       "flex"
              :alignItems    "center"
              :justifyContent "center"}}
     (if svg-html
       [:div {:style {:width "100%" :height "100%" :overflow "hidden"}
              :dangerouslySetInnerHTML {:__html svg-html}}]
       [:div.opacity-25
        (ui/icon "layout-board" {:size 36})])]))

(rum/defcs all-whiteboards
  "Gallery page listing all whiteboards in a 3-column grid with SVG thumbnails."
  < rum/reactive db-mixins/query
  (rum/local false ::creating?)
  (rum/local "" ::new-name)
  (rum/local nil  ::editing-uuid)
  (rum/local ""   ::rename-val)
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
        wclass-id     (:db/id (db/entity :logseq.class/Whiteboard))
        ;; Async reactive query via DB worker. react/q sends the query to the DB worker
        ;; (not the local replica), and re-runs automatically when whiteboard objects
        ;; change (the worker emits [::objects wclass-id] affected-keys on :block/tags txns).
        wb-atom       (when (and repo wclass-id)
                        (react/q repo [:frontend.worker.react/objects wclass-id]
                                 {:query-fn
                                  (fn [_db _]
                                    (p/let [result (db-async/<get-tag-objects repo wclass-id)]
                                      (->> result
                                           (filter :block/title)
                                           (sort-by #(or (:block/updated-at %) 0) >))))}
                                 nil))
        whiteboards   (or (some-> wb-atom rum/react) [])

        ;; Create: only close input if creation succeeded (not a duplicate)
        do-create!
        (fn []
          (let [trimmed (string/trim new-name)]
            (when (seq trimmed)
              (when (whiteboard-handler/<create-whiteboard! trimmed)
                ;; <create-whiteboard! returns nil on duplicate (shows notification)
                ;; Only close input when the promise resolves to a page
                (reset! *creating? false)
                (reset! *new-name "")))))

        ;; Commit rename
        do-rename!
        (fn []
          (let [trimmed (string/trim rename-val)]
            (when (and (seq trimmed) editing-uuid)
              (whiteboard-handler/<rename-whiteboard! editing-uuid trimmed)
              (reset! *editing-uuid nil)
              (reset! *rename-val ""))))

        cancel-rename!
        (fn [] (reset! *editing-uuid nil) (reset! *rename-val ""))]

    [:div.all-whiteboards-page
     {:style {:padding "24px 28px" :maxWidth "1000px" :margin "0 auto"}}

     ;; header
     [:div.flex.items-center.gap-3.mb-6
      (ui/icon "layout-board" {:size 22 :class "opacity-70"})
      [:h1.text-xl.font-bold.flex-1 "白板"]
      (if creating?
        [:div.flex.items-center.gap-2
         [:input
          {:type        "text"
           :auto-focus  true
           :placeholder "白板名称…"
           :value       new-name
           :style       {:fontSize "13px" :padding "5px 10px"
                         :borderRadius "6px"
                         :border "1px solid var(--lx-gray-07, #d1d5db)"
                         :outline "none" :width "180px"}
           :on-change   #(reset! *new-name (.. % -target -value))
           :on-key-down (fn [^js e]
                          (case (.-key e)
                            "Enter"  (do-create!)
                            "Escape" (do (reset! *creating? false)
                                         (reset! *new-name ""))
                            nil))}]
         (shui/button {:size :sm :on-click do-create!} "创建")
         (shui/button
          {:size :sm :variant :ghost
           :on-click (fn [] (reset! *creating? false) (reset! *new-name ""))}
          "取消")]
        (shui/button
         {:size     :sm
          :on-click (fn [] (reset! *creating? true) (reset! *new-name ""))}
         (ui/icon "plus" {:size 14 :class "mr-1"})
         "新建白板"))]

     ;; grid
     (if (seq whiteboards)
       [:div.wb-gallery-grid
        {:style {:display             "grid"
                 :gridTemplateColumns "repeat(3, 1fr)"
                 :gap                 "16px"}}
        (doall
         (for [wb whiteboards
               :let [wb-uuid  (str (:block/uuid wb))
                     wb-title (or (:block/title wb) "Untitled")
                     renaming? (= editing-uuid wb-uuid)]]
          [:div.wb-gallery-card
           {:key            (str "wbcard-" wb-uuid)
            :style          {:border       "1px solid var(--lx-gray-05, #e5e7eb)"
                             :borderRadius "10px"
                             :overflow     "hidden"
                             :cursor       "pointer"
                             :transition   "box-shadow 0.15s, transform 0.15s"
                             :background   "var(--lx-gray-01, #fff)"}
            :on-mouse-enter #(let [^js s  (.. % -currentTarget -style)
                                    ^js as (some-> % .-currentTarget
                                                   (.querySelector ".wb-card-actions") .-style)]
                               (set! (.-boxShadow s) "0 4px 16px rgba(0,0,0,0.12)")
                               (set! (.-transform s) "translateY(-2px)")
                               (when as (set! (.-opacity as) "1")))
            :on-mouse-leave #(let [^js s  (.. % -currentTarget -style)
                                    ^js as (some-> % .-currentTarget
                                                   (.querySelector ".wb-card-actions") .-style)]
                               (set! (.-boxShadow s) "none")
                               (set! (.-transform s) "none")
                               (when as (set! (.-opacity as) "0")))
            :on-click       (fn [^js e]
                              ;; Don't navigate if clicking action buttons or rename input
                              (when-not (some-> (.. e -target)
                                                (.closest ".wb-card-actions, .wb-rename-input"))
                                (whiteboard-handler/redirect-to-whiteboard! wb-uuid)))}
           ;; SVG thumbnail
           (whiteboard-thumbnail wb-uuid)
           ;; card footer
           [:div.flex.items-center.gap-2.px-3.py-2
            {:style {:borderTop "1px solid var(--lx-gray-05, #e5e7eb)"}}
            (ui/icon "layout-board" {:size 13 :class "opacity-50 shrink-0"})
            ;; Title or rename input
            (if renaming?
              [:input.wb-rename-input
               {:type        "text"
                :auto-focus  true
                :value       rename-val
                :style       {:flex       "1"
                              :fontSize   "13px"
                              :padding    "1px 4px"
                              :borderRadius "4px"
                              :border     "1px solid var(--lx-gray-07,#d1d5db)"
                              :outline    "none"
                              :minWidth   0}
                :on-change   #(reset! *rename-val (.. % -target -value))
                :on-blur     (fn [] (js/setTimeout cancel-rename! 150))
                :on-key-down (fn [^js e]
                               (case (.-key e)
                                 "Enter"  (do-rename!)
                                 "Escape" (cancel-rename!)
                                 nil))}]
              [:span.text-sm.font-medium.truncate {:style {:flex "1"}} wb-title])
            ;; Action buttons (rename + delete)
            [:div.wb-card-actions
             {:style {:display "flex" :gap "2px" :opacity "0"
                      :transition "opacity 0.15s"}
              ;; Show on card hover via inline style toggling
              :on-mouse-enter #(set! (.. % -currentTarget -style -opacity) "1")
              :on-mouse-leave #(set! (.. % -currentTarget -style -opacity) "0")}
             [:button
              {:title    "重命名"
               :on-click (fn [^js e]
                           (.stopPropagation e)
                           (reset! *editing-uuid wb-uuid)
                           (reset! *rename-val wb-title))
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
                                (str "删除白板「" wb-title "」？")]
                               [:div {:style {:font-size "12px" :opacity "0.7" :margin-bottom "12px"}}
                                "此操作不可撤销。"]
                               [:div {:style {:display "flex" :gap "8px"}}
                                [:button {:on-click (fn []
                                                      (notification/clear! uid)
                                                      (whiteboard-handler/<delete-whiteboard! wb-uuid))
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
                          :font-size "12px" :color "#ef4444"
                          :opacity "0.6"}}
              (ui/icon "trash" {:size 13})]]]]))]
       ;; empty state
       [:div.flex.flex-col.items-center.justify-center.gap-4
        {:style {:paddingTop "80px"}}
        (ui/icon "layout-board" {:size 56 :class "opacity-20"})
        [:div.text-sm.opacity-50 "还没有白板，点击「新建白板」开始"]])]))

;; ── route entry-points ─────────────────────────────────────────────────────────

(rum/defc whiteboard
  "Called by the reitit router.  Expects :path-params {:name <page-uuid>}."
  < rum/reactive
  [route-match]
  (let [raw-name (get-in route-match [:path-params :name])
        page     (or (when (util/uuid-string? raw-name)
                       (db/entity [:block/uuid (uuid raw-name)]))
                     (db/get-page raw-name))]
    (if page
      (whiteboard-page {:page-entity page})
      [:div.flex.flex-col.items-center.justify-center.gap-4
       {:style {:height "80vh"}}
       (ui/icon "layout-board" {:size 48 :class "opacity-30"})
       [:div.text-sm.opacity-60 "白板页面未找到"]
       (shui/button
        {:on-click #(whiteboard-handler/<create-whiteboard! "新白板")}
        "创建新白板")])))
