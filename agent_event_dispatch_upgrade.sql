-- 采集消息触发 Agent 的幂等登记。先执行 collector_event_outbox_upgrade.sql。
SET NAMES utf8mb4;
USE enterprise_db;

CREATE TABLE IF NOT EXISTS agent_event_dispatch (
    outbox_event_id BIGINT NOT NULL PRIMARY KEY,
    run_id VARCHAR(36) NULL,
    owner_id BIGINT NOT NULL,
    disposition VARCHAR(16) NOT NULL COMMENT 'DISPATCHED / SKIPPED',
    reason VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_event_dispatch_run (run_id),
    KEY idx_agent_event_dispatch_owner (owner_id, created_at),
    CONSTRAINT fk_agent_dispatch_outbox FOREIGN KEY (outbox_event_id) REFERENCES collector_event_outbox(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
             WHERE table_schema = DATABASE() AND table_name = 'agent_run' AND index_name = 'idx_agent_run_user_status_time');
SET @sql := IF(@idx = 0, 'ALTER TABLE agent_run ADD INDEX idx_agent_run_user_status_time (user_id, status, created_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO investment_schema_migration (migration_name) VALUES ('agent_event_dispatch_upgrade');
