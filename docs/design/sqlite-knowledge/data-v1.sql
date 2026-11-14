-- Design artifact: fresh database schema. Not yet connected to the application.
-- Configure journal_mode=WAL before this transaction; per connection: foreign_keys=ON, busy_timeout=5000.
PRAGMA foreign_keys=ON;
BEGIN IMMEDIATE;
CREATE TABLE todos (
  id INTEGER PRIMARY KEY,
  uid TEXT NOT NULL UNIQUE,
  title TEXT NOT NULL CHECK(length(trim(title)) > 0),
  description_md TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open','closed')),
  due_date TEXT,
  priority INTEGER NOT NULL DEFAULT 0 CHECK(priority BETWEEN 0 AND 3),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  completed_at INTEGER,
  deleted_at INTEGER,
  version INTEGER NOT NULL DEFAULT 1 CHECK(version >= 1),
  CHECK (due_date IS NULL OR (length(due_date)=10 AND due_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')),
  CHECK ((status='open' AND completed_at IS NULL)
      OR (status='closed' AND completed_at IS NOT NULL))
);
CREATE INDEX idx_todos_status_due ON todos(status, due_date, id)
  WHERE deleted_at IS NULL;
CREATE TABLE todo_sources (
  todo_id INTEGER NOT NULL REFERENCES todos(id) ON DELETE CASCADE,
  document_uid TEXT NOT NULL,
  last_known_path TEXT NOT NULL,
  source_hash TEXT NOT NULL,
  heading TEXT,
  PRIMARY KEY(todo_id, document_uid)
);
CREATE TABLE todo_events (
  id INTEGER PRIMARY KEY,
  todo_id INTEGER NOT NULL REFERENCES todos(id),
  version INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  snapshot_json TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  UNIQUE(todo_id, version)
);
CREATE TABLE reminder_batches (
  uid TEXT PRIMARY KEY,
  day TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('pending','delivered','cancelled')),
  payload_json TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  delivered_at INTEGER,
  CHECK ((state='delivered' AND delivered_at IS NOT NULL)
      OR (state IN ('pending','cancelled') AND delivered_at IS NULL))
);
CREATE TABLE reminder_slots (
  day TEXT NOT NULL,
  slot TEXT NOT NULL CHECK(slot IN ('LOGIN','TEN','FOURTEEN')),
  batch_uid TEXT NOT NULL REFERENCES reminder_batches(uid),
  PRIMARY KEY(day, slot)
);
CREATE TABLE app_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);

CREATE INDEX idx_reminder_pending ON reminder_batches(created_at,uid) WHERE state='pending';
CREATE TABLE todo_search_content (
  id INTEGER PRIMARY KEY REFERENCES todos(id) ON DELETE CASCADE,
  title_terms TEXT NOT NULL,
  body_terms TEXT NOT NULL
);
CREATE VIRTUAL TABLE todo_fts USING fts5(
  title_terms, body_terms,
  content='todo_search_content', content_rowid='id', tokenize='unicode61'
);

CREATE TRIGGER todo_search_content_ai AFTER INSERT ON todo_search_content BEGIN
  INSERT INTO todo_fts(rowid, title_terms, body_terms) VALUES(new.id, new.title_terms, new.body_terms);
END;
CREATE TRIGGER todo_search_content_ad AFTER DELETE ON todo_search_content BEGIN
  INSERT INTO todo_fts(todo_fts, rowid, title_terms, body_terms) VALUES('delete', old.id, old.title_terms, old.body_terms);
END;
CREATE TRIGGER todo_search_content_au AFTER UPDATE ON todo_search_content BEGIN
  INSERT INTO todo_fts(todo_fts, rowid, title_terms, body_terms) VALUES('delete', old.id, old.title_terms, old.body_terms);
  INSERT INTO todo_fts(rowid, title_terms, body_terms) VALUES(new.id, new.title_terms, new.body_terms);
END;

PRAGMA user_version=1;
COMMIT;
