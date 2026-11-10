-- 已知企业的企查查招聘监测：任务默认暂停，运行和租约可追溯。
SET NAMES utf8mb4;
USE enterprise_db;

CREATE TABLE IF NOT EXISTS collector_monitor_task (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    enterprise_id BIGINT NOT NULL,
    source VARCHAR(24) NOT NULL DEFAULT 'QCC',
    mode VARCHAR(12) NOT NULL COMMENT 'DEMO / LIVE',
    interval_minutes INT NOT NULL DEFAULT 1440,
    max_pages INT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    next_run_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token VARCHAR(36) NULL,
    lease_until DATETIME NULL,
    last_status VARCHAR(16) NULL,
    last_finished_at DATETIME NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_monitor_enterprise_source_mode (enterprise_id, source, mode),
    KEY idx_monitor_due (enabled, next_run_at, lease_until, id),
    CONSTRAINT fk_monitor_enterprise FOREIGN KEY (enterprise_id) REFERENCES ent_enterprise(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS collector_monitor_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    lease_token VARCHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'RUNNING / SUCCESS / EMPTY / FAILED / ABANDONED',
    error_code VARCHAR(32) NULL,
    created_records INT NOT NULL DEFAULT 0,
    updated_records INT NOT NULL DEFAULT 0,
    pages_fetched INT NOT NULL DEFAULT 0,
    possibly_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL,
    UNIQUE KEY uk_monitor_run_lease (lease_token),
    KEY idx_monitor_run_task (task_id, id),
    CONSTRAINT fk_monitor_run_task FOREIGN KEY (task_id) REFERENCES collector_monitor_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS collector_monitor_cursor (
    task_id BIGINT NOT NULL PRIMARY KEY,
    last_success_at DATETIME NOT NULL,
    last_pages_fetched INT NOT NULL DEFAULT 0,
    possibly_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_monitor_cursor_task FOREIGN KEY (task_id) REFERENCES collector_monitor_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO sys_menu(parent_id, menu_type, menu_key, menu_group, label, permission_code, sort, visible, enabled)
SELECT id, 'BUTTON', NULL, '数据采集', '管理监测任务', 'monitor:manage', 140, TRUE, TRUE
FROM sys_menu m WHERE m.menu_key = 'collector'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.permission_code = 'monitor:manage');

INSERT IGNORE INTO sys_role_menu(role_id, menu_id)
SELECT r.id, m.id FROM sys_role r JOIN sys_menu m ON m.permission_code = 'monitor:manage'
WHERE r.code = 'ADMIN';

INSERT IGNORE INTO investment_schema_migration (migration_name) VALUES ('collector_monitor_upgrade');
