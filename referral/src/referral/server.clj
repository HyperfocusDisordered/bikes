(ns referral.server
  "Karma Rent — HTTP сервер"
  (:require [org.httpkit.server :as http]
            [referral.db :as db]
            [referral.routes :as routes]
            [referral.config :as config])
  (:gen-class))

(defonce server (atom nil))

(defn start! []
  (db/init-db!)
  (let [port (config/port)]
    (reset! server (http/run-server #'routes/app {:port port :ip "0.0.0.0"}))
    (println (str "Karma Rent started on http://localhost:" port))))

(defn stop! []
  (when-let [s @server]
    (s :timeout 100)
    (reset! server nil)
    (println "Server stopped")))

(defn -main [& _]
  (start!))
