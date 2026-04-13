(ns frontend.extensions.sheet.core
  "Univer spreadsheet wrapper component (lazy-loaded module).

   This module is loaded on-demand via shadow.lazy and provides the raw
   sheet editor component.  All DB access happens through props callbacks
   — this namespace has zero dependency on Logseq's DB layer.

   Persistence strategy (mirrors Excalidraw pattern):
   - Fast write cache  : native localStorage, saved every 3 s while editing
   - Authoritative store: Logseq worker SQLite sidecar (via on-save-data callback)
   - On pagehide / visibilitychange: explicit immediate save
   - On unmount: fallback save to localStorage
   - Dirty tracking via univerAPI.onCommandExecuted"
  (:require [frontend.handler.visual-doc :as visual-doc]
            [frontend.state :as state]
            [promesa.core :as p]
            [rum.core :as rum]))

;; ── Univer factory (from webpack bundle window.UniverSheet) ─────────────────

(def ^:private createSheetInstance (.-createSheetInstance js/UniverSheet))

;; ── Default workbook data ────────────────────────────────────────────────────

(def ^:private default-sheet-id "sheet_1")

(defn- default-workbook-data
  [title]
  (clj->js {"id" "workbook_1"
            "name" (or title "Sheet")
            "sheetOrder" [default-sheet-id]
            "sheets" {default-sheet-id
                      {"id" default-sheet-id
                       "name" "Sheet1"
                       "rowCount" 50
                       "columnCount" 20
                       "cellData" {}}}}))

;; ── Snapshot helpers ─────────────────────────────────────────────────────────

(defn- snapshot->json
  "Extract workbook snapshot from a running Univer instance as JSON string.
   Uses the FUniver facade API: univerAPI.getActiveWorkbook().save()."
  [^js univer-api]
  (try
    (when-let [workbook (.getActiveWorkbook univer-api)]
      (js/JSON.stringify (.save workbook)))
    (catch :default e
      (js/console.error "[sheet] snapshot->json failed:" e)
      nil)))

(defn- json->workbook-data
  "Parse a JSON string to workbook data, returning nil on failure."
  [json-str title]
  (or (when (seq json-str)
        (try
          (js/JSON.parse json-str)
          (catch :default error
            (js/console.warn "[sheet] failed to parse workbook json:" error)
            nil)))
      (default-workbook-data title)))

;; ── localStorage draft cache (delegated to visual-doc shared helpers) ────────

(def ^:private cache-prefix "sheet-data")

(defn save-doc-cache!
  [page-uuid json-str]
  (visual-doc/save-doc-cache! cache-prefix page-uuid json-str))

(defn read-doc-cache
  [page-uuid]
  (visual-doc/read-doc-cache cache-prefix page-uuid))

(defn clear-doc-cache!
  [page-uuid]
  (visual-doc/clear-doc-cache! cache-prefix page-uuid))

;; ── Sync status UI ──────────────────────────────────────────────────────────

(defn- sync-status-dict []
  (let [lang (.toLowerCase (str (or (state/sub :preferred-language)
                                    (some-> js/window .-navigator .-language)
                                    "en")))]
    (cond
      (.includes lang "hant")
      {:draft "草稿" :graph "圖譜" :cached "已快取" :pending "待保存" :saved "已保存"}

      (.startsWith lang "zh")
      {:draft "草稿" :graph "图谱" :cached "已缓存" :pending "待保存" :saved "已保存"}

      :else
      {:draft "Draft" :graph "Graph" :cached "cached" :pending "pending" :saved "saved"})))

(defn- sync-status-copy
  [cached? persisted?]
  (let [{:keys [draft graph cached pending saved]} (sync-status-dict)]
    {:draft (str draft " " (if cached? cached pending))
     :graph (str graph " " (if persisted? saved pending))}))

;; ── Univer instance lifecycle ────────────────────────────────────────────────

(defn- create-univer-instance!
  "Creates a Univer instance via the JS factory, mounts into container-el.
   Returns {:univer inst :api univerAPI}."
  [^js container-el workbook-data]
  (let [^js result (createSheetInstance container-el workbook-data)]
    {:univer (.-univer result)
     :api    (.-univerAPI result)}))

(defn- destroy-univer-instance!
  [^js univer]
  (when univer
    (try (.dispose univer)
         (catch :default e
           (js/console.error "[sheet] destroy failed:" e)))))

