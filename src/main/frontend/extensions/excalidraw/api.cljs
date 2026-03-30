(ns frontend.extensions.excalidraw.api
  "Utility functions that operate on an ExcalidrawImperativeAPI instance.
   This namespace is part of the :main bundle (no Excalidraw import needed).
   The functions work by calling JS methods on the api object at runtime.

   Element customData schema (new format):
     {:linkedBlockIds  [uuid-str ...]   ;; references to existing Logseq blocks
      :noteBlockIds    [uuid-str ...]   ;; new blocks created under the whiteboard page
      ;; legacy block-card fields (backward compat):
      :type            \"logseq-block\"
      :blockId         uuid-str}"
  (:require [goog.object :as gobj]))

;; ── element ID generation ─────────────────────────────────────────────────────

(defn- gen-id []
  (str "ls-" (.toString (.now js/Date) 36)
       "-" (.toString (rand-int 0xFFFFFF) 16)))

;; ── element builders (kept for backward-compat; legacy block-card elements) ──

(defn make-block-element
  "Returns an Excalidraw rectangle element with customData linking to a block.
   Kept for backward compatibility with existing canvases."
  [block-id block-title page-title x y custom-label]
  (let [id (gen-id)]
    #js {:id             id
         :type           "rectangle"
         :x              (or x 120)
         :y              (or y 140)
         :width          240
         :height         100
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
         :customData     #js {:type           "logseq-block"
                              :blockId        (str block-id)
                              :blockTitle     (or block-title "")
                              :pageTitle      (or page-title "")
                              :customLabel    (or custom-label "")
                              :linkedBlockIds #js [(str block-id)]
                              :noteBlockIds   #js []}}))

(defn make-block-text-element
  "Returns an Excalidraw text element bound to the given block rectangle."
  [rect-id rect-x rect-y block-title page-title custom-label]
  (let [line1 "📌 Block"
        line2 (str "⊞ " (if (seq custom-label) custom-label "(未标记)"))
        raw   (or block-title "")
        line3 (if (> (count raw) 10) (str (subs raw 0 10) "…") (if (seq raw) raw "(空内容)"))
        label (str line1 "\n" line2 "\n" line3)]
    #js {:id             (str rect-id "-txt")
         :type           "text"
         :x              rect-x
         :y              (+ rect-y 10)
         :width          240
         :height         80
         :text           label
         :originalText   label
         :fontSize       12
         :fontFamily     1
         :textAlign      "center"
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

;; ── scene queries ─────────────────────────────────────────────────────────────

(defn get-element-by-id
  "Find a scene element by its Excalidraw element ID."
  [^js api elem-id]
  (when (and api elem-id)
    (.find (.getSceneElements api) #(= (gobj/get % "id") elem-id))))

(defn get-selected-element-id
  "Returns the ID of the single selected element, or nil if 0 or >1 elements selected."
  [^js api]
  (when api
    (let [app-state (.getAppState api)
          sel-ids   (js/Object.keys (or (gobj/get app-state "selectedElementIds") #js {}))]
      (when (= 1 (.-length sel-ids))
        (aget sel-ids 0)))))

;; ── element customData accessors ──────────────────────────────────────────────

(defn get-linked-block-ids
  "Returns a ClojureScript vector of linked block UUID strings for element `el`.
   Handles both new format (linkedBlockIds array) and legacy block-card (blockId string)."
  [^js el]
  (let [cd (some-> el (gobj/get "customData"))]
    (if cd
      (let [new-ids (gobj/get cd "linkedBlockIds")
            old-id  (gobj/get cd "blockId")]
        (cond
          (and new-ids (pos? (.-length new-ids))) (vec (array-seq new-ids))
          (seq old-id) [old-id]
          :else []))
      [])))

(defn get-note-block-ids
  "Returns a ClojureScript vector of note block UUID strings for element `el`."
  [^js el]
  (let [cd (some-> el (gobj/get "customData"))]
    (if cd
      (let [ids (gobj/get cd "noteBlockIds")]
        (if (and ids (pos? (.-length ids))) (vec (array-seq ids)) []))
      [])))

(defn get-block-aliases
  "Returns a ClojureScript map of uid → alias string for linked blocks."
  [^js el]
  (some-> el (gobj/get "customData") (gobj/get "linkedBlockAliases")
          (js->clj :keywordize-keys false)))

(defn get-note-aliases
  "Returns a ClojureScript map of uid → alias string for note blocks."
  [^js el]
  (some-> el (gobj/get "customData") (gobj/get "noteBlockAliases")
          (js->clj :keywordize-keys false)))

(defn- update-el-cd!
  "Apply `f` to element `elem-id`'s customData JS object, then updateScene."
  [^js api elem-id f]
  (when (and api elem-id)
    (let [els     (.getSceneElements api)
          new-els (.map els
                        (fn [^js el]
                          (if (= (gobj/get el "id") elem-id)
                            (let [old-cd (or (gobj/get el "customData") #js {})
                                  new-cd (f old-cd)]
                              (js/Object.assign #js {} el
                                                #js {:customData new-cd
                                                     :version    (inc (or (gobj/get el "version") 1))
                                                     :updated    (.now js/Date)}))
                            el)))]
      (.updateScene api #js {:elements new-els}))))

(defn set-block-alias!
  "Persist a custom alias for a linked block in element's customData."
  [^js api elem-id uid alias-str]
  (update-el-cd! api elem-id
    (fn [^js cd]
      (let [new-cd   (js/Object.assign #js {} cd)
            existing (or (gobj/get cd "linkedBlockAliases") #js {})]
        (gobj/set new-cd "linkedBlockAliases"
                  (doto (js/Object.assign #js {} existing)
                    (gobj/set uid alias-str)))
        new-cd))))

(defn set-note-alias!
  "Persist a custom alias for a note block in element's customData."
  [^js api elem-id uid alias-str]
  (update-el-cd! api elem-id
    (fn [^js cd]
      (let [new-cd   (js/Object.assign #js {} cd)
            existing (or (gobj/get cd "noteBlockAliases") #js {})]
        (gobj/set new-cd "noteBlockAliases"
                  (doto (js/Object.assign #js {} existing)
                    (gobj/set uid alias-str)))
        new-cd))))

(defn add-linked-block!
  "Add `block-uuid-str` to element's linkedBlockIds. No-op if already present."
  [^js api elem-id block-uuid-str]
  (js/console.log "[wb-api] add-linked-block! elem:" elem-id "block:" block-uuid-str)
  (update-el-cd! api elem-id
    (fn [^js cd]
      (let [existing    (or (gobj/get cd "linkedBlockIds") #js [])
            already-set (set (array-seq existing))]
        (if (already-set block-uuid-str)
          (do (js/console.log "[wb-api] block already linked, skipping") cd)
          (let [new-cd (js/Object.assign #js {} cd)]
            (gobj/set new-cd "linkedBlockIds" (.concat existing #js [block-uuid-str]))
            new-cd))))))

(defn remove-linked-block!
  "Remove `block-uuid-str` from element's linkedBlockIds."
  [^js api elem-id block-uuid-str]
  (js/console.log "[wb-api] remove-linked-block! elem:" elem-id "block:" block-uuid-str)
  (update-el-cd! api elem-id
    (fn [^js cd]
      (let [new-cd   (js/Object.assign #js {} cd)
            existing (or (gobj/get cd "linkedBlockIds") #js [])
            filtered (.filter existing #(not= % block-uuid-str))
            old-id   (gobj/get cd "blockId")]
        (gobj/set new-cd "linkedBlockIds" filtered)
        ;; Clear legacy single-blockId field if it matches
        (when (= old-id block-uuid-str)
          (gobj/set new-cd "blockId" ""))
        new-cd))))

(defn add-note-block!
  "Add `note-uuid-str` to element's noteBlockIds."
  [^js api elem-id note-uuid-str]
  (js/console.log "[wb-api] add-note-block! elem:" elem-id "note:" note-uuid-str)
  (update-el-cd! api elem-id
    (fn [^js cd]
      (let [existing (or (gobj/get cd "noteBlockIds") #js [])
            new-cd   (js/Object.assign #js {} cd)]
        (gobj/set new-cd "noteBlockIds" (.concat existing #js [note-uuid-str]))
        new-cd))))

(defn remove-note-block!
  "Remove `note-uuid-str` from element's noteBlockIds."
  [^js api elem-id note-uuid-str]
  (js/console.log "[wb-api] remove-note-block! elem:" elem-id "note:" note-uuid-str)
  (update-el-cd! api elem-id
    (fn [^js cd]
      (let [existing (or (gobj/get cd "noteBlockIds") #js [])
            filtered (.filter existing #(not= % note-uuid-str))
            new-cd   (js/Object.assign #js {} cd)]
        (gobj/set new-cd "noteBlockIds" filtered)
        new-cd))))

;; ── backward-compat helpers ───────────────────────────────────────────────────

(defn logseq-block-element?
  "True if `el` is a legacy Logseq block-card element."
  [^js el]
  (= "logseq-block" (some-> el (gobj/get "customData") (gobj/get "type"))))

(defn insert-block-elements!
  "Add a Logseq block card (rectangle + text) to the canvas.
   Kept for backward compatibility – no longer exposed in the UI."
  [^js api block-id block-title page-title custom-label]
  (when api
    (let [existing  (.getSceneElements api)
          app-state (.getAppState api)
          scroll-x  (gobj/get app-state "scrollX")
          scroll-y  (gobj/get app-state "scrollY")
          vp-w      (gobj/get app-state "width")
          vp-h      (gobj/get app-state "height")
          zoom      (or (gobj/getValueByKeys app-state "zoom" "value") 1)
          cx        (- (/ vp-w 2 zoom) scroll-x)
          cy        (- (/ vp-h 2 zoom) scroll-y)
          x         (+ cx (- (rand-int 60) 30))
          y         (+ cy (- (rand-int 40) 20))
          rect      (make-block-element block-id block-title page-title x y custom-label)
          rect-id   (gobj/get rect "id")
          txt       (make-block-text-element rect-id x y block-title page-title custom-label)
          new-elems (.concat existing #js [rect txt])]
      (.updateScene api #js {:elements new-elems}))))
