(ns referral.routes
  "API маршруты, middleware, авторизация по ролям"
  (:require [reitit.ring :as ring]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.cors :refer [wrap-cors]]
            [cheshire.core :as json]
            [referral.models :as models]
            [referral.qr :as qr]
            [referral.telegram :as telegram]
            [referral.bridge :as bridge]
            [referral.config :as config]
            [referral.db :as db]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; ── Response helpers ────────────────────────────────────

(defn- ok
  ([data] {:status 200 :body {:status "success" :data data}})
  ([data status] {:status status :body {:status "success" :data data}}))

(defn- err
  ([msg] {:status 400 :body {:status "error" :message msg}})
  ([msg status] {:status status :body {:status "error" :message msg}}))

(defn- redirect [url]
  {:status 302 :headers {"Location" url} :body ""})

;; ── Rate limiting ─────────────────────────────────────────

(defonce ^:private rate-limits (atom {}))

(defn- rate-limited?
  "Check if IP exceeds rate limit (sliding window 60s). Returns true if limited."
  [ip max-per-minute]
  (let [now    (System/currentTimeMillis)
        cutoff (- now 60000)
        timestamps (get @rate-limits ip [])
        recent (filterv #(> % cutoff) timestamps)]
    (swap! rate-limits assoc ip (conj recent now))
    (> (count recent) max-per-minute)))

(defn cleanup-rate-limits!
  "Remove stale entries (called from scheduler)"
  []
  (let [cutoff (- (System/currentTimeMillis) 300000)] ;; 5 min
    (swap! rate-limits
           (fn [m]
             (into {} (filter (fn [[_ ts]] (some #(> % cutoff) ts)) m))))))

(defn- wrap-rate-limit [handler max-per-minute]
  (fn [request]
    (let [ip (or (get-in request [:headers "fly-client-ip"])
                 (:remote-addr request)
                 "unknown")]
      (if (rate-limited? ip max-per-minute)
        {:status 429 :body {:status "error" :message "Too many requests"}}
        (handler request)))))

;; ── Error handling middleware ─────────────────────────────

(defn- wrap-exception [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (println "ERROR:" (.getMessage e) "path:" (:uri request))
        {:status 500 :body {:status "error" :message "Internal server error"}}))))

;; ── Request logging middleware ────────────────────────────

(defn- wrap-request-log [handler]
  (fn [request]
    (let [start    (System/currentTimeMillis)
          response (handler request)
          ms       (- (System/currentTimeMillis) start)
          method   (str/upper-case (name (:request-method request)))
          path     (:uri request)
          status   (:status response)]
      ;; Skip noisy polling endpoints
      (when-not (str/includes? (or path "") "owner-msgs")
        (println (format "%s %s %d %dms" method path status ms)))
      response)))

;; ── Health check ──────────────────────────────────────────

(defn- handle-health [_]
  (try
    (db/q1 "SELECT 1 as ok")
    (let [uptime (if-let [t @config/started-at]
                   (quot (- (System/currentTimeMillis) t) 1000)
                   0)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string
              {:status "ok"
               :db "connected"
               :uptime_seconds uptime})})
    (catch Exception e
      {:status 503
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string
              {:status "error"
               :db "disconnected"
               :error (.getMessage e)})})))

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

;; Webhook secret verification (Telegram X-Telegram-Bot-Api-Secret-Token header)
(defn- verify-webhook-secret [{:keys [headers]}]
  (let [secret (config/webhook-secret)]
    (or (nil? secret)
        (= secret (get headers "x-telegram-bot-api-secret-token")))))

;; Telegram webhook (public, with optional secret verification)
(defn- handle-telegram-webhook [request]
  (if (verify-webhook-secret request)
    (do (telegram/handle-webhook (:body request))
        {:status 200 :body "ok"})
    {:status 403 :body "Forbidden"}))

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
             :share      (* (config/partner-share-pct) revenue)
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
                    :partner_share (* (config/partner-share-pct) rev)}))
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
  (let [{:strs [count]} body
        n             (or count 10)
        existing      (models/list-qrcodes)
        existing-set  (set (map :code existing))
        new-codes     (loop [codes [] ec existing-set]
                        (if (= (clojure.core/count codes) n)
                          codes
                          (let [c (str (+ 100000 (rand-int 900000)))]
                            (if (ec c)
                              (recur codes ec)
                              (recur (conj codes c) (conj ec c))))))]
    (models/create-qrcodes! new-codes)
    (ok {:codes new-codes
         :urls (mapv #(str "https://karmarent.app/invite/" %) new-codes)
         :count (clojure.core/count new-codes)} 201)))

;; Admin: bikes
(defn- handle-list-bikes [{{:strs [status]} :query-params}]
  (ok {:bikes (if status
                (models/list-bikes status)
                (models/list-bikes))}))

(defn- handle-create-bike [{:keys [body]}]
  (let [{:strs [name plate_number daily_rate last_oil_change notes photo_url]} body]
    (if name
      (do (models/create-bike! {:name name :plate_number plate_number
                                :daily_rate daily_rate
                                :last_oil_change last_oil_change
                                :notes notes :photo_url photo_url})
          (ok {:message "Bike created"} 201))
      (err "name required"))))

(defn- handle-update-bike [{{:keys [id]} :path-params :keys [body]}]
  (let [{:strs [name plate_number status daily_rate last_oil_change notes photo_url]} body]
    (models/update-bike! (parse-long id)
                         {:name name :plate_number plate_number :status status
                          :daily_rate daily_rate :last_oil_change last_oil_change
                          :notes notes :photo_url photo_url})
    (ok {:message "Bike updated"})))

(defn- handle-delete-bike [{{:keys [id]} :path-params user :current-user}]
  (let [bike-id (parse-long id)]
    (if-let [deleted (models/delete-bike! bike-id (:id user))]
      (ok {:message (str "Bike '" (:name deleted) "' deleted")})
      (err "Bike not found or currently rented" 400))))

(defn- handle-audit-log [{{:strs [limit]} :query-params}]
  (ok {:log (models/list-audit-log (when limit (parse-long limit)))}))

(defn- handle-sync-bikes [{:keys [body]}]
  (let [{:strs [bikes]} body]
    (doseq [b bikes]
      (let [{:strs [name plate_number daily_rate notes photo_url last_oil_change]} b]
        (when name
          (models/create-bike! {:name name :plate_number plate_number
                                :daily_rate daily_rate :notes notes
                                :photo_url photo_url
                                :last_oil_change last_oil_change}))))
    (ok {:message (str (count bikes) " bikes synced")})))

;; Admin: payouts
(defn- handle-calc-payouts [{:keys [body]}]
  (let [{:strs [period]} body
        period   (or period (format-period))
        partners (models/list-persons "partner")
        results  (mapv (fn [p]
                         (let [r (models/calculate-payout! (:id p) period)]
                           ;; Notify partner about their payout
                           (when (pos? (:total_revenue r))
                             (telegram/notify-partner-payout!
                               (:id p) period (:total_revenue r) (:partner_share r)))
                           r))
                       partners)]
    (ok {:period period :payouts results})))

(defn- handle-mark-paid [{{:keys [id]} :path-params}]
  (models/mark-payout-paid! (parse-long id))
  (ok {:message "Payout marked as paid"}))

;; Owner communication (via HyperFocus Bridge bot)
(defn- handle-owner-send [{:keys [body]}]
  (let [{:strs [text buttons]} body]
    (if text
      (if buttons
        (let [parsed-buttons
              (mapv (fn [row]
                      (if (map? row)
                        {:label (get row "label") :data (get row "data")}
                        (mapv (fn [b] {:label (get b "label") :data (get b "data")}) row)))
                    buttons)]
          (let [resp (bridge/send-message-with-buttons text parsed-buttons)
                msg-id (bridge/extract-message-id resp)]
            (ok {:message "Sent to owner with buttons" :message_id msg-id})))
        (let [resp (bridge/send-message text)
              msg-id (bridge/extract-message-id resp)]
          (ok {:message "Sent to owner" :message_id msg-id})))
      (err "text required"))))

(defn- handle-owner-delete-msg [{:keys [body]}]
  (let [{:strs [message_id]} body]
    (if message_id
      (do (bridge/delete-message message_id)
          (ok {:deleted true}))
      (err "message_id required"))))

(defn- handle-owner-edit-msg [{:keys [body]}]
  (let [{:strs [message_id text]} body]
    (if (and message_id text)
      (do (bridge/edit-message message_id text)
          (ok {:edited true}))
      (err "message_id and text required"))))

;; HF webhook (public, with optional secret verification)
(defn- handle-hf-webhook [request]
  (if (verify-webhook-secret request)
    (do (bridge/handle-webhook (:body request))
        {:status 200 :body "ok"})
    {:status 403 :body "Forbidden"}))

(defn- handle-owner-messages [_]
  (let [msgs (models/list-owner-messages)]
    (ok {:messages msgs})))

(defn- handle-owner-ack [_]
  (models/mark-owner-messages-read!)
  (ok {:acked true}))

(defn- handle-owner-history [_]
  (ok {:messages (models/list-owner-messages-all)}))

;; ── n8n integration endpoints ────────────────────────────
;; Simple API key auth via ?key= query param or X-API-Key header
;; Returns data in Russian field names matching Google Sheets format
;; so the AI Agent system prompt works without changes

(def ^:private status-to-russian
  {"available"    "Свободен"
   "rented"       "В аренде"
   "booked"       "Забронирован"
   "maintenance"  "На обслуживании"
   "hold"         "Резерв"})

(def ^:private russian-to-status
  {"Свободен"         "available"
   "В аренде"         "rented"
   "Забронирован"     "booked"
   "На обслуживании"  "maintenance"
   "Резерв"           "hold"})

(defn- bike->sheets-format
  "Convert bike record to Google Sheets column format for AI Agent compatibility"
  [bike]
  {"row_number"    (:id bike)
   "Состояние"     (get status-to-russian (:status bike) (:status bike))
   "Байк"          (:name bike)
   "Номер байка"   (:plate_number bike)
   "Класс"         (or (:category bike) "scooter")
   "Фото"          (:photo_url bike)
   "Аксессуары"    ""
   "Коментарий"    (:notes bike)
   "Цена/день"     (:daily_rate bike)
   "Цена/месяц"    (:monthly_rate bike)})

(defn- wrap-n8n-auth
  "Check n8n API key from ?key= param or X-API-Key header"
  [handler]
  (fn [request]
    (let [expected (config/n8n-api-key)
          provided (or (get-in request [:query-params "key"])
                       (get-in request [:headers "x-api-key"]))]
      (if (or (nil? expected) ;; no key configured = open (dev mode)
              (= expected provided))
        (handler request)
        (err "Invalid API key" 401)))))

(defn- handle-n8n-bikes
  "GET /api/n8n/bikes — all bikes in Sheets-compatible format.
   ?status=Свободен to filter by Russian status name."
  [{{:strs [status]} :query-params}]
  (let [internal-status (when status (get russian-to-status status status))
        bikes (if internal-status
                (models/list-bikes internal-status)
                (models/list-bikes))]
    {:status 200
     :body (mapv bike->sheets-format bikes)}))

(defn- handle-n8n-tariffs
  "GET /api/n8n/tariffs — pricing info per bike category"
  [_]
  (let [bikes (models/list-bikes)
        by-category (group-by :category bikes)
        tariffs (mapv (fn [[cat bs]]
                        (let [rates (keep :daily_rate bs)
                              monthly (keep :monthly_rate bs)]
                          {"Категория"   cat
                           "Цена/день"   (when (seq rates) (apply min rates))
                           "Цена/месяц"  (when (seq monthly) (apply min monthly))
                           "Кол-во"      (count bs)
                           "Свободных"   (count (filter #(= "available" (:status %)) bs))}))
                      by-category)]
    {:status 200 :body tariffs}))

(defn- handle-n8n-update-bike
  "PATCH /api/n8n/bikes/:id — update bike status (for AI Agent booking).
   Accepts Russian status names: {\"Состояние\": \"Забронирован\"}"
  [{{:keys [id]} :path-params :keys [body]}]
  (let [bike-id (parse-long id)
        russian-status (get body "Состояние")
        internal-status (when russian-status
                          (get russian-to-status russian-status russian-status))]
    (if internal-status
      (do (models/update-bike! bike-id {:status internal-status})
          (ok {:message (str "Байк #" bike-id " → " russian-status)
               :row_number bike-id
               :Состояние russian-status}))
      (err "Состояние required"))))

;; Admin HTML page
(defn- handle-admin-page [_]
  (if-let [res (io/resource "public/admin.html")]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (slurp res)}
    (err "Admin page not found" 404)))

;; ── Router ──────────────────────────────────────────────

(def app
  (ring/ring-handler
   (ring/router
    [["/admin" {:get handle-admin-page}]
     ["/invite/tg/:code" {:get (fn [{{:keys [code]} :path-params}]
                                (let [result (qr/handle-invite-by-channel code "telegram")]
                                  (case (:action result)
                                    :redirect-whatsapp (redirect (:url result))
                                    :redirect-telegram (redirect (:url result))
                                    :not-found         (err (:message result) 404))))}]
     ["/invite/wa/:code" {:get (fn [{{:keys [code]} :path-params}]
                                (let [result (qr/handle-invite-by-channel code "whatsapp")]
                                  (case (:action result)
                                    :redirect-whatsapp (redirect (:url result))
                                    :redirect-telegram (redirect (:url result))
                                    :not-found         (err (:message result) 404))))}]
     ;; Legacy: /invite/:code — ищет по коду без фильтра канала (backward compat)
     ["/invite/:code" {:get (fn [{{:keys [code]} :path-params}]
                              (let [result (qr/handle-invite-by-code code)]
                                (case (:action result)
                                  :redirect-whatsapp (redirect (:url result))
                                  :redirect-telegram (redirect (:url result))
                                  :not-found         (err (:message result) 404))))}]
     ["/api"
      ["/health" {:get handle-health}]
      ["/qr/:code" {:get handle-qr-scan}]
      ["/telegram/webhook" {:post (wrap-rate-limit handle-telegram-webhook 60)}]
      ["/hf/webhook" {:post (wrap-rate-limit handle-hf-webhook 60)}]

      ["/rentals" {:post (wrap-auth handle-create-rental #{"admin" "moderator"})}]

      ["/partners/:id/stats" {:get (wrap-auth handle-partner-stats
                                              #{"admin" "moderator" "partner"})}]

      ["/n8n"
       ["/bikes"    {:get   (wrap-n8n-auth handle-n8n-bikes)
                     :patch (wrap-n8n-auth handle-n8n-update-bike)}]
       ["/bikes/:id" {:patch (wrap-n8n-auth handle-n8n-update-bike)}]
       ["/tariffs"  {:get   (wrap-n8n-auth handle-n8n-tariffs)}]]

      ["/admin"
       ["/report"       {:get  (wrap-auth handle-report #{"admin" "moderator"})}]
       ["/persons"      {:get  (wrap-auth handle-list-persons #{"admin"})
                         :post (wrap-auth handle-create-person #{"admin"})}]
       ["/qrcodes"      {:get  (wrap-auth handle-list-qrcodes #{"admin"})
                         :post (wrap-auth handle-generate-qrcodes #{"admin"})}]
       ["/bikes"        {:get  (wrap-auth handle-list-bikes #{"admin" "moderator"})
                         :post (wrap-auth handle-create-bike #{"admin"})}]
       ["/bikes-sync"   {:post (wrap-auth handle-sync-bikes #{"admin"})}]
       ["/bikes/:id"    {:patch  (wrap-auth handle-update-bike #{"admin" "moderator"})
                         :delete (wrap-auth handle-delete-bike #{"admin"})}]
       ["/audit-log"    {:get (wrap-auth handle-audit-log #{"admin"})}]
       ["/payouts"      {:post (wrap-auth handle-calc-payouts #{"admin"})}]
       ["/payouts/:id/paid" {:patch (wrap-auth handle-mark-paid #{"admin"})}]
       ["/owner-send"   {:post (wrap-auth handle-owner-send #{"admin"})}]
       ["/owner-delete-msg" {:post (wrap-auth handle-owner-delete-msg #{"admin"})}]
       ["/owner-edit-msg"   {:post (wrap-auth handle-owner-edit-msg #{"admin"})}]
       ["/owner-msgs"   {:get  (wrap-auth handle-owner-messages #{"admin"})}]
       ["/owner-ack"    {:post (wrap-auth handle-owner-ack #{"admin"})}]
       ["/owner-history" {:get (wrap-auth handle-owner-history #{"admin"})}]]]]

    {:data {:middleware [wrap-exception
                         wrap-request-log
                         [wrap-cors
                          :access-control-allow-origin [#"https://karmarent\.app" #"https://.*\.karmarent\.app" #"https://.*\.up\.railway\.app" #"http://localhost(:\d+)?"]
                          :access-control-allow-methods [:get :post :put :patch :delete :options]
                          :access-control-allow-headers ["Content-Type" "Authorization"]]
                         [wrap-json-body {:keywords? false}]
                         wrap-json-response]}})
   (ring/create-default-handler
    {:not-found (constantly (err "Not found" 404))})))
