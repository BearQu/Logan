DROP TABLE IF EXISTS logan_crash_fix;
DROP TABLE IF EXISTS logan_app;
ALTER TABLE logan_crash_issue DROP COLUMN reopen_count;
ALTER TABLE logan_crash_issue DROP COLUMN last_fix_note;
ALTER TABLE web_task DROP COLUMN app_id;
