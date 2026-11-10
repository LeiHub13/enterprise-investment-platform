-- 采集器前端页面菜单（只读），可重复执行。
SET NAMES utf8mb4;
USE enterprise_db;

INSERT INTO sys_menu (parent_id, menu_type, menu_key, menu_group, label, permission_code, sort, visible, enabled)
VALUES (0, 'MENU', 'collector', '数据采集', '采集器', 'governance:view', 43, TRUE, TRUE)
ON DUPLICATE KEY UPDATE menu_group = VALUES(menu_group), label = VALUES(label),
    permission_code = VALUES(permission_code), sort = VALUES(sort),
    visible = VALUES(visible), enabled = VALUES(enabled);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.menu_key = 'collector'
WHERE r.code IN ('ADMIN', 'DATA_STEWARD');
