CREATE TABLE wanted_book (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(512) NOT NULL,
    author VARCHAR(255),
    isbn VARCHAR(32),
    category VARCHAR(20) NOT NULL,
    library_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    excluded_guids TEXT,
    last_searched_at TIMESTAMP NULL,
    last_result_count INT,
    acquisition_job_id BIGINT NULL,
    requested_by_user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_wanted_book_status ON wanted_book(status);

INSERT INTO task_cron_configuration (task_type, cron_expression, enabled, created_by)
SELECT 'WANTED_LIST_SCAN', '0 0 * * * *', FALSE, -1
WHERE NOT EXISTS (
    SELECT 1 FROM task_cron_configuration WHERE task_type = 'WANTED_LIST_SCAN'
);
