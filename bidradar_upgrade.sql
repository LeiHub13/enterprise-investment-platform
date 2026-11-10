-- 存量 enterprise_db 升级：标讯雷达菜单与线索/商机来源字段
-- 使用前备份数据库；本脚本中的 ALTER TABLE 只需执行一次。
SET NAMES utf8mb4;
USE enterprise_db;

ALTER TABLE crm_lead
    ADD COLUMN source_type VARCHAR(30) NULL,
    ADD COLUMN source_url TEXT NULL,
    ADD COLUMN source_url_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci NULL,
    ADD COLUMN source_title VARCHAR(255) NULL,
    ADD UNIQUE KEY uk_crm_lead_source_hash (source_url_hash);

ALTER TABLE crm_business
    ADD COLUMN source_type VARCHAR(30) NULL,
    ADD COLUMN source_url TEXT NULL,
    ADD COLUMN source_url_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci NULL,
    ADD COLUMN source_title VARCHAR(255) NULL,
    ADD UNIQUE KEY uk_crm_business_source_hash (source_url_hash);

INSERT INTO sys_menu (id, parent_id, menu_type, menu_key, menu_group, label, permission_code, sort, visible, enabled) VALUES
    (15, 0, 'MENU', 'bidradar', '标讯雷达模块', '标讯雷达', 'bidradar:view', 42, TRUE, TRUE)
ON DUPLICATE KEY UPDATE menu_type = VALUES(menu_type), menu_key = VALUES(menu_key),
    menu_group = VALUES(menu_group), label = VALUES(label), permission_code = VALUES(permission_code),
    sort = VALUES(sort), visible = VALUES(visible), enabled = VALUES(enabled);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, id FROM sys_menu;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id) VALUES (3, 15), (4, 15);
