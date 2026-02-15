-- Phase-3: Audit log (append-only transaction log)
-- Записывает все важные действия для аудита и отката

CREATE TABLE IF NOT EXISTS audit_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    action      TEXT NOT NULL,            -- 'booking.create', 'booking.confirm', 'bike.delete', etc.
    entity_type TEXT,                     -- 'booking', 'bike', 'person', 'rental', 'payout'
    entity_id   INTEGER,                  -- ID сущности
    actor_id    INTEGER,                  -- Кто выполнил (person.id или 0=system)
    actor_name  TEXT,                     -- Имя для удобства чтения
    details     TEXT,                     -- JSON с деталями (старые/новые значения)
    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_audit_entity ON audit_log(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_action ON audit_log(action);
CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_log(actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at);
