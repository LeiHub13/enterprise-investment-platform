-- 招商优先级三分模型：增量添加可空列，保留旧任务评分；可重复执行。
SET NAMES utf8mb4;
USE enterprise_db;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_lead_score' AND column_name = 'fit_score');
SET @sql := IF(@col = 0, 'ALTER TABLE crm_lead_score ADD COLUMN fit_score INT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS investment_schema_migration (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    migration_name VARCHAR(120) NOT NULL,
    applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_investment_migration_name (migration_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_lead_score' AND column_name = 'intent_score');
SET @sql := IF(@col = 0, 'ALTER TABLE crm_lead_score ADD COLUMN intent_score INT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_lead_score' AND column_name = 'confidence_score');
SET @sql := IF(@col = 0, 'ALTER TABLE crm_lead_score ADD COLUMN confidence_score INT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_lead_score' AND column_name = 'score_model_version');
SET @sql := IF(@col = 0, 'ALTER TABLE crm_lead_score ADD COLUMN score_model_version VARCHAR(16) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT IGNORE INTO investment_schema_migration (migration_name) VALUES ('agent_score_v2_upgrade');
