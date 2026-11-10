-- 存量 enterprise_db 升级：CRM 商机 / 合同 / 回款 / 业绩统计
-- 使用前先备份数据库；本脚本只新增表、菜单和授权，不改动现有业务数据。
SET NAMES utf8mb4;
USE enterprise_db;

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
    remark TEXT,
    created_by BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_crm_business_no (business_no),
    UNIQUE KEY uk_crm_business_lead (lead_id),
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

-- ---------- CRM 种子数据（商机 / 合同 / 回款 / 统计） ----------
INSERT INTO sys_menu (id, parent_id, menu_type, menu_key, menu_group, label, permission_code, sort, visible, enabled) VALUES
    (11, 0, 'MENU', 'businesses', 'CRM业务模块', '商机', 'business:view', 45, TRUE, TRUE),
    (12, 0, 'MENU', 'contracts', 'CRM业务模块', '合同', 'contract:view', 47, TRUE, TRUE),
    (13, 0, 'MENU', 'receivables', 'CRM业务模块', '回款', 'receivable:view', 49, TRUE, TRUE),
    (14, 0, 'MENU', 'statistics', '概览', '业绩统计', 'statistics:view', 15, TRUE, TRUE),
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
    (3, 11), (3, 12), (3, 13), (3, 14),
    (3, 113), (3, 114), (3, 115), (3, 116), (3, 117), (3, 118), (3, 119), (3, 120), (3, 121),
    (4, 11), (4, 12), (4, 13), (4, 14), (4, 113), (4, 114);

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
