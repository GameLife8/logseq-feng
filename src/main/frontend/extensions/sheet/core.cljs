(ns frontend.extensions.sheet.core
  "Univer spreadsheet wrapper component (lazy-loaded module).

   This module is loaded on-demand via shadow.lazy and provides the raw
   sheet editor component.  All DB access happens through props callbacks
   — this namespace has zero dependency on Logseq's DB layer."
  (:require [frontend.handler.visual-doc :as visual-doc]
            [rum.core :as rum]))

;; ── Univer globals (from webpack bundle window.UniverSheet) ──────────────────

(def ^:private Univer           (.-Univer js/UniverSheet))
(def ^:private UniverInstanceType (.-UniverInstanceType js/UniverSheet))
(def ^:private LocaleType       (.-LocaleType js/UniverSheet))
(def ^:private defaultTheme     (.-defaultTheme js/UniverSheet))
(def ^:private UniverSheetsPlugin       (.-UniverSheetsPlugin js/UniverSheet))
(def ^:private UniverRenderEnginePlugin (.-UniverRenderEnginePlugin js/UniverSheet))
(def ^:private UniverSheetsUIPlugin     (.-UniverSheetsUIPlugin js/UniverSheet))
(def ^:private UniverUIPlugin           (.-UniverUIPlugin js/UniverSheet))

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
  "Extract workbook snapshot from a running Univer instance as JSON string."
  [^js univer-instance]
  (try
    (let [workbook (.getActiveUnitForType univer-instance (.-UNIVER_SHEET UniverInstanceType))]
      (when workbook
        (js/JSON.stringify (.save workbook))))
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

(defn- zh-cn-locales
  []
  (let [locale-key (.-ZH_CN LocaleType)
        locales (js-obj)
        locale-bundle (js/Object.assign
                       #js {}
                       (or (.-DesignZhCN js/UniverSheet) #js {})
                       (or (.-UIZhCN js/UniverSheet) #js {})
                       (or (.-SheetsZhCN js/UniverSheet) #js {})
                       (or (.-SheetsUIZhCN js/UniverSheet) #js {}))]
    (aset locales locale-key locale-bundle)
    locales))

;; ── Univer instance lifecycle ────────────────────────────────────────────────

(defn- create-univer-instance!
  "Creates a Univer instance, mounts into `container-el`, returns the instance."
  [^js container-el workbook-data]
  (let [univer (new Univer #js {:theme defaultTheme
                                :locale (.-ZH_CN LocaleType)
                                :locales (zh-cn-locales)})]
    (.registerPlugin univer UniverRenderEnginePlugin)
    (.registerPlugin univer UniverUIPlugin
                     #js {:container container-el
                          :footer false})
    (.registerPlugin univer UniverSheetsPlugin)
    (.registerPlugin univer UniverSheetsUIPlugin)
    (.createUnit univer
                 (.-UNIVER_SHEET UniverInstanceType)
                 workbook-data)
    univer))

(defn- destroy-univer-instance!
  [^js univer]
  (when univer
    (try (.dispose univer)
         (catch :default e
           (js/console.error "[sheet] destroy failed:" e)))))

;; ── Rum editor component ─────────────────────────────────────────────────────

(rum/defcs editor
  "Mounts a Univer spreadsheet. Props:
     :sheet-id        — page UUID string (required)
     :sheet-title     — display title
     :initial-json    — initial workbook JSON string (may be nil)
     :needs-initial-flush? — when true, flush initial-json to sidecar on mount
     :on-save-data    — (fn [page-uuid json-str]) called to persist data"
  < (rum/local nil ::univer)
    (rum/local nil ::container)
    (rum/local nil ::cache-timer)
    (rum/local nil ::flush-timer)
  {:did-mount
   (fn [state]
     (let [[props] (:rum/args state)
           {:keys [sheet-id initial-json needs-initial-flush? on-save-data]} props
           container-ref (::container state)
           univer-ref    (::univer state)
           cache-ref     (::cache-timer state)
           flush-ref     (::flush-timer state)]
       ;; Mount Univer after a tick so the DOM node is ready
       (js/requestAnimationFrame
        (fn []
          (when-let [el @container-ref]
            (let [wb-data (json->workbook-data initial-json (:sheet-title props))
                  inst    (create-univer-instance! el wb-data)]
              (reset! univer-ref inst)
              ;; If cache was newer, flush to sidecar immediately
              (when (and needs-initial-flush? on-save-data initial-json)
                (on-save-data sheet-id initial-json))
              ;; Auto-save to localStorage every 3s
              (reset! cache-ref
                      (js/setInterval
                       (fn []
                         (when-let [json (snapshot->json inst)]
                           (save-doc-cache! sheet-id json)))
                       3000))
              ;; Flush to sidecar every 9s
              (reset! flush-ref
                      (js/setInterval
                       (fn []
                         (when (and on-save-data inst)
                           (when-let [json (snapshot->json inst)]
                             (on-save-data sheet-id json))))
                       9000))))))
       state))

   :will-unmount
   (fn [state]
     (let [[props] (:rum/args state)
           {:keys [sheet-id on-save-data]} props
           univer-ref (::univer state)
           cache-ref  (::cache-timer state)
           flush-ref  (::flush-timer state)]
       ;; Clear timers
       (when-let [t @cache-ref] (js/clearInterval t))
       (when-let [t @flush-ref] (js/clearInterval t))
       ;; Final save
       (when-let [inst @univer-ref]
         (when-let [json (snapshot->json inst)]
           (save-doc-cache! sheet-id json)
           (when on-save-data
             (on-save-data sheet-id json)))
         (destroy-univer-instance! inst)
         (reset! univer-ref nil)))
     state)}

  [state props]
  (let [container-ref (::container state)]
    [:div.sheet-editor-wrapper
     {:style {:width "100%" :height "100%" :min-height "400px"
              :position "relative"}}
     [:div.sheet-univer-container
      {:ref   (fn [el] (reset! container-ref el))
       :style {:width "100%" :height "100%"
               :min-height "400px"}}]]))
