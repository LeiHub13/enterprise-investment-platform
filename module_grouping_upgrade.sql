-- 模块分组调整：CRM 业务模块 / 标讯雷达模块 / OpenClaw 模块
-- 适用于已建库环境，可重复执行。
SET NAMES utf8mb4;
USE enterprise_db;

-- 标讯雷达独立成组
UPDATE sys_menu SET menu_group = '标讯雷达模块' WHERE menu_key = 'bidradar';

-- 原“业务模块”整体更名为“CRM业务模块”
UPDATE sys_menu SET menu_group = 'CRM业务模块' WHERE menu_group = '业务模块';
UPDATE sys_menu SET label = 'CRM业务模块' WHERE menu_type = 'DIR' AND label = '业务模块';

-- OpenClaw 独立成组，仅管理员可见
INSERT INTO sys_menu (parent_id, menu_type, menu_key, menu_group, label, permission_code, sort, visible, enabled)
VALUES (0, 'MENU', 'openclaw', 'OpenClaw 模块', 'OpenClaw 助手', NULL, 44, TRUE, TRUE)
ON DUPLICATE KEY UPDATE menu_group = VALUES(menu_group), label = VALUES(label),
    sort = VALUES(sort), visible = VALUES(visible), enabled = VALUES(enabled);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.menu_key = 'openclaw'
WHERE r.code = 'ADMIN';
