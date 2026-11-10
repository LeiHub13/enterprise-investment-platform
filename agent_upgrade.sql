-- 招商 Agent 获客工作流 MVP 升级脚本（幂等，可重复执行）
-- 对应设计：企业信号 / 匹配评分 / Agent 运行审计 / 菜单权限
SET NAMES utf8mb4;
USE enterprise_db;

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

-- 隐藏旧的独立状态页，不删除历史授权和业务数据。
UPDATE sys_menu SET visible = FALSE, enabled = FALSE WHERE menu_key = 'panyue-agent';
-- 评分来源标记（企业数据库 / 外部企业），幂等
SET @col := (SELECT COUNT(*) FROM information_schema.columns
             WHERE table_schema = DATABASE() AND table_name = 'crm_lead_score' AND column_name = 'source_type');
SET @sql := IF(@col = 0,
    'ALTER TABLE crm_lead_score ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT ''INTERNAL'' AFTER grade',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
