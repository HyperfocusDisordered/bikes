-- Разделение QR: invite/tg/ID и invite/wa/ID — один номер для обоих каналов
-- Убрать UNIQUE на code, сделать UNIQUE(code, channel)
-- Идемпотентно: проверяем через temp таблицу

DROP TABLE IF EXISTS qrcode_new;

CREATE TABLE qrcode_new (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    code         TEXT NOT NULL,
    channel      TEXT NOT NULL DEFAULT 'telegram',
    partner_id   INTEGER REFERENCES person(id),
    activated_at TEXT,
    created_at   TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(code, channel)
);

INSERT INTO qrcode_new (id, code, channel, partner_id, activated_at, created_at)
SELECT id, code, channel, partner_id, activated_at, created_at FROM qrcode;

DROP TABLE qrcode;

ALTER TABLE qrcode_new RENAME TO qrcode;

CREATE INDEX IF NOT EXISTS idx_qrcode_code ON qrcode(code);
CREATE INDEX IF NOT EXISTS idx_qrcode_channel ON qrcode(code, channel);
CREATE INDEX IF NOT EXISTS idx_qrcode_partner ON qrcode(partner_id);
