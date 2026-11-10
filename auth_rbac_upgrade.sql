-- 存量 enterprise_db 升级：Token 认证 + RBAC 权限
-- 使用前先备份数据库；本脚本可重复执行，但会补齐内置角色的默认授权。
SET NAMES utf8mb4;
USE enterprise_db;

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

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r ON r.code = u.role
WHERE NOT EXISTS (SELECT 1 FROM sys_user_role ur WHERE ur.user_id = u.id);
