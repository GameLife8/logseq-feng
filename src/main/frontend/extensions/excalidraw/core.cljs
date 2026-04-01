(ns frontend.extensions.excalidraw.core
  "Excalidraw React component – loaded as a separate shadow-cljs lazy module.
   @excalidraw/excalidraw is loaded as a webpack bundle (excalidraw-bundle.js)
   and exposed as window.ExcalidrawLib global.

   Canvas data persistence strategy:
   - Fast write cache  : native localStorage, saved every 3 s while editing
   - Authoritative store: Logseq DB (via on-save-data callback)
   - On back  : explicit save to localStorage + DB BEFORE navigation (on-back callback)
   - On unmount: fallback save to localStorage

   IMPORTANT: Excalidraw is a React.memo-wrapped function component.
   Using :ref throws 'Function components cannot be given refs' and never fires.
   The correct API is the :excalidrawAPI prop, which accepts a callback fn(api)."
  (:require ["@excalidraw/excalidraw" :refer [Excalidraw]]
            [frontend.extensions.excalidraw.api :as ex-api]
            [frontend.handler.notification :as notification]
            [frontend.handler.visual-doc :as visual-doc]
            [frontend.state :as state]
            [goog.object :as gobj]
            [promesa.core :as p]
            [rum.core :as rum]))

;; ── localStorage fast cache ───────────────────────────────────────────────────

(def ^:private cache-prefix "whiteboard-data")

(defn- parse-canvas-json
  [json-str]
  (when (seq json-str)
    (try
      (js/JSON.parse json-str)
      (catch :default _ nil))))

(defn- sync-status-dict []
  (let [lang (.toLowerCase (str (or (state/sub :preferred-language)
                                    (some-> js/window .-navigator .-language)
                                    "en")))]
    (cond
      (.includes lang "hant")
      {:draft "草稿"
       :graph "圖譜"
       :cached "已快取"
       :pending "待保存"
       :saved "已保存"}

      (.startsWith lang "zh")
      {:draft "草稿"
       :graph "图谱"
       :cached "已缓存"
       :pending "待保存"
       :saved "已保存"}

      :else
      {:draft "Draft"
       :graph "Graph"
       :cached "cached"
       :pending "pending"
       :saved "saved"})))

(defn- sync-status-copy
  [cached? persisted?]
  (let [{:keys [draft graph cached pending saved]} (sync-status-dict)
        draft-label   draft
        graph-label   graph
        cached-label  cached
        pending-label pending
        saved-label   saved
        draft-state   (if cached? cached-label pending-label)
        graph-state   (if persisted? saved-label pending-label)]
    {:title (str draft-label ": " draft-state
                 " | " graph-label ": " graph-state)
     :label (str draft-label " " draft-state
                 " | " graph-label " " graph-state)}))

(defn- scene-json
  [elements app-state]
  (js/JSON.stringify
   #js {:elements elements
        :appState #js {:scrollX (gobj/get app-state "scrollX")
                       :scrollY (gobj/get app-state "scrollY")
                       :zoom    (gobj/get app-state "zoom")}}))

(declare canvas-json)

(defn- save-to-ls! [page-uuid ^js api]
  (when (and api page-uuid)
    (visual-doc/save-doc-cache! cache-prefix page-uuid (canvas-json api))))

(defn- canvas-json [^js api]
  (scene-json (.getSceneElements api) (.getAppState api)))

;; ── library (shared across whiteboards, per graph) ───────────────────────────

(defn- lib-key []
  (str "wb-library-" (state/get-current-repo)))

