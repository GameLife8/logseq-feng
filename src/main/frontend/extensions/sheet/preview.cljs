(ns frontend.extensions.sheet.preview
  "Shared HTML preview builders for sheet embeds and print export."
  (:require [clojure.string :as string]))

(def ^:private max-preview-rows 200)
(def ^:private max-preview-cols 50)

(defn- col-letter
  "Converts a 0-based column index to Excel-style letters."
  [idx]
  (loop [n idx result ""]
    (let [ch (char (+ 65 (mod n 26)))
          next-result (str ch result)
          q (dec (quot n 26))]
      (if (neg? (- n 26))
        next-result
        (recur q next-result)))))

(defn- escape-html
  [value]
  (-> (str (or value ""))
      (string/replace "&" "&amp;")
      (string/replace "<" "&lt;")
      (string/replace ">" "&gt;")
      (string/replace "\"" "&quot;")
      (string/replace "'" "&#39;")))

(defn- parse-int
  [value]
  (let [n (js/parseInt value 10)]
    (when-not (js/isNaN n)
      n)))

(defn- cell->display
  [cell]
  (let [value (some-> cell (get "v"))]
    (cond
      (nil? value) ""
      (string? value) value
      :else (str value))))

(defn- render-truncation-note!
  [sb row-truncated? col-truncated?]
  (when (or row-truncated? col-truncated?)
    (.push sb "<div class='sheet-preview-truncated'>")
    (.push sb "Preview limited to ")
    (.push sb (str max-preview-rows " rows"))
    (when (and row-truncated? col-truncated?)
      (.push sb " and "))
    (when col-truncated?
      (.push sb (str max-preview-cols " columns")))
    (.push sb ".</div>")))

(defn build-table-html
  "Builds a bounded, escaped HTML table string from workbook JSON for preview
   and print export. Returns nil when there is no visible cell data."
  [json-str]
  (try
    (when (seq json-str)
      (let [wb         (js->clj (js/JSON.parse json-str) :keywordize-keys false)
            sheet-id   (first (get wb "sheetOrder"))
            sheet      (get-in wb ["sheets" sheet-id])
            cell-data  (get sheet "cellData" {})]
        (when (seq cell-data)
          (let [row-keys        (->> (keys cell-data) (keep parse-int) sort vec)
                col-keys        (->> cell-data
                                     (mapcat (fn [[_ cols]] (keep parse-int (keys cols))))
                                     sort
                                     vec)
                row-start       (or (first row-keys) 0)
                col-start       (or (first col-keys) 0)
                row-span        (max 1 (inc (- (or (last row-keys) row-start) row-start)))
                col-span        (max 1 (inc (- (or (last col-keys) col-start) col-start)))
                row-count       (min max-preview-rows row-span)
                col-count       (min max-preview-cols col-span)
                row-end         (+ row-start row-count)
                col-end         (+ col-start col-count)
                row-truncated?  (> row-span max-preview-rows)
                col-truncated?  (> col-span max-preview-cols)
                sb              (js/Array.)]
            (render-truncation-note! sb row-truncated? col-truncated?)
            (.push sb "<table class='sheet-print-table'>")
            (.push sb "<thead><tr><th class='spt-corner'></th>")
            (doseq [c (range col-start col-end)]
              (.push sb (str "<th class='spt-col-hdr'>" (escape-html (col-letter c)) "</th>")))
            (.push sb "</tr></thead><tbody>")
            (doseq [r (range row-start row-end)]
              (.push sb (str "<tr><td class='spt-row-num'>" (inc r) "</td>"))
              (doseq [c (range col-start col-end)]
                (let [cell (get-in cell-data [(str r) (str c)])]
                  (.push sb (str "<td>" (escape-html (cell->display cell)) "</td>"))))
              (.push sb "</tr>"))
            (.push sb "</tbody></table>")
            (.join sb "")))))
    (catch :default e
      (js/console.warn "[sheet] build-table-html failed:" e)
      nil)))
