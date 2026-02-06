(ns referral.telegram
  "Telegram бот: привязка партнёра к QR-коду"
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [referral.models :as models]
            [referral.config :as config]))

(defn- api-url [method]
  (str "https://api.telegram.org/bot" (config/telegram-bot-token) "/" method))

(defn- send-message [chat-id text]
  (try
    (http/post (api-url "sendMessage")
               {:content-type :json
                :body (json/generate-string {:chat_id chat-id :text text})})
    (catch Exception e
      (println "Telegram send error:" (.getMessage e)))))

(defn- extract-start-payload [text]
  (when (and text (.startsWith text "/start "))
    (subs text 7)))

(defn- bind-partner!
  "Привязать Telegram-аккаунт к QR-коду"
  [chat-id from qr-code]
  (let [telegram-id (str (:id from))
        name        (or (:first_name from) "Partner")
        qr          (models/get-qrcode-by-code qr-code)]
    (cond
      (nil? qr)
      (send-message chat-id (str "QR-код " qr-code " не найден в системе."))

      (:partner_id qr)
      (send-message chat-id "Этот QR-код уже активирован другим партнёром.")

      :else
      (let [person (or (models/get-person-by-telegram telegram-id)
                       (do (models/create-person!
                            {:name        name
                             :telegram_id telegram-id
                             :role        "partner"})
                           (models/get-person-by-telegram telegram-id)))]
        (models/activate-qrcode! qr-code (:id person))
        (send-message chat-id
                      (str "Готово! QR-код " qr-code " привязан к вашему аккаунту.\n"
                           "Теперь клиенты, сканируя этот код, попадут к оператору с вашей реферальной ссылкой."))))))

(defn handle-webhook
  "Обработка входящего webhook от Telegram"
  [update]
  (let [message (get update "message")
        text    (get message "text")
        chat-id (get-in message ["chat" "id"])
        from    (get message "from")]
    (when (and text chat-id)
      (if-let [payload (extract-start-payload text)]
        (bind-partner! chat-id
                       {:id         (get from "id")
                        :first_name (get from "first_name")
                        :username   (get from "username")}
                       payload)
        (send-message chat-id
                      "Для активации QR-кода отсканируйте его — вы попадёте сюда автоматически.")))))

(defn set-webhook! [base-url]
  (let [url (str base-url "/api/telegram/webhook")]
    (http/post (api-url "setWebhook")
               {:content-type :json
                :body (json/generate-string {:url url})})
    (println "Telegram webhook set to:" url)))
