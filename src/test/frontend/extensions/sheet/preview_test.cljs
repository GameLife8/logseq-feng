(ns frontend.extensions.sheet.preview-test
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as string]
            [frontend.extensions.sheet.preview :as sheet-preview]))

(defn- workbook-json
  [cell-data]
  (js/JSON.stringify
   (clj->js {:id "workbook_1"
             :name "Sheet"
             :sheetOrder ["sheet_1"]
             :sheets {:sheet_1 {:id "sheet_1"
                                :name "Sheet1"
                                :rowCount 1200
                                :columnCount 120
                                :cellData cell-data}}})))

(deftest build-table-html-escapes-cell-content
  (testing "cell values are escaped before reaching innerHTML sinks"
    (let [html (sheet-preview/build-table-html
                (workbook-json {"0" {"0" {"v" "<img src=x onerror=alert(1)>"}}}))]
      (is (string/includes? html "&lt;img src=x onerror=alert(1)&gt;"))
      (is (not (string/includes? html "<img src=x onerror=alert(1)>"))))))

(deftest build-table-html-bounds-sparse-workbooks
  (testing "sparse workbooks render a bounded preview with a truncation hint"
    (let [html (sheet-preview/build-table-html
                (workbook-json {"0" {"0" {"v" "anchor"}}
                                "999" {"99" {"v" "far"}}}))]
      (is (string/includes? html "Preview limited to 200 rows and 50 columns."))
      (is (= 201 (count (re-seq #"<tr>" html))))
      (is (= 50 (count (re-seq #"spt-col-hdr" html)))))))
