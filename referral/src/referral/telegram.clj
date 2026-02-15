(ns referral.telegram
  "Telegram бот: CRM для операторов + клиентский сторфронт + привязка партнёров"
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [referral.models :as models]
            [referral.db :as db]
            [referral.config :as config]
            [referral.qr :as qr-gen]
            [referral.ai :as ai]
            [clojure.string :as str]))

;; ── Webhook dedup ────────────────────────────────────

(defonce ^:private recent-updates (atom #{}))

(defn- seen-update?
  "Returns true if this update_id was already processed. Tracks last 1000."
  [update-id]
  (when update-id
    (let [already? (contains? @recent-updates update-id)]
      (when-not already?
        (swap! recent-updates (fn [s]
          (let [s' (conj s update-id)]
            (if (> (count s') 1000)
              (set (take-last 500 (sort s')))
              s')))))
      already?)))

;; ── Telegram API helpers ──────────────────────────────

(defn- api-url [method]
  (str "https://api.telegram.org/bot" (config/telegram-bot-token) "/" method))

(defn- api-call [method body]
  (try
    (let [resp (http/post (api-url method)
                          {:content-type :json
                           :body (json/generate-string body)
                           :throw-exceptions false})]
      (when (>= (:status resp) 400)
        (println "TG API ERROR:" method "status:" (:status resp)
                 "resp:" (:body resp)
                 "req-keys:" (keys body)))
      resp)
    (catch Exception e
      (println "TG API EXCEPTION:" method (.getMessage e)))))

(defn- send-message
  ([chat-id text] (send-message chat-id text nil))
  ([chat-id text reply-markup]
   (api-call "sendMessage"
             (cond-> {:chat_id chat-id :text text :parse_mode "HTML"}
               reply-markup (assoc :reply_markup reply-markup)))))

(defn- edit-message [chat-id message-id text reply-markup]
  (api-call "editMessageText"
            (cond-> {:chat_id chat-id :message_id message-id
                     :text text :parse_mode "HTML"}
              reply-markup (assoc :reply_markup reply-markup))))

(defn- answer-callback [callback-id & [text]]
  (api-call "answerCallbackQuery"
            (cond-> {:callback_query_id callback-id}
              text (assoc :text text))))

(defn- delete-message [chat-id message-id]
  (api-call "deleteMessage" {:chat_id chat-id :message_id message-id}))

(defn- send-typing [chat-id]
  (api-call "sendChatAction" {:chat_id chat-id :action "typing"}))

;; ── Safe parse ───────────────────────────────────────

(defn- safe-long
  "Null-safe parse-long. Returns default (0) if nil or malformed."
  ([s] (safe-long s 0))
  ([s default]
   (if s
     (try (parse-long (str s)) (catch Exception _ default))
     default)))

;; ── UI builders ───────────────────────────────────────

(def PAGE_SIZE 8)

(defn- inline-kb [buttons]
  {:inline_keyboard buttons})

(defn btn [text data]
  {:text text :callback_data data})

(defn- nav-buttons [prefix page total]
  (let [max-page (max 0 (quot (dec total) PAGE_SIZE))]
    (when (pos? max-page)
      [(cond-> []
         (pos? page)    (conj (btn "◀️" (str prefix ":p:" (dec page))))
         true           (conj (btn (str (inc page) "/" (inc max-page)) "noop"))
         (< page max-page) (conj (btn "▶️" (str prefix ":p:" (inc page)))))])))

(defn- status-emoji [status]
  (case status
    "available"   "🟢"
    "rented"      "🔴"
    "booked"      "🟡"
    "maintenance" "🔧"
    "hold"        "⚪"
    "❓"))

(defn- oil-emoji [bike]
  (case (models/bike-oil-status bike)
    :critical "🔴"
    :warning  "🟠"
    :ok       "🟢"))

(defn- rental-emoji [bike]
  (case (models/bike-rental-status bike)
    :critical "🔴"
    :warning  "🟠"
    :ok       "🟢"))

(defn- status-label [status]
  (case status
    "available"   "Свободен"
    "rented"      "В аренде"
    "booked"      "Бронь"
    "maintenance" "На ремонте"
    "hold"        "На хранении"
    status))

(defn- share-pct-label []
  (str (Math/round (* 100.0 (config/partner-share-pct))) "%"))

;; ── Deep links ──────────────────────────────────────

(defn- deep-link
  "Генерит HTML-ссылку t.me/Bot?start=payload"
  [payload text]
  (str "<a href=\"https://t.me/" (config/telegram-bot-username) "?start=" payload "\">" text "</a>"))

;; ── Auth: кто пишет? ─────────────────────────────────

(defn- get-operator
  "Находит person по telegram_id. Оператор = admin/moderator."
  [telegram-id]
  (when-let [p (models/get-person-by-telegram (str telegram-id))]
    (when (#{"admin" "moderator"} (:role p))
      p)))

(defn- get-admin
  "Находит person по telegram_id. Только admin."
  [telegram-id]
  (when-let [p (models/get-person-by-telegram (str telegram-id))]
    (when (= "admin" (:role p))
      p)))

;; ── Partner self-view ─────────────────────────────────

(defn- get-partner
  "Находит person-partner по telegram_id"
  [telegram-id]
  (when-let [p (models/get-person-by-telegram (str telegram-id))]
    (when (= "partner" (:role p))
      p)))

(defn- build-partner-stats-text
  "Формирует текст статистики партнёра (используется в partner-self-menu и mystats callback)"
  [partner]
  (let [st      (models/partner-stats (:id partner))
        history (models/partner-rental-history (:id partner) 5)]
    (str "🤝 <b>" (:name partner) "</b>\n\n"
         "📈 <b>Твоя статистика</b>\n"
         "Клиентов: " (:clients_count st) "\n\n"
         "За месяц (" (get-in st [:monthly :period]) "):\n"
         "  Выручка: " (get-in st [:monthly :revenue]) " тыс\n"
         "  Твоя доля " (share-pct-label) ": <b>" (get-in st [:monthly :share]) " тыс</b>\n\n"
         "За всё время:\n"
         "  Выручка: " (get-in st [:all_time :revenue]) " тыс\n"
         "  Твоя доля " (share-pct-label) ": <b>" (get-in st [:all_time :share]) " тыс</b>\n\n"
         (when (seq history)
           (str "📋 <b>Последние операции</b>\n"
                (str/join "\n"
                  (map (fn [r]
                         (str "  " (if (= "service" (:transaction_type r)) "🔧 " "💰 ")
                              (:date r) " — "
                              (or (:client_name r) "?") " — "
                              (:amount r) " тыс"
                              (when (:bike_name r) (str " (" (:bike_name r) ")"))))
                       history)))))))

(defn- partner-self-menu [chat-id partner]
  (send-message chat-id (build-partner-stats-text partner)
    (inline-kb [[(btn "📋 Все операции" (str "myops:" (:id partner)))]
                [(btn "🔄 Обновить" "mystats")]])))

;; ── Main menu ─────────────────────────────────────────

(defn- main-menu [chat-id & [from-id]]
  (let [pending   (models/list-pending-bookings)
        bikes     (models/list-bikes)
        rented    (filter #(= "rented" (:status %)) bikes)
        free      (filter #(= "available" (:status %)) bikes)
        on-hold   (filter #(= "hold" (:status %)) bikes)
        ;; Use SQL-precomputed urgency (2=critical, 1=warning, 0=ok) — no N+1
        oil-crit  (filter #(= 2 (:oil_urgency %)) bikes)
        rent-crit (filter #(= 2 (:rental_urgency %)) bikes)
        rent-warn (filter #(= 1 (:rental_urgency %)) bikes)
        ;; Собираем action items — что требует внимания
        alerts    (cond-> []
                    (seq pending)
                    (conj (str "⚡ <b>" (count pending) "</b> новых бронирований"))
                    (seq rent-crit)
                    (conj (str "⏱ <b>" (count rent-crit) "</b> аренд просрочено!"))
                    (seq rent-warn)
                    (conj (str "⏱ <b>" (count rent-warn) "</b> аренд — скоро конец"))
                    (seq oil-crit)
                    (conj (str "🛢 <b>" (count oil-crit) "</b> байков — замена масла!")))
        summary   (str "🏍 " (count free) " свободных • "
                       (count rented) " в аренде"
                       (when (pos? (count on-hold))
                         (str " • " (count on-hold) " на хранении"))
                       " • " (count bikes) " всего")
        is-admin  (get-admin from-id)]  ;; cache: one DB query instead of 3
    (send-message chat-id
      (str "📋 <b>Карма Рент</b>\n\n"
           summary
           (when (seq alerts)
             (str "\n\n" (clojure.string/join "\n" alerts)))
           (when (empty? alerts) "\n\n✅ Всё в порядке"))
      (inline-kb (cond-> []
                   (seq pending)
                   (conj [(btn (str "📦 Брони (" (count pending) ")") "bookings:list")])
                   true
                   (conj [(btn "🚗 Транспорт" "transport:menu") (btn "💰 Аренда" "rental:start")])
                   true
                   (conj (filterv some?
                           [(btn "👥 Клиенты" "clients:list")
                            (when is-admin (btn "🤝 Партнёры" "partners:list"))]))
                   true
                   (conj [(btn "📊 Статистика" "stats:summary") (btn "👁 Клиент" "preview:client")])
                   is-admin
                   (conj [(btn "📋 Список QR TG" "qr:list") (btn "📋 Список QR WA" "qr:wa_list")])
                   is-admin
                   (conj [(btn "➕ Новые QR TG" "qr:range") (btn "➕ Новые QR WA" "qr:wa_range")]))))))

;; ── QR codes management ─────────────────────────────────

(declare send-photo)

(defn- qr-channel-list
  "Список QR-кодов для канала (telegram/whatsapp)"
  [chat-id channel]
  (let [ch-prefix (if (= "whatsapp" channel) "wa" "tg")
        label     (if (= "whatsapp" channel) "💬 QR-коды → WhatsApp" "📱 QR-коды → Telegram")
        codes     (models/list-qrcodes channel)
        active    (filter :partner_id codes)
        free      (remove :partner_id codes)]
    (send-message chat-id
      (str "<b>" label "</b>\n\n"
           "Всего: " (count codes) "\n"
           "✅ Активированы: " (count active) "\n"
           "⬜ Свободные: " (count free))
      (inline-kb [[(btn "◀️ Меню" "menu")]]))
    (doseq [q codes]
      (let [invite-url (str "https://karmarent.app/invite/" ch-prefix "/" (:code q))
            img-url    (qr-gen/qr-image-url invite-url)
            caption    (if (:partner_id q)
                         (str "✅ <b>" (:code q) "</b> → " (or (:partner_name q) "?")
                              "\n📎 " invite-url)
                         (str "⬜ <b>" (:code q) "</b> — свободен"
                              "\n📎 " invite-url))]
        (send-photo chat-id img-url caption nil)))))

(defn- qr-generate-range!
  "Генерирует QR-коды по диапазону номеров партнёров (например 5-10)"
  [chat-id from-n to-n channel]
  (let [existing      (set (mapv :code (models/list-qrcodes channel)))
        codes         (mapv str (range from-n (inc to-n)))
        new-codes     (remove existing codes)
        skipped       (filter existing codes)
        label         (if (= "whatsapp" channel) "💬 WhatsApp" "📱 Telegram")
        ch-prefix     (if (= "whatsapp" channel) "wa" "tg")]
    (if (empty? new-codes)
      (send-message chat-id
        (str "⚠️ Все номера " from-n "–" to-n " уже существуют для " label ".")
        (inline-kb [[(btn "◀️ Меню" "menu")]]))
      (do
        (models/create-qrcodes! new-codes channel)
        (send-message chat-id
          (str "✅ Создано <b>" (count new-codes) "</b> " label " QR-кодов"
               (when (seq skipped) (str " (пропущено " (count skipped) " — уже есть)"))
               ":"))
        (doseq [code new-codes]
          (let [invite-url (str "https://karmarent.app/invite/" ch-prefix "/" code)
                img-url    (qr-gen/qr-image-url invite-url)
                caption    (str label " партнёр <b>#" code "</b>\n📎 " invite-url)]
            (send-photo chat-id img-url caption nil)))
        (send-message chat-id
          (str "Готово! Партнёры " from-n "–" to-n ".")
          (inline-kb [[(btn "◀️ Меню" "menu")]]))))))


;; ── Bikes ─────────────────────────────────────────────

(defn- send-photo [chat-id photo-url caption reply-markup]
  (api-call "sendPhoto"
    (cond-> {:chat_id chat-id :photo photo-url
             :caption caption :parse_mode "HTML"}
      reply-markup (assoc :reply_markup reply-markup))))

(defn- send-document
  ([chat-id doc-url caption] (send-document chat-id doc-url caption nil))
  ([chat-id doc-url caption reply-markup]
   (api-call "sendDocument"
     (cond-> {:chat_id chat-id :document doc-url
              :caption caption :parse_mode "HTML"}
       reply-markup (assoc :reply_markup reply-markup)))))


(def ^:private cat-labels
  {"car" "🚗 Авто" "bike" "🏍 Мото" "scooter" "🛵 Скутеры" "bicycle" "🚲 Велосипеды"})

(def ^:private cat-emoji
  {"car" "🚗" "bike" "🏍" "scooter" "🛵" "bicycle" "🚲"})

(defn- transport-menu
  "Подменю выбора категории транспорта (оператор).
   Показывает только непустые категории."
  [chat-id msg-id]
  (let [all-bikes (models/list-bikes)  ;; one query instead of 4
        counts (frequencies (map :category all-bikes))
        cats   [["bike" "🏍 Мото"] ["scooter" "🛵 Скутеры"]
                ["car" "🚗 Авто"] ["bicycle" "🚲 Велосипеды"]]
        ;; Только непустые категории
        non-empty (filterv (fn [[k _]] (pos? (counts k 0))) cats)
        ;; Разбиваем по 2 в ряд
        rows (mapv (fn [pair]
                     (mapv (fn [[k label]]
                             (btn (str label " (" (counts k 0) ")") (str "bikes:cat:" k)))
                           pair))
                   (partition-all 2 non-empty))
        text (str "🚗 <b>Транспорт</b>\n\nВыберите категорию:")
        kb (inline-kb (conj (vec rows) [(btn "📋 Все" "bikes:list")]))]
    (if msg-id
      (edit-message chat-id msg-id text kb)
      (send-message chat-id text kb))))

(defn- bikes-list [chat-id msg-id _page & [category]]
  (let [all   (if category (models/list-bikes nil category) (models/list-bikes))
        total (count all)
        title (if category
                (str (get cat-emoji category "🚗") " <b>" (get cat-labels category "Транспорт") "</b>")
                "🚗 <b>Весь транспорт</b>")]
    ;; Заголовок
    (if msg-id
      (edit-message chat-id msg-id (str title " (" total " шт)") nil)
      (send-message chat-id (str title " (" total " шт)")))
    ;; Все карточки параллельно (без пагинации) — используем предвычисленные SQL поля
    (let [futs (mapv (fn [b]
                       (future
                         (let [oil-u    (:oil_urgency b)     ;; 2=critical 1=warning 0=ok
                               rent-u   (:rental_urgency b)  ;; 2=critical 1=warning 0=ok
                               days-oil (when (:days_since_oil b) (long (:days_since_oil b)))
                               end-date (:rental_end_date b)
                               rt       (:rental_type b)
                               oil-e    (case oil-u 2 "🔴" 1 "🟠" "🟢")
                               rent-e   (case rent-u 2 "🔴" 1 "🟠" "🟢")
                               caption  (str (status-emoji (:status b))
                                             " <b>" (:name b) "</b>"
                                             (when (:plate_number b) (str " [" (:plate_number b) "]"))
                                             "\n" (status-label (:status b))
                                             (when (:client_name b) (str " — " (:client_name b)))
                                             " • " (or (:daily_rate b) "?") " тыс/день"
                                             (when (:monthly_rate b) (str " / " (:monthly_rate b) " тыс/мес"))
                                             "\n🛢 " oil-e " "
                                             (case oil-u 2 "ЗАМЕНА!" 1 "скоро" "ок")
                                             (when (and days-oil (< days-oil 9999)) (str " (" days-oil "д)"))
                                             (when (= "rented" (:status b))
                                               (str "\n⏱ " rent-e " "
                                                    (if (= "monthly" rt) "помесячно" "посуточно")
                                                    (case rent-u 2 " — ПРОСРОЧЕНА!" 1 " — скоро конец" "")
                                                    (when end-date (str " до " end-date))))
                                             "\n" (deep-link (str "adm_b" (:id b)) "Детали"))]
                           (if (:photo_url b)
                             (send-document chat-id (:photo_url b) caption)
                             (send-message chat-id caption)))))
                     all)]
      (run! deref futs))
    ;; Навигация
    (send-message chat-id "☝️"
      (inline-kb
        (cond-> []
          category (conj [(btn "◀️ Категории" "transport:menu")])
          true (conj [(btn "➕ Добавить" "bike:add") (btn "🏠 Меню" "main:menu")]))))))

(defn- bike-detail [chat-id msg-id bike-id & [from-id]]
  (when-let [b (models/get-bike bike-id)]
    (let [oil      (models/bike-oil-status b)
          days-oil (when (:last_oil_change b)
                     (-> (java.time.temporal.ChronoUnit/DAYS)
                         (.between (java.time.LocalDate/parse (:last_oil_change b))
                                   (java.time.LocalDate/now))))
          ;; Если байк в аренде — показать клиента и кнопку возврата
          active   (when (= "rented" (:status b))
                     (models/active-rental-for-bike bike-id))
          rent-st  (models/bike-rental-status b)
          end-date (:rental_end_date active)
          rt       (or (:rental_type active) "daily")
          text     (str (get cat-emoji (:category b) "🏍") " <b>" (:name b) "</b>"
                        (when (:plate_number b) (str " [" (:plate_number b) "]"))
                        "\n" (get cat-labels (:category b) "Транспорт")
                        "\n\n"
                        "Статус: " (status-emoji (:status b)) " " (status-label (:status b)) "\n"
                        (when active
                          (str "👤 Клиент: " (or (:client_name active) "?")
                               (when (:client_telegram_id active)
                                 (str " — <a href=\"tg://user?id=" (:client_telegram_id active) "\">TG</a>"))
                               "\n"
                               "⏱ Аренда: " (rental-emoji b) " "
                               (if (= "monthly" rt) "помесячная" "посуточная")
                               (when end-date (str " до " end-date))
                               (case rent-st
                                 :critical " — ПРОСРОЧЕНА!"
                                 :warning  " — скоро конец"
                                 "")
                               "\n"))
                        "Цена: " (or (:daily_rate b) "—") " тыс/день"
                        (when (:monthly_rate b) (str " / " (:monthly_rate b) " тыс/мес"))
                        "\n"
                        "🛢 Масло: " (oil-emoji b) " "
                        (if days-oil
                          (str days-oil " дней назад (лимит " (config/oil-change-days) ")")
                          "не указано")
                        "\n"
                        (when (:notes b) (str "Заметки: " (:notes b) "\n"))
                        (when active
                          (str "\n" (deep-link (str "adm_ret" (:booking_id active)) "🔑 Вернуть байк")))
                        "\n" (deep-link (str "adm_bs" bike-id) "Сменить статус")
                        "\n" (deep-link (str "adm_bo" bike-id) "Масло заменено")
                        (when (and (not= "rented" (:status b)) (get-admin from-id))
                          (str "\n" (deep-link (str "adm_bdel" bike-id) "🗑 Удалить")))
                        "\n" (deep-link "adm_bikes" "◀️ Транспорт"))]
      (if (and msg-id (not (:photo_url b)))
        (edit-message chat-id msg-id text nil)
        (do
          (when msg-id (delete-message chat-id msg-id))
          (if (:photo_url b)
            (send-document chat-id (:photo_url b) text)
            (send-message chat-id text)))))))


(defn- bike-status-menu [chat-id msg-id bike-id]
  (let [text "Выберите новый статус:"
        kb   (inline-kb
               [[(btn "🟢 Свободен" (str "bike:set:" bike-id ":available"))
                 (btn "🔧 Ремонт" (str "bike:set:" bike-id ":maintenance"))]
                [(btn "⚪ На хранении" (str "bike:set:" bike-id ":hold"))]
                [(btn "◀️ Назад" (str "bike:detail:" bike-id))]])]
    (if msg-id
      (edit-message chat-id msg-id text kb)
      (send-message chat-id text kb))))

;; ── Partners ──────────────────────────────────────────

(defn- partners-list [chat-id msg-id page]
  (let [all     (models/list-persons "partner")
        total   (count all)
        parts   (take PAGE_SIZE (drop (* page PAGE_SIZE) all))]
    ;; Заголовок
    (if msg-id
      (edit-message chat-id msg-id
        (str "🤝 <b>Партнёры</b> (" total " шт)  •  стр " (inc page))
        nil)
      (send-message chat-id
        (str "🤝 <b>Партнёры</b> (" total " шт)  •  стр " (inc page))))
    ;; Каждый партнёр = чистая карточка
    (doseq [p parts]
      (let [st (models/partner-stats (:id p))]
        (send-message chat-id
          (str "🤝 <b>" (:name p) "</b>"
               (when (:phone p) (str " • " (:phone p)))
               "\nКлиентов: " (:clients_count st)
               "\nВсего: " (get-in st [:all_time :revenue]) " тыс"
               " (доля: " (get-in st [:all_time :share]) " тыс)"
               "\nМесяц: " (get-in st [:monthly :revenue]) " тыс"
               " (доля: " (get-in st [:monthly :share]) " тыс)"
               "\n" (deep-link (str "adm_p" (:id p)) "Подробнее")))))
    ;; Навигация текстом
    (let [max-page (max 0 (quot (dec total) PAGE_SIZE))
          nav      (str "📄 " (inc page) "/" (inc max-page)
                        (when (< page max-page)
                          (str "  →  " (deep-link (str "adm_partners_" (+ page 2)) "Дальше")))
                        (when (pos? page)
                          (str "  ←  " (deep-link (str "adm_partners_" page) "Назад")))
                        "\n" (deep-link "adm_menu" "Меню"))]
      (send-message chat-id nav))))


(defn- partner-detail [chat-id msg-id partner-id]
  (when-let [p (models/get-person partner-id)]
    (let [st      (models/partner-stats partner-id)
          history (models/partner-rental-history partner-id 5)
          text    (str "🤝 <b>" (:name p) "</b>\n"
                       (when (:phone p) (str "📱 " (:phone p) "\n"))
                       (when (:telegram_id p) (str "💬 ID: " (:telegram_id p) "\n"))
                       "\n📈 <b>Статистика</b>\n"
                       "Клиентов: " (:clients_count st) "\n\n"
                       "За месяц (" (get-in st [:monthly :period]) "):\n"
                       "  Выручка: " (get-in st [:monthly :revenue]) " тыс\n"
                       "  Доля " (share-pct-label) ": " (get-in st [:monthly :share]) " тыс\n\n"
                       "За всё время:\n"
                       "  Выручка: " (get-in st [:all_time :revenue]) " тыс\n"
                       "  Доля " (share-pct-label) ": " (get-in st [:all_time :share]) " тыс\n\n"
                       (when (seq history)
                         (str "📋 <b>Последние операции</b>\n"
                              (str/join "\n"
                                (map (fn [r]
                                       (str "  " (:date r) " — "
                                            (or (:client_name r) "?") " — "
                                            (:amount r) " тыс"
                                            (when (:bike_name r) (str " (" (:bike_name r) ")"))))
                                     history))))
                       "\n\n" (deep-link (str "adm_ph" partner-id) "Все операции")
                       "\n" (deep-link "adm_partners" "Назад")
                       "\n" (deep-link "adm_menu" "Меню"))]
      (if msg-id
        (edit-message chat-id msg-id text nil)
        (send-message chat-id text)))))

;; ── Clients ───────────────────────────────────────────

(defn- clients-list [chat-id msg-id page]
  (let [all     (models/list-persons "client")
        total   (count all)
        clients (take PAGE_SIZE (drop (* page PAGE_SIZE) all))]
    ;; Заголовок
    (if msg-id
      (edit-message chat-id msg-id
        (str "👥 <b>Клиенты</b> (" total " шт)  •  стр " (inc page))
        nil)
      (send-message chat-id
        (str "👥 <b>Клиенты</b> (" total " шт)  •  стр " (inc page))))
    ;; Каждый клиент = чистая карточка
    (doseq [c clients]
      (send-message chat-id
        (str "👤 <b>" (:name c) "</b>"
             (when (:phone c) (str " • " (:phone c)))
             "\n" (deep-link (str "adm_c" (:id c)) "Подробнее"))))
    ;; Навигация + добавить клиента
    (send-message chat-id "➕ Добавить клиента"
      (inline-kb [[(btn "➕ Новый клиент" "client:add")]
                  [(btn "◀️ Меню" "menu")]]))
    (let [max-page (max 0 (quot (dec total) PAGE_SIZE))
          nav      (str "📄 " (inc page) "/" (inc max-page)
                        (when (< page max-page)
                          (str "  →  " (deep-link (str "adm_clients_" (+ page 2)) "Дальше")))
                        (when (pos? page)
                          (str "  ←  " (deep-link (str "adm_clients_" page) "Назад"))))]
      (when (pos? max-page)
        (send-message chat-id nav)))))


;; ── Stats ─────────────────────────────────────────────

(defn- period-str
  "Формат YYYY-MM для даты"
  [^java.time.LocalDate d]
  (format "%d-%02d" (.getYear d) (.getMonthValue d)))

(defn- stats-summary [chat-id msg-id & [period-offset from-id]]
  (let [bikes    (models/list-bikes)
        free     (count (filter #(= "available" (:status %)) bikes))
        total-b  (count bikes)
        is-admin (get-admin from-id)
        partners (when is-admin (models/list-persons "partner"))
        now      (java.time.LocalDate/now)
        offset   (or period-offset 0)
        target   (.minusMonths now offset)
        period   (period-str target)
        revenue  (when is-admin (reduce + (map #(models/partner-revenue (:id %) period) partners)))
        share    (when revenue (* (config/partner-share-pct) revenue))
        ;; Транзакции за период
        rentals  (models/list-rentals-by-period period)
        svc-count (count (filter #(= "service" (:transaction_type %)) rentals))
        svc-total (reduce + 0 (map :amount (filter #(= "service" (:transaction_type %)) rentals)))
        oil-crit (count (filter #(= :critical (models/bike-oil-status %)) bikes))
        oil-warn (count (filter #(= :warning (models/bike-oil-status %)) bikes))
        text     (str "📊 <b>Статистика</b> (" period ")\n\n"
                      "🏍 Байков: " total-b " (свободных: " free ")\n"
                      (when is-admin
                        (str "🤝 Партнёров: " (count partners) "\n\n"
                             "💰 Доход: " revenue " тыс\n"
                             "📤 Партнёрам (" (share-pct-label) "): " share " тыс\n"
                             (when (pos? svc-count)
                               (str "🔧 Сервис: " svc-total " тыс (" svc-count " шт)\n"))))
                      "📋 Транзакций: " (count rentals) "\n"
                      (when (seq rentals)
                        (str "\n<b>Транзакции:</b>\n"
                             (str/join "\n"
                               (map (fn [r]
                                      (str (if (= "service" (:transaction_type r)) "  🔧 " "  ")
                                           (:date r) " | "
                                           (or (:client_name r) "?") " | "
                                           (:amount r) " тыс"
                                           (when (:bike_name r) (str " | " (:bike_name r)))
                                           (when (and is-admin (:partner_name r)) (str " 🤝" (:partner_name r)))))
                                    (take 15 rentals)))
                             (when (> (count rentals) 15)
                               (str "\n  ... ещё " (- (count rentals) 15)))))
                      "\n\n"
                      "🛢 Масло: 🔴" oil-crit " 🟠" oil-warn
                      "\n\n" (deep-link "adm_menu" "Меню"))
        nav-row  (filterv some?
                   [(btn (str "◀️ " (period-str (.minusMonths target 1)))
                         (str "stats:period:" (inc offset)))
                    (when (pos? offset)
                      (btn (str (period-str (.plusMonths target 1)) " ▶️")
                           (str "stats:period:" (dec offset))))])
        kb       (inline-kb [nav-row [(btn "◀️ Меню" "menu")]])]
    (if msg-id
      (edit-message chat-id msg-id text kb)
      (send-message chat-id text kb))))

;; ── Bookings (operator) ──────────────────────────────

(defn- bookings-list
  "Список бронирований для оператора"
  [chat-id msg-id]
  (let [pending   (models/list-pending-bookings)
        all-books (models/list-bookings)]
    (if (empty? all-books)
      (send-message chat-id
        (str "📦 <b>Бронирования</b>\n\nНет бронирований."
             "\n\n" (deep-link "adm_menu" "Меню")))
      (do
        (send-message chat-id
          (str "📦 <b>Бронирования</b>"
               (when (seq pending)
                 (str "\n⚡ Ожидают подтверждения: <b>" (count pending) "</b>"))))
        ;; Pending первые
        (doseq [b pending]
          (let [rt (or (:rental_type b) "daily")]
            (send-message chat-id
              (str "🆕 <b>Бронь #" (:id b) "</b> — ожидает\n"
                   "👤 " (or (:client_name b) "?")
                   (when (:client_telegram_id b)
                     (str " — <a href=\"tg://user?id=" (:client_telegram_id b) "\">TG</a>"))
                   "\n🏍 " (or (:bike_name b) "?") " — "
                   (if (= "monthly" rt)
                     (str (or (:bike_monthly_rate b) (:bike_rate b) "?") " тыс/мес")
                     (str (or (:bike_rate b) "?") " тыс/день"))
                   "\n📋 " (if (= "monthly" rt) "Помесячный" "Посуточный")
                   "\n📅 " (:created_at b))
              (inline-kb [[(btn "✅ Подтвердить" (str "bkng:confirm:" (:id b)))
                           (btn "❌ Отклонить" (str "bkng:cancel:" (:id b)))]]))))
        ;; Последние завершённые/отменённые (лимит 5)
        (let [recent (take 5 (filter #(not= "pending" (:status %)) all-books))]
          (when (seq recent)
            (send-message chat-id
              (str "📋 <b>Последние</b>\n"
                   (str/join "\n"
                     (map (fn [b]
                            (str (case (:status b)
                                   "confirmed" "✅"
                                   "cancelled" "❌"
                                   "completed" "🏁"
                                   "❓")
                                 " #" (:id b) " " (or (:client_name b) "?")
                                 " — " (or (:bike_name b) "?")))
                          recent))))))
        (send-message chat-id (deep-link "adm_menu" "Меню"))))))

(defn- notify-client-booking-confirmed!
  "Уведомить клиента что бронь подтверждена (с фото байка)"
  [booking]
  (when-let [chat-id (some-> (:client_telegram_id booking) parse-long)]
    (let [rt (or (:rental_type booking) "daily")
          price-label (if (= "monthly" rt)
                        (str (or (:bike_monthly_rate booking) (:bike_rate booking) "?") " тыс/мес")
                        (str (or (:bike_rate booking) "?") " тыс/день"))
          caption (str "🎉 <b>Ваша бронь подтверждена!</b>\n\n"
                       "🏍 " (or (:bike_name booking) "?") " — " price-label
                       "\n📋 Тариф: " (if (= "monthly" rt) "помесячный" "посуточный")
                       "\n🔢 Бронь #" (:id booking)
                       (when (:rental_end_date booking)
                         (str "\n📅 Аренда до: " (:rental_end_date booking)))
                       "\n\nОператор скоро свяжется с вами. Спасибо! 🙏")
          kb (inline-kb [[(btn "📋 Каталог" "cat:menu")]])]
      (if (:bike_photo booking)
        (send-photo chat-id (:bike_photo booking) caption kb)
        (send-message chat-id caption kb)))))

(defn- notify-client-booking-cancelled!
  "Уведомить клиента что бронь отменена"
  [booking]
  (when-let [chat-id (some-> (:client_telegram_id booking) parse-long)]
    (send-message chat-id
      (str "😔 <b>Ваша бронь отменена</b>\n\n"
           "🏍 " (or (:bike_name booking) "?")
           "\n\nНе расстраивайтесь — у нас есть другие варианты! 🛵")
      (inline-kb [[(btn "🔍 Смотреть другие байки" "cat:menu")]
                   [(btn "💬 Помочь подобрать" "ai:consult")]]))))

;; ── Client: мои бронирования ────────────────────────

(defn- client-my-bookings
  "Показать клиенту его бронирования"
  [chat-id from-id]
  (let [person (models/get-person-by-telegram (str from-id))]
    (if person
      (let [bookings (models/client-bookings (:id person))
            active   (filter #(#{"pending" "confirmed"} (:status %)) bookings)
            recent   (take 3 (filter #(#{"completed" "cancelled"} (:status %)) bookings))
            cancel-btns (mapv (fn [b]
                                [(btn (str "❌ Отменить " (:bike_name b)) (str "cbcancel:" (:id b)))])
                              (filter #(= "pending" (:status %)) active))]
        (if (empty? bookings)
          (send-message chat-id
            "У вас пока нет бронирований.\nВыберите транспорт в каталоге! 👇"
            (inline-kb [[(btn "📋 Каталог" "cat:menu")]]))
          (send-message chat-id
            (str "📋 <b>Мои бронирования</b>\n\n"
                 (when (seq active)
                   (str (str/join "\n"
                          (map (fn [b]
                                 (str (case (:status b)
                                        "pending"   "⏳"
                                        "confirmed" "✅"
                                        "❓")
                                      " <b>" (:bike_name b) "</b> — "
                                      (case (:status b)
                                        "pending"   "ожидает подтверждения"
                                        "confirmed" "подтверждена"
                                        (:status b))
                                      (when (:rental_type b)
                                        (str " (" (if (= "monthly" (:rental_type b)) "помесячно" "посуточно") ")"))))
                               active))
                        "\n\n"))
                 (when (seq recent)
                   (str "📜 <b>Завершённые:</b>\n"
                        (str/join "\n"
                          (map (fn [b]
                                 (str (case (:status b)
                                        "completed" "✅"
                                        "cancelled" "❌"
                                        "❓")
                                      " " (:bike_name b) " — "
                                      (case (:status b)
                                        "completed" "завершена"
                                        "cancelled" "отменена"
                                        (:status b))))
                               recent)))))
            (inline-kb (vec (concat cancel-btns [[(btn "📋 Каталог" "cat:menu")]]))))))
      (send-message chat-id
        "Вы ещё не бронировали у нас. Выберите транспорт! 👇"
        (inline-kb [[(btn "📋 Каталог" "cat:menu")]])))))

;; ── Conversation state (in-memory, simple) ───────────

(defonce ^:private conv-state (atom {}))

(defn- set-state! [chat-id state]
  (swap! conv-state assoc chat-id state))

(defn- get-state [chat-id]
  (get @conv-state chat-id))

(defn- clear-state! [chat-id]
  (swap! conv-state dissoc chat-id))

(defn cleanup-stale-state!
  "Remove conv-state entries older than 24h + trim webhook dedup set.
   Called from scheduler."
  []
  ;; Conv-state: no timestamps, so just cap total size (stale entries from idle chats)
  (let [cnt (count @conv-state)]
    (when (> cnt 200)
      (reset! conv-state {})
      (println "Conv-state cleared:" cnt "entries")))
  ;; Webhook dedup: trim to last 500
  (let [cnt (count @recent-updates)]
    (when (> cnt 800)
      (swap! recent-updates (fn [s] (set (take-last 500 (sort s)))))
      (println "Webhook dedup trimmed:" cnt "→ 500"))))

;; ── Rental entry flow ─────────────────────────────────

(defn- rental-select-client [chat-id msg-id page]
  (let [all     (models/list-persons "client")
        total   (count all)
        clients (take PAGE_SIZE (drop (* page PAGE_SIZE) all))
        text    "💰 <b>Внести аренду</b>\nШаг 1: Выберите клиента"
        btns    (vec (concat
                      (mapv (fn [c] [(btn (:name c) (str "rental:client:" (:id c)))])
                            clients)
                      (nav-buttons "rental_cl" page total)
                      [[(btn "➕ Новый клиент" "rental:newclient")]
                       [(btn "◀️ Меню" "menu")]]))]
    (if msg-id
      (edit-message chat-id msg-id text (inline-kb btns))
      (send-message chat-id text (inline-kb btns)))))

(defn- rental-select-bike [chat-id msg-id client-id page]
  (let [all   (models/list-bikes "available")
        total (count all)
        bikes (take PAGE_SIZE (drop (* page PAGE_SIZE) all))
        client-name (or (:name (models/get-person client-id)) (str "#" client-id))
        text  (str "💰 <b>Внести аренду</b>\nШаг 2: Выберите байк\n👤 " client-name)
        btns  (vec (concat
                    (mapv (fn [b] [(btn (str (status-emoji (:status b)) " " (:name b))
                                       (str "rental:bike:" client-id ":" (:id b)))])
                          bikes)
                    (nav-buttons (str "rental_bk:" client-id) page total)
                    [[(btn "◀️ Назад" "rental:start")]]))]
    (if msg-id
      (edit-message chat-id msg-id text (inline-kb btns))
      (send-message chat-id text (inline-kb btns)))))

(defn- rental-enter-amount [chat-id msg-id client-id bike-id]
  (set-state! chat-id {:step :awaiting-rental-amount
                        :client_id client-id
                        :bike_id bike-id})
  (let [text "💰 <b>Внести аренду</b>\nШаг 3: Введите сумму в тысячах.\n\nПример: <code>150</code> = 150 000"]
    (if msg-id
      (edit-message chat-id msg-id text nil)
      (send-message chat-id text))))

(declare bind-partner!)

(defn- create-client-from-text!
  "Парсит 'Имя|Телефон|P5' и создаёт клиента. Возвращает результат для отправки.
   after-buttons — inline кнопки после успешного создания."
  [chat-id text after-buttons]
  (let [parts      (str/split text #"\|" 3)
        cname      (str/trim (first parts))
        phone      (when (second parts) (str/trim (second parts)))
        p-field    (when (nth parts 2 nil) (str/trim (nth parts 2)))
        p-num      (when (and p-field (str/starts-with? (str/upper-case p-field) "P"))
                     (safe-long (subs p-field 1) nil))
        partner-id (when p-num
                     (let [qr (models/get-qrcode-by-code (str p-num))]
                       (or (:partner_id qr)
                           (when (models/get-person p-num) p-num))))]
    (if (str/blank? cname)
      (send-message chat-id "Введите хотя бы имя клиента")
      (do
        (models/create-person! {:name cname :phone phone :role "client"})
        (let [new-client (models/get-last-created-person cname "client")]
          (when (and partner-id new-client)
            (models/create-rental! {:client_id  (:id new-client)
                                    :amount     0
                                    :partner_id partner-id
                                    :notes      "WA partner attribution"}))
          (clear-state! chat-id)
          (send-message chat-id
            (str "✅ Клиент <b>" cname "</b> добавлен!"
                 (when partner-id
                   (let [partner (models/get-person partner-id)]
                     (str "\n🤝 Партнёр: <b>" (or (:name partner) (str "#" partner-id)) "</b>")))
                 (when (and p-num (nil? partner-id))
                   (str "\n⚠️ Партнёр P" p-num " не найден — клиент создан без привязки")))
            (inline-kb after-buttons)))))))

;; ── Client storefront ───────────────────────────────

(defn- client-category-menu
  "Начальное меню для клиента — только непустые категории"
  [chat-id]
  (let [cnt    (fn [cats] (count (models/list-bikes "available" cats)))
        items  [["bikes" "🏍 Байки" #{"bike" "scooter"}]
                ["bicycles" "🚲 Велосипеды" #{"bicycle"}]
                ["cars" "🚗 Авто" #{"car"}]]
        rows   (filterv some?
                 (mapv (fn [[key label cats]]
                         (let [n (cnt cats)]
                           (when (pos? n)
                             [(btn (str label " (" n ")") (str "cat:" key))])))
                       items))]
    (send-message chat-id
      (str "🚗 <b>Karma Rent</b> — Нячанг\n\n"
           "Выберите категорию или напишите что ищете — я подскажу! 💬")
      (inline-kb (conj rows
                   [(btn "💬 Помочь подобрать под ваши цели" "ai:consult")]
                   [(btn "📋 Мои бронирования" "mybooking")])))))

(defn- client-storefront
  "Каталог свободных транспортов по категории"
  [chat-id category]
  (let [cats (case category
               "bikes" #{"bike" "scooter"}
               "cars" #{"car"}
               "bicycles" #{"bicycle"}
               nil)
        bikes (models/list-bikes "available" cats)
        title (case category
                "bikes" "🏍 Байки и скутеры"
                "cars" "🚗 Авто"
                "bicycles" "🚲 Велосипеды"
                "🚗 Транспорт")]
    (if (empty? bikes)
      (send-message chat-id
        (str title "\n\nК сожалению, сейчас нет свободного транспорта в этой категории.\nПопробуйте позже!")
        (inline-kb [[(btn "◀️ Назад" "cat:menu")]]))
      (do
        ;; Заголовок
        (send-message chat-id
          (str "<b>" title "</b>\n\n"
               "Доступно: " (count bikes)))
        ;; Все карточки параллельно
        (let [futs (mapv (fn [b]
                           (future
                             (let [has-pending (models/bike-has-pending-booking? (:id b))
                                   e (get cat-emoji (:category b) "🚗")
                                   caption (str e " <b>" (:name b) "</b>"
                                                "\n💰 " (or (:daily_rate b) "?") " тыс/день"
                                                (when (:monthly_rate b)
                                                  (str " • " (:monthly_rate b) " тыс/мес"))
                                                (when has-pending "\n🔴 Есть заявка")
                                                (when (:notes b) (str "\n📝 " (:notes b)))
                                                "\n\n" (deep-link (str "bike_" (:id b)) "📋 Подробнее / Забронировать"))]
                                   (if (:photo_url b)
                                     (send-photo chat-id (:photo_url b) caption nil)
                                     (send-message chat-id caption)))))
                         bikes)]
          ;; Дождаться все
          (run! deref futs))
        ;; Кнопки навигации
        (send-message chat-id "☝️ Выберите транспорт или попросите помощь"
          (inline-kb [[(btn "💬 Помочь подобрать" "ai:consult")]
                       [(btn "◀️ Назад в меню" "cat:menu")]]))))))

(defn- client-bike-detail
  "Детальная карточка байка для клиента"
  [chat-id bike-id]
  (if-let [b (models/get-bike bike-id)]
    (if (= "available" (:status b))
      (let [has-pending (models/bike-has-pending-booking? bike-id)
            text (str "🏍 <b>" (:name b) "</b>"
                      (when (:plate_number b) (str " [" (:plate_number b) "]"))
                      "\n\n💰 Цена: <b>" (or (:daily_rate b) "—") " тыс/день</b>"
                      (when (:monthly_rate b)
                        (str "\n💰 Помесячно: <b>" (:monthly_rate b) " тыс/мес</b>"))
                      (when has-pending "\n\n🔴 <i>Этот байк предварительно забронирован другим клиентом, но ещё не подтверждён. Вы тоже можете оставить заявку.</i>")
                      (when (:notes b) (str "\n📝 " (:notes b)))
                      "\n\nВыберите тариф или задайте вопрос 💬")
            kb   (inline-kb
                   (cond-> [[(btn (str "📅 Посуточно — " (or (:daily_rate b) "?") " тыс/день")
                                  (str "cbook:" (:id b) ":daily"))]]
                     (:monthly_rate b)
                     (conj [(btn (str "📆 Помесячно — " (:monthly_rate b) " тыс/мес")
                                 (str "cbook:" (:id b) ":monthly"))])
                     true
                     (conj [(btn "💬 Спросить про этот байк" (str "askbike:" (:id b)))])
                     true
                     (conj [(btn "◀️ Назад в каталог" "cat:menu")])))]
        (if (:photo_url b)
          (send-photo chat-id (:photo_url b) text kb)
          (send-message chat-id text kb)))
      (send-message chat-id
        (str "К сожалению, этот байк уже занят.\n\n"
             (deep-link "catalog" "Посмотреть другие"))))
    (send-message chat-id
      (str "Байк не найден.\n\n" (deep-link "catalog" "К каталогу")))))

(defn- ensure-client-person!
  "Находит или создаёт person с role=client по telegram_id.
   Handles race condition: if INSERT fails (UNIQUE violation), fetches existing.
   Saves all available Telegram profile data on creation."
  [from]
  (let [tid (str (:id from))]
    (or (models/get-person-by-telegram tid)
        (do (try
              (models/create-person!
                {:name          (or (:first_name from) "Client")
                 :telegram_id   tid
                 :role          "client"
                 :username      (:username from)
                 :last_name     (:last_name from)
                 :language_code (:language_code from)})
              (catch Exception _ nil)) ;; UNIQUE violation = already exists
            (models/get-person-by-telegram tid)))))

(defn- notify-operators-new-booking!
  "Уведомить всех операторов о новой бронировании"
  [booking-id]
  (let [booking (models/get-booking booking-id)
        ops     (models/list-operators)]
    (doseq [op ops]
      (when-let [op-chat (some-> (:telegram_id op) parse-long)]
        (let [rt (or (:rental_type booking) "daily")
              price-label (if (= "monthly" rt)
                            (str (or (:bike_monthly_rate booking) (:bike_rate booking) "?") " тыс/мес")
                            (str (or (:bike_rate booking) "?") " тыс/день"))]
          (send-message op-chat
            (str "🆕 <b>Новая бронь!</b>\n\n"
                 "👤 " (or (:client_name booking) "?")
                 (when (:client_telegram_id booking)
                   (str " — <a href=\"tg://user?id=" (:client_telegram_id booking) "\">написать в Telegram</a>"))
                 "\n🏍 " (or (:bike_name booking) "?") " — " price-label
                 "\n📋 Тариф: " (if (= "monthly" rt) "помесячный" "посуточный")
                 "\n\n📋 Бронь #" booking-id))
          ;; Фото байка + готовое сообщение для пересылки клиенту
          (when (:bike_photo booking)
            (send-photo op-chat (:bike_photo booking)
              (str "Привет! Ваш байк <b>" (or (:bike_name booking) "?") "</b> ждёт вас 🏍\n"
                   "Цена: " price-label "\n\n"
                   "Напишите когда будете готовы забрать!")
              nil)))))))

(defn- client-book-bike!
  "Клиент бронирует байк с выбранным тарифом"
  [chat-id from bike-id rental-type]
  (if-let [b (models/get-bike bike-id)]
    ;; Atomic check: try to set bike status to 'booked' only if 'available'
    (if (models/try-book-bike! bike-id)
      (let [person  (ensure-client-person! from)
            active  (models/get-active-booking-by-client (:id person))]
        (if active
          (do ;; Atomic rollback: only revert if still 'booked' (prevents race)
            (db/exec! "UPDATE bike SET status = 'available' WHERE id = ? AND status = 'booked'" bike-id)
            (send-message chat-id
              (str "У вас уже есть активная бронь на <b>" (:bike_name active) "</b>.\n"
                   "Дождитесь ответа оператора.")
              (inline-kb [[(btn "📋 Мои бронирования" "mybooking")]
                           [(btn "📋 Каталог" "cat:menu")]])))
          (let [rt       (or rental-type "daily")
                price    (if (= "monthly" rt)
                           (or (:monthly_rate b) (:daily_rate b))
                           (:daily_rate b))
                price-label (if (= "monthly" rt)
                              (str price " тыс/мес")
                              (str price " тыс/день"))
                ;; Track partner attribution from client's QR referral
                partner    (models/get-client-partner (:id person))
                booking-id (models/create-booking! {:client_id  (:id person)
                                                    :bike_id    bike-id
                                                    :rental_type rt
                                                    :partner_id (when partner (:id partner))})]
            (send-message chat-id
              (str "✅ <b>Забронировано!</b>\n\n"
                   "🏍 " (:name b) " — " price-label
                   "\n📋 Тариф: " (if (= "monthly" rt) "помесячный" "посуточный")
                   "\n\nНаш оператор напишет вам в течение нескольких минут.\n"
                   "Спасибо за выбор Karma Rent! 🙏")
              (inline-kb [[(btn "📋 Каталог" "cat:menu")]]))
            (notify-operators-new-booking! booking-id))))
      (send-message chat-id
        (str "К сожалению, этот байк уже занят.\n\n"
             (deep-link "catalog" "Посмотреть другие"))))
    (send-message chat-id
      (str "Байк не найден.\n\n" (deep-link "catalog" "К каталогу")))))

;; ── Start payload router ────────────────────────────

(defn- parse-start-payload
  "Роутер для /start PAYLOAD deep links"
  [chat-id from payload]
  (cond
    ;; Client: каталог
    (= payload "catalog")
    (client-category-menu chat-id)

    ;; Client: каталог через QR-реферал (ref_PARTNER_ID)
    (str/starts-with? payload "ref_")
    (let [partner-id (safe-long (subs payload 4) nil)
          partner    (when partner-id (models/get-person partner-id))
          person     (ensure-client-person! from)]
      ;; Привязать партнёра к клиенту через фиктивную rental запись (amount=0)
      ;; чтобы get-client-partner работал при последующих бронированиях
      ;; Только если партнёр реально существует в БД
      (when (and partner person
                 (not (models/get-client-partner (:id person))))
        (models/create-rental! {:client_id  (:id person)
                                :amount     0
                                :partner_id (:id partner)
                                :notes      "QR referral attribution"}))
      (client-category-menu chat-id))

    ;; Client: детали байка
    (str/starts-with? payload "bike_")
    (when-let [id (safe-long (subs payload 5) nil)]
      (client-bike-detail chat-id id))

    ;; Client: бронирование (legacy deep link)
    (str/starts-with? payload "book_")
    (when-let [id (safe-long (subs payload 5) nil)]
      (client-bike-detail chat-id id))

    ;; Operator: меню
    (= payload "adm_menu")
    (when (get-operator (:id from))
      (main-menu chat-id (:id from)))

    ;; Operator: транспорт меню
    (= payload "adm_bikes")
    (when (get-operator (:id from))
      (transport-menu chat-id nil))

    ;; Operator: байки пагинация
    (re-matches #"adm_bikes_(\d+)" payload)
    (when (get-operator (:id from))
      (let [[_ pg] (re-matches #"adm_bikes_(\d+)" payload)]
        (bikes-list chat-id nil (dec (parse-long pg)))))

    ;; Admin: партнёры список
    (= payload "adm_partners")
    (when (get-admin (:id from))
      (partners-list chat-id nil 0))

    ;; Admin: партнёры пагинация
    (re-matches #"adm_partners_(\d+)" payload)
    (when (get-admin (:id from))
      (let [[_ pg] (re-matches #"adm_partners_(\d+)" payload)]
        (partners-list chat-id nil (dec (parse-long pg)))))

    ;; Operator: клиенты список
    (= payload "adm_clients")
    (when (get-operator (:id from))
      (clients-list chat-id nil 0))

    ;; Operator: клиенты пагинация
    (re-matches #"adm_clients_(\d+)" payload)
    (when (get-operator (:id from))
      (let [[_ pg] (re-matches #"adm_clients_(\d+)" payload)]
        (clients-list chat-id nil (dec (parse-long pg)))))

    ;; Operator: bike detail
    (re-matches #"adm_b(\d+)" payload)
    (when (get-operator (:id from))
      (let [[_ id-str] (re-matches #"adm_b(\d+)" payload)]
        (bike-detail chat-id nil (parse-long id-str) (:id from))))

    ;; Operator: bike status menu
    (re-matches #"adm_bs(\d+)" payload)
    (when (get-operator (:id from))
      (let [[_ id-str] (re-matches #"adm_bs(\d+)" payload)]
        (bike-status-menu chat-id nil (parse-long id-str))))

    ;; Operator: oil change — выбор даты
    (re-matches #"adm_bo(\d+)" payload)
    (when (get-operator (:id from))
      (let [[_ id-str] (re-matches #"adm_bo(\d+)" payload)
            id (parse-long id-str)
            b (models/get-bike id)]
        (send-message chat-id
          (str "🛢 <b>Замена масла</b> — " (:name b)
               "\n\nВыберите дату замены:")
          (inline-kb [[(btn "📅 Сегодня" (str "bike:oilnow:" id))]
                      [(btn "✏️ Указать дату" (str "bike:oilask:" id))]
                      [(btn "◀️ Назад" (str "bike:detail:" id))]]))))

    ;; Admin only: delete bike — confirmation
    (re-matches #"adm_bdel(\d+)" payload)
    (when-let [op (get-admin (:id from))]
      (let [[_ id-str] (re-matches #"adm_bdel(\d+)" payload)
            bike-id (parse-long id-str)]
        (when-let [b (models/get-bike bike-id)]
          (send-message chat-id
            (str "⚠️ <b>Удалить байк?</b>\n\n"
                 "🏍 " (:name b)
                 (when (:plate_number b) (str " [" (:plate_number b) "]"))
                 "\n\nЭто действие необратимо!")
            (inline-kb [[(btn "🗑 Да, удалить" (str "bike:del:confirm:" bike-id))
                         (btn "❌ Отмена" (str "bike:" bike-id))]])))))

    ;; Admin: partner detail
    (re-matches #"adm_p(\d+)" payload)
    (when (get-admin (:id from))
      (let [[_ id-str] (re-matches #"adm_p(\d+)" payload)]
        (partner-detail chat-id nil (parse-long id-str))))

    ;; Admin: partner history
    (re-matches #"adm_ph(\d+)" payload)
    (when (get-admin (:id from))
      (let [[_ id-str] (re-matches #"adm_ph(\d+)" payload)
            pid (parse-long id-str)
            history (models/partner-rental-history pid 20)
            text (str "📋 <b>Все операции партнёра</b>\n\n"
                      (if (empty? history) "Нет операций"
                        (str/join "\n"
                          (map (fn [r]
                                 (str (:date r) " | "
                                      (or (:client_name r) "?") " | "
                                      (:amount r) " тыс"
                                      (when (:bike_name r) (str " | " (:bike_name r)))))
                               history)))
                      "\n\n" (deep-link (str "adm_p" pid) "Назад")
                      "\n" (deep-link "adm_menu" "Меню"))]
        (send-message chat-id text)))

    ;; Operator: client detail
    (re-matches #"adm_c(\d+)" payload)
    (when (get-operator (:id from))
      (let [[_ id-str] (re-matches #"adm_c(\d+)" payload)
            cid      (parse-long id-str)]
        (when-let [c (models/get-person cid)]
          (let [bookings (models/client-bookings cid)
                rentals  (models/client-rentals cid)
                partner  (models/get-client-partner cid)]
            (send-message chat-id
              (str "👤 <b>" (:name c) "</b>"
                   (when (:phone c) (str "\n📱 " (:phone c)))
                   (when (:telegram_id c)
                     (str "\n💬 <a href=\"tg://user?id=" (:telegram_id c) "\">Telegram</a>"))
                   (when partner
                     (str "\n🤝 Партнёр: " (:name partner)))
                   "\n"
                   (when (seq bookings)
                     (str "\n📦 <b>Бронирования</b> (" (count bookings) ")\n"
                          (str/join "\n"
                            (map (fn [b]
                                   (str "  " (case (:status b) "pending" "🟡" "confirmed" "✅" "cancelled" "❌" "completed" "🏁" "❓")
                                        " " (or (:bike_name b) "?") " — " (or (:bike_rate b) "?") " тыс"))
                                 (take 5 bookings)))))
                   (when (seq rentals)
                     (str "\n\n💰 <b>Аренды</b> (" (count rentals) ")\n"
                          (str/join "\n"
                            (map (fn [r]
                                   (str "  " (:date r) " — " (:amount r) " тыс"
                                        (when (:bike_name r) (str " (" (:bike_name r) ")"))))
                                 (take 5 rentals)))))
                   "\n\n" (deep-link "adm_clients" "Назад")
                   "\n" (deep-link "adm_menu" "Меню")))))))

    ;; Operator: return bike (complete booking)
    (re-matches #"adm_ret(\d+)" payload)
    (when (get-operator (:id from))
      (let [[_ id-str] (re-matches #"adm_ret(\d+)" payload)
            booking-id (parse-long id-str)
            bk         (models/complete-booking! booking-id (:id (get-operator (:id from))))]
        (if bk
          (do
            (send-message chat-id
              (str "🔑 <b>Байк возвращён!</b>\n\n"
                   "🏍 " (or (:bike_name bk) "?") " → свободен\n"
                   "👤 " (or (:client_name bk) "?")
                   "\n\n" (deep-link (str "adm_b" (:bike_id bk)) "К байку")
                   "\n" (deep-link "adm_menu" "Меню")))
            ;; Уведомить клиента
            (when-let [client-chat (some-> (:client_telegram_id bk) parse-long)]
              (send-message client-chat
                (str "🔑 Аренда <b>" (or (:bike_name bk) "") "</b> завершена.\n"
                     "Спасибо за выбор Karma Rent! 🙏\n\n"
                     (deep-link "catalog" "Арендовать снова")))))
          (send-message chat-id "❌ Бронь не найдена"
            (inline-kb [[(btn "📋 Брони" "bookings:list") (btn "◀️ Меню" "menu")]])))))

    ;; Operator: bookings list
    (= payload "adm_bookings")
    (when (get-operator (:id from))
      (bookings-list chat-id nil))

    ;; QR code fallback (partner binding)
    :else
    (bind-partner! chat-id from payload)))

;; ── Handle free text ─────────────────────────────────

(defn- handle-text-input [chat-id text from]
  (let [state (get-state chat-id)]
    ;; Universal cancel — works in any text-input state
    (if (and (:step state) (or (= text "/cancel") (= text "/menu")))
      (do (clear-state! chat-id)
          (if (get-operator (get from :id))
            (main-menu chat-id (get from :id))
            (send-message chat-id "❌ Отменено.\n/start — начало")))
    (case (:step state)
      ;; Режим клиентского просмотра — AI-консультант
      :client-preview
      (if (= text "/menu")
        (do (clear-state! chat-id)
            (main-menu chat-id (get from :id)))
        (do
          (send-typing chat-id)
          (let [ai-response (ai/consult chat-id text)]
            (send-message chat-id
              (str "🤖 " ai-response)
              (inline-kb [[(btn "📋 Каталог" "cat:menu")
                           (btn "◀️ Выйти" "cat:menu")]])))))

      ;; AI-консультация (общая — подбор по запросу)
      :ai-consult
      (if (= text "/catalog")
        (do (clear-state! chat-id)
            (client-category-menu chat-id))
        (do
          (send-typing chat-id)
          (let [ai-response (ai/consult chat-id text)]
            (send-message chat-id
              (str "🤖 " ai-response)
              (inline-kb [[(btn "📋 Каталог" "cat:menu")]
                           [(btn "💬 Ещё вопрос" "ai:consult")]])))))

      ;; Клиент спрашивает про конкретный байк
      :asking-about-bike
      (if (= text "/catalog")
        (do (clear-state! chat-id)
            (client-category-menu chat-id))
        (let [bike-id (:bike_id state)
              b       (models/get-bike bike-id)]
          (if b
            (do
              (send-typing chat-id)
            (let [context (str "Клиент спрашивает про конкретный байк: "
                               (:name b) " (" (:category b) ")"
                               ", цена " (or (:daily_rate b) "?") "k/день"
                               (when (:monthly_rate b) (str ", " (:monthly_rate b) "k/мес"))
                               (when (:notes b) (str ". " (:notes b)))
                               ". Вопрос клиента: " text)
                  ai-response (ai/consult chat-id context)]
              (send-message chat-id
                (str "🤖 " ai-response)
                (inline-kb [[(btn "❓ Ещё вопрос" (str "askbike:" bike-id))]
                             [(btn (str "📅 Забронировать " (:name b))
                                   (str "cbook:" bike-id ":daily"))]
                             [(btn "📋 Каталог" "cat:menu")]]))))
          (do (clear-state! chat-id)
              (send-message chat-id "Байк не найден."
                (inline-kb [[(btn "📋 К каталогу" "cat:menu")]]))))))

      ;; Добавление байка: ожидаем "Название|Номер|Цена"
      :awaiting-bike-info
      (let [parts (str/split text #"\|" 3)
            name  (str/trim (first parts))
            plate (when (second parts) (str/trim (second parts)))
            rate  (when (nth parts 2 nil)
                    (try (Double/parseDouble (str/trim (nth parts 2)))
                         (catch Exception _ nil)))
            cat   (:category state)]
        (if (str/blank? name)
          (send-message chat-id "Введите хотя бы название")
          (do
            (models/create-bike! {:name name :plate_number plate :daily_rate rate :category cat})
            (clear-state! chat-id)
            (send-message chat-id
              (str "✅ <b>" name "</b> добавлен!")
              (inline-kb [[(btn "🚗 Транспорт" "transport:menu")]
                           [(btn "◀️ Меню" "menu")]])))))

      ;; Ввод даты замены масла
      :awaiting-oil-date
      (let [bike-id (:bike_id state)
            date-str (str/trim text)]
        (if (re-matches #"\d{4}-\d{2}-\d{2}" date-str)
          (try
            (java.time.LocalDate/parse date-str)
            (models/update-bike! bike-id {:last_oil_change date-str})
            (clear-state! chat-id)
            (send-message chat-id (str "✅ Масло: дата установлена — " date-str))
            (bike-detail chat-id nil bike-id (get from :id))
            (catch Exception _
              (send-message chat-id "❌ Неверная дата. Формат: <code>2026-02-05</code>")))
          (send-message chat-id "❌ Неверный формат. Введите дату: <code>2026-02-05</code> (ГГГГ-ММ-ДД)")))

      ;; Ввод суммы аренды
      :awaiting-rental-amount
      (let [amount (try (Double/parseDouble (str/trim text)) (catch Exception _ nil))]
        (if amount
          (let [{:keys [client_id bike_id]} state
                client (models/get-person client_id)
                bike   (models/get-bike bike_id)]
            (set-state! chat-id (assoc state :amount amount))
            (send-message chat-id
              (str "💰 <b>Внести аренду</b>\nШаг 4: Тип транзакции\n\n"
                   "👤 " (or (:name client) "?") "\n"
                   "🏍 " (or (:name bike) "?") "\n"
                   "💵 " amount " тыс\n\n"
                   "Выберите тип:")
              (inline-kb [[(btn (str "💰 Доход (в " (share-pct-label) ")") "rental:type:revenue")
                           (btn (str "🔧 Сервис (не в " (share-pct-label) ")") "rental:type:service")]
                          [(btn "◀️ Назад" "rental:start")]])))
          (send-message chat-id "Введите сумму в тысячах.\n\nПример: <code>150</code> = 150 000")))

      ;; Ввод данных нового клиента (из rental flow)
      :awaiting-new-client
      (create-client-from-text! chat-id text
        [[(btn "💰 Записать аренду" "rental:start")]
         [(btn "◀️ Меню" "menu")]])

      ;; Ввод диапазона QR-кодов по номерам партнёров
      :awaiting-qr-range
      (let [parts (str/split (str/trim text) #"[-–— ]+")
            from-n (safe-long (first parts) nil)
            to-n   (safe-long (second parts) nil)]
        (if (and from-n to-n (pos? from-n) (<= from-n to-n) (<= (- to-n from-n) 50))
          (do (clear-state! chat-id)
              (qr-generate-range! chat-id from-n to-n "telegram"))
          (send-message chat-id "Введите диапазон: <code>1-10</code> (макс 50 штук)")))

      :awaiting-wa-qr-range
      (let [parts (str/split (str/trim text) #"[-–— ]+")
            from-n (safe-long (first parts) nil)
            to-n   (safe-long (second parts) nil)]
        (if (and from-n to-n (pos? from-n) (<= from-n to-n) (<= (- to-n from-n) 50))
          (do (clear-state! chat-id)
              (qr-generate-range! chat-id from-n to-n "whatsapp"))
          (send-message chat-id "Введите диапазон: <code>1-10</code> (макс 50 штук)")))

      ;; Ввод данных нового клиента (standalone)
      :awaiting-new-client-standalone
      (create-client-from-text! chat-id text
        [[(btn "👥 Клиенты" "clients:list")]
         [(btn "◀️ Меню" "menu")]])

      ;; Дефолт: попробовать /start payload (deep links), иначе команды/меню
      (if-let [payload (when (and text (str/starts-with? text "/start "))
                         (subs text 7))]
        (parse-start-payload chat-id from payload)
        (cond
          (= text "/start")
          (let [tid (get from :id)]
            (ai/clear-conversation! chat-id)
            (cond
              (get-operator tid)  (main-menu chat-id tid)
              (get-partner tid)   (partner-self-menu chat-id (get-partner tid))
              :else
              (do
                (send-message chat-id
                  (str "👋 <b>Добрый день!</b>\n\n"
                       "Я — бот Karma Rent, аренда транспорта в Нячанге 🏍\n\n"
                       "Помогу подобрать байк, скутер или авто под ваши цели!\n"
                       "Напишите что ищете или выберите категорию ниже 👇"))
                (client-category-menu chat-id))))
          (= text "/menu")     (when (get-operator (get from :id)) (main-menu chat-id (get from :id)))
          (= text "/bikes")    (when (get-operator (get from :id)) (transport-menu chat-id nil))
          (= text "/partners") (when (get-admin (get from :id)) (partners-list chat-id nil 0))
          (= text "/clients")  (when (get-operator (get from :id)) (clients-list chat-id nil 0))
          (= text "/stats")    (when (get-operator (get from :id)) (stats-summary chat-id nil nil (get from :id)))
          (= text "/rental")   (when (get-operator (get from :id)) (rental-select-client chat-id nil 0))
          (= text "/catalog")  (client-category-menu chat-id)
          (= text "/mybooking") (client-my-bookings chat-id (get from :id))
          (str/starts-with? (or text "") "/testai")
          (when (get-operator (get from :id))
            (let [query (str/trim (subs text (min (count text) 7)))]
              (if (str/blank? query)
                (send-message chat-id "Использование: <code>/testai что есть для города?</code>\nТестирует AI-консультанта от лица клиента.")
                (do
                  (send-typing chat-id)
                  (let [ai-response (ai/consult chat-id query)]
                    (send-message chat-id
                      (str "🤖 <b>[ТЕСТ AI]</b>\n\n" ai-response
                           "\n\n<i>Режим: " (if (ai/enabled?) "Claude API" "Demo (без ключа)") "</i>")
                      (inline-kb [[(btn "📋 Каталог" "cat:menu")
                                   (btn "🔄 Ещё тест" "testai:prompt")]])))))))
          (= text "/mystats")  (when-let [p (get-partner (get from :id))]
                                 (partner-self-menu chat-id p))
          (= text "/help")
          (let [tid (get from :id)
                is-op (get-operator tid)]
            (send-message chat-id
              (str "📖 <b>Karma Rent — справка</b>\n\n"
                   "🛵 <b>Для клиентов</b>\n"
                   "/start — главное меню\n"
                   "/catalog — каталог транспорта\n"
                   "/mybooking — мои бронирования\n"
                   "/cancel — отменить ввод\n\n"
                   "💬 Напишите что ищете — AI-помощник подберёт байк!\n"
                   (when is-op
                     (str "\n👨‍💼 <b>Для операторов</b>\n"
                          "/menu — меню\n"
                          "/bikes — транспорт\n"
                          "/clients — клиенты\n"
                          "/stats — статистика\n"
                          "/rental — записать аренду\n")))
              (inline-kb [[(btn "📋 Каталог" "cat:menu")]])))
          :else
          (let [tid (get from :id)]
            (cond
              (get-operator tid)  (main-menu chat-id tid)
              (get-partner tid)   (partner-self-menu chat-id (get-partner tid))
              :else
              ;; AI-консультант для клиентов
              (do
                (send-typing chat-id)
                (let [ai-response (ai/consult chat-id text)]
                  (send-message chat-id
                    (str "🤖 " ai-response)
                    (inline-kb [[(btn "📋 Каталог" "cat:menu")]]))))))))))))

;; ── Partner binding (from Phase 1) ───────────────────

(defn- bind-partner! [chat-id from qr-code]
  (let [telegram-id (str (:id from))
        name        (or (:first_name from) "Partner")
        qr          (models/get-qrcode-by-code qr-code)]
    (cond
      (nil? qr)
      (send-message chat-id (str "❌ QR-код " qr-code " не найден.\nОбратитесь к администратору для получения правильного кода."))

      (:partner_id qr)
      (send-message chat-id "⚠️ Этот QR-код уже привязан к другому партнёру.\nОбратитесь к администратору для получения нового кода.")

      :else
      (let [person (or (models/get-person-by-telegram telegram-id)
                       (do (models/create-person!
                            {:name        name
                             :telegram_id telegram-id
                             :role        "partner"})
                           (models/get-person-by-telegram telegram-id)))]
        ;; Upgrade role to partner if was client/other
        (when (and person (not= "partner" (:role person)))
          (models/update-person! (:id person) {:role "partner"}))
        (if (models/activate-qrcode! qr-code (:id person))
          (send-message chat-id
            (str "🎉 <b>Добро пожаловать в Karma Rent!</b>\n\n"
                 "✅ QR-код <b>" qr-code "</b> привязан к вашему аккаунту.\n\n"
                 "Вы теперь <b>партнёр</b> Karma Rent:\n"
                 "• 💰 <b>" (share-pct-label) "</b> с каждой аренды от ваших клиентов\n"
                 "• 📊 Статистика и операции — прямо в этом боте\n"
                 "• 🔗 Клиенты сканируют ваш QR → попадают к оператору с вашей ссылкой\n\n"
                 "Нажмите кнопку ниже чтобы посмотреть вашу статистику 👇")
            (inline-kb [[(btn "📊 Моя статистика" "mystats")]]))
          ;; Race condition: someone else activated this QR between our check and activation
          (send-message chat-id "⚠️ Этот QR-код уже привязан к другому партнёру.\nОбратитесь к администратору для получения нового кода."))))))


;; ── Callback query handler ───────────────────────────

(defn- handle-callback [callback-query]
  (let [data       (get callback-query "data")
        msg        (get callback-query "message")
        chat-id    (get-in msg ["chat" "id"])
        msg-id     (get msg "message_id")
        cb-id      (get callback-query "id")
        from-id    (get-in callback-query ["from" "id"])
        parts      (str/split data #":")
        section    (first parts)
        ;; Operator-only sections require auth
        op-section #{"menu" "main" "transport" "bikes" "bike" "partners" "partner" "clients" "client"
                     "stats" "rental" "rental_cl" "rental_bk" "bookings" "bkng" "qr"}]

    (answer-callback cb-id)

    ;; Auth gate: operator-only callbacks require operator role
    (if (and (op-section section) (not (get-operator from-id)))
      nil ;; silently ignore — not an operator
      (case section
        "menu"     (do (clear-state! chat-id) (main-menu chat-id from-id))
        "main"     (do (clear-state! chat-id) (main-menu chat-id from-id))
        "noop"     nil

        "preview"  (let [action (second parts)]
                     (case action
                       "client" (do (set-state! chat-id {:step :client-preview})
                                    (ai/clear-conversation! chat-id)
                                    (send-message chat-id
                                      (str "👁 <b>Режим клиента</b>\n\n"
                                           "Вы видите бота глазами клиента.\n"
                                           "Напишите что-нибудь — AI-консультант ответит!\n\n"
                                           "<i>Режим: " (if (ai/enabled?) "Claude API" "Demo") "</i>")
                                      (inline-kb [[(btn "📋 Каталог" "cat:menu")]])))
                       nil))

        "transport" (let [action (second parts)]
                      (case action
                        "menu" (transport-menu chat-id msg-id)
                        nil))

        "bikes"    (let [action (second parts)]
                     (case action
                       "list" (do (when msg-id (delete-message chat-id msg-id))
                                  (bikes-list chat-id nil 0))
                       "cat"  (let [cat (nth parts 2 nil)]
                                (when msg-id (delete-message chat-id msg-id))
                                (bikes-list chat-id nil 0 cat))
                       nil))

        "bike"     (let [action (second parts)]
                     (case action
                       "add"    (edit-message chat-id msg-id
                                  "➕ <b>Добавить транспорт</b>\n\nВыберите категорию:"
                                  (inline-kb [[(btn "🏍 Мото" "bike:addcat:bike")]
                                              [(btn "🛵 Скутер" "bike:addcat:scooter")]
                                              [(btn "🚗 Авто" "bike:addcat:car")]
                                              [(btn "🚲 Велосипед" "bike:addcat:bicycle")]
                                              [(btn "◀️ Назад" "transport:menu")]]))
                       "addcat" (let [cat (nth parts 2 nil)]
                                  (set-state! chat-id {:step :awaiting-bike-info :category cat})
                                  (edit-message chat-id msg-id
                                    (str "➕ Введите данные:\n<code>Название|Номер|Цена</code>\n\n"
                                         "Пример: <code>Honda Air Blade 125|59F1-12345|150</code>\n"
                                         "Номер и цена — необязательно.")
                                    nil))
                       "status" (bike-status-menu chat-id msg-id (safe-long (nth parts 2 nil)))
                       "oil"    (let [id (safe-long (nth parts 2 nil))
                                      b (models/get-bike id)]
                                  (when b
                                    (edit-message chat-id msg-id
                                      (str "🛢 <b>Замена масла</b> — " (:name b)
                                           "\n\nВыберите дату замены:")
                                      (inline-kb [[(btn "📅 Сегодня" (str "bike:oilnow:" id))]
                                                  [(btn "✏️ Указать дату" (str "bike:oilask:" id))]
                                                  [(btn "◀️ Назад" (str "bike:detail:" id))]]))))
                       "oilnow" (let [id (safe-long (nth parts 2 nil))]
                                  (when (pos? id)
                                    (models/update-bike! id {:last_oil_change (str (java.time.LocalDate/now))})
                                    (edit-message chat-id msg-id (str "✅ Масло заменено (сегодня)") nil)
                                    (bike-detail chat-id nil id from-id)))
                       "oilask" (let [id (safe-long (nth parts 2 nil))]
                                  (when (pos? id)
                                    (set-state! chat-id {:step :awaiting-oil-date :bike_id id})
                                    (edit-message chat-id msg-id
                                      (str "🛢 Введите дату замены масла:\n\n"
                                           "Формат: <code>2026-02-05</code> (ГГГГ-ММ-ДД)")
                                      nil)))
                       "detail" (let [id (safe-long (nth parts 2 nil))]
                                  (when (pos? id) (bike-detail chat-id msg-id id from-id)))
                       "set"    (let [id     (safe-long (nth parts 2 nil))
                                      status (nth parts 3)]
                                  (when (and (pos? id) status)
                                    (models/update-bike! id {:status status})
                                    (bike-detail chat-id msg-id id from-id)))
                       "del"    (when (= "confirm" (nth parts 2 nil))
                                  (if-let [adm (get-admin from-id)]
                                    (let [id (safe-long (nth parts 3 nil))
                                          deleted (models/delete-bike! id (:id adm))]
                                      (if deleted
                                        (edit-message chat-id msg-id
                                          (str "🗑 Байк <b>" (:name deleted) "</b> удалён.")
                                          (inline-kb [[(btn "◀️ К байкам" "bikes:list")]]))
                                        (edit-message chat-id msg-id
                                          "❌ Не удалось удалить (байк в аренде или не найден)"
                                          (inline-kb [[(btn "◀️ Назад" "bikes:list")]]))))
                                    (edit-message chat-id msg-id
                                      "⛔ Удаление доступно только администратору."
                                      (inline-kb [[(btn "◀️ Назад" "bikes:list")]]))))
                       ;; default: bike:ID → detail
                       (when-let [id (safe-long action nil)]
                         (bike-detail chat-id msg-id id from-id))))

        "partners" (if (get-admin from-id)
                     (let [action (second parts)]
                       (case action
                         "list" (do (when msg-id (delete-message chat-id msg-id))
                                    (partners-list chat-id nil 0))
                         "p"    (partners-list chat-id nil (safe-long (nth parts 2 "0")))
                         nil))
                     (edit-message chat-id msg-id
                       "⛔ Партнёры доступны только администратору." nil))

        "partner"  (if-not (get-admin from-id)
                     (edit-message chat-id msg-id
                       "⛔ Партнёры доступны только администратору." nil)
                     (let [action (second parts)]
                     (if (= action "history")
                       (let [pid     (safe-long (nth parts 2 nil))
                             history (models/partner-rental-history pid 20)
                             text    (str "📋 <b>Все операции партнёра</b>\n\n"
                                         (if (empty? history) "Нет операций"
                                           (str/join "\n"
                                             (map (fn [r]
                                                    (str (:date r) " | "
                                                         (or (:client_name r) "?") " | "
                                                         (:amount r) " тыс"
                                                         (when (:bike_name r) (str " | " (:bike_name r)))))
                                                  history))))]
                         (edit-message chat-id msg-id text
                           (inline-kb [[(btn "◀️ Партнёр" (str "partner:" pid))]])))
                       (when-let [pid (safe-long action nil)]
                         (partner-detail chat-id msg-id pid)))))

        "clients"  (let [action (second parts)]
                     (case action
                       "list" (clients-list chat-id nil 0)
                       "p"    (clients-list chat-id nil (safe-long (nth parts 2 "0")))
                       nil))

        "client"   (let [action (second parts)]
                     (case action
                       "add" (do (set-state! chat-id {:step :awaiting-new-client-standalone})
                                 (edit-message chat-id msg-id
                                   "👤 Новый клиент:\n<code>Имя | Телефон | P номер</code>\n\nПример: <code>Миша|+84123456789|P5</code>\n\nТелефон (WhatsApp) и номер партнёра — не обязательны.\nP5 = клиент пришёл от партнёра #5."
                                   nil))
                       nil))

        "stats"    (case (second parts)
                     "summary" (stats-summary chat-id msg-id nil from-id)
                     "period"  (stats-summary chat-id msg-id (safe-long (nth parts 2 "0")) from-id)
                     nil)

        ;; QR codes management
        "qr"       (if (get-admin from-id)
                     (let [action (second parts)]
                       (case action
                         "list"     (qr-channel-list chat-id "telegram")
                         "wa_list"  (qr-channel-list chat-id "whatsapp")
                         "range"    (do (set-state! chat-id {:step :awaiting-qr-range})
                                       (send-message chat-id
                                         "📱 <b>Новые QR-коды Telegram</b>\n\nВведите диапазон номеров партнёров.\n\nПример: <code>1-10</code>\n\nБудут созданы QR с номерами 1, 2, 3... 10.\nКаждый номер = отдельный партнёр."))
                         "wa_range" (do (set-state! chat-id {:step :awaiting-wa-qr-range})
                                       (send-message chat-id
                                         "💬 <b>Новые QR-коды WhatsApp</b>\n\nВведите диапазон номеров партнёров.\n\nПример: <code>1-10</code>\n\nБудут созданы QR с номерами 1, 2, 3... 10.\nКаждый номер = отдельный партнёр."))
                         nil))
                     (edit-message chat-id msg-id
                       "⛔ QR-коды доступны только администратору." nil))

        ;; Rental flow
        "rental"   (let [action (second parts)]
                     (case action
                       "start"     (rental-select-client chat-id msg-id 0)
                       "client"    (rental-select-bike chat-id msg-id
                                     (safe-long (nth parts 2 nil)) 0)
                       "bike"      (rental-enter-amount chat-id msg-id
                                     (safe-long (nth parts 2 nil))
                                     (safe-long (nth parts 3 nil)))
                       "newclient" (do (set-state! chat-id {:step :awaiting-new-client})
                                      (edit-message chat-id msg-id
                                        "👤 Новый клиент:\n<code>Имя | Телефон | P номер</code>\n\nПример: <code>Миша|+84123456789|P5</code>\n\nТелефон (WhatsApp) и номер партнёра — не обязательны.\nP5 = клиент пришёл от партнёра #5."
                                        nil))
                       "type"      (let [tx-type (nth parts 2 "revenue")
                                         st      (get-state chat-id)]
                                     (when-let [amount (:amount st)]
                                       (let [{:keys [client_id bike_id]} st
                                             partner    (models/get-client-partner client_id)
                                             rental-data {:client_id        client_id
                                                          :amount           amount
                                                          :partner_id       (when partner (:id partner))
                                                          :bike_id          bike_id
                                                          :date             (str (java.time.LocalDate/now))
                                                          :transaction_type tx-type}]
                                         (models/create-rental! rental-data)
                                         (models/update-bike! bike_id {:status "rented"})
                                         (clear-state! chat-id)
                                         (let [client (models/get-person client_id)
                                               bike   (models/get-bike bike_id)]
                                           (edit-message chat-id msg-id
                                             (str "✅ Аренда записана!\n\n"
                                                  "Клиент: <b>" (:name client) "</b>\n"
                                                  "Байк: <b>" (:name bike) "</b>\n"
                                                  "Сумма: <b>" amount " тыс</b>\n"
                                                  "Тип: " (if (= "service" tx-type) "🔧 Сервис" "💰 Доход")
                                                  (when partner (str "\n🤝 Партнёр: " (:name partner)))
                                                  "\n\n" (deep-link "adm_menu" "Меню"))
                                             nil)))))
                       nil))

        ;; Rental pagination
        "rental_cl" (rental-select-client chat-id msg-id
                      (safe-long (nth parts 2 "0")))

        "rental_bk" (rental-select-bike chat-id msg-id
                      (safe-long (nth parts 1 nil))
                      (safe-long (nth parts 3 "0")))

      ;; Partner self-view callbacks
      "myops"    (let [from-id (get-in callback-query ["from" "id"])
                       partner (get-partner from-id)
                       pid     (if partner (:id partner) (some-> (second parts) parse-long))
                       history (models/partner-rental-history pid 20)
                       text    (str "📋 <b>Все твои операции</b>\n\n"
                                   (if (empty? history) "Пока нет операций"
                                     (str/join "\n"
                                       (map (fn [r]
                                              (str (if (= "service" (:transaction_type r)) "🔧 " "💰 ")
                                                   (:date r) " | "
                                                   (or (:client_name r) "?") " | "
                                                   (:amount r) " тыс"
                                                   (when (:bike_name r) (str " | " (:bike_name r)))))
                                            history))))]
                   (edit-message chat-id msg-id text
                     (inline-kb [[(btn "◀️ Назад" "mystats")]])))

      "mystats"  (let [from-id (get-in callback-query ["from" "id"])]
                   (when-let [p (get-partner from-id)]
                     (edit-message chat-id msg-id (build-partner-stats-text p)
                       (inline-kb [[(btn "📋 Все операции" (str "myops:" (:id p)))]
                                   [(btn "🔄 Обновить" "mystats")]]))))

      ;; Bookings management
      "bookings" (case (second parts)
                   "list" (bookings-list chat-id msg-id)
                   nil)

      "bkng"    (let [action     (second parts)
                      booking-id (safe-long (nth parts 2 nil))
                      from-id    (get-in callback-query ["from" "id"])
                      operator   (get-operator from-id)]
                  (when operator
                    (case action
                      "confirm"
                      (let [bk (models/confirm-booking! booking-id (:id operator))]
                        (if bk
                          (do
                            (let [rt (or (:rental_type bk) "daily")
                                  price (if (= "monthly" rt)
                                          (str (or (:bike_monthly_rate bk) (:bike_rate bk) "?") " тыс/мес")
                                          (str (or (:bike_rate bk) "?") " тыс/день"))]
                              (edit-message chat-id msg-id
                                (str "✅ <b>Бронь #" booking-id " подтверждена</b>\n\n"
                                     "👤 " (or (:client_name bk) "?")
                                     "\n🏍 " (or (:bike_name bk) "?") " — " price
                                     "\n📋 " (if (= "monthly" rt) "Помесячно" "Посуточно")
                                     "\n\n💰 Аренда записана, байк → «В аренде»")
                                nil))
                            (notify-client-booking-confirmed! bk))
                          (edit-message chat-id msg-id "❌ Бронь не найдена"
                            (inline-kb [[(btn "📋 Брони" "bookings:list") (btn "◀️ Меню" "menu")]]))))

                      "cancel"
                      (let [bk (models/get-booking booking-id)]
                        (if bk
                          (edit-message chat-id msg-id
                            (str "⚠️ <b>Отклонить бронь #" booking-id "?</b>\n\n"
                                 "👤 " (or (:client_name bk) "?")
                                 "\n🏍 " (or (:bike_name bk) "?")
                                 "\n\nКлиент получит уведомление об отмене.")
                            (inline-kb [[(btn "❌ Да, отклонить" (str "bkng:cancelok:" booking-id))
                                         (btn "◀️ Назад" "bookings:list")]]))
                          (edit-message chat-id msg-id "❌ Бронь не найдена"
                            (inline-kb [[(btn "📋 Брони" "bookings:list") (btn "◀️ Меню" "menu")]]))))

                      "cancelok"
                      (let [bk (models/cancel-booking! booking-id (:id operator))]
                        (if bk
                          (do
                            (edit-message chat-id msg-id
                              (str "❌ <b>Бронь #" booking-id " отклонена</b>\n\n"
                                   "👤 " (or (:client_name bk) "?")
                                   "\n🏍 " (or (:bike_name bk) "?") " → свободен")
                              nil)
                            (notify-client-booking-cancelled! bk))
                          (edit-message chat-id msg-id "❌ Бронь не найдена"
                            (inline-kb [[(btn "📋 Брони" "bookings:list") (btn "◀️ Меню" "menu")]]))))
                      nil)))

      ;; Client category menu (cat:bikes, cat:bicycles, cat:cars, cat:menu)
      "cat"     (let [action (second parts)]
                  (case action
                    "bikes"    (client-storefront chat-id "bikes")
                    "bicycles" (client-storefront chat-id "bicycles")
                    "cars"     (client-storefront chat-id "cars")
                    "menu"     (let [st (get-state chat-id)]
                                 (if (= :client-preview (:step st))
                                   (do (clear-state! chat-id)
                                       (main-menu chat-id from-id))
                                   (client-category-menu chat-id)))
                    nil))

      ;; AI consultation — клиент хочет помощь с выбором
      "ai"      (let [action (second parts)]
                  (case action
                    "consult" (do (set-state! chat-id {:step :ai-consult})
                                  (send-message chat-id
                                    (str "💬 <b>AI-консультант</b>\n\n"
                                         "Расскажите что ищете — я подберу лучший вариант!\n\n"
                                         "Например:\n"
                                         "• Нужен скутер для города на неделю\n"
                                         "• Что-нибудь недорогое для двоих\n"
                                         "• Мощный байк на месяц\n\n"
                                         "<i>Для возврата — /catalog</i>")))
                    nil))

      ;; Client: мои бронирования
      "mybooking" (client-my-bookings chat-id from-id)

      ;; Client: отменить свою pending бронь
      "cbcancel" (let [booking-id (safe-long (second parts) nil)
                       person     (models/get-person-by-telegram (str from-id))]
                   (if (and booking-id person)
                     (if-let [bk (models/client-cancel-booking! booking-id (:id person))]
                       (do
                         (edit-message chat-id msg-id
                           (str "❌ <b>Бронь отменена</b>\n\n"
                                "🏍 " (or (:bike_name bk) "?") " — теперь свободен.\n\n"
                                "Вы можете выбрать другой транспорт!")
                           (inline-kb [[(btn "📋 Каталог" "cat:menu")]
                                       [(btn "📋 Мои брони" "mybooking")]]))
                         ;; Уведомить операторов об отмене клиентом
                         (let [ops (models/list-operators)]
                           (doseq [op ops]
                             (when-let [op-chat (some-> (:telegram_id op) parse-long)]
                               (send-message op-chat
                                 (str "ℹ️ Клиент <b>" (or (:name person) "?") "</b> отменил бронь\n"
                                      "🏍 " (or (:bike_name bk) "?") " → свободен"))))))
                       (edit-message chat-id msg-id
                         "⚠️ Не удалось отменить бронь. Возможно, она уже подтверждена или отменена."
                         (inline-kb [[(btn "📋 Мои брони" "mybooking")]])))
                     (send-message chat-id "Ошибка. Попробуйте /mybooking")))

      ;; Client asks about a specific bike via AI
      "askbike" (let [bike-id (safe-long (second parts) nil)
                      b       (when bike-id (models/get-bike bike-id))]
                  (if b
                    (do (set-state! chat-id {:step :asking-about-bike :bike_id bike-id})
                        (send-message chat-id
                          (str "💬 <b>Спросите про " (:name b) "</b>\n\n"
                               "Напишите ваш вопрос — например:\n"
                               "• Подойдёт ли для двоих?\n"
                               "• Какой расход бензина?\n"
                               "• Есть ли шлем?\n\n"
                               "<i>Для возврата в каталог — /catalog</i>")))
                    (send-message chat-id "Байк не найден."
                      (inline-kb [[(btn "📋 К каталогу" "cat:menu")]]))))

      ;; Client booking (cbook:BIKE_ID:RENTAL_TYPE)
      "cbook"   (let [st (get-state chat-id)]
                  (if (= :client-preview (:step st))
                    ;; В режиме превью — не бронируем, возвращаем в меню
                    (do (clear-state! chat-id)
                        (send-message chat-id "👁 Это был режим просмотра. Бронирование не создано.")
                        (main-menu chat-id from-id))
                    (let [bike-id     (safe-long (second parts) nil)
                          rental-type (nth parts 2 "daily")
                          from-data   (get callback-query "from")]
                      (client-book-bike! chat-id
                        {:id            (get from-data "id")
                         :first_name    (get from-data "first_name")
                         :username      (get from-data "username")
                         :last_name     (get from-data "last_name")
                         :language_code (get from-data "language_code")
                         :is_premium    (get from-data "is_premium")}
                        bike-id rental-type))))

      ;; unknown
      nil))))

;; ── Webhook entry point ──────────────────────────────

(defn handle-webhook
  "Обработка входящего webhook от Telegram (Karma Rent CRM бот)"
  [update]
  (try
    ;; Dedup: skip already-processed updates (Telegram may redeliver)
    (when-not (seen-update? (get update "update_id"))
      ;; Touch profile: update last_active_at + profile data for ANY known user
      (let [from-obj (or (get-in update ["callback_query" "from"])
                         (get-in update ["message" "from"]))]
        (when from-obj
          (models/touch-person! (get from-obj "id")
            {:username      (get from-obj "username")
             :last_name     (get from-obj "last_name")
             :language_code (get from-obj "language_code")
             :is_premium    (get from-obj "is_premium")})))
      ;; Dispatch
      (if-let [callback (get update "callback_query")]
        (handle-callback callback)
        (let [message (get update "message")
              text    (get message "text")
              chat-id (get-in message ["chat" "id"])
              from    (get message "from")]
          (when (and text chat-id)
            (handle-text-input chat-id text
              {:id            (get from "id")
               :first_name    (get from "first_name")
               :username      (get from "username")
               :last_name     (get from "last_name")
               :language_code (get from "language_code")
               :is_premium    (get from "is_premium")})))))
    (catch Exception e
      (println "Webhook error:" (.getMessage e)))))

;; ── Scheduled notifications ──────────────────────────

(defn check-rental-expiry!
  "Проверяет аренды с приближающимся/прошедшим концом.
   Вызывается по расписанию (каждые 6 часов).
   Уведомляет операторов и клиентов."
  []
  (try
    (let [bikes (models/list-bikes "rented")  ;; SQL returns rental_urgency, client_name, client_telegram_id
          ops   (models/list-operators)]
      (doseq [b bikes]
        (let [rent-u (:rental_urgency b)]  ;; 2=critical 1=warning 0=ok — from SQL
          (when (and (:client_name b) (#{1 2} rent-u))
            ;; Уведомить операторов
            (doseq [op ops]
              (when-let [op-chat (some-> (:telegram_id op) parse-long)]
                (send-message op-chat
                  (str (if (= 2 rent-u) "🔴" "🟠")
                       " <b>Аренда " (if (= 2 rent-u) "ПРОСРОЧЕНА!" "скоро заканчивается") "</b>\n\n"
                       "🏍 " (:name b)
                       "\n👤 " (or (:client_name b) "?")
                       (when (:rental_end_date b)
                         (str "\n📅 До: " (:rental_end_date b)))
                       "\n\n" (deep-link (str "adm_b" (:id b)) "Подробнее")))))
            ;; Уведомить клиента при warning
            (when (and (= 1 rent-u) (:client_telegram_id b))
              (when-let [client-chat (some-> (:client_telegram_id b) parse-long)]
                (send-message client-chat
                  (str "⏱ Ваша аренда <b>" (:name b) "</b> скоро заканчивается"
                       (when (:rental_end_date b)
                         (str " (" (:rental_end_date b) ")"))
                       ".\n\nСвяжитесь с оператором для продления!")))))))
      (println "Rental expiry check done:" (count bikes) "rented bikes"))
      ;; Oil change alerts disabled (spammy)
    (catch Exception e
      (println "Rental expiry check error:" (.getMessage e)))))

(defn notify-partner-payout!
  "Уведомить партнёра о рассчитанной выплате"
  [partner-id period revenue share]
  (when-let [p (models/get-person partner-id)]
    (when-let [chat-id (some-> (:telegram_id p) parse-long)]
      (send-message chat-id
        (str "💰 <b>Выплата за " period "</b>\n\n"
             "Выручка: " revenue " тыс\n"
             "Твоя доля (" (share-pct-label) "): <b>" share " тыс</b>\n\n"
             "Спасибо за партнёрство! 🤝")))))

(defn set-webhook! [base-url]
  (let [url    (str base-url "/api/telegram/webhook")
        params (cond-> {:url url}
                 (config/webhook-secret) (assoc :secret_token (config/webhook-secret)))]
    (api-call "setWebhook" params)
    (println "Telegram webhook set to:" url
             (when (config/webhook-secret) "(with secret)"))))

(defn set-bot-commands!
  "Настроить команды бота (видны в меню ≡ Telegram)"
  []
  ;; Команды для операторов/админов
  (api-call "setMyCommands"
    {:commands [{:command "menu"     :description "Главное меню"}
                {:command "bikes"    :description "Список байков"}
                {:command "rental"   :description "Внести аренду"}
                {:command "clients"  :description "Клиенты"}
                {:command "stats"    :description "Статистика"}
                {:command "catalog"  :description "Каталог (клиент)"}]})
  (println "Bot commands set"))
