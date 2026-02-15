(ns referral.db
  "SQLite подключение и миграции"
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [referral.config :as config]))

(defonce ^:private ds-atom (atom nil))
(defonce ^:private dev-ds-atom (atom nil))

;; Dynamic var: when bound to dev datasource, all queries go to dev DB
(def ^:dynamic *active-ds* nil)

(defn ds []
  (or *active-ds*
      @ds-atom
      (reset! ds-atom
              (jdbc/get-datasource
               {:jdbcUrl (str "jdbc:sqlite:" (config/db-path))}))))

(defn dev-ds []
  (or @dev-ds-atom
      (reset! dev-ds-atom
              (jdbc/get-datasource
               {:jdbcUrl (str "jdbc:sqlite:" (config/dev-db-path))}))))

(def ^:private jdbc-opts
  {:builder-fn rs/as-unqualified-maps})

(defn q
  "Выполнить SELECT, вернуть вектор мап"
  [sql & params]
  (jdbc/execute! (ds) (into [sql] params) jdbc-opts))

(defn q1
  "Выполнить SELECT, вернуть одну запись"
  [sql & params]
  (jdbc/execute-one! (ds) (into [sql] params) jdbc-opts))

(defn exec!
  "Выполнить INSERT/UPDATE/DELETE"
  [sql & params]
  (jdbc/execute-one! (ds) (into [sql] params) jdbc-opts))

(defn init-db!
  "Запустить миграции и включить WAL + FK"
  []
  (let [conn (ds)]
    (jdbc/execute-one! conn ["PRAGMA journal_mode=WAL"])
    (jdbc/execute-one! conn ["PRAGMA foreign_keys=ON"])
    (doseq [file ["migrations/001_init.sql" "migrations/002_bikes.sql" "migrations/003_bookings.sql" "migrations/004_tariffs.sql" "migrations/005_owner_messages.sql" "migrations/006_audit_log.sql" "migrations/007_booking_partner.sql" "migrations/008_qr_channel.sql" "migrations/009_bike_category.sql" "migrations/010_qr_channel_unique.sql" "migrations/011_person_profile.sql" "migrations/012_transaction_type.sql"]]
      (let [sql (slurp (io/resource file))
            stmts (->> (str/split sql #";")
                       (map str/trim)
                       (remove str/blank?))]
        (doseq [stmt stmts]
          (try
            (jdbc/execute-one! conn [stmt])
            (catch Exception e
              (when-not (str/includes? (.getMessage e) "duplicate column")
                (throw e)))))))
    ;; Post-migration: recreate bike table if CHECK constraint doesn't include 'hold'
    (let [result    (jdbc/execute-one! conn
                     ["SELECT sql FROM sqlite_master WHERE type='table' AND name='bike'"]
                     jdbc-opts)
          table-sql (:sql result)]
      (when (and table-sql (not (str/includes? table-sql "hold")))
        (try
          (jdbc/execute-one! conn ["CREATE TABLE bike_new (
             id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
             plate_number TEXT UNIQUE,
             status TEXT NOT NULL DEFAULT 'available' CHECK (status IN ('available','rented','booked','maintenance','hold')),
             daily_rate REAL, monthly_rate REAL, last_oil_change TEXT,
             notes TEXT, photo_url TEXT, category TEXT DEFAULT 'scooter',
             created_at TEXT NOT NULL DEFAULT (datetime('now')))"])
          (jdbc/execute-one! conn ["INSERT INTO bike_new (id,name,plate_number,status,daily_rate,monthly_rate,last_oil_change,notes,photo_url,category,created_at)
             SELECT id,name,plate_number,status,daily_rate,monthly_rate,last_oil_change,notes,photo_url,category,created_at FROM bike"])
          (jdbc/execute-one! conn ["DROP TABLE bike"])
          (jdbc/execute-one! conn ["ALTER TABLE bike_new RENAME TO bike"])
          (jdbc/execute-one! conn ["CREATE INDEX IF NOT EXISTS idx_bike_status ON bike(status)"])
          (println "Migrated bike table: added 'hold' + monthly_rate + category")
          (catch Exception e
            (println "bike migration error:" (.getMessage e)))))))
  (println "DB initialized"))

(defn init-dev-db!
  "Запустить миграции на dev БД"
  []
  (let [conn (dev-ds)]
    (jdbc/execute-one! conn ["PRAGMA journal_mode=WAL"])
    (jdbc/execute-one! conn ["PRAGMA foreign_keys=ON"])
    (doseq [file ["migrations/001_init.sql" "migrations/002_bikes.sql" "migrations/003_bookings.sql" "migrations/004_tariffs.sql" "migrations/005_owner_messages.sql" "migrations/006_audit_log.sql" "migrations/007_booking_partner.sql" "migrations/008_qr_channel.sql" "migrations/009_bike_category.sql" "migrations/010_qr_channel_unique.sql" "migrations/011_person_profile.sql" "migrations/012_transaction_type.sql"]]
      (let [sql (slurp (io/resource file))
            stmts (->> (str/split sql #";")
                       (map str/trim)
                       (remove str/blank?))]
        (doseq [stmt stmts]
          (try
            (jdbc/execute-one! conn [stmt])
            (catch Exception e
              (when-not (str/includes? (.getMessage e) "duplicate column")
                (throw e)))))))
    ;; Same bike table migration as prod
    (let [result    (jdbc/execute-one! conn
                     ["SELECT sql FROM sqlite_master WHERE type='table' AND name='bike'"]
                     jdbc-opts)
          table-sql (:sql result)]
      (when (and table-sql (not (str/includes? table-sql "hold")))
        (try
          (jdbc/execute-one! conn ["CREATE TABLE bike_new (
             id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
             plate_number TEXT UNIQUE,
             status TEXT NOT NULL DEFAULT 'available' CHECK (status IN ('available','rented','booked','maintenance','hold')),
             daily_rate REAL, monthly_rate REAL, last_oil_change TEXT,
             notes TEXT, photo_url TEXT, category TEXT DEFAULT 'scooter',
             created_at TEXT NOT NULL DEFAULT (datetime('now')))"])
          (jdbc/execute-one! conn ["INSERT INTO bike_new (id,name,plate_number,status,daily_rate,monthly_rate,last_oil_change,notes,photo_url,category,created_at)
             SELECT id,name,plate_number,status,daily_rate,monthly_rate,last_oil_change,notes,photo_url,category,created_at FROM bike"])
          (jdbc/execute-one! conn ["DROP TABLE bike"])
          (jdbc/execute-one! conn ["ALTER TABLE bike_new RENAME TO bike"])
          (jdbc/execute-one! conn ["CREATE INDEX IF NOT EXISTS idx_bike_status ON bike(status)"])
          (catch Exception _)))))
  (println "Dev DB initialized"))

(defmacro with-dev-db
  "Выполнить body с dev datasource"
  [& body]
  `(binding [*active-ds* (dev-ds)]
     ~@body))
