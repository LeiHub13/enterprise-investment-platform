-- 授权企业核验记录：只保存企业身份和经营状态所需字段，不保存联系人信息。
SET NAMES utf8mb4;
USE enterprise_db;

CREATE TABLE IF NOT EXISTS ent_enterprise_verification (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    enterprise_id BIGINT NOT NULL,
    source VARCHAR(24) NOT NULL COMMENT 'QCC_2001 / QCC_2001_DEMO',
    order_ref VARCHAR(128) NOT NULL,
    is_demo BOOLEAN NOT NULL DEFAULT FALSE,
    result_status VARCHAR(16) NOT NULL COMMENT 'VERIFIED / INACTIVE / NOT_FOUND / MISMATCH / PENDING',
    verified_name VARCHAR(1000) NULL,
    verified_uscc VARCHAR(50) NULL,
    business_status VARCHAR(100) NULL,
    registered_address VARCHAR(1000) NULL,
    reason VARCHAR(255) NOT NULL,
    verified_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_verification_source_order (source, order_ref),
    KEY idx_verification_enterprise (enterprise_id, is_demo, id),
    CONSTRAINT fk_verification_enterprise FOREIGN KEY (enterprise_id) REFERENCES ent_enterprise(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO investment_schema_migration (migration_name) VALUES ('enterprise_verification_upgrade');
