-- 事件驱动线索流水线升级（幂等，可重复执行）
-- 对应流程图：数据事件 → 来源分析 → 信号 → 核验 → 实体关联 → 楼宇匹配 → 评分 → 线索条件 → 人工审核 → 建线索/保存观察记录
-- 本脚本只新增「观察记录」表与菜单，不改动现有业务数据。
SET NAMES utf8mb4;
USE enterprise_db;

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