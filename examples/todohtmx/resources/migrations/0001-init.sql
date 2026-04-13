-- prev: base

CREATE TABLE todos (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  title     TEXT    NOT NULL,
  completed INTEGER NOT NULL DEFAULT 0
);
