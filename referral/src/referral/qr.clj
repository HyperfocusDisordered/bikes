(ns referral.qr
  "Логика обработки QR-сканирования + генерация QR-изображений"
  (:require [referral.models :as models]
            [referral.config :as config])
  (:import [java.net URLEncoder]))

(defn qr-image-url
  "URL для QR-изображения через внешний API (белое на чёрном)"
  [data]
  (str "https://api.qrserver.com/v1/create-qr-code/"
       "?size=400x400"
       "&data=" (URLEncoder/encode data "UTF-8")
       "&color=ffffff&bgcolor=000000"
       "&format=png"))

(defn- telegram-bot-url [code]
  (str "https://t.me/" (config/telegram-bot-username) "?start=" code))

(defn whatsapp-qr-data-url
  "wa.me URL для QR-кода WhatsApp с партнёрским кодом в сообщении"
  [partner-code]
  (let [text (URLEncoder/encode
              (str partner-code "\n"
                   "Здравствуйте! Для быстрого подбора напишите цифру:\n"
                   "1 — байк\n"
                   "2 — велосипед\n"
                   "3 — авто\n"
                   "Ответ: ___")
              "UTF-8")]
    (str "https://wa.me/" (config/whatsapp-number) "?text=" text)))

(defn- redirect-for-client
  "Куда перенаправить клиента в зависимости от channel QR-кода.
   WA: P-номер = код QR (совпадает с номером на брелке).
   TG: ref_ = partner_id (для автопривязки в БД)."
  [qr]
  (if (= "whatsapp" (:channel qr))
    {:action :redirect-whatsapp
     :url    (whatsapp-qr-data-url (str "P" (:code qr)))}
    {:action :redirect-telegram
     :url    (str "https://t.me/" (config/telegram-bot-username) "?start=ref_" (:partner_id qr))}))

(defn handle-scan
  "Обработка скана QR-кода (по code).
   Без партнёра → Telegram для привязки.
   С партнёром → channel-зависимый редирект (TG или WA)."
  [code]
  (if-let [qr (models/get-qrcode-by-code code)]
    (if (:partner_id qr)
      (redirect-for-client qr)
      {:action :redirect-telegram
       :url    (telegram-bot-url code)})
    {:action  :not-found
     :message "QR code not found"}))

(defn handle-invite-by-code
  "Обработка /invite/:code — QR-код по числовому коду (legacy, без фильтра канала).
   Без партнёра → Telegram привязка.
   С партнёром → channel-зависимый редирект."
  [code]
  (if-let [qr (models/get-qrcode-by-code code)]
    (if (:partner_id qr)
      (redirect-for-client qr)
      {:action :redirect-telegram
       :url    (telegram-bot-url (:code qr))})
    {:action  :not-found
     :message "Invite not found"}))

(defn handle-invite-by-channel
  "Обработка /invite/tg/:code или /invite/wa/:code — QR с явным каналом.
   channel: 'telegram' или 'whatsapp'"
  [code channel]
  (if-let [qr (models/get-qrcode-by-code code channel)]
    (if (:partner_id qr)
      (redirect-for-client qr)
      {:action :redirect-telegram
       :url    (telegram-bot-url (:code qr))})
    {:action  :not-found
     :message "Invite not found"}))
