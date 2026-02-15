-- Phase 3: Бронирование клиентами

CREATE TABLE IF NOT EXISTS booking (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    client_id   INTEGER NOT NULL REFERENCES person(id),
    bike_id     INTEGER NOT NULL REFERENCES bike(id),
    status      TEXT NOT NULL DEFAULT 'pending'
                CHECK (status IN ('pending', 'confirmed', 'cancelled', 'completed')),
    operator_id INTEGER REFERENCES person(id),
    notes       TEXT,
    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_booking_client ON booking(client_id);
CREATE INDEX IF NOT EXISTS idx_booking_bike ON booking(bike_id);
CREATE INDEX IF NOT EXISTS idx_booking_status ON booking(status);
