CREATE TABLE todos_v2 (
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
  legacy_completion_unknown INTEGER NOT NULL DEFAULT 0 CHECK(legacy_completion_unknown IN (0,1)),
  version INTEGER NOT NULL DEFAULT 1 CHECK(version >= 1),
  CHECK (due_date IS NULL OR (length(due_date)=10 AND due_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')),
  CHECK ((status='open' AND completed_at IS NULL AND legacy_completion_unknown=0)
      OR (status='closed' AND ((completed_at IS NOT NULL AND legacy_completion_unknown=0)
      OR (completed_at IS NULL AND legacy_completion_unknown=1))))
);
INSERT INTO todos_v2(id,uid,title,description_md,status,due_date,priority,created_at,updated_at,completed_at,deleted_at,version)
 SELECT id,uid,title,description_md,status,due_date,priority,created_at,updated_at,completed_at,deleted_at,version FROM todos;
DROP TABLE todos;
ALTER TABLE todos_v2 RENAME TO todos;
CREATE INDEX idx_todos_status_due ON todos(status, due_date, id) WHERE deleted_at IS NULL;
CREATE TABLE legacy_todo_imports (
  source_path TEXT PRIMARY KEY,
  source_hash TEXT NOT NULL,
  todo_uid TEXT NOT NULL UNIQUE REFERENCES todos(uid)
);
PRAGMA user_version=2;
