(ns frontend.extensions.excalidraw.api
  "Utility functions that operate on an ExcalidrawImperativeAPI instance.
   This namespace is part of the :main bundle (no Excalidraw import needed).
   The functions work by calling JS methods on the api object at runtime."
  (:require [goog.object :as gobj]))

;; ── element builders ──────────────────────────────────────────────────────────

(defn- gen-id []
  (str "ls-" (.toString (.now js/Date) 36)
       "-" (.toString (rand-int 0xFFFFFF) 16)))

(defn make-block-element
  "Returns an Excalidraw rectangle element representing a Logseq block card."
  [block-id block-title page-title x y]
  (let [id (gen-id)]
    #js {:id             id
         :type           "rectangle"
         :x              (or x 120)
         :y              (or y 140)
         :width          240
         :height         80
         :strokeColor    "#6366f1"
         :backgroundColor "#eef2ff"
         :fillStyle      "solid"
         :strokeWidth    2
         :roughness      0
         :opacity        100
         :groupIds       #js []
         :roundness      #js {:type 3 :value 10}
         :isDeleted      false
         :boundElements  #js [#js {:type "text" :id (str id "-txt")}]
         :updated        (.now js/Date)
         :version        1
         :seed           (rand-int 100000)
         :versionNonce   (rand-int 100000)
         :angle          0
         :locked         false
         :customData     #js {:type       "logseq-block"
                              :blockId    (str block-id)
                              :blockTitle (or block-title "")
                              :pageTitle  (or page-title "")}}))

(defn make-block-text-element
  "Returns an Excalidraw text element bound to the given block rectangle."
  [rect-id rect-x rect-y block-title page-title]
  (let [label (str (when (seq page-title) (str "📄 " page-title "\n"))
                   (if (> (count block-title) 55)
                     (str (subs block-title 0 55) "…")
                     (or block-title "(空内容)")))]
    #js {:id             (str rect-id "-txt")
         :type           "text"
         :x              (+ rect-x 12)
         :y              (+ rect-y 10)
         :width          216
         :height         60
         :text           label
         :originalText   label
         :fontSize       12
         :fontFamily     1
         :textAlign      "left"
         :verticalAlign  "top"
         :containerId    rect-id
         :strokeColor    "#3730a3"
         :backgroundColor "transparent"
         :fillStyle      "solid"
         :strokeWidth    1
         :roughness      0
         :opacity        100
         :groupIds       #js []
         :isDeleted      false
         :updated        (.now js/Date)
         :version        1
         :seed           (rand-int 100000)
         :versionNonce   (rand-int 100000)
         :angle          0
         :locked         false
         :boundElements  nil
         :lineHeight     1.25
         :customData     nil}))

;; ── api helpers ───────────────────────────────────────────────────────────────

(defn insert-block-elements!
  "Add a Logseq block card (rectangle + text) to the Excalidraw canvas.
   `api` – ExcalidrawImperativeAPI instance obtained from the :ref callback."
  [^js api block-id block-title page-title]
  (when api
    (let [existing  (.getSceneElements api)
          app-state (.getAppState api)
          scroll-x  (gobj/get app-state "scrollX")
          scroll-y  (gobj/get app-state "scrollY")
          ;; centre the card in the current viewport
          vp-w      (gobj/get app-state "width")
          vp-h      (gobj/get app-state "height")
          zoom      (or (gobj/getValueByKeys app-state "zoom" "value") 1)
          cx        (- (/ vp-w 2 zoom) scroll-x)
          cy        (- (/ vp-h 2 zoom) scroll-y)
          ;; jitter so repeated inserts don't perfectly overlap
          x         (+ cx (- (rand-int 60) 30))
          y         (+ cy (- (rand-int 40) 20))
          rect      (make-block-element block-id block-title page-title x y)
          rect-id   (gobj/get rect "id")
          txt       (make-block-text-element rect-id x y block-title page-title)
          new-elems (.concat existing #js [rect txt])]
      (.updateScene api #js {:elements new-elems}))))

(defn logseq-block-element?
  "True if `el` is a Logseq block card."
  [^js el]
  (= "logseq-block" (some-> el (gobj/get "customData") (gobj/get "type"))))

(defn get-selected-block-element
  "Returns the selected block-card element (one selected, must be logseq-block), or nil."
  [^js api]
  (when api
    (let [els       (.getSceneElements api)
          app-state (.getAppState api)
          sel-ids   (js/Object.keys (or (gobj/get app-state "selectedElementIds") #js {}))]
      (when (= 1 (.-length sel-ids))
        (let [el (.find els #(= (gobj/get % "id") (aget sel-ids 0)))]
          (when (logseq-block-element? el) el))))))
