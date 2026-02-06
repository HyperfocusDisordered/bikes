(ns referral.routes
  "API маршруты, middleware, авторизация по ролям"
  (:require [reitit.ring :as ring]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.cors :refer [wrap-cors]]
            [cheshire.core :as json]
            [referral.models :as models]
            [referral.qr :as qr]
            [referral.telegram :as telegram]
            [referral.config :as config]
            [clojure.string :as str]))

;; ── Response helpers ────────────────────────────────────

(defn- ok
  ([data] {:status 200 :body {:status "success" :data data}})
  ([data status] {:status status :body {:status "success" :data data}}))

(defn- err
  ([msg] {:status 400 :body {:status "error" :message msg}})
  ([msg status] {:status status :body {:status "error" :message msg}}))

(defn- redirect [url]
  {:status 302 :headers {"Location" url} :body ""})

;; ── Auth ────────────────────────────────────────────────

(defn- extract-token [request]
  (some-> (get-in request [:headers "authorization"])
          (str/split #" " 2)
          second))

(defn- wrap-auth
  "Проверяет Bearer токен.
   Для MVP: admin-key → admin-юзер, иначе ищем person по token=telegram_id"
  [handler required-roles]
  (fn [request]
    (if-let [token (extract-token request)]
      (let [person (if (= token (config/admin-key))
                     {:id 0 :role "admin" :name "Admin"}
                     (models/get-person-by-telegram token))]
        (if (and person (contains? (set required-roles) (:role person)))
          (handler (assoc request :current-user person))
          (err "Forbidden" 403)))
      (err "Unauthorized" 401))))

;; ── Helpers ─────────────────────────────────────────────

(defn- format-period []
  (let [now (java.time.LocalDate/now)]
    (format "%d-%02d" (.getYear now) (.getMonthValue now))))

;; ── Handlers ────────────────────────────────────────────

;; QR scan (public)
(defn- handle-qr-scan [{{:keys [code]} :path-params}]
  (let [result (qr/handle-scan code)]
    (case (:action result)
      :redirect-whatsapp (redirect (:url result))
      :redirect-telegram (redirect (:url result))
      :not-found         (err (:message result) 404))))

;; Telegram webhook (public)
(defn- handle-telegram-webhook [{:keys [body]}]
  (telegram/handle-webhook body)
  {:status 200 :body "ok"})

;; Record rental (moderator+)
(defn- handle-create-rental [{:keys [body]}]
  (let [{:strs [client_id amount partner_id date notes]} body]
    (if (and client_id amount)
      (do (models/create-rental! {:client_id  client_id
                                  :amount     amount
                                  :partner_id partner_id
                                  :date       date
                                  :notes      notes})
          (ok {:message "Rental recorded"}))
      (err "client_id and amount required"))))

;; Partner stats
(defn- handle-partner-stats [{{:keys [id]} :path-params
                              {:strs [period]} :query-params
                              user :current-user}]
  (let [partner-id (parse-long id)
        period     (or period (format-period))
        allowed?   (or (#{"admin" "moderator"} (:role user))
                       (and (= "partner" (:role user))
                            (= partner-id (:id user))))]
    (if allowed?
      (let [revenue  (models/partner-revenue partner-id period)
            rentals  (models/list-rentals-by-partner partner-id period)]
        (ok {:partner_id partner-id
             :period     period
             :revenue    revenue
             :share      (* 0.20 revenue)
             :rentals    rentals}))
      (err "Forbidden" 403))))

;; Admin: report
(defn- handle-report [{{:strs [period]} :query-params}]
  (let [period   (or period (format-period))
        partners (models/list-persons "partner")]
    (ok {:period period
         :partners
         (mapv (fn [p]
                 (let [rev (models/partner-revenue (:id p) period)]
                   {:partner_id    (:id p)
                    :name          (:name p)
                    :total_revenue rev
                    :partner_share (* 0.20 rev)}))
               partners)})))

;; Admin: persons
(defn- handle-list-persons [{{:strs [role]} :query-params}]
  (ok {:persons (if role
                  (models/list-persons role)
                  (models/list-persons))}))

(defn- handle-create-person [{:keys [body]}]
  (let [{:strs [name phone telegram_id role]} body]
    (if name
      (do (models/create-person! {:name name :phone phone
                                  :telegram_id telegram_id :role role})
          (ok {:message "Person created"} 201))
      (err "name required"))))

;; Admin: QR codes
(defn- handle-list-qrcodes [_]
  (ok {:qrcodes (models/list-qrcodes)}))

(defn- handle-generate-qrcodes [{:keys [body]}]
  (let [{:strs [prefix count]} body
        prefix (or prefix "KR")
        n      (or count 10)
        codes  (mapv #(str prefix "_" (format "%03d" %)) (range 1 (inc n)))]
    (models/create-qrcodes! codes)
    (ok {:codes codes :count (clojure.core/count codes)} 201)))

;; Admin: payouts
(defn- handle-calc-payouts [{:keys [body]}]
  (let [{:strs [period]} body
        period   (or period (format-period))
        partners (models/list-persons "partner")
        results  (mapv #(models/calculate-payout! (:id %) period) partners)]
    (ok {:period period :payouts results})))

(defn- handle-mark-paid [{{:keys [id]} :path-params}]
  (models/mark-payout-paid! (parse-long id))
  (ok {:message "Payout marked as paid"}))

;; ── Router ──────────────────────────────────────────────

(def app
  (ring/ring-handler
   (ring/router
    [["/api"
      ["/qr/:code" {:get handle-qr-scan}]
      ["/telegram/webhook" {:post handle-telegram-webhook}]

      ["/rentals" {:post (wrap-auth handle-create-rental #{"admin" "moderator"})}]

      ["/partners/:id/stats" {:get (wrap-auth handle-partner-stats
                                              #{"admin" "moderator" "partner"})}]

      ["/admin"
       ["/report"       {:get  (wrap-auth handle-report #{"admin" "moderator"})}]
       ["/persons"      {:get  (wrap-auth handle-list-persons #{"admin"})
                         :post (wrap-auth handle-create-person #{"admin"})}]
       ["/qrcodes"      {:get  (wrap-auth handle-list-qrcodes #{"admin"})
                         :post (wrap-auth handle-generate-qrcodes #{"admin"})}]
       ["/payouts"      {:post (wrap-auth handle-calc-payouts #{"admin"})}]
       ["/payouts/:id/paid" {:patch (wrap-auth handle-mark-paid #{"admin"})}]]]]

    {:data {:middleware [[wrap-cors
                          :access-control-allow-origin [#".*"]
                          :access-control-allow-methods [:get :post :put :patch :delete :options]
                          :access-control-allow-headers ["Content-Type" "Authorization"]]
                         [wrap-json-body {:keywords? false}]
                         wrap-json-response]}})
   (ring/create-default-handler
    {:not-found (constantly (err "Not found" 404))})))
