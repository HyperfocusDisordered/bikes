(ns referral.models
  "CRUD для Person, QRCode, Rental, Payout"
  (:require [referral.db :as db]))

;; ── Person ──────────────────────────────────────────────

(defn create-person! [{:keys [name phone telegram_id role]}]
  (db/exec! "INSERT INTO person (name, phone, telegram_id, role) VALUES (?, ?, ?, ?)"
            name phone telegram_id (or role "client")))

(defn get-person [id]
  (db/q1 "SELECT * FROM person WHERE id = ?" id))

(defn get-person-by-telegram [telegram-id]
  (db/q1 "SELECT * FROM person WHERE telegram_id = ?" (str telegram-id)))

(defn list-persons
  ([] (db/q "SELECT * FROM person ORDER BY created_at DESC"))
  ([role] (db/q "SELECT * FROM person WHERE role = ? ORDER BY created_at DESC" role)))

(defn update-person! [id data]
  (let [fields (keep (fn [[k v]] (when v [(clojure.core/name k) v]))
                     (select-keys data [:name :phone :telegram_id :role]))
        sets   (clojure.string/join ", " (map #(str (first %) " = ?") fields))
        vals   (mapv second fields)]
    (when (seq fields)
      (apply db/exec! (str "UPDATE person SET " sets " WHERE id = ?")
             (conj vals id)))))

;; ── QRCode ──────────────────────────────────────────────

(defn create-qrcodes! [codes]
  (doseq [code codes]
    (db/exec! "INSERT OR IGNORE INTO qrcode (code) VALUES (?)" code)))

(defn get-qrcode-by-code [code]
  (db/q1 "SELECT * FROM qrcode WHERE code = ?" code))

(defn activate-qrcode! [code partner-id]
  (db/exec! "UPDATE qrcode SET partner_id = ?, activated_at = datetime('now')
             WHERE code = ? AND partner_id IS NULL"
            partner-id code))

(defn list-qrcodes []
  (db/q "SELECT q.*, p.name as partner_name
         FROM qrcode q LEFT JOIN person p ON q.partner_id = p.id
         ORDER BY q.created_at DESC"))

;; ── Rental ──────────────────────────────────────────────

(defn create-rental! [{:keys [client_id amount partner_id date notes]}]
  (db/exec! "INSERT INTO rental (client_id, amount, partner_id, date, notes) VALUES (?, ?, ?, ?, ?)"
            client_id amount partner_id
            (or date (str (java.time.LocalDate/now)))
            notes))

(defn list-rentals-by-partner [partner-id period]
  (db/q "SELECT r.*, p.name as client_name
         FROM rental r LEFT JOIN person p ON r.client_id = p.id
         WHERE r.partner_id = ? AND strftime('%Y-%m', r.date) = ?
         ORDER BY r.date DESC"
        partner-id period))

(defn partner-revenue [partner-id period]
  (let [row (db/q1 "SELECT COALESCE(SUM(amount), 0) as total
                     FROM rental
                     WHERE partner_id = ? AND strftime('%Y-%m', date) = ?"
                    partner-id period)]
    (:total row 0)))

;; ── Payout ──────────────────────────────────────────────

(defn calculate-payout! [partner-id period]
  (let [revenue (partner-revenue partner-id period)
        share   (* 0.20 revenue)]
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
