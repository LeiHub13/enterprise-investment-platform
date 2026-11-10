-- 待审核线索及 Agent 创建幂等记录。先执行 agent_upgrade.sql、data_upgrade.sql 和 agent_score_v2_upgrade.sql。
SET NAMES utf8mb4;
USE enterprise_db;

SET @exists := (SELECT COUNT(*) FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE() AND table_name = 'crm_lead'
                  AND constraint_name = 'chk_crm_lead_status' AND constraint_type = 'CHECK');
SET @sql := IF(@exists > 0, 'ALTER TABLE crm_lead DROP CHECK chk_crm_lead_status', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
ALTER TABLE crm_lead ADD CONSTRAINT chk_crm_lead_status
    CHECK (lead_status IN ('PENDING_REVIEW', 'REJECTED', 'NEW', 'QUALIFIED', 'CONTACTED', 'NEGOTIATING', 'WON', 'LOST'));

CREATE TABLE IF NOT EXISTS agent_lead_creation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    enterprise_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    lead_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_run_company_project (run_id, enterprise_id, project_id),
    UNIQUE KEY uk_agent_company_project (enterprise_id, project_id),
    CONSTRAINT fk_agent_lead_creation_lead FOREIGN KEY (lead_id) REFERENCES crm_lead(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO investment_schema_migration (migration_name) VALUES ('agent_pending_leads_upgrade');
