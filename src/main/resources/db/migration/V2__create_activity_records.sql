ALTER TABLE members ADD COLUMN record_key VARCHAR(100);
-- 기존 회원에게도 고유한 키를 부여한다. 키 자체는 인증 수단이 아니다.
UPDATE members SET record_key = CONCAT('member-', id) WHERE record_key IS NULL;
ALTER TABLE members MODIFY COLUMN record_key VARCHAR(100) NOT NULL;
ALTER TABLE members ADD CONSTRAINT uk_members_record_key UNIQUE (record_key);

CREATE TABLE activity_records (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    record_key VARCHAR(100) NOT NULL,
    source_name VARCHAR(30) NOT NULL,
    source_mode INT NOT NULL,
    source_type VARCHAR(100) NOT NULL,
    product_name VARCHAR(100) NOT NULL,
    product_vendor VARCHAR(100) NOT NULL,
    period_from DATETIME(6) NOT NULL,
    period_to DATETIME(6) NOT NULL,
    steps DECIMAL(38,20) NOT NULL,
    calories DECIMAL(38,20) NOT NULL,
    distance DECIMAL(38,20) NOT NULL,
    last_update DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_activity_member FOREIGN KEY (record_key) REFERENCES members (record_key),
    CONSTRAINT uk_activity_interval UNIQUE (record_key, source_name, period_from, period_to),
    CONSTRAINT ck_activity_period CHECK (period_to >= period_from),
    CONSTRAINT ck_activity_metrics CHECK (steps >= 0 AND calories >= 0 AND distance >= 0)
);
CREATE INDEX ix_activity_record_date ON activity_records (record_key, period_from);
