-- Person profile enrichment: collect all available Telegram data
ALTER TABLE person ADD COLUMN username TEXT;
ALTER TABLE person ADD COLUMN last_name TEXT;
ALTER TABLE person ADD COLUMN language_code TEXT;
ALTER TABLE person ADD COLUMN is_premium INTEGER DEFAULT 0;
ALTER TABLE person ADD COLUMN last_active_at TEXT;

CREATE INDEX IF NOT EXISTS idx_person_username ON person(username);
CREATE INDEX IF NOT EXISTS idx_person_last_active ON person(last_active_at);
