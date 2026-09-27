-- 医院信息科排班表 · 初始表结构（与 docs/design/architecture.md §4 一致）

CREATE TABLE staff (
    id            BIGSERIAL PRIMARY KEY,
    emp_no        VARCHAR(32)  NOT NULL UNIQUE,
    name          VARCHAR(32)  NOT NULL,
    position      VARCHAR(32),
    phone         VARCHAR(32),
    schedulable   BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order    INT          NOT NULL DEFAULT 0,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id                    BIGSERIAL PRIMARY KEY,
    username              VARCHAR(32)  NOT NULL UNIQUE,
    password_hash         VARCHAR(100) NOT NULL,
    display_name          VARCHAR(32)  NOT NULL,
    role                  VARCHAR(16)  NOT NULL CHECK (role IN ('ADMIN', 'MEMBER', 'SCREEN')),
    staff_id              BIGINT REFERENCES staff (id),
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password  BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_attempts       INT          NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    last_login_at         TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE shift_type (
    code            VARCHAR(4)   PRIMARY KEY,
    name            VARCHAR(16)  NOT NULL,
    start_time      TIME,
    end_time        TIME,
    cross_day       BOOLEAN      NOT NULL DEFAULT FALSE,
    work_hours      NUMERIC(4,1) NOT NULL DEFAULT 0,
    counts_as_work  BOOLEAN      NOT NULL DEFAULT FALSE,
    color           VARCHAR(16)  NOT NULL,
    sort_order      INT          NOT NULL DEFAULT 0,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE
);

INSERT INTO shift_type (code, name, start_time, end_time, cross_day, work_hours, counts_as_work, color, sort_order) VALUES
    ('D', '白班', '08:00', '17:30', FALSE,  8.0, TRUE,  '#1d4ed8', 1),
    ('N', '夜班', '17:30', '08:00', TRUE,  14.5, TRUE,  '#6d28d9', 2),
    ('Z', '值班', '08:00', '08:00', TRUE,  24.0, TRUE,  '#b91c1c', 3),
    ('B', '备班', NULL,    NULL,    FALSE,  0.0, FALSE, '#92400e', 4),
    ('L', '请假', NULL,    NULL,    FALSE,  0.0, FALSE, '#166534', 5),
    ('X', '休息', NULL,    NULL,    FALSE,  0.0, FALSE, '#6b7280', 6);

CREATE TABLE holiday (
    id          BIGSERIAL PRIMARY KEY,
    year        INT          NOT NULL,
    name        VARCHAR(32)  NOT NULL,
    start_date  DATE         NOT NULL,
    end_date    DATE         NOT NULL,
    type        VARCHAR(16)  NOT NULL CHECK (type IN ('HOLIDAY', 'WORKDAY')),
    remark      VARCHAR(200),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (end_date >= start_date)
);
CREATE INDEX idx_holiday_year ON holiday (year);

CREATE TABLE schedule_month (
    year_month    CHAR(7)      PRIMARY KEY,              -- YYYY-MM
    status        VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED')),
    version       INT          NOT NULL DEFAULT 0,
    published_at  TIMESTAMPTZ,
    published_by  BIGINT REFERENCES app_user (id)
);

CREATE TABLE schedule_entry (
    id          BIGSERIAL PRIMARY KEY,
    staff_id    BIGINT       NOT NULL REFERENCES staff (id),
    work_date   DATE         NOT NULL,
    shift_code  VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    is_manual   BOOLEAN      NOT NULL DEFAULT FALSE,
    remark      VARCHAR(200),
    updated_by  BIGINT REFERENCES app_user (id),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (staff_id, work_date)
);
CREATE INDEX idx_schedule_entry_date ON schedule_entry (work_date);

CREATE TABLE schedule_published_entry (
    id          BIGSERIAL PRIMARY KEY,
    staff_id    BIGINT       NOT NULL REFERENCES staff (id),
    work_date   DATE         NOT NULL,
    shift_code  VARCHAR(4)   NOT NULL REFERENCES shift_type (code),
    remark      VARCHAR(200),
    version     INT          NOT NULL,
    UNIQUE (staff_id, work_date)
);
CREATE INDEX idx_schedule_pub_date ON schedule_published_entry (work_date);

CREATE TABLE swap_request (
    id                 BIGSERIAL PRIMARY KEY,
    type               VARCHAR(16)  NOT NULL CHECK (type IN ('SWAP', 'LEAVE', 'COVER')),
    applicant_staff_id BIGINT       NOT NULL REFERENCES staff (id),
    applicant_date     DATE         NOT NULL,
    target_staff_id    BIGINT REFERENCES staff (id),
    target_date        DATE,
    reason             VARCHAR(200),
    status             VARCHAR(16)  NOT NULL
        CHECK (status IN ('PENDING_PEER', 'PENDING_ADMIN', 'APPROVED', 'REJECTED', 'CANCELLED')),
    peer_confirmed_at  TIMESTAMPTZ,
    reviewed_by        BIGINT REFERENCES app_user (id),
    reviewed_at        TIMESTAMPTZ,
    review_comment     VARCHAR(200),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_swap_status ON swap_request (status);

CREATE TABLE operation_log (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT,
    username    VARCHAR(32),
    action      VARCHAR(32)  NOT NULL,
    target      VARCHAR(100),
    detail      VARCHAR(500),
    ip          VARCHAR(45),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_oplog_created ON operation_log (created_at DESC);
