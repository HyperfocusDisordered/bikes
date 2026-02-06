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
(def port                  #(Integer/parseInt (env "PORT" "3001")))
(def admin-key             #(env "KARMA_ADMIN_KEY" "dev-admin-key"))
