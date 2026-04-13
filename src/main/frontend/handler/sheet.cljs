(ns frontend.handler.sheet
  "Spreadsheet data persistence using the visual-doc sidecar pattern.

   Unlike mind-map, sheet does NOT need a gallery page or class tags.
   Each sheet = a page entity, identified by :block/sheet-data attribute.
   The full workbook payload lives in the worker SQLite sidecar."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [frontend.db :as db]
            [frontend.handler.common.page :as common-page-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.visual-doc :as visual-doc]
            [frontend.state :as state]
            [promesa.core :as p]))

(def sheet-attr :block/sheet-data)
(def sheet-cache-prefix "sheet-data")

(defn save-sheet-to-db!
  "Writes the sheet payload to sidecar storage."
  [page-uuid json-str]
  (if-not (and (seq page-uuid) (seq json-str))
    (p/resolved false)
    (-> (visual-doc/<flush-doc! (state/get-current-repo) page-uuid sheet-attr json-str)
        (p/then boolean)
        (p/catch (fn [error]
                   (js/console.error "[sheet] save-sheet-to-db! failed:" error)
                   false)))))

(defn <load-sheet-doc
  "Loads the sheet payload from sidecar, resolving whether DB or cache is newer."
  [page-uuid]
  (visual-doc/<load-doc (state/get-current-repo) page-uuid sheet-attr sheet-cache-prefix))

(defn <create-sheet!
  "Creates a new sheet page with a default empty workbook. Returns the page entity."
  [name]
  (let [title (string/trim (or name "新建表格"))]
    (p/let [page (common-page-handler/<create! title {:redirect? false})]
      (when page
        (let [repo      (state/get-current-repo)
              page-uuid (str (:block/uuid page))
              initial   (js/JSON.stringify
                         (clj->js {:id    "workbook_1"
                                   :name  title
                                   :sheetOrder ["sheet_1"]
                                   :sheets {:sheet_1
                                            {:id         "sheet_1"
                                             :name       "Sheet1"
                                             :rowCount   50
                                             :columnCount 20
                                             :cellData   {}}}}))]
          (visual-doc/save-doc-cache! sheet-cache-prefix page-uuid initial)
          (p/let [_ (visual-doc/<flush-doc! repo page-uuid sheet-attr initial)]
            page))))))
