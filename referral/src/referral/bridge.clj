(ns referral.bridge
  "HyperFocus Bridge Bot — коммуникация Claude ↔ Denis через Telegram"
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [referral.models :as models]
            [referral.config :as config]
            [clojure.string :as str]))

;; ── Telegram API ─────────────────────────────────────

(defn- api-url [method]
  (str "https://api.telegram.org/bot" (config/hf-bot-token) "/" method))

(defn- api-call [method body]
  (try
    (let [resp (http/post (api-url method)
                          {:content-type :json
                           :body (json/generate-string body)
                           :throw-exceptions false})]
      (when (>= (:status resp) 400)
        (println "HF BOT ERROR:" method "status:" (:status resp) "resp:" (:body resp)))
      resp)
    (catch Exception e
      (println "HF BOT EXCEPTION:" method (.getMessage e)))))

(defn send-message
  "Отправить сообщение owner'у"
  ([text] (send-message text nil))
  ([text reply-markup]
   (when-let [chat-id @config/owner-chat-id]
     (models/save-owner-message! "out" text chat-id)
     (api-call "sendMessage"
               (cond-> {:chat_id chat-id :text text :parse_mode "HTML"}
                 reply-markup (assoc :reply_markup reply-markup))))))

(defn send-message-with-buttons
  "Отправить сообщение с inline кнопками"
  [text buttons]
  (send-message text
    {:inline_keyboard
     (mapv (fn [row]
             (if (map? row)
               [{:text (:label row) :callback_data (:data row)}]
               (mapv (fn [b] {:text (:label b) :callback_data (:data b)}) row)))
           buttons)}))

(defn extract-message-id
  "Извлечь message_id из ответа Telegram API"
  [resp]
  (when resp
    (try
      (let [body (json/parse-string (:body resp) true)]
        (get-in body [:result :message_id]))
      (catch Exception _ nil))))

(defn delete-message
  "Удалить сообщение по message_id"
  [message-id]
  (when-let [chat-id @config/owner-chat-id]
    (api-call "deleteMessage" {:chat_id chat-id :message_id message-id})))

(defn edit-message
  "Редактировать текст сообщения по message_id"
  [message-id text]
  (when-let [chat-id @config/owner-chat-id]
    (api-call "editMessageText"
              {:chat_id chat-id :message_id message-id
               :text text :parse_mode "HTML"})))

(defn answer-callback [callback-id text]
  (api-call "answerCallbackQuery"
            (cond-> {:callback_query_id callback-id}
              text (assoc :text text))))

;; ── Voice transcription (Whisper) ────────────────────

(defn- get-file-path
  "Получить file_path для скачивания файла через getFile API"
  [file-id]
  (try
    (let [resp (http/get (api-url (str "getFile?file_id=" file-id))
                         {:throw-exceptions false})]
      (when (= 200 (:status resp))
        (let [data (json/parse-string (:body resp) true)]
          (get-in data [:result :file_path]))))
    (catch Exception e
      (println "getFile error:" (.getMessage e))
      nil)))

(defn- download-voice-file
  "Скачать голосовой файл с Telegram servers"
  [file-path]
  (try
    (let [url (str "https://api.telegram.org/file/bot" (config/hf-bot-token) "/" file-path)
          resp (http/get url {:as :byte-array :throw-exceptions false})]
      (when (= 200 (:status resp))
        (:body resp)))
    (catch Exception e
      (println "Download voice error:" (.getMessage e))
      nil)))

(defn- transcribe-audio
  "Отправить аудио в OpenAI Whisper API для транскрипции"
  [audio-bytes filename]
  (try
    (when-let [api-key (config/openai-api-key)]
      (let [resp (http/post "https://api.openai.com/v1/audio/transcriptions"
                            {:multipart [{:name "file"
                                          :content audio-bytes
                                          :filename filename}
                                         {:name "model"
                                          :content "whisper-1"}
                                         {:name "language"
                                          :content "ru"}]
                             :headers {"Authorization" (str "Bearer " api-key)}
                             :throw-exceptions false})]
        (when (= 200 (:status resp))
          (let [data (json/parse-string (:body resp) true)]
            (:text data)))))
    (catch Exception e
      (println "Whisper API error:" (.getMessage e))
      nil)))

;; ── Owner check (hardcoded, no gaps) ─────────────────

;; Hardcoded owner username — the only person who can use this bot
(def ^:private OWNER-USERNAME "dovchar")

