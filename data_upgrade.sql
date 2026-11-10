-- 数据采集底座升级（幂等，可重复执行）
-- 对应《泛悦城市广场招商 Multi-Agent 系统》阶段 1：统一企业行为数据池
SET NAMES utf8mb4;
USE enterprise_db;

-- 外部数据原始记录：同一来源同一天快照按 content_hash 去重
-- 外部数据原始记录：同一来源同一天快照按 content_hash 去重
-- 注意：与平台已有导入治理表 data_source_record（source_id/checksum 结构）区分，使用独立表名
CREATE TABLE IF NOT EXISTS collector_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source VARCHAR(24) NOT NULL COMMENT 'QCC / 58 / MANUAL',
    external_id VARCHAR(160) NOT NULL DEFAULT '' COMMENT '来源侧记录标识（如 信用代码:快照日期）',
    record_type VARCHAR(16) NOT NULL COMMENT 'RECRUITMENT / RENTAL',
    enterprise_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary TEXT NULL,
    raw_payload JSON NULL COMMENT '仅保存获准使用的字段，不得长期保存未授权原文',
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    published_at DATETIME NULL,
    fetched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    validity_status VARCHAR(16) NOT NULL DEFAULT 'VALID' COMMENT 'VALID / EXPIRED',
    UNIQUE KEY uk_collector_record_hash (content_hash),
    KEY idx_collector_record_company (enterprise_id, record_type),
    CONSTRAINT fk_collector_record_company FOREIGN KEY (enterprise_id) REFERENCES ent_enterprise (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='采集原始记录（采集服务专用）';

-- 信号表扩展：证据来源、可信度、失效时间
SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ent_company_signal' AND column_name = 'source_record_id');
SET @sql := IF(@col = 0,
    'ALTER TABLE ent_company_signal ADD COLUMN source_record_id BIGINT NULL AFTER source_url',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ent_company_signal' AND column_name = 'confidence');
SET @sql := IF(@col = 0,
    'ALTER TABLE ent_company_signal ADD COLUMN confidence DECIMAL(4,3) NULL AFTER strength',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'ent_company_signal' AND column_name = 'expires_at');
SET @sql := IF(@col = 0,
    'ALTER TABLE ent_company_signal ADD COLUMN expires_at DATETIME NULL AFTER observed_at',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 线索证据：每条推荐线索可追溯到信号与原始记录
CREATE TABLE IF NOT EXISTS crm_lead_evidence (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lead_id BIGINT NOT NULL,
    signal_id BIGINT NULL,
    source_record_id BIGINT NULL,
    evidence_type VARCHAR(24) NOT NULL COMMENT 'RECRUITMENT / RENTAL / VERIFY',
    title VARCHAR(255) NOT NULL,
    summary TEXT NULL,
    source_url VARCHAR(512) NULL,
    confidence DECIMAL(4,3) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_lead_evidence_lead (lead_id),
    CONSTRAINT fk_lead_evidence_lead FOREIGN KEY (lead_id) REFERENCES crm_lead (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='招商线索证据';

-- 楼宇/房源字段挂在招商项目上（一楼宇一项目）
SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_project' AND column_name = 'address');
SET @sql := IF(@col = 0,
    'ALTER TABLE crm_project ADD COLUMN address VARCHAR(500) NULL AFTER project_name',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_project' AND column_name = 'area_min');
SET @sql := IF(@col = 0,
    'ALTER TABLE crm_project ADD COLUMN area_min DECIMAL(10,2) NULL AFTER address',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_project' AND column_name = 'area_max');
SET @sql := IF(@col = 0,
    'ALTER TABLE crm_project ADD COLUMN area_max DECIMAL(10,2) NULL AFTER area_min',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_project' AND column_name = 'rent_price');
SET @sql := IF(@col = 0,
    'ALTER TABLE crm_project ADD COLUMN rent_price DECIMAL(10,2) NULL COMMENT ''元/㎡/天，待定'' AFTER area_max',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_project' AND column_name = 'facilities');
SET @sql := IF(@col = 0,
    'ALTER TABLE crm_project ADD COLUMN facilities JSON NULL AFTER rent_price',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;