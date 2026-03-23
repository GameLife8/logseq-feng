(ns frontend.components.mind-map
  "思维导图页面，基于 simple-mind-map。

   架构：
     frontend.extensions.mind-map.core – simple-mind-map 画布封装（懒加载模块）
     frontend.handler.mind-map         – DB 读写（主模块）
     frontend.components.mind-map      – 页面 UI（主模块）"
  (:require [frontend.handler.mind-map :as mind-map-handler]
            [frontend.handler.route :as route-handler]
            [frontend.state :as state]
            [rum.core :as rum]
            [shadow.lazy :as lazy]))

;; ── 懒加载 mind-map 模块 ─────────────────────────────────────────────────────

#_:clj-kondo/ignore
(def ^:private lazy-mind-map
  (lazy/loadable frontend.extensions.mind-map.core/editor))

(defonce ^:private *mind-map-loaded? (atom false))

(defn- ensure-mind-map-loaded! [on-done]
  (if @*mind-map-loaded?
    (on-done)
    (lazy/load lazy-mind-map
               (fn []
                 (reset! *mind-map-loaded? true)
                 (on-done)))))

;; ── 思维导图主页 ──────────────────────────────────────────────────────────────

(rum/defcs mind-map-page
  "全屏思维导图页面。"
  < rum/reactive
  (rum/local false ::loaded?)
  {:did-mount
   (fn [state]
     (ensure-mind-map-loaded!
      (fn []
        (reset! (::loaded? state) true)))
     state)}
  [state]
  (let [loaded? (rum/react (::loaded? state))
        repo    (state/get-current-repo)
        map-id  (str "default-" repo)]
    [:div.mind-map-page
     {:style {:width         "100%"
              :height        "100%"
              :display       "flex"
              :flexDirection "column"}}

     (if-not loaded?
       [:div {:style {:display        "flex"
                      :alignItems     "center"
                      :justifyContent "center"
                      :flex           "1"
                      :color          "var(--ls-secondary-text-color,#666)"}}
        "加载思维导图中…"]

       (@lazy-mind-map
        {:map-id       map-id
         :map-title    "思维导图"
         :on-back      (fn [] (route-handler/redirect-to-home!))
         :on-load-data mind-map-handler/load-mind-map-from-db
         :on-save-data mind-map-handler/save-mind-map-to-db!}))]))
