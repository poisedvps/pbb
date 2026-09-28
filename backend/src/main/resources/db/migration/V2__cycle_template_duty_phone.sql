-- 系统设置（键值），目前只有值班电话底色一项
CREATE TABLE app_setting (
    setting_key    VARCHAR(64)  PRIMARY KEY,
    setting_value  VARCHAR(200) NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
INSERT INTO app_setting (setting_key, setting_value) VALUES ('duty_phone_color', '#fde047');

-- 排班周期模板：day1..day7 = 周一..周日的班次代号
CREATE TABLE cycle_template (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(32)  NOT NULL UNIQUE,
    day1        VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    day2        VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    day3        VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    day4        VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    day5        VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    day6        VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    day7        VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 最多一个默认模板
CREATE UNIQUE INDEX uq_cycle_template_default ON cycle_template (is_default) WHERE is_default;
INSERT INTO cycle_template (name, day1, day2, day3, day4, day5, day6, day7, is_default)
VALUES ('标准周期', 'D', 'D', 'D', 'D', 'D', 'X', 'X', TRUE);

-- 该月最近一次按规则生成所用模板；“恢复规则默认”按它重算。模板被删时置空，退回内置规则
ALTER TABLE schedule_month
    ADD COLUMN cycle_template_id BIGINT REFERENCES cycle_template (id) ON DELETE SET NULL;

-- 值班电话草稿：一周一行，week_start 必须是周一
CREATE TABLE duty_phone_week (
    week_start  DATE         PRIMARY KEY CHECK (EXTRACT(ISODOW FROM week_start) = 1),
    staff_id    BIGINT       NOT NULL REFERENCES staff (id),
    updated_by  BIGINT REFERENCES app_user (id),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 值班电话已发布快照（成员、大屏、统计读）
CREATE TABLE duty_phone_published (
    week_start  DATE         PRIMARY KEY CHECK (EXTRACT(ISODOW FROM week_start) = 1),
    staff_id    BIGINT       NOT NULL REFERENCES staff (id)
);
