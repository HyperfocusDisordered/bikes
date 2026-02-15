(ns referral.models
  "CRUD для Person, QRCode, Rental, Payout, Bike"
  (:require [referral.db :as db]
            [referral.config :as config]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ── Audit Log (append-only) ─────────────────────────────

(defn audit!
  "Записать действие в audit log. details — map, сериализуется в JSON."
  [action entity-type entity-id actor-id actor-name details]
  (db/exec! "INSERT INTO audit_log (action, entity_type, entity_id, actor_id, actor_name, details) VALUES (?, ?, ?, ?, ?, ?)"
            action entity-type entity-id actor-id actor-name
            (when details (json/generate-string details))))

(defn list-audit-log
  "Последние записи audit log"
  [& [limit]]
  (db/q "SELECT * FROM audit_log ORDER BY created_at DESC LIMIT ?"
        (or limit 50)))

;; ── Person ──────────────────────────────────────────────

(defn create-person! [{:keys [name phone telegram_id role username last_name language_code]}]
  (db/exec! "INSERT INTO person (name, phone, telegram_id, role, username, last_name, language_code, last_active_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, datetime('now'))"
            name phone telegram_id (or role "client") username last_name language_code))

(defn get-person [id]
  (db/q1 "SELECT * FROM person WHERE id = ?" id))

(defn get-person-by-telegram [telegram-id]
  (db/q1 "SELECT * FROM person WHERE telegram_id = ?" (str telegram-id)))

(defn list-persons
  ([] (db/q "SELECT * FROM person ORDER BY created_at DESC"))
  ([role] (db/q "SELECT * FROM person WHERE role = ? ORDER BY created_at DESC" role)))

(defn get-last-created-person
  "Последний созданный person по name+role (для надёжного lookup после INSERT)"
  [name role]
  (db/q1 "SELECT * FROM person WHERE name = ? AND role = ? ORDER BY id DESC LIMIT 1" name role))

(defn update-person! [id data]
  (let [fields (keep (fn [[k v]] (when v [(clojure.core/name k) v]))
                     (select-keys data [:name :phone :telegram_id :role :username :last_name :language_code]))
        sets   (str/join ", " (map #(str (first %) " = ?") fields))
        vals   (mapv second fields)]
    (when (seq fields)
      (apply db/exec! (str "UPDATE person SET " sets " WHERE id = ?")
             (conj vals id)))))

(defn touch-person!
  "Update profile data + last_active_at from Telegram interaction.
   Only updates non-nil fields. Works for any role."
  [telegram-id {:keys [username last_name language_code is_premium]}]
  (when telegram-id
    (let [tid    (str telegram-id)
          parts  (cond-> ["last_active_at = datetime('now')"]
                   username       (conj "username = ?")
                   last_name      (conj "last_name = ?")
                   language_code  (conj "language_code = ?")
                   (some? is_premium) (conj "is_premium = ?"))
          params (cond-> []
                   username       (conj username)
                   last_name      (conj last_name)
                   language_code  (conj language_code)
                   (some? is_premium) (conj (if is_premium 1 0)))]
      (apply db/exec!
             (str "UPDATE person SET " (str/join ", " parts) " WHERE telegram_id = ?")
             (conj params tid)))))

;; ── QRCode ──────────────────────────────────────────────

(defn create-qrcodes!
  ([codes] (create-qrcodes! codes "telegram"))
  ([codes channel]
   (doseq [code codes]
     (db/exec! "INSERT OR IGNORE INTO qrcode (code, channel) VALUES (?, ?)" code channel))))

(defn get-qrcode [id]
  (db/q1 "SELECT * FROM qrcode WHERE id = ?" id))

(defn get-qrcode-by-code
  "Найти QR по коду. Если channel указан — ищет точно по (code, channel)."
  ([code] (db/q1 "SELECT * FROM qrcode WHERE code = ?" code))
  ([code channel] (db/q1 "SELECT * FROM qrcode WHERE code = ? AND channel = ?" code channel)))

(defn activate-qrcode!
  "Активировать QR-код(ы) для партнёра. Без channel — активирует ВСЕ каналы с этим кодом.
   Возвращает true если хотя бы одна строка обновлена."
  ([code partner-id]
   (let [result (db/exec! "UPDATE qrcode SET partner_id = ?, activated_at = datetime('now')
                           WHERE code = ? AND partner_id IS NULL"
                          partner-id code)]
     (pos? (or (:next.jdbc/update-count result) 0))))
  ([code channel partner-id]
   (let [result (db/exec! "UPDATE qrcode SET partner_id = ?, activated_at = datetime('now')
                           WHERE code = ? AND channel = ? AND partner_id IS NULL"
                          partner-id code channel)]
     (pos? (or (:next.jdbc/update-count result) 0)))))

(defn list-qrcodes
  ([] (db/q "SELECT q.*, p.name as partner_name
             FROM qrcode q LEFT JOIN person p ON q.partner_id = p.id
             ORDER BY CAST(q.code AS INTEGER), q.code"))
  ([channel] (db/q "SELECT q.*, p.name as partner_name
                    FROM qrcode q LEFT JOIN person p ON q.partner_id = p.id
                    WHERE q.channel = ?
                    ORDER BY CAST(q.code AS INTEGER), q.code" channel)))

;; ── Rental ──────────────────────────────────────────────

(defn create-rental! [{:keys [client_id amount partner_id bike_id date notes transaction_type]}]
  (db/exec! "INSERT INTO rental (client_id, amount, partner_id, bike_id, date, notes, transaction_type) VALUES (?, ?, ?, ?, ?, ?, ?)"
            client_id amount partner_id bike_id
            (or date (str (java.time.LocalDate/now)))
            notes (or transaction_type "revenue")))

(defn list-rentals-by-partner [partner-id period]
  (db/q "SELECT r.*, p.name as client_name
         FROM rental r LEFT JOIN person p ON r.client_id = p.id
         WHERE r.partner_id = ? AND strftime('%Y-%m', r.date) = ?
         ORDER BY r.date DESC"
        partner-id period))

(defn partner-revenue [partner-id period]
  (let [row (db/q1 "SELECT COALESCE(SUM(amount), 0) as total
                     FROM rental
                     WHERE partner_id = ? AND strftime('%Y-%m', date) = ?
                     AND COALESCE(transaction_type, 'revenue') = 'revenue'"
                    partner-id period)]
    (:total row 0)))

;; ── Payout ──────────────────────────────────────────────

(defn calculate-payout! [partner-id period]
  (let [revenue (partner-revenue partner-id period)
        share   (* (config/partner-share-pct) revenue)]
    (db/exec! "INSERT INTO payout (partner_id, period, total_revenue, partner_share)
               VALUES (?, ?, ?, ?)
               ON CONFLICT (partner_id, period)
               DO UPDATE SET total_revenue = ?, partner_share = ?"
              partner-id period revenue share revenue share)
    {:partner_id partner-id :period period
     :total_revenue revenue :partner_share share}))

(defn get-payout [partner-id period]
  (db/q1 "SELECT * FROM payout WHERE partner_id = ? AND period = ?"
          partner-id period))

(defn list-payouts [period]
  (db/q "SELECT py.*, p.name as partner_name
         FROM payout py JOIN person p ON py.partner_id = p.id
         WHERE py.period = ?
         ORDER BY py.total_revenue DESC"
        period))

(defn mark-payout-paid! [payout-id]
  (db/exec! "UPDATE payout SET status = 'paid' WHERE id = ?" payout-id))

;; ── Bike ──────────────────────────────────────────────

(defn create-bike! [{:keys [name plate_number daily_rate monthly_rate last_oil_change notes photo_url category]}]
  (db/exec! "INSERT INTO bike (name, plate_number, daily_rate, monthly_rate, last_oil_change, notes, photo_url, category) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            name plate_number daily_rate monthly_rate
            (or last_oil_change (str (java.time.LocalDate/now)))
            notes photo_url (or category "scooter")))

(defn get-bike [id]
  (db/q1 "SELECT * FROM bike WHERE id = ?" id))

(defn update-bike! [id data]
  (let [allowed [:name :plate_number :status :daily_rate :monthly_rate :last_oil_change :notes :photo_url :category]
        fields  (keep (fn [[k v]] (when (some? v) [(clojure.core/name k) v]))
                      (select-keys data allowed))
        sets    (clojure.string/join ", " (map #(str (first %) " = ?") fields))
        vals    (mapv second fields)]
    (when (seq fields)
      (apply db/exec! (str "UPDATE bike SET " sets " WHERE id = ?")
             (conj vals id)))))

(defn delete-bike!
  "Удалить байк (только если не rented). Записывает в audit log."
  [id actor-id]
  (when-let [bike (get-bike id)]
    (when (not= "rented" (:status bike))
      (db/exec! "DELETE FROM bike WHERE id = ?" id)
      (audit! "bike.delete" "bike" id actor-id nil
              {:name (:name bike) :plate (:plate_number bike) :status (:status bike)})
      bike)))

(defn list-bikes
  "Список байков, отсортированный по срочности (масло + аренда).
   Красные (просрочены) первые, потом оранжевые, потом остальные.
   rental_urgency основана на rental_end_date из booking.
   category-filter: 'car', 'bike', 'scooter', 'bicycle', или набор вроде #{'bike' 'scooter'}"
  ([] (list-bikes nil nil))
  ([status-filter] (list-bikes status-filter nil))
  ([status-filter category-filter]
   (let [oil-days (config/oil-change-days)
         cats (cond
                (nil? category-filter) nil
                (string? category-filter) [category-filter]
                (coll? category-filter) (vec category-filter))
         where-parts (cond-> []
                       status-filter (conj "b.status = ?")
                       cats (conj (str "b.category IN (" (clojure.string/join "," (repeat (count cats) "?")) ")")))
         where-clause (when (seq where-parts) (str " WHERE " (clojure.string/join " AND " where-parts)))
         params (cond-> []
                  status-filter (conj status-filter)
                  cats (into cats))
         base (str "SELECT b.*,
                      CASE WHEN b.last_oil_change IS NOT NULL THEN
                        julianday('now') - julianday(b.last_oil_change)
                      ELSE 9999 END as days_since_oil,
                      CASE WHEN b.last_oil_change IS NULL THEN 2
                           WHEN julianday('now') - julianday(b.last_oil_change) >= " oil-days " THEN 2
                           WHEN julianday('now') - julianday(b.last_oil_change) >= " (- oil-days 14) " THEN 1
                           ELSE 0 END as oil_urgency,
                      COALESCE(
                        (SELECT julianday('now') - julianday(bk.created_at)
                         FROM booking bk WHERE bk.bike_id = b.id AND bk.status = 'confirmed'
                         ORDER BY bk.created_at DESC LIMIT 1), 0) as days_rented,
                      (SELECT bk.rental_type FROM booking bk
                       WHERE bk.bike_id = b.id AND bk.status = 'confirmed'
                       ORDER BY bk.created_at DESC LIMIT 1) as rental_type,
                      (SELECT bk.rental_end_date FROM booking bk
                       WHERE bk.bike_id = b.id AND bk.status = 'confirmed'
                       ORDER BY bk.created_at DESC LIMIT 1) as rental_end_date,
                      CASE WHEN b.status != 'rented' THEN 0
                           WHEN (SELECT bk.rental_end_date FROM booking bk
                                 WHERE bk.bike_id = b.id AND bk.status = 'confirmed'
                                 ORDER BY bk.created_at DESC LIMIT 1) IS NULL THEN 0
                           WHEN julianday('now') > julianday(
                                 (SELECT bk.rental_end_date FROM booking bk
                                  WHERE bk.bike_id = b.id AND bk.status = 'confirmed'
                                  ORDER BY bk.created_at DESC LIMIT 1)) THEN 2
                           WHEN julianday('now') >= julianday(
                                 (SELECT bk.rental_end_date FROM booking bk
                                  WHERE bk.bike_id = b.id AND bk.status = 'confirmed'
                                  ORDER BY bk.created_at DESC LIMIT 1)) - 3 THEN 1
                           ELSE 0 END as rental_urgency,
                      (SELECT bk.id FROM booking bk
                       WHERE bk.bike_id = b.id AND bk.status = 'confirmed'
                       ORDER BY bk.created_at DESC LIMIT 1) as booking_id,
                      (SELECT p.name FROM booking bk JOIN person p ON bk.client_id = p.id
                       WHERE bk.bike_id = b.id AND bk.status = 'confirmed'
                       ORDER BY bk.created_at DESC LIMIT 1) as client_name,
                      (SELECT p.telegram_id FROM booking bk JOIN person p ON bk.client_id = p.id
                       WHERE bk.bike_id = b.id AND bk.status = 'confirmed'
                       ORDER BY bk.created_at DESC LIMIT 1) as client_telegram_id
                    FROM bike b"
                   where-clause
                   " ORDER BY (oil_urgency + rental_urgency) DESC, days_since_oil DESC")]
     (if (seq params)
       (apply db/q base params)
       (db/q base)))))

(defn try-book-bike!
  "Atomically set bike status to 'booked' only if currently 'available'.
   Returns true on success, false if bike was already taken."
  [bike-id]
  (let [result (db/exec! "UPDATE bike SET status = 'booked' WHERE id = ? AND status = 'available'" bike-id)]
    (pos? (or (:next.jdbc/update-count result) 0))))

(defn bike-oil-status
  "Возвращает :critical :warning :ok для байка"
  [{:keys [last_oil_change]}]
  (if-not last_oil_change
    :critical
    (let [days-since (-> (java.time.temporal.ChronoUnit/DAYS)
                         (.between (java.time.LocalDate/parse last_oil_change)
                                   (java.time.LocalDate/now)))
          limit      (config/oil-change-days)]
      (cond
        (>= days-since limit)       :critical
        (>= days-since (- limit 14)) :warning
        :else                        :ok))))

(defn bike-rental-status
  "Возвращает :critical :warning :ok по rental_end_date.
   :critical = просрочена (end_date < today), :warning = осталось <= 3 дня, :ok = нормально.
   Для посуточных (daily) без end_date — всегда :ok."
  [bike]
  (if (not= "rented" (:status bike))
    :ok
    (if-let [end-date (:rental_end_date bike)]
      (let [end   (java.time.LocalDate/parse end-date)
            today (java.time.LocalDate/now)
            days-left (-> (java.time.temporal.ChronoUnit/DAYS)
                          (.between today end))]
        (cond
          (neg? days-left) :critical  ;; просрочена
          (<= days-left 3) :warning   ;; осталось <= 3 дня
          :else            :ok))
      :ok)))

;; ── Booking ──────────────────────────────────────────────

(defn create-booking! [{:keys [client_id bike_id rental_type partner_id]}]
  (let [result (db/q1 "INSERT INTO booking (client_id, bike_id, rental_type, partner_id) VALUES (?, ?, ?, ?) RETURNING id"
                       client_id bike_id (or rental_type "daily") partner_id)]
    (:id result)))

(defn get-booking [id]
  (db/q1 "SELECT b.*, p.name as client_name, p.phone as client_phone,
                 p.telegram_id as client_telegram_id,
                 bk.name as bike_name, bk.daily_rate as bike_rate,
                 bk.monthly_rate as bike_monthly_rate, bk.photo_url as bike_photo
          FROM booking b
          JOIN person p ON b.client_id = p.id
          JOIN bike bk ON b.bike_id = bk.id
          WHERE b.id = ?" id))

(defn get-active-booking-by-client [client-id]
  (db/q1 "SELECT b.*, bk.name as bike_name, bk.daily_rate as bike_rate
          FROM booking b JOIN bike bk ON b.bike_id = bk.id
          WHERE b.client_id = ? AND b.status IN ('pending','confirmed')
          ORDER BY b.created_at DESC LIMIT 1" client-id))

(defn update-booking! [id data]
  (let [allowed [:status :operator_id :notes :rental_type :rental_end_date]
        fields  (keep (fn [[k v]] (when (some? v) [(clojure.core/name k) v]))
                      (select-keys data allowed))
        sets    (clojure.string/join ", " (map #(str (first %) " = ?") fields))
        vals    (mapv second fields)]
    (when (seq fields)
      (apply db/exec! (str "UPDATE booking SET " sets " WHERE id = ?")
             (conj vals id)))))

(defn list-bookings
  ([] (db/q "SELECT b.*, p.name as client_name, bk.name as bike_name
             FROM booking b
             JOIN person p ON b.client_id = p.id
             JOIN bike bk ON b.bike_id = bk.id
             ORDER BY b.created_at DESC"))
  ([status] (db/q "SELECT b.*, p.name as client_name, bk.name as bike_name
                    FROM booking b
                    JOIN person p ON b.client_id = p.id
                    JOIN bike bk ON b.bike_id = bk.id
                    WHERE b.status = ?
                    ORDER BY b.created_at DESC" status)))

(defn list-operators
  "Все admin/moderator с telegram_id"
  []
  (db/q "SELECT * FROM person WHERE role IN ('admin','moderator') AND telegram_id IS NOT NULL"))

(defn list-pending-bookings
  "Все ожидающие бронирования"
  []
  (db/q "SELECT b.*, p.name as client_name, p.telegram_id as client_telegram_id,
                bk.name as bike_name, bk.daily_rate as bike_rate,
                bk.monthly_rate as bike_monthly_rate, bk.photo_url as bike_photo
         FROM booking b
         JOIN person p ON b.client_id = p.id
         JOIN bike bk ON b.bike_id = bk.id
         WHERE b.status = 'pending'
         ORDER BY b.created_at ASC"))

(defn confirm-booking!
  "Подтвердить бронь → создать rental, сменить статус байка.
   Рассчитать rental_end_date по тарифу. Атомарная проверка pending статуса."
  [booking-id operator-id]
  ;; Atomic status transition: only one operator can confirm
  (let [upd (db/exec! "UPDATE booking SET status = 'confirmed', operator_id = ? WHERE id = ? AND status = 'pending'"
                       operator-id booking-id)]
    (when (pos? (or (:next.jdbc/update-count upd) 0))
      (when-let [bk (get-booking booking-id)]
        (let [rental-type (or (:rental_type bk) "daily")
              today       (java.time.LocalDate/now)
              end-date    (case rental-type
                            "monthly" (str (.plusMonths today 1))
                            nil) ;; daily — без конечной даты
              bike        (get-bike (:bike_id bk))
              amount      (case rental-type
                            "monthly" (or (:monthly_rate bike) (:bike_rate bk) 0)
                            (or (:bike_rate bk) 0))]
          (when end-date
            (db/exec! "UPDATE booking SET rental_end_date = ? WHERE id = ?" end-date booking-id))
          (update-bike! (:bike_id bk) {:status "rented"})
          ;; Partner attribution: prefer booking.partner_id, fallback to previous rentals
        (let [partner-id (or (:partner_id bk)
                             (:partner_id (db/q1 "SELECT r.partner_id FROM rental r
                                                   WHERE r.client_id = ? AND r.partner_id IS NOT NULL
                                                   ORDER BY r.created_at DESC LIMIT 1"
                                                  (:client_id bk))))]
            (create-rental! {:client_id  (:client_id bk)
                             :amount     amount
                             :partner_id partner-id
                             :bike_id    (:bike_id bk)
                             :notes      (str "Бронь #" booking-id " (" rental-type ")")}))
          (audit! "booking.confirm" "booking" booking-id operator-id nil
                  {:client (:client_name bk) :bike (:bike_name bk) :type rental-type :amount amount})
          bk)))))

(defn cancel-booking!
  "Отменить бронь. Pending или confirmed — обе можно отменить.
   Если confirmed — байк вернётся в available (если нет других активных букингов)."
  [booking-id operator-id]
  ;; Atomic: only cancel if still pending/confirmed
  (let [upd (db/exec! "UPDATE booking SET status = 'cancelled', operator_id = ? WHERE id = ? AND status IN ('pending','confirmed')"
                       operator-id booking-id)]
    (when (pos? (or (:next.jdbc/update-count upd) 0))
      (let [bk (get-booking booking-id)]
        (when-let [bike (get-bike (:bike_id bk))]
          ;; Only free bike if no OTHER active bookings exist for it
          (when (and (#{"booked" "rented"} (:status bike))
                     (not (db/q1 "SELECT id FROM booking WHERE bike_id = ? AND status IN ('pending','confirmed') AND id != ? LIMIT 1"
                                 (:bike_id bk) booking-id)))
            (update-bike! (:bike_id bk) {:status "available"})))
        (audit! "booking.cancel" "booking" booking-id operator-id nil
                {:client (:client_name bk) :bike (:bike_name bk)})
        bk))))

(defn client-cancel-booking!
  "Клиент сам отменяет PENDING бронь. Confirmed — нельзя (только оператор)."
  [booking-id client-id]
  (let [upd (db/exec! "UPDATE booking SET status = 'cancelled' WHERE id = ? AND client_id = ? AND status = 'pending'"
                       booking-id client-id)]
    (when (pos? (or (:next.jdbc/update-count upd) 0))
      (let [bk (get-booking booking-id)]
        (when-let [bike (get-bike (:bike_id bk))]
          (when (and (#{"booked"} (:status bike))
                     (not (db/q1 "SELECT id FROM booking WHERE bike_id = ? AND status IN ('pending','confirmed') AND id != ? LIMIT 1"
                                 (:bike_id bk) booking-id)))
            (update-bike! (:bike_id bk) {:status "available"})))
        (audit! "booking.client_cancel" "booking" booking-id client-id nil
                {:client_id client-id :bike (:bike_name bk)})
        bk))))

(defn client-bookings
  "Все бронирования клиента"
  [client-id]
  (db/q "SELECT b.*, bk.name as bike_name, bk.daily_rate as bike_rate
         FROM booking b JOIN bike bk ON b.bike_id = bk.id
         WHERE b.client_id = ?
         ORDER BY b.created_at DESC" client-id))

(defn client-rentals
  "Все аренды клиента"
  [client-id]
  (db/q "SELECT r.*, bk.name as bike_name, p.name as partner_name
         FROM rental r
         LEFT JOIN bike bk ON r.bike_id = bk.id
         LEFT JOIN person p ON r.partner_id = p.id
         WHERE r.client_id = ?
         ORDER BY r.date DESC" client-id))

(defn bike-has-pending-booking?
  "Есть ли pending бронь на этот байк?"
  [bike-id]
  (some? (db/q1 "SELECT id FROM booking WHERE bike_id = ? AND status = 'pending' LIMIT 1" bike-id)))

(defn bike-pending-booking
  "Вернуть pending бронь на байк (если есть)"
  [bike-id]
  (db/q1 "SELECT b.*, p.name as client_name, p.telegram_id as client_telegram_id
          FROM booking b JOIN person p ON b.client_id = p.id
          WHERE b.bike_id = ? AND b.status = 'pending'
          ORDER BY b.created_at DESC LIMIT 1" bike-id))

(defn get-client-partner
  "Найти партнёра клиента (по последней аренде с partner_id)"
  [client-id]
  (db/q1 "SELECT p.* FROM person p
          JOIN rental r ON r.partner_id = p.id
          WHERE r.client_id = ? AND r.partner_id IS NOT NULL
          ORDER BY r.created_at DESC LIMIT 1" client-id))

(defn complete-booking!
  "Завершить аренду: booking→completed, bike→available.
   Atomic: UPDATE WHERE status='confirmed' (same pattern as confirm/cancel)."
  [booking-id operator-id]
  (let [upd (db/exec! "UPDATE booking SET status = 'completed', operator_id = ? WHERE id = ? AND status = 'confirmed'"
                       operator-id booking-id)]
    (when (pos? (or (:next.jdbc/update-count upd) 0))
      (when-let [bk (get-booking booking-id)]
        ;; Only free bike if no OTHER active bookings exist for it
        (when (not (db/q1 "SELECT id FROM booking WHERE bike_id = ? AND status = 'confirmed' AND id != ? LIMIT 1"
                          (:bike_id bk) booking-id))
          (update-bike! (:bike_id bk) {:status "available"}))
        (audit! "booking.complete" "booking" booking-id operator-id nil
                {:client (:client_name bk) :bike (:bike_name bk)})
        bk))))

(defn active-rental-for-bike
  "Активная бронь (confirmed) для байка"
  [bike-id]
  (db/q1 "SELECT b.*, p.name as client_name, p.telegram_id as client_telegram_id,
                 bk.name as bike_name, bk.daily_rate as bike_rate,
                 bk.monthly_rate as bike_monthly_rate
          FROM booking b
          JOIN person p ON b.client_id = p.id
          JOIN bike bk ON b.bike_id = bk.id
          WHERE b.bike_id = ? AND b.status = 'confirmed'
          ORDER BY b.created_at DESC LIMIT 1" bike-id))

(defn rented-bikes-with-clients
  "Байки в аренде с данными клиента и брони"
  []
  (db/q "SELECT bk.*, b.id as booking_id, b.created_at as booking_date,
                p.name as client_name, p.telegram_id as client_telegram_id
         FROM bike bk
         LEFT JOIN booking b ON b.bike_id = bk.id AND b.status = 'confirmed'
         LEFT JOIN person p ON b.client_id = p.id
         WHERE bk.status = 'rented'
         ORDER BY b.created_at DESC"))

;; ── Partner stats (extended) ──────────────────────────

(defn partner-revenue-all-time [partner-id]
  (let [row (db/q1 "SELECT COALESCE(SUM(amount), 0) as total FROM rental
                     WHERE partner_id = ? AND COALESCE(transaction_type, 'revenue') = 'revenue'"
                    partner-id)]
    (:total row 0)))

(defn partner-stats [partner-id]
  (let [now       (java.time.LocalDate/now)
        period    (format "%d-%02d" (.getYear now) (.getMonthValue now))
        all-time  (partner-revenue-all-time partner-id)
        monthly   (partner-revenue partner-id period)
        share-pct (config/partner-share-pct)]
    {:partner_id     partner-id
     :all_time       {:revenue all-time  :share (* share-pct all-time)}
     :monthly        {:period period :revenue monthly :share (* share-pct monthly)}
     :clients_count  (:cnt (db/q1 "SELECT COUNT(DISTINCT client_id) as cnt FROM rental WHERE partner_id = ?" partner-id) 0)}))

;; ── Owner messages ───────────────────────────────────

(defn save-owner-message!
  "Сохранить сообщение от/для owner"
  [direction text chat-id]
  (db/exec! "INSERT INTO owner_message (direction, text, chat_id) VALUES (?, ?, ?)"
            direction text chat-id))

(defn list-owner-messages
  "Непрочитанные входящие сообщения от owner"
  []
  (db/q "SELECT * FROM owner_message WHERE direction = 'in' AND read = 0 ORDER BY created_at ASC"))

(defn list-owner-messages-all
  "Все сообщения (для истории)"
  [& [limit]]
  (db/q "SELECT * FROM owner_message ORDER BY created_at DESC LIMIT ?"
        (or limit 50)))

(defn mark-owner-messages-read! []
  (db/exec! "UPDATE owner_message SET read = 1 WHERE direction = 'in' AND read = 0"))

(defn get-owner-chat-id
  "Последний известный chat_id owner'а из сохранённых сообщений"
  []
  (:chat_id (first (db/q "SELECT chat_id FROM owner_message WHERE chat_id IS NOT NULL ORDER BY id DESC LIMIT 1"))))

;; ── Rentals by period (for stats) ──────────────────

(defn list-rentals-by-period
  "Все транзакции за период YYYY-MM с join на client/bike/partner"
  [period]
  (db/q "SELECT r.*, p.name as client_name, b.name as bike_name, pr.name as partner_name
         FROM rental r
         LEFT JOIN person p ON r.client_id = p.id
         LEFT JOIN bike b ON r.bike_id = b.id
         LEFT JOIN person pr ON r.partner_id = pr.id
         WHERE strftime('%Y-%m', r.date) = ?
         ORDER BY r.date DESC" period))

;; ── Partner stats (extended) ── (continued below)

(defn partner-rental-history
  "Последние аренды от клиентов этого партнёра"
  [partner-id & [limit]]
  (db/q (str "SELECT r.*, p.name as client_name, b.name as bike_name
              FROM rental r
              LEFT JOIN person p ON r.client_id = p.id
              LEFT JOIN bike b ON r.bike_id = b.id
              WHERE r.partner_id = ? AND r.amount > 0
              ORDER BY r.date DESC
              LIMIT ?")
         partner-id (or limit 10)))
