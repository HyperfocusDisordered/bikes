-- Owner communication channel
CREATE TABLE IF NOT EXISTS owner_message (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  direction TEXT NOT NULL CHECK (direction IN ('in', 'out')),
  text TEXT NOT NULL,
  chat_id INTEGER,
  read INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
