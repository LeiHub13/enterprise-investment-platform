-- MySQL 8.4 | 全国企业资源与招商管理平台
SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS enterprise_db
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

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

CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    built_in BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_sys_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT NOT NULL DEFAULT 0,
    menu_type VARCHAR(16) NOT NULL,
    menu_key VARCHAR(64),
    menu_group VARCHAR(32),
    label VARCHAR(100) NOT NULL,
    permission_code VARCHAR(100),
    sort INT NOT NULL DEFAULT 0,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_sys_menu_key (menu_key),
    KEY idx_sys_menu_type_sort (menu_type, sort)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, role_id),
    KEY idx_sys_user_role_role (role_id),
    CONSTRAINT fk_sys_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS sys_role_menu (
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (role_id, menu_id),
    KEY idx_sys_role_menu_menu (menu_id),
    CONSTRAINT fk_sys_role_menu_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE,
    CONSTRAINT fk_sys_role_menu_menu FOREIGN KEY (menu_id) REFERENCES sys_menu (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS sys_role_data_scope (
    role_id BIGINT NOT NULL,
    resource VARCHAR(64) NOT NULL,
    scope VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (role_id, resource),
    CONSTRAINT fk_sys_role_scope_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE,
    CONSTRAINT chk_sys_role_scope CHECK (scope IN ('ALL', 'SELF', 'OWN_LEADS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS sys_access_token (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    ip VARCHAR(64),
    user_agent VARCHAR(255),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_sys_access_token_hash (token_hash),
    KEY idx_sys_access_token_user (user_id),
    KEY idx_sys_access_token_expiry (expires_at),
    CONSTRAINT fk_sys_access_token_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
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
    source_type VARCHAR(30),
    source_url TEXT,
    source_url_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci,
    source_title VARCHAR(255),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_crm_lead_source_hash (source_url_hash),
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

CREATE TABLE IF NOT EXISTS crm_business (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    business_no VARCHAR(32) NOT NULL,
    name VARCHAR(255) NOT NULL,
    customer_id BIGINT NOT NULL,
    project_id BIGINT,
    lead_id BIGINT,
    owner_user_id BIGINT,
    stage VARCHAR(30) NOT NULL DEFAULT 'NEW',
    amount DECIMAL(18,2),
    probability INT,
    expected_close_date DATE,
    deal_date DATE,
    source VARCHAR(50),
    source_type VARCHAR(30),
    source_url TEXT,
    source_url_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci,
    source_title VARCHAR(255),
    remark TEXT,
    created_by BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_crm_business_no (business_no),
    UNIQUE KEY uk_crm_business_lead (lead_id),
    UNIQUE KEY uk_crm_business_source_hash (source_url_hash),
    KEY idx_crm_business_customer (customer_id),
    KEY idx_crm_business_project (project_id),
    KEY idx_crm_business_owner_stage (owner_user_id, stage),
    CONSTRAINT fk_crm_business_customer FOREIGN KEY (customer_id) REFERENCES ent_enterprise (id),
    CONSTRAINT fk_crm_business_project FOREIGN KEY (project_id) REFERENCES crm_project (id) ON DELETE SET NULL,
    CONSTRAINT fk_crm_business_lead FOREIGN KEY (lead_id) REFERENCES crm_lead (id) ON DELETE SET NULL,
    CONSTRAINT fk_crm_business_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_crm_business_creator FOREIGN KEY (created_by) REFERENCES sys_user (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS crm_contract (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    contract_no VARCHAR(32) NOT NULL,
    name VARCHAR(255) NOT NULL,
    customer_id BIGINT NOT NULL,
    business_id BIGINT,
    project_id BIGINT,
    owner_user_id BIGINT,
    amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    sign_date DATE,
    start_date DATE,
    end_date DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    remark TEXT,
    created_by BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_crm_contract_no (contract_no),
    KEY idx_crm_contract_customer (customer_id),
    KEY idx_crm_contract_business (business_id),
    KEY idx_crm_contract_owner_status (owner_user_id, status),
    CONSTRAINT fk_crm_contract_customer FOREIGN KEY (customer_id) REFERENCES ent_enterprise (id),
    CONSTRAINT fk_crm_contract_business FOREIGN KEY (business_id) REFERENCES crm_business (id) ON DELETE SET NULL,
    CONSTRAINT fk_crm_contract_project FOREIGN KEY (project_id) REFERENCES crm_project (id) ON DELETE SET NULL,
    CONSTRAINT fk_crm_contract_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_crm_contract_creator FOREIGN KEY (created_by) REFERENCES sys_user (id) ON DELETE SET NULL,
    CONSTRAINT chk_crm_contract_status CHECK (status IN ('DRAFT', 'SIGNED', 'TERMINATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS crm_receivable (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    receivable_no VARCHAR(32) NOT NULL,
    contract_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    business_id BIGINT,
    owner_user_id BIGINT,
    plan_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    plan_date DATE,
    received_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
    received_date DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    remark TEXT,
    created_by BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_crm_receivable_no (receivable_no),
    KEY idx_crm_receivable_contract (contract_id),
    KEY idx_crm_receivable_customer (customer_id),
    KEY idx_crm_receivable_owner_status (owner_user_id, status),
    KEY idx_crm_receivable_plan_date (plan_date),
    CONSTRAINT fk_crm_receivable_contract FOREIGN KEY (contract_id) REFERENCES crm_contract (id),
    CONSTRAINT fk_crm_receivable_customer FOREIGN KEY (customer_id) REFERENCES ent_enterprise (id),
    CONSTRAINT fk_crm_receivable_business FOREIGN KEY (business_id) REFERENCES crm_business (id) ON DELETE SET NULL,
    CONSTRAINT fk_crm_receivable_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (id) ON DELETE SET NULL,
    CONSTRAINT fk_crm_receivable_creator FOREIGN KEY (created_by) REFERENCES sys_user (id) ON DELETE SET NULL,
    CONSTRAINT chk_crm_receivable_status CHECK (status IN ('PENDING', 'RECEIVED'))
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

-- ---------- RBAC 种子数据 ----------
INSERT INTO sys_role (id, code, name, enabled, built_in) VALUES
    (1, 'ADMIN', '系统管理员', TRUE, TRUE),
    (2, 'DATA_STEWARD', '数据管理员', TRUE, TRUE),
    (3, 'INVESTMENT_MANAGER', '招商经理', TRUE, TRUE),
    (4, 'INVESTMENT_OFFICER', '招商专员', TRUE, TRUE)
ON DUPLICATE KEY UPDATE name = VALUES(name), enabled = VALUES(enabled), built_in = VALUES(built_in);

INSERT INTO sys_menu (id, parent_id, menu_type, menu_key, menu_group, label, permission_code, sort, visible, enabled) VALUES
    (1, 0, 'DIR', NULL, '概览', '概览', NULL, 0, TRUE, TRUE),
    (2, 0, 'MENU', 'dashboard', '概览', '招商工作台', 'dashboard:view', 10, TRUE, TRUE),
    (3, 0, 'DIR', NULL, 'CRM业务模块', 'CRM业务模块', NULL, 0, TRUE, TRUE),
    (4, 0, 'MENU', 'enterprises', 'CRM业务模块', '企业资源库', 'enterprise:view', 20, TRUE, TRUE),
    (5, 0, 'MENU', 'projects', 'CRM业务模块', '招商项目', 'project:view', 30, TRUE, TRUE),
    (6, 0, 'MENU', 'leads', 'CRM业务模块', '招商线索', 'lead:view', 40, TRUE, TRUE),
    (7, 0, 'DIR', NULL, '系统管理', '系统管理', NULL, 0, TRUE, TRUE),
    (8, 0, 'MENU', 'users', '系统管理', '账号管理', 'user:manage', 50, TRUE, TRUE),
    (9, 0, 'MENU', 'data', '系统管理', '数据管理', 'governance:view', 60, TRUE, TRUE),
    (17, 0, 'MENU', 'collector', '数据采集', '采集器', 'governance:view', 43, TRUE, TRUE),
    (10, 0, 'MENU', 'roles', '系统管理', '角色权限', 'system:role:manage', 70, TRUE, TRUE),
    (101, 4, 'BUTTON', NULL, 'CRM业务模块', '企业档案维护', 'enterprise:write', 101, TRUE, TRUE),
    (102, 4, 'BUTTON', NULL, 'CRM业务模块', 'Excel 批量导入', 'enterprise:import', 102, TRUE, TRUE),
    (103, 4, 'BUTTON', NULL, 'CRM业务模块', '企业标签维护', 'enterprise:tag:write', 103, TRUE, TRUE),
    (104, 5, 'BUTTON', NULL, 'CRM业务模块', '招商项目维护', 'project:write', 104, TRUE, TRUE),
    (105, 6, 'BUTTON', NULL, 'CRM业务模块', '新建线索', 'lead:create', 105, TRUE, TRUE),
    (106, 6, 'BUTTON', NULL, 'CRM业务模块', '编辑线索与跟进', 'lead:update', 106, TRUE, TRUE),
    (107, 6, 'BUTTON', NULL, 'CRM业务模块', '删除线索', 'lead:delete', 107, TRUE, TRUE),
    (108, 6, 'BUTTON', NULL, 'CRM业务模块', '分配线索', 'lead:assign', 108, TRUE, TRUE),
    (109, 4, 'BUTTON', NULL, 'CRM业务模块', '查看联系人', 'contact:view', 109, TRUE, TRUE),
    (110, 4, 'BUTTON', NULL, 'CRM业务模块', '维护联系人', 'contact:write', 110, TRUE, TRUE),
    (111, 4, 'BUTTON', NULL, 'CRM业务模块', '删除联系人', 'contact:delete', 111, TRUE, TRUE),
    (112, 8, 'BUTTON', NULL, '系统管理', '查看账号列表', 'user:view', 112, TRUE, TRUE)
ON DUPLICATE KEY UPDATE menu_type = VALUES(menu_type), menu_key = VALUES(menu_key), menu_group = VALUES(menu_group),
    label = VALUES(label), permission_code = VALUES(permission_code), sort = VALUES(sort),
    visible = VALUES(visible), enabled = VALUES(enabled);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
    (2, 1), (2, 2), (2, 3), (2, 4), (2, 7), (2, 9), (2, 17), (2, 101), (2, 102), (2, 103),
    (3, 1), (3, 2), (3, 3), (3, 4), (3, 5), (3, 6), (3, 7),
    (3, 104), (3, 105), (3, 106), (3, 107), (3, 108), (3, 109), (3, 110), (3, 111), (3, 112),
    (4, 1), (4, 2), (4, 3), (4, 4), (4, 5), (4, 6), (4, 105), (4, 106), (4, 109), (4, 110);

INSERT INTO sys_role_data_scope (role_id, resource, scope) VALUES
    (1, 'lead', 'ALL'), (1, 'project', 'ALL'), (1, 'contact', 'ALL'),
    (3, 'lead', 'ALL'), (3, 'project', 'ALL'), (3, 'contact', 'ALL'),
    (4, 'lead', 'SELF'), (4, 'project', 'ALL'), (4, 'contact', 'OWN_LEADS')
ON DUPLICATE KEY UPDATE scope = VALUES(scope);

-- ---------- CRM 种子数据（商机 / 合同 / 回款 / 统计） ----------
INSERT INTO sys_menu (id, parent_id, menu_type, menu_key, menu_group, label, permission_code, sort, visible, enabled) VALUES
    (11, 0, 'MENU', 'businesses', 'CRM业务模块', '商机', 'business:view', 45, TRUE, TRUE),
    (12, 0, 'MENU', 'contracts', 'CRM业务模块', '合同', 'contract:view', 47, TRUE, TRUE),
    (13, 0, 'MENU', 'receivables', 'CRM业务模块', '回款', 'receivable:view', 49, TRUE, TRUE),
    (14, 0, 'MENU', 'statistics', '概览', '业绩统计', 'statistics:view', 15, TRUE, TRUE),
    (15, 0, 'MENU', 'bidradar', '标讯雷达模块', '标讯雷达', 'bidradar:view', 42, TRUE, TRUE),
    (16, 0, 'MENU', 'openclaw', 'OpenClaw 模块', 'OpenClaw 助手', NULL, 44, TRUE, TRUE),
    (113, 11, 'BUTTON', NULL, 'CRM业务模块', '新建商机', 'business:create', 113, TRUE, TRUE),
    (114, 11, 'BUTTON', NULL, 'CRM业务模块', '编辑商机', 'business:update', 114, TRUE, TRUE),
    (115, 11, 'BUTTON', NULL, 'CRM业务模块', '删除商机', 'business:delete', 115, TRUE, TRUE),
    (116, 12, 'BUTTON', NULL, 'CRM业务模块', '新建合同', 'contract:create', 116, TRUE, TRUE),
    (117, 12, 'BUTTON', NULL, 'CRM业务模块', '编辑合同', 'contract:update', 117, TRUE, TRUE),
    (118, 12, 'BUTTON', NULL, 'CRM业务模块', '删除合同', 'contract:delete', 118, TRUE, TRUE),
    (119, 13, 'BUTTON', NULL, 'CRM业务模块', '新建回款', 'receivable:create', 119, TRUE, TRUE),
    (120, 13, 'BUTTON', NULL, 'CRM业务模块', '编辑回款', 'receivable:update', 120, TRUE, TRUE),
    (121, 13, 'BUTTON', NULL, 'CRM业务模块', '删除回款', 'receivable:delete', 121, TRUE, TRUE)
ON DUPLICATE KEY UPDATE menu_type = VALUES(menu_type), menu_key = VALUES(menu_key), menu_group = VALUES(menu_group),
    label = VALUES(label), permission_code = VALUES(permission_code), sort = VALUES(sort),
    visible = VALUES(visible), enabled = VALUES(enabled);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES
    (3, 11), (3, 12), (3, 13), (3, 14), (3, 15),
    (3, 113), (3, 114), (3, 115), (3, 116), (3, 117), (3, 118), (3, 119), (3, 120), (3, 121),
    (4, 11), (4, 12), (4, 13), (4, 14), (4, 15), (4, 113), (4, 114);

INSERT INTO sys_role_data_scope (role_id, resource, scope) VALUES
    (1, 'business', 'ALL'), (1, 'contract', 'ALL'), (1, 'receivable', 'ALL'),
    (3, 'business', 'ALL'), (3, 'contract', 'ALL'), (3, 'receivable', 'ALL'),
    (4, 'business', 'SELF'), (4, 'contract', 'SELF'), (4, 'receivable', 'SELF')
ON DUPLICATE KEY UPDATE scope = VALUES(scope);

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r ON r.code = u.role
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id);

-- ============ 合并自 agent_upgrade.sql ============
-- 招商 Agent 获客工作流 MVP 升级脚本（幂等，可重复执行）
-- 对应设计：企业信号 / 匹配评分 / Agent 运行审计 / 菜单权限
SET NAMES utf8mb4;

-- 企业扩张信号：招聘、融资、分支、经营范围、迁址、新部门
CREATE TABLE IF NOT EXISTS ent_company_signal (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    enterprise_id BIGINT NOT NULL,
    signal_type VARCHAR(24) NOT NULL COMMENT 'RECRUITMENT/FUNDING/BRANCH/SCOPE_CHANGE/RELOCATION/DEPARTMENT',
    title VARCHAR(255) NOT NULL,
    detail TEXT NULL,
    source_name VARCHAR(128) NULL,
    source_url VARCHAR(512) NULL,
    observed_at DATE NULL COMMENT '信号观察日期，推荐时须说明依据时效',
    strength TINYINT NOT NULL DEFAULT 1 COMMENT '1弱 2中 3强',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_signal_enterprise (enterprise_id, observed_at),
    CONSTRAINT fk_signal_enterprise FOREIGN KEY (enterprise_id) REFERENCES ent_enterprise (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='企业扩张信号';

-- 企业-楼宇匹配评分：规则引擎产出，分项可解释
CREATE TABLE IF NOT EXISTS crm_lead_score (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    enterprise_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    total_score INT NOT NULL,
    industry_score INT NOT NULL DEFAULT 0,
    region_score INT NOT NULL DEFAULT 0,
    scale_score INT NOT NULL DEFAULT 0,
    signal_score INT NOT NULL DEFAULT 0,
    completeness_score INT NOT NULL DEFAULT 0,
    grade VARCHAR(16) NOT NULL COMMENT 'PRIORITY/VERIFY/HOLD',
    reasons TEXT NULL COMMENT 'JSON 数组：分项理由与待核实项',
    duplicate_lead_id BIGINT NULL COMMENT '查重命中的已有线索',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_score_run (run_id),
    KEY idx_score_enterprise (enterprise_id, project_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='企业匹配评分';

-- Agent 会话
CREATE TABLE IF NOT EXISTS agent_conversation (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_conversation_user (user_id, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 会话';

-- Agent 任务运行：一次完整获客工作流
CREATE TABLE IF NOT EXISTS agent_run (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    project_id BIGINT NULL,
    message TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/AWAITING_REVIEW/DONE/REJECTED/ERROR',
    report MEDIUMTEXT NULL,
    lead_ids VARCHAR(255) NULL COMMENT '逗号分隔的已创建线索 ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL,
    KEY idx_run_user (user_id, created_at),
    KEY idx_run_conversation (conversation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 任务运行';

-- Agent 工具调用日志：审计与追踪
CREATE TABLE IF NOT EXISTS agent_tool_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    node VARCHAR(40) NOT NULL,
    tool VARCHAR(40) NOT NULL,
    args_summary TEXT NULL,
    result_summary TEXT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OK' COMMENT 'OK/ERROR',
    duration_ms INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_tool_run (run_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='Agent 工具调用日志';

-- 菜单与权限：招商 Agent（管理员 / 招商经理 / 招商专员）
INSERT INTO sys_menu (parent_id, menu_type, menu_key, menu_group, label, permission_code, sort, visible, enabled)
SELECT 0, 'MENU', 'agent', '智能招商', '招商Agent', 'agent:use', 46, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_key = 'agent');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
         JOIN sys_menu m ON m.menu_key = 'agent'
WHERE r.code IN ('ADMIN', 'INVESTMENT_MANAGER', 'INVESTMENT_OFFICER');

-- 移除已废弃的独立泛悦 Agent 菜单（若历史库仍存在）。
DELETE FROM sys_role_menu WHERE menu_id IN (SELECT id FROM sys_menu WHERE menu_key = 'panyue-agent');
DELETE FROM sys_menu WHERE menu_key = 'panyue-agent';
-- 评分来源标记（企业数据库 / 外部企业），幂等
SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_lead_score' AND column_name = 'source_type');
SET @sql := IF(@col = 0,
    'ALTER TABLE crm_lead_score ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT ''INTERNAL'' AFTER grade',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ============ 合并自 data_upgrade.sql ============
-- 数据采集底座升级（幂等，可重复执行）
-- 阶段 1：统一企业行为数据池（招聘/求租信号）
SET NAMES utf8mb4;

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
-- ============ 合并自 event_pipeline_upgrade.sql ============
-- 事件驱动线索流水线升级（幂等，可重复执行）
-- 对应流程图：数据事件 → 来源分析 → 信号 → 核验 → 实体关联 → 楼宇匹配 → 评分 → 线索条件 → 人工审核 → 建线索/保存观察记录
-- 本脚本只新增「观察记录」表与菜单，不改动现有业务数据。
SET NAMES utf8mb4;

-- 观察记录：未达线索条件、核验失败或人工驳回的候选留痕，便于后续复析
CREATE TABLE IF NOT EXISTS crm_lead_observation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id VARCHAR(36) NULL COMMENT '来源 Agent 运行 ID',
    enterprise_id BIGINT NOT NULL,
    project_id BIGINT NULL COMMENT '匹配到的招商项目/楼宇，可空',
    signal_id BIGINT NULL COMMENT '触发事件的企业信号',
    source_record_id BIGINT NULL COMMENT '触发事件的采集原始记录',
    total_score INT NULL COMMENT '规则评分（若有）',
    grade VARCHAR(16) NULL COMMENT 'PRIORITY/VERIFY/HOLD',
    reason TEXT NULL COMMENT '保存观察的原因说明',
    status VARCHAR(20) NOT NULL DEFAULT 'WATCHING' COMMENT 'WATCHING/SUB_THRESHOLD/REJECTED/VERIFY_FAILED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_observation_enterprise (enterprise_id, created_at),
    KEY idx_observation_status (status, created_at),
    KEY idx_observation_run (run_id),
    CONSTRAINT fk_observation_enterprise FOREIGN KEY (enterprise_id) REFERENCES ent_enterprise (id) ON DELETE CASCADE,
    CONSTRAINT fk_observation_project FOREIGN KEY (project_id) REFERENCES crm_project (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='招商线索观察记录';

-- 菜单：观察记录（只读，复用 lead:view 权限），授予管理员 / 招商经理 / 招商专员
INSERT INTO sys_menu (parent_id, menu_type, menu_key, menu_group, label, permission_code, sort, visible, enabled)
SELECT 0, 'MENU', 'observations', 'CRM业务模块', '观察记录', 'lead:view', 41, TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_key = 'observations');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
         JOIN sys_menu m ON m.menu_key = 'observations'
WHERE r.code IN ('ADMIN', 'INVESTMENT_MANAGER', 'INVESTMENT_OFFICER');
-- ============ 合并自 agent_score_v2_upgrade.sql ============
-- 招商优先级三分模型：增量添加可空列，保留旧任务评分；可重复执行。
SET NAMES utf8mb4;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_lead_score' AND column_name = 'fit_score');
SET @sql := IF(@col = 0, 'ALTER TABLE crm_lead_score ADD COLUMN fit_score INT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


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


-- ============ 合并自 collector_event_outbox_upgrade.sql ============
-- 采集事件事务型发件箱：与采集记录同事务创建，供采集服务投递 RabbitMQ。
SET NAMES utf8mb4;

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


-- ============ 合并自 agent_pending_leads_upgrade.sql ============
-- 待审核线索及 Agent 创建幂等记录。先执行 agent_upgrade.sql、data_upgrade.sql 和 agent_score_v2_upgrade.sql。
SET NAMES utf8mb4;

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


-- ============ 合并自 collector_incremental_upgrade.sql ============
-- 增量版本与过期事件。先执行 data_upgrade.sql、collector_event_outbox_upgrade.sql。
-- 历史来源快照保留原行；后续首次触及时才建立游标，不批量改写旧数据。
SET NAMES utf8mb4;

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


-- ============ 合并自 agent_event_dispatch_upgrade.sql ============
-- 采集消息触发 Agent 的幂等登记。先执行 collector_event_outbox_upgrade.sql。
SET NAMES utf8mb4;

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


-- ============ 合并自 collector_monitor_upgrade.sql ============
-- 已知企业的企查查招聘监测：任务默认暂停，运行和租约可追溯。
SET NAMES utf8mb4;

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


-- ============ 合并自 enterprise_verification_upgrade.sql ============
-- 授权企业核验记录：只保存企业身份和经营状态所需字段，不保存联系人信息。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS ent_enterprise_verification (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    enterprise_id BIGINT NOT NULL,
    source VARCHAR(24) NOT NULL COMMENT 'QCC_2001 / QCC_2001_DEMO',
    order_ref VARCHAR(128) NOT NULL,
    is_demo BOOLEAN NOT NULL DEFAULT FALSE,
    result_status VARCHAR(16) NOT NULL COMMENT 'VERIFIED / INACTIVE / NOT_FOUND / MISMATCH / PENDING',
    verified_name VARCHAR(1000) NULL,
    verified_uscc VARCHAR(50) NULL,
    business_status VARCHAR(100) NULL,
    registered_address VARCHAR(1000) NULL,
    reason VARCHAR(255) NOT NULL,
    verified_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_verification_source_order (source, order_ref),
    KEY idx_verification_enterprise (enterprise_id, is_demo, id),
    CONSTRAINT fk_verification_enterprise FOREIGN KEY (enterprise_id) REFERENCES ent_enterprise(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ============ 合并自 agent_building_seed_upgrade.sql ============
-- 默认招商目标：目标面积不是已核实的实际可租面积；不覆盖已有项目信息。
SET NAMES utf8mb4;

SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_project' AND column_name = 'district_code');
SET @sql := IF(@col = 0, 'ALTER TABLE crm_project ADD COLUMN district_code VARCHAR(12) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_project' AND column_name = 'building_type');
SET @sql := IF(@col = 0, 'ALTER TABLE crm_project ADD COLUMN building_type VARCHAR(50) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_project' AND column_name = 'target_area_min');
SET @sql := IF(@col = 0, 'ALTER TABLE crm_project ADD COLUMN target_area_min DECIMAL(10,2) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_project' AND column_name = 'target_area_max');
SET @sql := IF(@col = 0, 'ALTER TABLE crm_project ADD COLUMN target_area_max DECIMAL(10,2) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

INSERT INTO crm_project (project_name, province_code, city_code, district_code, building_type,
                         target_area_min, target_area_max, requirements, project_status)
SELECT '南京鼓楼示范写字楼', '320000', '320100', '320106', '普通写字楼', 100, 300,
       '招商目标办公面积100～300㎡；实际可租面积、地址、租金和配套待核实', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM crm_project WHERE project_name = '南京鼓楼示范写字楼');

