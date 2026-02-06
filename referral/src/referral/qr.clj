(ns referral.qr
  "Логика обработки QR-сканирования"
  (:require [referral.models :as models]
            [referral.config :as config])
  (:import [java.net URLEncoder]))

(defn- telegram-bot-url [code]
  (str "https://t.me/" (config/telegram-bot-username) "?start=" code))

(defn- whatsapp-url [partner-id]
  (let [text (URLEncoder/encode
              (str "Хочу арендовать байк (ref:" partner-id ")")
              "UTF-8")]
    (str "https://wa.me/" (config/whatsapp-number) "?text=" text)))

(defn handle-scan
  "Обработка скана QR-кода.
   Первый скан (без партнёра) → Telegram для привязки.
   Повторный скан → WhatsApp оператора с ref."
  [code]
  (if-let [qr (models/get-qrcode-by-code code)]
    (if (:partner_id qr)
      {:action :redirect-whatsapp
       :url    (whatsapp-url (:partner_id qr))}
      {:action :redirect-telegram
       :url    (telegram-bot-url code)})
    {:action  :not-found
     :message "QR code not found"}))