;; ── collapsible toolbar (module-level stable React component ref) ────────────
;;
;; Defined OUTSIDE rum/defcs so React always sees the same function reference.
;; This avoids remounting (and state loss) on every Rum re-render.
;;
;; Props (JS object):
;;   pageTitle          – current whiteboard title string
;;   saveAndBack        – fn() save + navigate back
;;   onRename           – fn(string) called with new title
;;   renderTags         – fn() → ReactElement  (Rum tags-bar from main bundle)
;;   onShowLinkedBlocks – fn(element-id-string) open linked-blocks panel for selected el
;;   selElId            – string ID of the single selected element | nil
(def ^:private toolbar-buttons
  (fn toolbar-buttons [^js props]
    (let [page-title          (gobj/get props "pageTitle")
          save-and-back!      (gobj/get props "saveAndBack")
          on-rename           (gobj/get props "onRename")
          render-tags         (gobj/get props "renderTags")
          on-show-linked      (gobj/get props "onShowLinkedBlocks")
          sel-el-id           (gobj/get props "selElId")
          cached?             (boolean (gobj/get props "cached"))
          persisted?          (boolean (gobj/get props "persisted"))
          sync-status         (sync-status-copy cached? persisted?)
          ;; hooks – must be unconditionally at top level
          [open?     set-open!]    (rum/use-state false)
          [editing?  set-editing!] (rum/use-state false)
          [input-val set-input!]   (rum/use-state page-title)
          commit!  (fn []
                     (let [t (.trim input-val)]
                       (when (and (seq t) on-rename) (on-rename t)))
                     (set-editing! false))]
      ;; Sync the rename input when the upstream page-title prop changes
      (js/React.useEffect (fn [] (set-input! page-title) js/undefined)
                          #js [page-title])

      (if-not open?
        ;; ── collapsed: single "☰ 工具" button ──────────────────────────────
        (js/React.createElement
         "button"
         #js {:title   "展开工具栏"
              :onClick #(set-open! true)
              :style   #js {:display "flex" :alignItems "center" :gap "5px"
                            :padding "5px 10px"
                            :background "var(--lx-gray-03,#f3f4f6)"
                            :color "var(--lx-gray-12,#111)"
                            :border "1px solid var(--lx-gray-06,#e5e7eb)"
                            :borderRadius "6px" :cursor "pointer"
                            :fontSize "13px" :whiteSpace "nowrap"}}
         "☰ 工具")

        ;; ── expanded: full button row ────────────────────────────────────────
        (js/React.createElement
         "div"
         #js {:style #js {:display "flex" :gap "6px" :alignItems "center"}}

         ;; ← 返回
         (js/React.createElement
          "button"
          #js {:title   "保存并返回白板列表"
               :onClick save-and-back!
               :style   #js {:display "flex" :alignItems "center" :gap "4px"
                             :padding "5px 10px"
                             :background "var(--lx-gray-03,#f3f4f6)"
                             :color "var(--lx-gray-12,#111)"
                             :border "1px solid var(--lx-gray-06,#e5e7eb)"
                             :borderRadius "6px" :cursor "pointer"
                             :fontSize "13px" :whiteSpace "nowrap"}}
          "← 返回")

         ;; Title: editable chip (click to rename, Enter/Escape to confirm/cancel)
         (if editing?
           (js/React.createElement
            "input"
            #js {:type      "text"
                 :autoFocus true
                 :value     input-val
                 :onChange  (fn [^js e] (set-input! (.. e -target -value)))
                 :onBlur    (fn [] (js/setTimeout commit! 150))
                 :onKeyDown (fn [^js e]
                              (case (.-key e)
                                "Enter"  (commit!)
                                "Escape" (do (set-input! page-title)
                                             (set-editing! false))
                                nil))
                 :style     #js {:padding "4px 8px"
                                 :border "1px solid var(--lx-gray-07,#d1d5db)"
                                 :borderRadius "6px" :fontSize "13px"
                                 :width "140px" :outline "none"}})
           (js/React.createElement
            "span"
            #js {:title   "点击重命名"
                 :onClick (fn [] (set-input! page-title) (set-editing! true))
                 :style   #js {:padding "4px 10px"
                               :background "var(--lx-gray-02,#f9fafb)"
                               :border "1px solid var(--lx-gray-05,#e5e7eb)"
                               :borderRadius "6px" :fontSize "13px"
                               :fontWeight "600" :maxWidth "180px"
                               :overflow "hidden" :textOverflow "ellipsis"
                               :whiteSpace "nowrap" :cursor "pointer"}}
            page-title))

         (js/React.createElement
          "span"
          #js {:title (:title sync-status)
               :style #js {:padding "4px 8px"
                           :borderRadius "999px"
                           :fontSize "11px"
                           :background (if persisted?
                                         "rgba(16,185,129,0.12)"
                                         "rgba(245,158,11,0.12)")
                           :color (if persisted? "#047857" "#b45309")
                           :whiteSpace "nowrap"}}
          (:label sync-status))

         ;; 🔗 链接块 — shown only when exactly one element is selected
         (when (and sel-el-id on-show-linked)
           (js/React.createElement
            "button"
            #js {:title   "管理此元素的关联块和备注"
                 :onClick (fn [] (on-show-linked sel-el-id))
                 :style   #js {:display "flex" :alignItems "center" :gap "4px"
                               :padding "5px 10px"
                               :background "#6366f1" :color "#fff"
                               :border "none" :borderRadius "6px"
                               :cursor "pointer" :fontSize "13px"
                               :whiteSpace "nowrap"}}
            "🔗 链接块"))

         ;; × 收起
         (js/React.createElement
          "button"
          #js {:title   "收起工具栏"
               :onClick #(set-open! false)
               :style   #js {:display "flex" :alignItems "center"
                             :padding "5px 8px"
                             :background "var(--lx-gray-03,#f3f4f6)"
                             :color "var(--lx-gray-12,#111)"
                             :border "1px solid var(--lx-gray-06,#e5e7eb)"
                             :borderRadius "6px" :cursor "pointer"
                             :fontSize "14px" :lineHeight "1" :opacity "0.6"}}
          "×"))))))



