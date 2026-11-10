-- 下线旧的泛悦 Agent 状态页入口；保留菜单及角色关联以便审计，可重复执行。
SET NAMES utf8mb4;
USE enterprise_db;

UPDATE sys_menu SET visible = FALSE, enabled = FALSE WHERE menu_key = 'panyue-agent';