;; Once we know the owner's user_id, we lock to it (telegram user_id is immutable)
(def ^:private owner-user-id (atom nil))

(defn- owner? [from]
  (let [username (get from "username")
        user-id  (get from "id")]
    (if-let [locked-id @owner-user-id]
      ;; After first contact: check by immutable user_id
      (= user-id locked-id)
      ;; First contact: verify username, then lock user_id
      (when (and username (= (str/lower-case username) OWNER-USERNAME))
        (reset! owner-user-id user-id)
        (println "HF: Owner locked to user_id:" user-id)
        true))))

;; ── Webhook handler ──────────────────────────────────

(defn handle-webhook
  "Обработка webhook от HyperFocus бота"
  [update]
  (try
    (if-let [callback (get update "callback_query")]
      ;; Callback кнопка
      (let [from    (get callback "from")
            data    (get callback "data")
            cb-id   (get callback "id")
            chat-id (get-in callback ["message" "chat" "id"])]
        (when (owner? from)
          (reset! config/owner-chat-id chat-id)
          (models/save-owner-message! "in" (str "[btn] " data) chat-id)
          (println "HF OWNER CALLBACK:" data)
          (answer-callback cb-id "Received")
          (api-call "sendMessage"
                    {:chat_id chat-id
                     :text (str "✅ <b>" data "</b>")
                     :parse_mode "HTML"})))
      ;; Текстовое, голосовое или фото сообщение
      (let [message (get update "message")
            text    (get message "text")
            voice   (get message "voice")
            photo   (get message "photo")
            caption (get message "caption")
            chat-id (get-in message ["chat" "id"])
            from    (get message "from")]
        (cond
          ;; Текст
          (and text chat-id)
          (if (owner? from)
            (do
              (reset! config/owner-chat-id chat-id)
              (models/save-owner-message! "in" text chat-id)
              (println "HF OWNER MSG:" text))
            ;; Не owner — игнорируем, но логируем
            (println "HF IGNORED msg from:" (get from "username") "text:" text))

          ;; Voice — сохраняем file_id, транскрипция локально через daemon
          (and voice chat-id (owner? from))
          (let [file-id (get voice "file_id")
                duration (get voice "duration" 0)]
            (reset! config/owner-chat-id chat-id)
            (println "HF OWNER VOICE:" file-id "duration:" duration)
            (send-message "🎤 Транскрибирую...")
            (models/save-owner-message! "in" (str "[voice:" file-id "]") chat-id))

          ;; Photo — берём самый большой размер, сохраняем file_id
          (and photo chat-id (owner? from))
          (let [largest  (last photo)  ;; Telegram sorts by size, last = biggest
                file-id  (get largest "file_id")]
            (reset! config/owner-chat-id chat-id)
            (println "HF OWNER PHOTO:" file-id "caption:" caption)
            (send-message "📷 Получил фото")
            (models/save-owner-message! "in"
              (if caption
                (str "[photo:" file-id "|" caption "]")
                (str "[photo:" file-id "]"))
              chat-id))

          ;; Document (zip, pdf, etc.) — сохраняем file_id + filename
          (and (get message "document") chat-id (owner? from))
          (let [document  (get message "document")
                file-id   (get document "file_id")
                file-name (get document "file_name" "unknown")
                mime-type (get document "mime_type" "")
                file-size (get document "file_size" 0)]
            (reset! config/owner-chat-id chat-id)
            (println "HF OWNER DOCUMENT:" file-name "mime:" mime-type "size:" file-size "file_id:" file-id)
            (send-message (str "📄 Получил: " file-name))
            (models/save-owner-message! "in"
              (if caption
                (str "[doc:" file-id "|" file-name "|" caption "]")
                (str "[doc:" file-id "|" file-name "]"))
              chat-id))

          ;; Voice/Photo/Document от не-owner
          (and (or voice photo (get message "document")) (not (owner? from)))
          (println "HF IGNORED media from:" (get from "username")))))
    (catch Exception e
      (println "HF Webhook error:" (.getMessage e)))))

(defn set-webhook! [base-url]
  (let [url    (str base-url "/api/hf/webhook")
        params (cond-> {:url url}
                 (config/webhook-secret) (assoc :secret_token (config/webhook-secret)))]
    (api-call "setWebhook" params)
    (println "HF Bot webhook set to:" url
             (when (config/webhook-secret) "(with secret)"))))
