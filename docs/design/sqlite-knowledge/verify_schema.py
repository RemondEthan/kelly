"""Executable checks for design SQL; no application/user data is opened."""
from pathlib import Path
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parent


def database(name):
    conn = sqlite3.connect(':memory:')
    conn.execute('PRAGMA foreign_keys=ON')
    conn.executescript((ROOT / name).read_text())
    return conn


def matches(conn, table, word):
    return [row[0] for row in conn.execute(
        f'SELECT rowid FROM {table} WHERE {table} MATCH ?', (word,))]


class SchemaChecks(unittest.TestCase):
    def setUp(self):
        self.data = database('data-v1.sql')
        self.index = database('index-v1.sql')

    def tearDown(self):
        self.data.close()
        self.index.close()

    def todo(self, uid='t1', status='open', due='2026-11-04'):
        return self.data.execute('''INSERT INTO todos
            (uid,title,status,due_date,created_at,updated_at,completed_at)
            VALUES (?, 'task', ?, ?, 1, 1, ?)''',
            (uid, status, due, 1 if status == 'closed' else None)).lastrowid

    def article(self):
        doc = self.index.execute('''INSERT INTO documents
            (document_uid,path,kind,title,aliases_json,mtime_ms,size_bytes,
             content_hash,parser_version,state)
            VALUES ('doc1','knowledge/articles/a.md','article','Title','[]',1,1,'h1',1,'ready')''').lastrowid
        chunk = self.index.execute('''INSERT INTO chunks
            (document_id,ordinal,heading,start_line,end_line,raw_text)
            VALUES (?,0,'Title',1,5,'original body')''', (doc,)).lastrowid
        return doc, chunk

    def test_versions_and_foreign_keys(self):
        for conn in (self.data, self.index):
            self.assertEqual(conn.execute('PRAGMA user_version').fetchone()[0], 1)
            self.assertEqual(conn.execute('PRAGMA foreign_keys').fetchone()[0], 1)
            self.assertEqual(conn.execute('PRAGMA foreign_key_check').fetchall(), [])

    def test_todo_fts_insert_update_delete(self):
        rowid = self.todo()
        self.data.execute('INSERT INTO todo_search_content VALUES (?,?,?)',
                          (rowid, 'licence 许可 可证 许可证', 'oldword'))
        self.assertEqual(matches(self.data, 'todo_fts', '许可'), [rowid])
        self.data.execute('UPDATE todo_search_content SET body_terms=? WHERE id=?',
                          ('newword', rowid))
        self.assertEqual(matches(self.data, 'todo_fts', 'oldword'), [])
        self.assertEqual(matches(self.data, 'todo_fts', 'newword'), [rowid])
        self.data.execute('DELETE FROM todo_search_content WHERE id=?', (rowid,))
        self.assertEqual(matches(self.data, 'todo_fts', 'newword'), [])
        self.assertEqual(self.data.execute('SELECT count(*) FROM todos').fetchone()[0], 1)

    def test_article_fts_and_cascade(self):
        doc, chunk = self.article()
        self.index.execute('INSERT INTO search_content VALUES (?,?,?,?)',
                           (chunk, 'delivery', 'licence', 'oldword'))
        self.index.execute('UPDATE search_content SET body_terms=? WHERE id=?',
                           ('newword', chunk))
        self.assertEqual(matches(self.index, 'article_fts', 'oldword'), [])
        self.assertEqual(matches(self.index, 'article_fts', 'newword'), [chunk])
        self.index.execute('DELETE FROM documents WHERE id=?', (doc,))
        self.assertEqual(matches(self.index, 'article_fts', 'newword'), [])
        self.assertEqual(self.index.execute('SELECT count(*) FROM chunks').fetchone()[0], 0)

    def test_rollback_restores_fts(self):
        rowid = self.todo()
        self.data.execute('INSERT INTO todo_search_content VALUES (?,?,?)', (rowid, 'alpha', 'body'))
        self.data.commit()
        self.data.execute('BEGIN IMMEDIATE')
        self.data.execute('UPDATE todos SET title=?,version=2 WHERE id=?', ('changed', rowid))
        self.data.execute('UPDATE todo_search_content SET title_terms=? WHERE id=?', ('beta', rowid))
        self.data.rollback()
        self.assertEqual(self.data.execute('SELECT title,version FROM todos').fetchone(), ('task', 1))
        self.assertEqual(matches(self.data, 'todo_fts', 'alpha'), [rowid])
        self.assertEqual(matches(self.data, 'todo_fts', 'beta'), [])

    def test_rebuild_and_integrity(self):
        rowid = self.todo()
        self.data.execute('INSERT INTO todo_search_content VALUES (?,?,?)', (rowid, 'alpha', 'body'))
        _, chunk = self.article()
        self.index.execute('INSERT INTO search_content VALUES (?,?,?,?)', (chunk, 'alpha', 'alias', 'body'))
        for conn, fts, expected in [(self.data, 'todo_fts', rowid), (self.index, 'article_fts', chunk)]:
            conn.execute(f"INSERT INTO {fts}({fts}) VALUES('delete-all')")
            self.assertEqual(matches(conn, fts, 'alpha'), [])
            conn.execute(f"INSERT INTO {fts}({fts}) VALUES('rebuild')")
            conn.execute(f"INSERT INTO {fts}({fts},rank) VALUES('integrity-check',1)")
            self.assertEqual(matches(conn, fts, 'alpha'), [expected])

    def test_status_date_before_limit(self):
        self.todo('closed', 'closed')
        wanted = self.todo('open', 'open')
        self.todo('undated', 'open', None)
        for rowid, in self.data.execute('SELECT id FROM todos').fetchall():
            self.data.execute('INSERT INTO todo_search_content VALUES (?,?,?)', (rowid, 'licence', ''))
        rows = self.data.execute('''SELECT t.id FROM todo_fts
            JOIN todos t ON t.id=todo_fts.rowid
            WHERE todo_fts MATCH ? AND t.status='open' AND t.deleted_at IS NULL
              AND t.due_date >= ? AND t.due_date < ?
            ORDER BY bm25(todo_fts),t.id LIMIT 1''',
            ('licence', '2026-11-04', '2026-11-05')).fetchall()
        self.assertEqual(rows, [(wanted,)])

    def test_constraints_and_slot_atomicity(self):
        self.todo()
        with self.assertRaises(sqlite3.IntegrityError):
            self.data.execute("UPDATE todos SET status='closed' WHERE uid='t1'")
        with self.assertRaises(sqlite3.IntegrityError):
            self.data.execute("UPDATE todos SET version=0 WHERE uid='t1'")
        self.data.execute('''INSERT INTO reminder_batches
            (uid,day,state,payload_json,created_at) VALUES ('b1','2026-11-04','pending','{}',1)''')
        self.data.execute("INSERT INTO reminder_slots VALUES ('2026-11-04','LOGIN','b1')")
        self.data.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            with self.data:
                self.data.execute("INSERT INTO reminder_slots VALUES ('2026-11-04','TEN','b1')")
                self.data.execute("INSERT INTO reminder_slots VALUES ('2026-11-04','LOGIN','b1')")
        self.assertEqual(self.data.execute('SELECT slot FROM reminder_slots').fetchall(), [('LOGIN',)])
        with self.assertRaises(sqlite3.IntegrityError):
            self.data.execute("UPDATE reminder_batches SET state='delivered' WHERE uid='b1'")


if __name__ == '__main__':
    print(f'SQLite {sqlite3.sqlite_version}', flush=True)
    unittest.main(verbosity=2)
