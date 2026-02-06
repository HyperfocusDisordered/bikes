(ns user
  "REPL helpers для разработки"
  (:require [referral.server :as server]
            [referral.db :as db]
            [referral.models :as models]
            [referral.telegram :as telegram]))

(defn go []
  (server/start!))

(defn halt []
  (server/stop!))

(defn reset []
  (halt)
  (go))

(defn seed!
  "Заполнить тестовыми данными"
  []
  (models/create-person! {:name "Admin" :role "admin" :phone "+79001234567"})
  (models/create-person! {:name "Moderator" :role "moderator"})
  (models/create-qrcodes! ["KR_001" "KR_002" "KR_003" "KR_004" "KR_005"])
  (println "Seeded: 2 users, 5 QR codes"))

(defn setup-webhook! [base-url]
  (telegram/set-webhook! base-url))
