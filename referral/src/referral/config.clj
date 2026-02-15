(ns referral.config
  "Конфигурация из переменных окружения")

(defn env
  "Получить значение env-переменной с опциональным дефолтом"
  ([k] (System/getenv k))
  ([k default] (or (System/getenv k) default)))

(def telegram-bot-token    #(env "TELEGRAM_BOT_TOKEN"))
(def telegram-bot-username #(env "TELEGRAM_BOT_USERNAME" "karma_rent_bot"))
(def whatsapp-number       #(env "WHATSAPP_NUMBER"))
(def db-path               #(env "KARMA_DB_PATH" "karma_rent.db"))
(def dev-db-path           #(env "KARMA_DEV_DB_PATH" "karma_rent_dev.db"))

;; Telegram IDs that route to dev DB (Denis @dovchar)
(def dev-telegram-ids      #{124694357})
(def port                  #(Integer/parseInt (env "PORT" "3001")))
(def admin-key             #(env "KARMA_ADMIN_KEY" "kr-unsafe-default-change-me"))
(def oil-change-days       #(Integer/parseInt (env "OIL_CHANGE_DAYS" "90")))
(def owner-username        #(env "OWNER_USERNAME" "dovchar"))
(def owner-chat-id         (atom nil))
(def hf-bot-token          #(env "HF_BOT_TOKEN"))
(def webhook-secret        #(env "WEBHOOK_SECRET"))
(def openai-api-key        #(env "OPENAI_API_KEY"))
(def anthropic-api-key     #(env "ANTHROPIC_API_KEY"))
(def partner-share-pct     #(Double/parseDouble (env "PARTNER_SHARE_PCT" "0.15")))
(def n8n-api-key           #(env "N8N_API_KEY"))
(def started-at            (atom nil))
