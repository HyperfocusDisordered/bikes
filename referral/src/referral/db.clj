(ns referral.db
  "SQLite подключение и миграции"
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [referral.config :as config]))

(defonce ^:private ds-atom (atom nil))

(defn ds []
  (or @ds-atom
      (reset! ds-atom
              (jdbc/get-datasource
               {:jdbcUrl (str "jdbc:sqlite:" (config/db-path))}))))

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
    (let [sql (slurp (io/resource "migrations/001_init.sql"))
          stmts (->> (str/split sql #";")
                     (map str/trim)
                     (remove str/blank?))]
      (doseq [stmt stmts]
        (jdbc/execute-one! conn [stmt]))))
  (println "DB initialized"))
