-- MySQL 8.4. Stop application writes and back up before applying after 001/002.
-- Session identity includes this per-account value; old sessions must log in again.
-- Run without mysql --force. MySQL DDL commits implicitly.
ALTER TABLE t_user ADD COLUMN auth_epoch CHAR(36) NULL;
UPDATE t_user SET auth_epoch = UUID() WHERE auth_epoch IS NULL;
ALTER TABLE t_user MODIFY COLUMN auth_epoch CHAR(36) NOT NULL;
