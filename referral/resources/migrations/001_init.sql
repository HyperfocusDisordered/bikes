-- Карма Рент: реферальная система аренды байков

CREATE TABLE IF NOT EXISTS person (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL,
    phone       TEXT,
    telegram_id TEXT UNIQUE,
    role        TEXT NOT NULL DEFAULT 'client'
                CHECK (role IN ('client', 'partner', 'moderator', 'admin')),
    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS qrcode (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    code         TEXT NOT NULL UNIQUE,
    partner_id   INTEGER REFERENCES person(id),
    activated_at TEXT,
    created_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS rental (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    client_id  INTEGER REFERENCES person(id),
    amount     REAL NOT NULL,
    partner_id INTEGER REFERENCES person(id),
    date       TEXT NOT NULL DEFAULT (date('now')),
    notes      TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS payout (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    partner_id     INTEGER NOT NULL REFERENCES person(id),
    period         TEXT NOT NULL,
    total_revenue  REAL NOT NULL DEFAULT 0,
    partner_share  REAL NOT NULL DEFAULT 0,
    status         TEXT NOT NULL DEFAULT 'pending'
                   CHECK (status IN ('pending', 'paid')),
    created_at     TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (partner_id, period)
);

CREATE INDEX IF NOT EXISTS idx_qrcode_code ON qrcode(code);
CREATE INDEX IF NOT EXISTS idx_qrcode_partner ON qrcode(partner_id);
CREATE INDEX IF NOT EXISTS idx_rental_partner ON rental(partner_id);
CREATE INDEX IF NOT EXISTS idx_rental_date ON rental(date);
CREATE INDEX IF NOT EXISTS idx_payout_partner_period ON payout(partner_id, period);
CREATE INDEX IF NOT EXISTS idx_person_telegram ON person(telegram_id);
CREATE INDEX IF NOT EXISTS idx_person_role ON person(role);
