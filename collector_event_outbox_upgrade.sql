-- 采集事件事务型发件箱：与采集记录同事务创建，供采集服务投递 RabbitMQ。
-- 先执行 agent_upgrade.sql、data_upgrade.sql、agent_score_v2_upgrade.sql。
SET NAMES utf8mb4;
USE enterprise_db;

CREATE TABLE IF NOT EXISTS collector_event_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_record_id BIGINT NOT NULL,
    event_type VARCHAR(40) NOT NULL DEFAULT 'COLLECTOR_RECORD_CREATED',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    lease_token VARCHAR(36) NULL,
    lease_until DATETIME NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at DATETIME NULL,
    UNIQUE KEY uk_outbox_record (source_record_id),
    KEY idx_outbox_delivery (status, next_attempt_at, lease_until, id),
    CONSTRAINT fk_outbox_record FOREIGN KEY (source_record_id) REFERENCES collector_record(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO investment_schema_migration (migration_name) VALUES ('collector_event_outbox_upgrade');
