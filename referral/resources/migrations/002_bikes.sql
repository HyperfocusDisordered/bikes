-- Phase 2: Байки, статусы, обслуживание

CREATE TABLE IF NOT EXISTS bike (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL,
    plate_number    TEXT UNIQUE,
    status          TEXT NOT NULL DEFAULT 'available'
                    CHECK (status IN ('available', 'rented', 'booked', 'maintenance')),
    daily_rate      REAL,
    last_oil_change TEXT,
    notes           TEXT,
    created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Привязка аренды к байку
ALTER TABLE rental ADD COLUMN bike_id INTEGER REFERENCES bike(id);

ALTER TABLE bike ADD COLUMN photo_url TEXT;

CREATE INDEX IF NOT EXISTS idx_bike_status ON bike(status);
CREATE INDEX IF NOT EXISTS idx_rental_bike ON rental(bike_id);
