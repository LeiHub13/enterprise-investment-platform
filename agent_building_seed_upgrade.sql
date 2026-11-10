-- 默认招商目标：目标面积不是已核实的实际可租面积；不覆盖已有项目信息。
-- 先执行 agent_upgrade.sql、data_upgrade.sql、agent_score_v2_upgrade.sql。
SET NAMES utf8mb4;
USE enterprise_db;

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
SELECT '泛悦城市广场', '320000', '320100', '320106', '普通写字楼', 100, 300,
       '招商目标办公面积100～300㎡；实际可租面积、地址、租金和配套待核实', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM crm_project WHERE project_name = '泛悦城市广场');

INSERT IGNORE INTO investment_schema_migration (migration_name) VALUES ('agent_building_seed_upgrade');
