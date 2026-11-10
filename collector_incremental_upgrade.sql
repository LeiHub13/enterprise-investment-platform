-- 增量版本与过期事件。先执行 data_upgrade.sql、collector_event_outbox_upgrade.sql。
-- 历史来源快照保留原行；后续首次触及时才建立游标，不批量改写旧数据。
SET NAMES utf8mb4;
USE enterprise_db;

CREATE TABLE IF NOT EXISTS collector_source_cursor (
    identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci PRIMARY KEY,
    enterprise_id BIGINT NOT NULL,
    current_record_id BIGINT NULL,
    current_version INT NOT NULL DEFAULT 0,
    current_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci NULL,
    source_updated_at DATETIME(6) NULL COMMENT '来源更新时间，统一使用 UTC；未知时为 NULL',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_collector_cursor_enterprise (enterprise_id),
    CONSTRAINT fk_collector_cursor_enterprise FOREIGN KEY (enterprise_id) REFERENCES ent_enterprise(id) ON DELETE CASCADE,
    CONSTRAINT fk_collector_cursor_record FOREIGN KEY (current_record_id) REFERENCES collector_record(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @precision := (SELECT datetime_precision FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'collector_source_cursor' AND column_name = 'source_updated_at');
SET @sql := IF(@precision != 6,
    'ALTER TABLE collector_source_cursor MODIFY source_updated_at DATETIME(6) NULL COMMENT ''来源更新时间，统一使用 UTC；未知时为 NULL''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'collector_record' AND column_name = 'identity_hash');
SET @sql := IF(@col = 0,
    'ALTER TABLE collector_record ADD COLUMN identity_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'collector_record' AND column_name = 'record_version');
SET @sql := IF(@col = 0,
    'ALTER TABLE collector_record ADD COLUMN record_version INT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'collector_record' AND column_name = 'change_type');
SET @sql := IF(@col = 0,
    'ALTER TABLE collector_record ADD COLUMN change_type VARCHAR(16) NOT NULL DEFAULT ''NEW''', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema = DATABASE() AND table_name = 'collector_record' AND index_name = 'idx_collector_identity_version');
SET @sql := IF(@idx = 0,
    'ALTER TABLE collector_record ADD INDEX idx_collector_identity_version (identity_hash, record_version)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 一个来源版本可以分别发布创建/更新与失效事件，但每类事件只能发布一次。
SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema = DATABASE() AND table_name = 'collector_event_outbox' AND index_name = 'uk_outbox_record_event');
SET @sql := IF(@idx = 0,
    'ALTER TABLE collector_event_outbox ADD UNIQUE KEY uk_outbox_record_event (source_record_id, event_type)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema = DATABASE() AND table_name = 'collector_event_outbox' AND index_name = 'uk_outbox_record');
SET @sql := IF(@idx > 0, 'ALTER TABLE collector_event_outbox DROP INDEX uk_outbox_record', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO investment_schema_migration (migration_name) VALUES ('collector_incremental_upgrade');