;; ── Rum editor component ─────────────────────────────────────────────────────

(rum/defcs editor
  "Mounts a Univer spreadsheet with Excalidraw-style dirty tracking.

   Props:
     :sheet-id             — page UUID string (required)
     :sheet-title          — display title
     :initial-json         — initial workbook JSON string (may be nil)
     :needs-initial-flush? — when true, flush initial-json to sidecar on mount
     :on-save-data         — (fn [page-uuid json-str]) called to persist data"
  < rum/reactive
    (rum/local nil    ::univer)           ; raw Univer instance (for dispose)
    (rum/local nil    ::univer-api)       ; FUniver facade
    (rum/local nil    ::container)        ; DOM element ref
    (rum/local false  ::cache-dirty?)     ; needs localStorage write
    (rum/local false  ::persist-dirty?)   ; needs sidecar flush
    (rum/local true   ::cached?)          ; last cache write succeeded
    (rum/local true   ::persisted?)       ; last sidecar write succeeded
    (rum/local nil    ::last-cached-json)
    (rum/local nil    ::last-persisted-json)
    (rum/local nil    ::cache-timer-id)
    (rum/local nil    ::flush-timer-id)
    (rum/local nil    ::command-listener) ; IDisposable from onCommandExecuted
    (rum/local nil    ::pagehide-handler)
    (rum/local nil    ::visibility-handler)
    (rum/local nil    ::current-save-fn)
    (rum/local nil    ::current-page-uuid)
  {:did-mount
   (fn [state]
     (let [*univer            (::univer state)
           *univer-api        (::univer-api state)
           *container         (::container state)
           *cache-dirty?      (::cache-dirty? state)
           *persist-dirty?    (::persist-dirty? state)
           *cached?           (::cached? state)
           *persisted?        (::persisted? state)
           *last-cached-json  (::last-cached-json state)
           *last-persisted-json (::last-persisted-json state)
           *cache-timer       (::cache-timer-id state)
           *flush-timer       (::flush-timer-id state)
           *cmd-listener      (::command-listener state)
           *pagehide          (::pagehide-handler state)
           *visibility        (::visibility-handler state)
           *save-fn           (::current-save-fn state)
           *p-uuid            (::current-page-uuid state)
           args               (first (:rum/args state))
           {:keys [sheet-id initial-json needs-initial-flush? on-save-data]} args
           _                  (do (reset! *save-fn on-save-data)
                                  (reset! *p-uuid sheet-id))

           ;; persist! — flush to both localStorage and sidecar DB
           persist!
           (fn []
             (if-let [api @*univer-api]
               (let [json-str (snapshot->json api)
                     p-uuid   @*p-uuid
                     save-fn  @*save-fn]
                 (when json-str
                   ;; Cache to localStorage
                   (save-doc-cache! p-uuid json-str)
                   (reset! *last-cached-json json-str)
                   (reset! *cached? true)
                   (reset! *cache-dirty? false)
                   ;; Flush to sidecar
                   (if save-fn
                     (-> (p/let [result (save-fn p-uuid json-str)]
                           (let [saved? (boolean result)]
                             (reset! *persisted? saved?)
                             (if saved?
                               (do (reset! *last-persisted-json json-str)
                                   (reset! *persist-dirty? false))
                               (reset! *persist-dirty? true))
                             saved?))
                         (p/catch (fn [error]
                                    (js/console.error "[sheet] persist failed:" error)
                                    (reset! *persisted? false)
                                    (reset! *persist-dirty? true)
                                    false)))
                     (do (reset! *persisted? false)
                         (p/resolved false)))))
               (p/resolved false)))]

       ;; Initialize dirty tracking baseline
       (reset! *last-cached-json initial-json)
       (reset! *last-persisted-json (when-not needs-initial-flush? initial-json))
       (reset! *cached? true)
       (reset! *persisted? (not needs-initial-flush?))

       ;; Mount Univer after a tick so the DOM node is ready
       (js/requestAnimationFrame
        (fn []
          (when-let [el @*container]
            (let [wb-data  (json->workbook-data initial-json (:sheet-title args))
                  {:keys [univer ^js api]} (create-univer-instance! el wb-data)]
              (reset! *univer univer)
              (reset! *univer-api api)

              ;; If cache was newer than sidecar, flush immediately
              (when (and needs-initial-flush? on-save-data initial-json)
                (on-save-data sheet-id initial-json))

              ;; Listen for all command executions → mark dirty
              (when api
                (try
                  (reset! *cmd-listener
                          (.onCommandExecuted api
                            (fn [_cmd-info]
                              (when-let [current-json (snapshot->json api)]
                                (when (not= current-json @*last-cached-json)
                                  (reset! *cache-dirty? true)
                                  (reset! *cached? false))
                                (when (not= current-json @*last-persisted-json)
                                  (reset! *persist-dirty? true)
                                  (reset! *persisted? false))))))
                  (catch :default e
                    (js/console.warn "[sheet] onCommandExecuted listener failed:" e))))

              ;; 3s cache timer — save to localStorage if dirty
              (reset! *cache-timer
                      (js/setInterval
                       (fn []
                         (when (and @*cache-dirty? @*univer-api)
                           (when-let [json (snapshot->json @*univer-api)]
                             (save-doc-cache! @*p-uuid json)
                             (reset! *last-cached-json json))
                           (reset! *cached? true)
                           (reset! *cache-dirty? false)))
                       3000))

              ;; 9s flush timer — persist to sidecar if dirty
              (reset! *flush-timer
                      (js/setInterval
                       (fn []
                         (when @*persist-dirty?
                           (persist!)))
                       9000))))))

       ;; Event handlers for immediate save on tab hide/close
       (let [pagehide   (fn [] (persist!))
             visibility (fn []
                          (when (= "hidden" (.-visibilityState js/document))
                            (persist!)))]
         (reset! *pagehide pagehide)
         (reset! *visibility visibility)
         (.addEventListener js/window "pagehide" pagehide)
         (.addEventListener js/document "visibilitychange" visibility))

       state))

   :did-update
   (fn [state]
     ;; Keep save-fn / page-uuid atoms in sync with latest props
     (let [args (first (:rum/args state))]
       (reset! (::current-save-fn state) (:on-save-data args))
       (reset! (::current-page-uuid state) (:sheet-id args)))
     state)

   :will-unmount
   (fn [state]
     (let [*univer     (::univer state)
           *univer-api (::univer-api state)
           *cache-timer (::cache-timer-id state)
           *flush-timer (::flush-timer-id state)
           *cmd-listener (::command-listener state)
           *pagehide   (::pagehide-handler state)
           *visibility (::visibility-handler state)
           p-uuid      @(::current-page-uuid state)
           save-fn     @(::current-save-fn state)]
       ;; Clear timers
       (when-let [t @*cache-timer] (js/clearInterval t))
       (when-let [t @*flush-timer] (js/clearInterval t))
       ;; Remove event listeners
       (when-let [h @*pagehide] (.removeEventListener js/window "pagehide" h))
       (when-let [h @*visibility] (.removeEventListener js/document "visibilitychange" h))
       ;; Dispose command listener
       (when-let [d @*cmd-listener]
         (try (.dispose d) (catch :default _ nil)))
       ;; Final save
       (when-let [api @*univer-api]
         (when-let [json (snapshot->json api)]
           (save-doc-cache! p-uuid json)
           (when save-fn
             (save-fn p-uuid json))))
       ;; Destroy Univer
       (when-let [inst @*univer]
         (destroy-univer-instance! inst)
         (reset! *univer nil)
         (reset! *univer-api nil)))
     state)}

  [state props]
  (let [container-ref (::container state)
        cached?       (rum/react (::cached? state))
        persisted?    (rum/react (::persisted? state))
        {:keys [draft graph]} (sync-status-copy cached? persisted?)]
    [:div.sheet-editor-wrapper
     {:style {:width "100%" :height "100%" :min-height "400px"
              :position "relative"}}
     [:div.sheet-univer-container
      {:ref   (fn [el] (reset! container-ref el))
       :style {:width "100%" :height "100%"
               :min-height "400px"}}]
     ;; Sync status overlay (mirrors Excalidraw pattern)
     [:div.sheet-sync-status
      {:style {:position "absolute" :bottom "4px" :right "8px"
               :font-size "11px" :line-height "1.3" :opacity 0.7
               :pointer-events "none" :z-index 10
               :text-align "right"
               :color "var(--ls-secondary-text-color, #666)"}}
      [:div {:style {:color (if cached? "#3b82f6" "#f59e0b")}}
       draft]
      [:div {:style {:color (if persisted? "#22c55e" "#f59e0b")}}
       graph]]]))
