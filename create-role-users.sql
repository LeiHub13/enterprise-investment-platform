-- 为 DATA_STEWARD / INVESTMENT_MANAGER / INVESTMENT_OFFICER 各创建一个初始账号
-- 初始密码见交付说明；首次登录后请立即修改密码。
-- 执行前请备份数据库；本脚本可重复执行。
SET NAMES utf8mb4;
USE enterprise_db;

START TRANSACTION;

INSERT INTO sys_user (username, password_hash, role, enabled) VALUES
    ('data_steward', '$2b$10$Hdd06dUlvqCenTTYsoqb0.ja/mie48xVieDzn2FQmW/jdqcH0Bwhy', 'DATA_STEWARD', TRUE)
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), role = VALUES(role), enabled = TRUE;
SET @data_steward_id = (SELECT id FROM sys_user WHERE username = 'data_steward');
INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT @data_steward_id, id FROM sys_role WHERE code = 'DATA_STEWARD';
INSERT INTO audit_log (user_id, operation, resource_type, resource_id)
SELECT 1, 'USER_CREATE', 'sys_user', @data_steward_id
WHERE NOT EXISTS (
    SELECT 1 FROM audit_log WHERE user_id = 1 AND operation = 'USER_CREATE'
      AND resource_type = 'sys_user' AND resource_id = @data_steward_id
);

INSERT INTO sys_user (username, password_hash, role, enabled) VALUES
    ('investment_manager', '$2b$10$Hdd06dUlvqCenTTYsoqb0.ja/mie48xVieDzn2FQmW/jdqcH0Bwhy', 'INVESTMENT_MANAGER', TRUE)
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), role = VALUES(role), enabled = TRUE;
SET @investment_manager_id = (SELECT id FROM sys_user WHERE username = 'investment_manager');
INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT @investment_manager_id, id FROM sys_role WHERE code = 'INVESTMENT_MANAGER';
INSERT INTO audit_log (user_id, operation, resource_type, resource_id)
SELECT 1, 'USER_CREATE', 'sys_user', @investment_manager_id
WHERE NOT EXISTS (
    SELECT 1 FROM audit_log WHERE user_id = 1 AND operation = 'USER_CREATE'
      AND resource_type = 'sys_user' AND resource_id = @investment_manager_id
);

INSERT INTO sys_user (username, password_hash, role, enabled) VALUES
    ('investment_officer', '$2b$10$Hdd06dUlvqCenTTYsoqb0.ja/mie48xVieDzn2FQmW/jdqcH0Bwhy', 'INVESTMENT_OFFICER', TRUE)
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), role = VALUES(role), enabled = TRUE;
SET @investment_officer_id = (SELECT id FROM sys_user WHERE username = 'investment_officer');
INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT @investment_officer_id, id FROM sys_role WHERE code = 'INVESTMENT_OFFICER';
INSERT INTO audit_log (user_id, operation, resource_type, resource_id)
SELECT 1, 'USER_CREATE', 'sys_user', @investment_officer_id
WHERE NOT EXISTS (
    SELECT 1 FROM audit_log WHERE user_id = 1 AND operation = 'USER_CREATE'
      AND resource_type = 'sys_user' AND resource_id = @investment_officer_id
);

COMMIT;

SELECT u.id, u.username, u.role, u.enabled, COUNT(ur.role_id) AS role_count
FROM sys_user u
LEFT JOIN sys_user_role ur ON ur.user_id = u.id
WHERE u.username IN ('data_steward', 'investment_manager', 'investment_officer')
GROUP BY u.id
ORDER BY u.id;
