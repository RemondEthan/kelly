-- Design artifact: fresh database schema. Not yet connected to the application.
-- Configure journal_mode=WAL before this transaction; per connection: foreign_keys=ON, busy_timeout=5000.
PRAGMA foreign_keys=ON;
BEGIN IMMEDIATE;
CREATE TABLE documents (
  id INTEGER PRIMARY KEY,
  document_uid TEXT NOT NULL UNIQUE,
  path TEXT NOT NULL UNIQUE,
  kind TEXT NOT NULL,
  title TEXT NOT NULL,
  aliases_json TEXT NOT NULL,
  event_date TEXT,
  mtime_ms INTEGER NOT NULL,
  size_bytes INTEGER NOT NULL,
  content_hash TEXT NOT NULL,
  parser_version INTEGER NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('ready','stale','missing','conflict','error'))
);
CREATE INDEX idx_documents_kind_date ON documents(kind,event_date,id);
CREATE TABLE document_tags (
  document_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  tag TEXT NOT NULL,
  PRIMARY KEY(document_id,tag)
);
CREATE INDEX idx_tags_lookup ON document_tags(tag,document_id);
CREATE TABLE chunks (
  id INTEGER PRIMARY KEY,
  document_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL,
  heading TEXT NOT NULL,
  start_line INTEGER NOT NULL,
  end_line INTEGER NOT NULL,
  raw_text TEXT NOT NULL,
  UNIQUE(document_id,ordinal)
);
CREATE TABLE search_content (
  id INTEGER PRIMARY KEY REFERENCES chunks(id) ON DELETE CASCADE,
  title_terms TEXT NOT NULL,
  alias_terms TEXT NOT NULL,
  body_terms TEXT NOT NULL
);
CREATE VIRTUAL TABLE article_fts USING fts5(
  title_terms, alias_terms, body_terms,
  content='search_content', content_rowid='id', tokenize='unicode61'
);

CREATE TRIGGER search_content_ai AFTER INSERT ON search_content BEGIN
  INSERT INTO article_fts(rowid, title_terms, alias_terms, body_terms) VALUES(new.id, new.title_terms, new.alias_terms, new.body_terms);
END;
CREATE TRIGGER search_content_ad AFTER DELETE ON search_content BEGIN
  INSERT INTO article_fts(article_fts, rowid, title_terms, alias_terms, body_terms) VALUES('delete', old.id, old.title_terms, old.alias_terms, old.body_terms);
END;
CREATE TRIGGER search_content_au AFTER UPDATE ON search_content BEGIN
  INSERT INTO article_fts(article_fts, rowid, title_terms, alias_terms, body_terms) VALUES('delete', old.id, old.title_terms, old.alias_terms, old.body_terms);
  INSERT INTO article_fts(rowid, title_terms, alias_terms, body_terms) VALUES(new.id, new.title_terms, new.alias_terms, new.body_terms);
END;

PRAGMA user_version=1;
COMMIT;