(rum/defcs excalidraw-editor
  "Core Excalidraw canvas component.

   Props map:
     :page-uuid          – UUID string of the whiteboard page
     :page-title         – Display title string shown in the toolbar chip
     :on-back            – fn() called to navigate back (after save completes)
     :on-show-linked-blocks – fn(element-id-str) open linked-blocks panel in parent
     :on-selection-change   – fn(element-id-str|nil) fired on every selection change
     :on-api-ready       – fn called with the ExcalidrawImperativeAPI once mounted
     :initial-json       – preloaded JSON string for the initial scene
     :on-save-data       – fn(page-uuid, json-string)          (writes to DB)"
  < rum/static
  (rum/local nil    ::api)
  (rum/local nil    ::sel-el-id)   ; ID string of selected element, or nil
  (rum/local false  ::cache-dirty?)
  (rum/local false  ::persist-dirty?)
  (rum/local true   ::cached?)
  (rum/local true   ::persisted?)
  (rum/local nil    ::last-cached-json)
  (rum/local nil    ::last-persisted-json)
  (rum/local nil    ::cache-timer-id)
  (rum/local nil    ::flush-timer-id)
  (rum/local nil    ::pagehide-handler)
  (rum/local nil    ::visibility-handler)
  (rum/local nil    ::library-items)
  {:did-mount
   (fn [state]
      (let [*cache-timer        (::cache-timer-id state)
            *flush-timer        (::flush-timer-id state)
            *cache-dirty?       (::cache-dirty? state)
            *persist-dirty?     (::persist-dirty? state)
            *cached?            (::cached? state)
            *persisted?         (::persisted? state)
            *last-cached-json   (::last-cached-json state)
            *last-persisted-json (::last-persisted-json state)
            *pagehide           (::pagehide-handler state)
            *visibility         (::visibility-handler state)
            *api                (::api state)
            *library            (::library-items state)
            args                (first (:rum/args state))
            p-uuid              (:page-uuid args)
            save-fn             (:on-save-data args)
            initial-json        (:initial-json args)
            needs-initial-flush? (boolean (:needs-initial-flush? args))
            persist!            (fn []
                                  (if-let [api @*api]
                                    (let [json-str (canvas-json api)]
                                      (save-to-ls! p-uuid api)
                                      (reset! *last-cached-json json-str)
                                      (reset! *cached? true)
                                      (reset! *cache-dirty? false)
                                      (if save-fn
                                        (-> (p/let [save-result (save-fn p-uuid json-str)]
                                              (let [saved? (boolean save-result)]
                                                (reset! *persisted? saved?)
                                                (if saved?
                                                  (do
                                                    (reset! *last-persisted-json json-str)
                                                    (reset! *persist-dirty? false))
                                                  (reset! *persist-dirty? true))
                                                saved?))
                                            (p/catch (fn [error]
                                                       (js/console.error "[excalidraw] persist failed:" error)
                                                       (reset! *persisted? false)
                                                       (reset! *persist-dirty? true)
                                                       false)))
                                        (do
                                          (reset! *persisted? false)
                                          (p/resolved false))))
                                    (p/resolved false)))
            cache-timer    (js/setInterval
                            (fn []
                              (when (and @*cache-dirty? @*api)
                                (let [json-str (canvas-json @*api)]
                                  (save-to-ls! p-uuid @*api)
                                  (reset! *last-cached-json json-str))
                                (reset! *cached? true)
                                (reset! *cache-dirty? false)))
                            3000)
           flush-timer    (js/setInterval
                           (fn []
                             (when @*persist-dirty?
                               (persist!)))
                           9000)
           pagehide       (fn [] (persist!))
           visibility     (fn []
                            (when (= "hidden" (.-visibilityState js/document))
                              (persist!)))]
        (reset! *last-cached-json initial-json)
        (reset! *last-persisted-json (when-not needs-initial-flush? initial-json))
        (reset! *cached? true)
        (reset! *persisted? (not needs-initial-flush?))
       (reset! *cache-timer cache-timer)
       (reset! *flush-timer flush-timer)
       (reset! *pagehide pagehide)
       (reset! *visibility visibility)
       (.addEventListener js/window "pagehide" pagehide)
       (.addEventListener js/document "visibilitychange" visibility)
       (when-let [raw (.getItem js/localStorage (lib-key))]
         (try (reset! *library (js/JSON.parse raw))
              (catch :default _ nil)))
       ;; Inject/update CSS: hide shortcut keys + compact context menu
       (let [el (or (.getElementById js/document "excalidraw-custom-css")
                    (let [new-el (.createElement js/document "style")]
                      (set! (.-id new-el) "excalidraw-custom-css")
                      (.. js/document -head (appendChild new-el))
                      new-el))]
         (set! (.-textContent el)
               (str ".excalidraw .dropdown-menu-item__shortcut { display: none !important; }\n"
                    ".excalidraw .context-menu-item__shortcut { display: none !important; }\n"
                    ".excalidraw .context-menu-option__shortcut { display: none !important; }\n"
                    ".excalidraw .context-menu-item-separator { display: none !important; }\n"
                    ".excalidraw .context-menu-item__label { font-size: 12px !important; }\n"
                    ".excalidraw .context-menu-item { min-height: 28px !important; }")))
       ;; Inject @font-face overrides for custom font paths configured by the user.
       ;; Excalidraw uses font-family names "Virgil", "Helvetica" and "Cascadia".
       ;; Supports Unix paths (/usr/…), Windows paths (C:\… or C:/…), and URLs.
       (let [path->url  (fn [p]
                          (cond
                            (or (nil? p) (= "" p))              nil
                            (re-find #"^(file|http|https)://" p) p
                            (re-find #"^[A-Za-z]:[/\\]" p)      ; Windows drive letter
                            (str "file:///"
                                 (clojure.string/replace p #"\\" "/"))
                            :else                               ; Unix absolute
                            (str "file://" p)))
             ext->fmt   (fn [p]
                          (condp re-find (or p "")
                            #"(?i)\.woff2$" "woff2"
                            #"(?i)\.woff$"  "woff"
                            #"(?i)\.otf$"   "opentype"
                            "truetype"))
             face-rule  (fn [family path]
                          (when-let [url (path->url path)]
                            (str "@font-face { font-family: '" family "'; "
                                 "src: url('" url "') format('" (ext->fmt path) "'); "
                                 "font-style: normal; font-weight: normal; }\n")))
             args       (first (:rum/args state))
             fonts      (:custom-fonts args)
             face-rules (str (face-rule "Virgil"    (:virgil    fonts))
                             (face-rule "Helvetica" (:helvetica fonts))
                             (face-rule "Cascadia"  (:cascadia  fonts)))]
         (when (seq face-rules)
           (let [fe (or (.getElementById js/document "excalidraw-custom-fonts")
                        (let [new-el (.createElement js/document "style")]
                          (set! (.-id new-el) "excalidraw-custom-fonts")
                          (.. js/document -head (appendChild new-el))
                          new-el))]
             (set! (.-textContent fe) face-rules)
             (js/console.log "[excalidraw] injected custom @font-face rules")))))
     state)
   :did-update
   (fn [state]
     ;; Re-inject @font-face CSS when custom-fonts prop changes (e.g. after
     ;; async config load in the parent causes a re-render with real paths).
     (let [args      (first (:rum/args state))
           fonts     (:custom-fonts args)
           path->url (fn [p]
                       (cond
                         (or (nil? p) (= "" p))              nil
                         (re-find #"^(file|http|https)://" p) p
                         (re-find #"^[A-Za-z]:[/\\]" p)
                         (str "file:///" (clojure.string/replace p #"\\" "/"))
                         :else (str "file://" p)))
           ext->fmt  (fn [p]
                       (condp re-find (or p "")
                         #"(?i)\.woff2$" "woff2"
                         #"(?i)\.woff$"  "woff"
                         #"(?i)\.otf$"   "opentype"
                         "truetype"))
           face-rule (fn [family path]
                       (when-let [url (path->url path)]
                         (str "@font-face { font-family: '" family "'; "
                              "src: url('" url "') format('" (ext->fmt path) "'); "
                              "font-style: normal; font-weight: normal; }\n")))
           face-rules (str (face-rule "Virgil"    (:virgil    fonts))
                           (face-rule "Helvetica" (:helvetica fonts))
                           (face-rule "Cascadia"  (:cascadia  fonts)))]
       (when (seq face-rules)
         (let [fe (or (.getElementById js/document "excalidraw-custom-fonts")
                      (let [new-el (.createElement js/document "style")]
                        (set! (.-id new-el) "excalidraw-custom-fonts")
                        (.. js/document -head (appendChild new-el))
                        new-el))]
           (set! (.-textContent fe) face-rules))))
     state)
   :will-unmount
   (fn [state]
     (let [api     @(::api state)
           cache-timer @(::cache-timer-id state)
           flush-timer @(::flush-timer-id state)
           pagehide-handler @(::pagehide-handler state)
           visibility-handler @(::visibility-handler state)
           p-uuid  (-> state :rum/args first :page-uuid)
           save-fn (-> state :rum/args first :on-save-data)]
       (when cache-timer (js/clearInterval cache-timer))
       (when flush-timer (js/clearInterval flush-timer))
       (when pagehide-handler
         (.removeEventListener js/window "pagehide" pagehide-handler))
       (when visibility-handler
         (.removeEventListener js/document "visibilitychange" visibility-handler))
       (when api
         (save-to-ls! p-uuid api)
         (when save-fn (save-fn p-uuid (canvas-json api)))))
     state)}
  [state {:keys [page-uuid page-title on-back on-api-ready
                 on-show-linked-blocks on-selection-change
                 initial-json needs-initial-flush? on-save-data render-tags on-rename
                 validate-embeddable custom-fonts]}]
  (let [*api        (::api state)
         *sel-el-id  (::sel-el-id state)
         *cache-dirty? (::cache-dirty? state)
         *persist-dirty? (::persist-dirty? state)
         *cached?    (::cached? state)
         *persisted? (::persisted? state)
         *last-cached-json   (::last-cached-json state)
         *last-persisted-json (::last-persisted-json state)
         *library    (::library-items state)
         init-data   (parse-canvas-json initial-json)
         persist-now!
         (fn []
           (if-let [api @*api]
             (let [json-str (canvas-json api)]
               (save-to-ls! page-uuid api)
               (reset! *last-cached-json json-str)
               (reset! *cached? true)
               (reset! *cache-dirty? false)
               (if on-save-data
                 (-> (p/let [save-result (on-save-data page-uuid json-str)]
                       (let [saved? (boolean save-result)]
                         (reset! *persisted? saved?)
                         (if saved?
                           (do
                             (reset! *last-persisted-json json-str)
                             (reset! *persist-dirty? false))
                           (reset! *persist-dirty? true))
                         saved?))
                     (p/catch (fn [error]
                                (js/console.error "[excalidraw] explicit persist failed:" error)
                                (reset! *persisted? false)
                                (reset! *persist-dirty? true)
                                false)))
                 (p/resolved false)))
             (p/resolved false)))
         save-and-back!
         (fn []
           (-> (p/let [saved? (persist-now!)]
                 (if saved?
                   (do
                     (notification/show! "白板已保存" :success)
                     (when on-back (on-back)))
                   (notification/show! "白板保存失败，请稍后重试" :warning)))
               (p/catch (fn [error]
                          (js/console.error "[excalidraw] save-and-back failed:" error)
                          (notification/show! "白板保存失败，请稍后重试" :warning))))) ]

    [:div.excalidraw-wrapper
     {:style {:width "100%" :height "100%" :position "relative"}}

     (js/React.createElement
      Excalidraw
      #js {;; ── IMPORTANT: use :excalidrawAPI, NOT :ref ──────────────────────
            :excalidrawAPI    (fn [^js api]
                                (reset! *api api)
                                (when on-api-ready (on-api-ready api))
                                (let [current-json (canvas-json api)]
                                  (when-not @*last-cached-json
                                    (reset! *last-cached-json current-json))
                                  (when (and (not needs-initial-flush?)
                                             (not @*last-persisted-json))
                                    (reset! *last-persisted-json current-json)))
                                (when needs-initial-flush?
                                  (let [json-str (canvas-json api)]
                                    (save-to-ls! page-uuid api)
                                    (reset! *last-cached-json json-str)
                                    (reset! *cached? true)
                                    (when on-save-data
                                      (-> (p/let [save-result (on-save-data page-uuid json-str)]
                                            (let [saved? (boolean save-result)]
                                              (reset! *persisted? saved?)
                                              (if saved?
                                                (do
                                                  (reset! *last-persisted-json json-str)
                                                  (reset! *persist-dirty? false))
                                                (reset! *persist-dirty? true))))
                                          (p/catch (fn [error]
                                                     (js/console.error "[excalidraw] initial flush failed:" error)
                                                     (reset! *persisted? false)
                                                     (reset! *persist-dirty? true))))))))
           :initialData      (or init-data #js {})
           :langCode         "zh-CN"
           :theme            (if (= "dark" (state/sub :ui/theme)) "dark" "light")
           :UIOptions        #js {:canvasActions #js {:export    false
                                                      :loadScene false}}
           ;; validateEmbeddable controls Excalidraw's own domain whitelist.
           ;; Value comes from the user's excalidraw settings config:
           ;;   false  → block all (default when whitelist is empty)
           ;;   true   → allow all (whitelist contains "*")
           ;;   fn(url)→ custom check against whitelist domains
           :validateEmbeddable (or validate-embeddable false)
           :libraryItems     (or @*library #js [])
           :onLibraryChange  (fn [^js items]
                               (reset! *library items)
                               (.setItem js/localStorage (lib-key)
                                         (js/JSON.stringify items)))
            :onChange         (fn [elements ^js app-state _files]
                                (let [current-json    (scene-json elements app-state)
                                      cache-dirty?    (not= current-json @*last-cached-json)
                                      persist-dirty?  (not= current-json @*last-persisted-json)]
                                  (reset! *cache-dirty? cache-dirty?)
                                  (reset! *persist-dirty? persist-dirty?)
                                  (reset! *cached? (not cache-dirty?))
                                  (reset! *persisted? (not persist-dirty?)))
                                ;; Track selected element ID for the 🔗 toolbar button.
                                ;; Guard: only fire on-selection-change when value actually
                                ;; changes, not on every animation frame that triggers onChange.
                               (let [sel-ids (js/Object.keys
                                              (or (gobj/get app-state "selectedElementIds") #js {}))
                                     sel-id  (when (= 1 (.-length sel-ids)) (aget sel-ids 0))]
                                 (when (not= sel-id @*sel-el-id)
                                   (reset! *sel-el-id sel-id)
                                   (when on-selection-change (on-selection-change sel-id)))))
           ;; Top-right: collapsible toolbar (collapsed by default → click "☰ 工具" to expand)
           :renderTopRightUI
           (fn []
             (js/React.createElement
              toolbar-buttons
              #js {:pageTitle          page-title
                   :saveAndBack        save-and-back!
                   :cached             @*cached?
                   :persisted          @*persisted?
                   :onRename           on-rename
                   :renderTags         render-tags
                   :onShowLinkedBlocks (fn [el-id]
                                         (when on-show-linked-blocks
                                           (on-show-linked-blocks el-id)))
                   :selElId            @*sel-el-id}))})]))

;; Export for shadow.lazy loadable
(def ^:export editor excalidraw-editor)
