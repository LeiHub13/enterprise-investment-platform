-- MySQL 8.4 | 全国企业资源与招商管理平台
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS enterprise_db
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE enterprise_db;

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(80) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_sys_user_username (username),
    CONSTRAINT chk_sys_user_role CHECK (
        role IN ('ADMIN', 'DATA_STEWARD', 'INVESTMENT_MANAGER', 'INVESTMENT_OFFICER')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS data_source (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_name VARCHAR(120) NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    base_url VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_data_source_type_name (source_type, source_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ent_enterprise (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uscc CHAR(18) CHARACTER SET ascii COLLATE ascii_general_ci,
    name VARCHAR(255) NOT NULL,
    name_normalized VARCHAR(255) NOT NULL,
    enterprise_type VARCHAR(50),
    province_code VARCHAR(12),
    city_code VARCHAR(12),
    district_code VARCHAR(12),
    industry_code VARCHAR(20),
    industry_name VARCHAR(100),
    registered_capital DECIMAL(18,2),
    established_date DATE,
    business_status VARCHAR(30),
    registered_address VARCHAR(500),
    business_scope TEXT,
    website VARCHAR(500),
    source_id BIGINT,
    last_verified_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_ent_enterprise_uscc (uscc),
    KEY idx_ent_enterprise_name (name_normalized),
    KEY idx_ent_enterprise_region (province_code, city_code, district_code),
    KEY idx_ent_enterprise_industry (industry_code, business_status),
    CONSTRAINT fk_ent_enterprise_source FOREIGN KEY (source_id) REFERENCES data_source (id),
    CONSTRAINT chk_ent_enterprise_uscc CHECK (uscc IS NULL OR CHAR_LENGTH(uscc) = 18),
    CONSTRAINT chk_ent_enterprise_capital CHECK (registered_capital IS NULL OR registered_capital >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ent_tag (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tag_name VARCHAR(100) NOT NULL,
    tag_type VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_ent_tag_type_name (tag_type, tag_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ent_enterprise_tag (
    enterprise_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (enterprise_id, tag_id),
    KEY idx_ent_enterprise_tag_reverse (tag_id, enterprise_id),
    CONSTRAINT fk_ent_enterprise_tag_enterprise FOREIGN KEY (enterprise_id)
        REFERENCES ent_enterprise (id) ON DELETE CASCADE,
    CONSTRAINT fk_ent_enterprise_tag_tag FOREIGN KEY (tag_id)
        REFERENCES ent_tag (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS crm_project (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_name VARCHAR(255) NOT NULL,
    province_code VARCHAR(12),
    city_code VARCHAR(12),
    industry_code VARCHAR(20),
    investment_min DECIMAL(18,2),
    investment_max DECIMAL(18,2),
    requirements TEXT,
    project_status VARCHAR(30),
    owner_id BIGINT,
    created_by BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY idx_crm_project_owner_status (owner_id, project_status),
    CONSTRAINT fk_crm_project_owner FOREIGN KEY (owner_id) REFERENCES sys_user (id),
    CONSTRAINT fk_crm_project_creator FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT chk_crm_project_investment CHECK (
        (investment_min IS NULL OR investment_min >= 0)
        AND (investment_max IS NULL OR investment_max >= 0)
        AND (investment_min IS NULL OR investment_max IS NULL OR investment_max >= investment_min)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS crm_lead (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    enterprise_id BIGINT NOT NULL,
    project_id BIGINT,
    source_channel VARCHAR(50),
    lead_status VARCHAR(30) NOT NULL DEFAULT 'NEW',
    lead_score DECIMAL(5,2),
    owner_id BIGINT,
    next_followup_at DATETIME(6),
    created_by BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    KEY idx_crm_lead_enterprise (enterprise_id),
    KEY idx_crm_lead_project (project_id),
    KEY idx_crm_lead_owner_status (owner_id, lead_status),
    KEY idx_crm_lead_due (next_followup_at),
    CONSTRAINT fk_crm_lead_enterprise FOREIGN KEY (enterprise_id) REFERENCES ent_enterprise (id),
    CONSTRAINT fk_crm_lead_project FOREIGN KEY (project_id) REFERENCES crm_project (id),
    CONSTRAINT fk_crm_lead_owner FOREIGN KEY (owner_id) REFERENCES sys_user (id),
    CONSTRAINT fk_crm_lead_creator FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT chk_crm_lead_status CHECK (
        lead_status IN ('NEW', 'QUALIFIED', 'CONTACTED', 'NEGOTIATING', 'WON', 'LOST')
    ),
    CONSTRAINT chk_crm_lead_score CHECK (lead_score IS NULL OR lead_score BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS crm_contact (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    enterprise_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    position VARCHAR(100),
    phone_ciphertext VARBINARY(512),
    email_ciphertext VARBINARY(768),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_crm_contact_enterprise FOREIGN KEY (enterprise_id)
        REFERENCES ent_enterprise (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS crm_followup (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    lead_id BIGINT NOT NULL,
    contact_id BIGINT,
    followup_type VARCHAR(30),
    content TEXT NOT NULL,
    result TEXT,
    next_action TEXT,
    next_followup_at DATETIME(6),
    created_by BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_crm_followup_lead_time (lead_id, created_at, id),
    CONSTRAINT fk_crm_followup_lead FOREIGN KEY (lead_id) REFERENCES crm_lead (id),
    CONSTRAINT fk_crm_followup_contact FOREIGN KEY (contact_id)
        REFERENCES crm_contact (id) ON DELETE SET NULL,
    CONSTRAINT fk_crm_followup_creator FOREIGN KEY (created_by) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS data_import_batch (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    total_count INT UNSIGNED NOT NULL DEFAULT 0,
    success_count INT UNSIGNED NOT NULL DEFAULT 0,
    fail_count INT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_by BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6),
    CONSTRAINT fk_data_import_batch_source FOREIGN KEY (source_id) REFERENCES data_source (id),
    CONSTRAINT fk_data_import_batch_creator FOREIGN KEY (created_by) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS data_source_record (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    enterprise_id BIGINT,
    import_batch_id BIGINT,
    external_id VARCHAR(255),
    entity_key VARCHAR(255),
    raw_payload JSON,
    checksum CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    parse_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    parse_error TEXT,
    fetched_at DATETIME(6),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_data_source_record_key (entity_key),
    KEY idx_data_source_record_external (source_id, external_id, fetched_at),
    KEY idx_data_source_record_enterprise (enterprise_id, fetched_at),
    CONSTRAINT fk_data_source_record_source FOREIGN KEY (source_id) REFERENCES data_source (id),
    CONSTRAINT fk_data_source_record_enterprise FOREIGN KEY (enterprise_id)
        REFERENCES ent_enterprise (id) ON DELETE SET NULL,
    CONSTRAINT fk_data_source_record_batch FOREIGN KEY (import_batch_id)
        REFERENCES data_import_batch (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS ent_enterprise_change (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    enterprise_id BIGINT,
    source_record_id BIGINT,
    field_name VARCHAR(100) NOT NULL,
    old_value TEXT,
    new_value TEXT,
    changed_by BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_ent_enterprise_change_time (enterprise_id, created_at, id),
    CONSTRAINT fk_ent_enterprise_change_enterprise FOREIGN KEY (enterprise_id)
        REFERENCES ent_enterprise (id) ON DELETE SET NULL,
    CONSTRAINT fk_ent_enterprise_change_record FOREIGN KEY (source_record_id)
        REFERENCES data_source_record (id),
    CONSTRAINT fk_ent_enterprise_change_user FOREIGN KEY (changed_by) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT,
    operation VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_audit_log_resource (resource_type, resource_id, created_at),
    KEY idx_audit_log_user_time (user_id, created_at),
    CONSTRAINT fk_audit_log_user FOREIGN KEY (user_id) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO data_source (source_name, source_type)
VALUES ('人工录入', 'MANUAL'), ('Excel 批量导入', 'EXCEL')
ON DUPLICATE KEY UPDATE id = data_source.id;
