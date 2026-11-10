-- PanYue MVP, MySQL 8.0.16+; execute with the intended empty database selected.
-- Times are UTC. Do not execute this script against the existing enterprise_db.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS t_company (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_name VARCHAR(255) NOT NULL,
    credit_code CHAR(18) CHARACTER SET ascii COLLATE ascii_bin NULL,
    identity_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    -- ID from the existing enterprise service; integration is not implemented.
    enterprise_id BIGINT UNSIGNED NULL,
    industry VARCHAR(128) NULL,
    city VARCHAR(64) NULL,
    district VARCHAR(64) NULL,
    address VARCHAR(512) NULL,
    employee_scale VARCHAR(64) NULL,
    business_status VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_company_credit_code (credit_code),
    UNIQUE KEY uq_company_enterprise_id (enterprise_id),
    KEY idx_company_name (company_name),
    KEY idx_company_region (city, district),
    CONSTRAINT chk_company_name CHECK (CHAR_LENGTH(TRIM(company_name)) > 0),
    CONSTRAINT chk_company_credit_code CHECK (credit_code IS NULL OR CHAR_LENGTH(credit_code) = 18),
    CONSTRAINT chk_company_identity CHECK (
        identity_status IN ('PENDING', 'VERIFIED', 'REJECTED')
        AND (identity_status <> 'VERIFIED' OR credit_code IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS t_source_record (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    source VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_id VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NULL,
    record_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    company_id BIGINT UNSIGNED NULL,
    -- Only store payloads permitted by the data agreement; a summary may suffice.
    raw_payload JSON NULL,
    evidence_summary TEXT NULL,
    source_url VARCHAR(2048) NULL,
    content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    fallback_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin
        GENERATED ALWAYS AS (IF(external_id IS NULL, content_hash, NULL)) STORED,
    published_at DATETIME(6) NULL,
    fetched_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    validity_status VARCHAR(16) NOT NULL DEFAULT 'VALID',
    PRIMARY KEY (id),
    UNIQUE KEY uq_source_external (source, record_type, external_id),
    UNIQUE KEY uq_source_fallback (source, record_type, fallback_hash),
    UNIQUE KEY uq_source_id_company (id, company_id),
    UNIQUE KEY uq_source_id_company_type (id, company_id, record_type),
    KEY idx_source_company_time (company_id, fetched_at),
    CONSTRAINT fk_source_company FOREIGN KEY (company_id) REFERENCES t_company (id),
    CONSTRAINT chk_source_name CHECK (CHAR_LENGTH(TRIM(source)) > 0),
    CONSTRAINT chk_source_external_id CHECK (external_id IS NULL OR CHAR_LENGTH(TRIM(external_id)) > 0),
    CONSTRAINT chk_source_type CHECK (record_type IN ('RECRUITMENT', 'RENTAL', 'COMPANY_PROFILE')),
    CONSTRAINT chk_source_hash CHECK (content_hash REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_source_validity CHECK (validity_status IN ('VALID', 'EXPIRED', 'INVALID'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS t_company_signal (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_id BIGINT UNSIGNED NOT NULL,
    signal_type VARCHAR(32) NOT NULL,
    signal_score TINYINT UNSIGNED NOT NULL,
    source_record_id BIGINT UNSIGNED NOT NULL,
    source_record_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    confidence DECIMAL(5,4) NOT NULL,
    description TEXT NULL,
    details JSON NULL,
    occurred_at DATETIME(6) NULL,
    expires_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_signal_record_type (source_record_id, signal_type),
    UNIQUE KEY uq_signal_id_company_source (id, company_id, source_record_id),
    KEY idx_signal_company_type (company_id, signal_type, occurred_at),
    CONSTRAINT fk_signal_company FOREIGN KEY (company_id) REFERENCES t_company (id),
    CONSTRAINT fk_signal_source_company FOREIGN KEY (source_record_id, company_id, source_record_type)
        REFERENCES t_source_record (id, company_id, record_type),
    CONSTRAINT chk_signal_type CHECK (
        (signal_type = 'RECRUITMENT_EXPANSION' AND source_record_type = 'RECRUITMENT')
        OR (signal_type = 'OFFICE_RENTAL' AND source_record_type = 'RENTAL')
    ),
    CONSTRAINT chk_signal_score CHECK (signal_score BETWEEN 0 AND 100),
    CONSTRAINT chk_signal_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT chk_signal_time CHECK (expires_at IS NULL OR occurred_at IS NULL OR expires_at >= occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS t_building (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    building_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    building_name VARCHAR(255) NOT NULL,
    city VARCHAR(64) NOT NULL,
    district VARCHAR(64) NOT NULL,
    address VARCHAR(512) NULL,
    -- The seeded values are the target range, not verified available inventory.
    min_area DECIMAL(10,2) NULL,
    max_area DECIMAL(10,2) NULL,
    rent_price DECIMAL(12,2) NULL,
    rent_unit VARCHAR(32) NULL,
    building_type VARCHAR(64) NULL,
    facilities JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_building_code (building_code),
    CONSTRAINT chk_building_min_area CHECK (min_area IS NULL OR min_area > 0),
    CONSTRAINT chk_building_max_area CHECK (max_area IS NULL OR max_area > 0),
    CONSTRAINT chk_building_area_order CHECK (min_area IS NULL OR max_area IS NULL OR min_area <= max_area),
    CONSTRAINT chk_building_rent CHECK (rent_price IS NULL OR rent_price >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS t_lead (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_id BIGINT UNSIGNED NOT NULL,
    building_id BIGINT UNSIGNED NOT NULL,
    intent_score TINYINT UNSIGNED NOT NULL,
    match_score TINYINT UNSIGNED NOT NULL,
    confidence_score TINYINT UNSIGNED NOT NULL,
    priority_score TINYINT UNSIGNED NOT NULL,
    scoring_reasons JSON NULL,
    lead_status VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW',
    reviewed_by_user_id BIGINT UNSIGNED NULL,
    reviewed_at DATETIME(6) NULL,
    review_note TEXT NULL,
    -- External CRM ID, populated only after approved publication.
    crm_lead_id BIGINT UNSIGNED NULL,
    last_followup_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_lead_company_building (company_id, building_id),
    UNIQUE KEY uq_lead_id_company (id, company_id),
    UNIQUE KEY uq_lead_crm_id (crm_lead_id),
    KEY idx_lead_status_priority (lead_status, priority_score),
    CONSTRAINT fk_lead_company FOREIGN KEY (company_id) REFERENCES t_company (id),
    CONSTRAINT fk_lead_building FOREIGN KEY (building_id) REFERENCES t_building (id),
    CONSTRAINT chk_lead_scores CHECK (
        intent_score <= 100 AND match_score <= 100
        AND confidence_score <= 100 AND priority_score <= 100
    ),
    CONSTRAINT chk_lead_review CHECK (
        (lead_status = 'PENDING_REVIEW' AND reviewed_by_user_id IS NULL AND reviewed_at IS NULL)
        OR (lead_status IN ('APPROVED', 'REJECTED') AND reviewed_by_user_id IS NOT NULL AND reviewed_at IS NOT NULL)
    ),
    CONSTRAINT chk_lead_crm_approved CHECK (crm_lead_id IS NULL OR lead_status = 'APPROVED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS t_lead_evidence (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lead_id BIGINT UNSIGNED NOT NULL,
    company_id BIGINT UNSIGNED NOT NULL,
    source_record_id BIGINT UNSIGNED NOT NULL,
    signal_id BIGINT UNSIGNED NULL,
    signal_key BIGINT UNSIGNED GENERATED ALWAYS AS (COALESCE(signal_id, 0)) STORED,
    description TEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_lead_evidence (lead_id, source_record_id, signal_key),
    KEY idx_evidence_lead_company (lead_id, company_id),
    KEY idx_evidence_source_company (source_record_id, company_id),
    KEY idx_evidence_signal_company_source (signal_id, company_id, source_record_id),
    CONSTRAINT fk_evidence_lead_company FOREIGN KEY (lead_id, company_id)
        REFERENCES t_lead (id, company_id),
    CONSTRAINT fk_evidence_source_company FOREIGN KEY (source_record_id, company_id)
        REFERENCES t_source_record (id, company_id),
    CONSTRAINT fk_evidence_signal_company_source FOREIGN KEY (signal_id, company_id, source_record_id)
        REFERENCES t_company_signal (id, company_id, source_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS t_lead_followup (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    lead_id BIGINT UNSIGNED NOT NULL,
    created_by_user_id BIGINT UNSIGNED NOT NULL,
    followup_content TEXT NOT NULL,
    followed_at DATETIME(6) NOT NULL,
    next_followup_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_followup_lead_time (lead_id, followed_at),
    CONSTRAINT fk_followup_lead FOREIGN KEY (lead_id) REFERENCES t_lead (id),
    CONSTRAINT chk_followup_content CHECK (CHAR_LENGTH(TRIM(followup_content)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS t_agent_run (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_name VARCHAR(64) NOT NULL,
    input_data JSON NULL,
    output_data JSON NULL,
    execution_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    error_message TEXT NULL,
    start_time DATETIME(6) NULL,
    end_time DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_agent_run_id (run_id),
    KEY idx_agent_task (task_id, created_at),
    KEY idx_agent_status (execution_status, created_at),
    CONSTRAINT chk_agent_status CHECK (execution_status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_agent_time CHECK (end_time IS NULL OR start_time IS NULL OR end_time >= start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS t_monitor_schedule (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_id BIGINT UNSIGNED NOT NULL,
    source VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    interval_days SMALLINT UNSIGNED NOT NULL,
    schedule_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    last_checked_at DATETIME(6) NULL,
    next_check_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_schedule_company_source (company_id, source),
    KEY idx_schedule_due (schedule_status, next_check_at),
    CONSTRAINT fk_schedule_company FOREIGN KEY (company_id) REFERENCES t_company (id),
    CONSTRAINT chk_schedule_interval CHECK (interval_days > 0),
    CONSTRAINT chk_schedule_status CHECK (schedule_status IN ('ACTIVE', 'PAUSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS t_api_call_log (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    company_id BIGINT UNSIGNED NULL,
    source VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    api_name VARCHAR(128) NOT NULL,
    task_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    call_status VARCHAR(16) NOT NULL,
    http_status SMALLINT UNSIGNED NULL,
    charged_amount DECIMAL(12,4) NULL,
    called_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_api_call_source_time (source, called_at),
    KEY idx_api_call_company_time (company_id, called_at),
    CONSTRAINT fk_api_call_company FOREIGN KEY (company_id) REFERENCES t_company (id),
    CONSTRAINT chk_api_call_status CHECK (call_status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT chk_api_call_amount CHECK (charged_amount IS NULL OR charged_amount >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO t_building (building_code, building_name, city, district, min_area, max_area)
SELECT 'PANYUE_CITY_SQUARE', '泛悦城市广场', '南京', '鼓楼区', 100.00, 300.00
WHERE NOT EXISTS (SELECT 1 FROM t_building WHERE building_code = 'PANYUE_CITY_SQUARE');
