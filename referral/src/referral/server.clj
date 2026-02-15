(ns referral.server
  "Karma Rent — HTTP сервер"
  (:require [org.httpkit.server :as http]
            [referral.db :as db]
            [referral.routes :as routes]
            [referral.bridge :as bridge]
            [referral.telegram :as telegram]
            [referral.models :as models]
            [referral.config :as config])
  (:gen-class))

;; Forward declare for routes/health to resolve

(defonce server (atom nil))
(defonce scheduler (atom nil))

(defn- auto-calc-payouts!
  "Автоматический расчёт выплат 1-го числа за предыдущий месяц"
  []
  (let [today (java.time.LocalDate/now)]
    (when (= 1 (.getDayOfMonth today))
      (let [prev    (.minusMonths today 1)
            period  (format "%d-%02d" (.getYear prev) (.getMonthValue prev))
            partners (models/list-persons "partner")]
        (println "Auto-payout calculation for" period)
        (doseq [p partners]
          (try
            (let [r (models/calculate-payout! (:id p) period)]
              (when (pos? (:total_revenue r))
                (telegram/notify-partner-payout!
                  (:id p) period (:total_revenue r) (:partner_share r))))
            (catch Exception e
              (println "Auto-payout error for partner" (:id p) (.getMessage e)))))))))

(defn start-scheduler!
  "Запускает периодические задачи: проверка аренд (6ч) + автовыплаты (6ч, но 1-го числа)"
  []
  (let [interval-ms (* 6 60 60 1000) ;; 6 hours
        running (atom true)]
    (reset! scheduler running)
    (future
      (Thread/sleep 60000) ;; initial delay 1 min
      (while @running
        (try
          (telegram/check-rental-expiry!)
          (auto-calc-payouts!)
          (routes/cleanup-rate-limits!)
          (telegram/cleanup-stale-state!)
          (catch Exception e
            (println "Scheduler error:" (.getMessage e))))
        (Thread/sleep interval-ms)))
    (println "Scheduler started (rental expiry + auto-payouts, every 6h)")))

(defn start! []
  (reset! config/started-at (System/currentTimeMillis))
  ;; Security warnings
  (when (= "kr-unsafe-default-change-me" (config/admin-key))
    (println "")
    (println "⚠️⚠️⚠️  WARNING: Using DEFAULT admin key! Set KARMA_ADMIN_KEY env variable!  ⚠️⚠️⚠️")
    (println ""))
  (when-not (config/webhook-secret)
    (println "⚠️  WARNING: WEBHOOK_SECRET not set — webhooks accepted without verification"))
  (db/init-db!)
  ;; (db/init-dev-db!) ;; Enable when gate launches
  ;; Restore owner chat-id from DB (survives deploys)
  (when-let [cid (models/get-owner-chat-id)]
    (reset! config/owner-chat-id cid)
    (println "Owner chat-id restored:" cid))
  (let [port (config/port)]
    (reset! server (http/run-server #'routes/app {:port port :ip "0.0.0.0"}))
    (println (str "Karma Rent started on http://localhost:" port))
    ;; Set Telegram bot webhook (with secret if configured)
    (telegram/set-webhook! "https://karmarent.app")
    ;; Set HF bot webhook if token configured
    (when (config/hf-bot-token)
      (bridge/set-webhook! "https://karmarent.app"))
    ;; Set bot commands menu
    (telegram/set-bot-commands!)
    ;; Start rental expiry scheduler
    (start-scheduler!)))

(defn stop! []
  (when-let [s @scheduler]
    (reset! s false)
    (println "Scheduler stopped"))
  (when-let [s @server]
    (s :timeout 100)
    (reset! server nil)
    (println "Server stopped")))

(defn -main [& _]
  (.addShutdownHook (Runtime/getRuntime)
    (Thread. ^Runnable (fn []
                         (println "Shutdown hook: stopping server...")
                         (stop!))))
  (start!))
