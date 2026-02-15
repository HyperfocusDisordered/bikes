-- Fix: partner attribution on bookings + missing indexes

ALTER TABLE booking ADD COLUMN partner_id INTEGER REFERENCES person(id);

CREATE INDEX IF NOT EXISTS idx_rental_client ON rental(client_id);
CREATE INDEX IF NOT EXISTS idx_booking_bike_status ON booking(bike_id, status);
CREATE INDEX IF NOT EXISTS idx_booking_created ON booking(created_at);
