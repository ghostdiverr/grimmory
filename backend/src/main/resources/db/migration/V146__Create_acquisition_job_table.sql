CREATE TABLE acquisition_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    book_id BIGINT NULL,
    library_id BIGINT NULL,
    query TEXT NOT NULL,
    category VARCHAR(20) NOT NULL,
    release_title VARCHAR(1024) NOT NULL,
    release_guid VARCHAR(512) NOT NULL,
    indexer_id BIGINT NOT NULL,
    indexer_name VARCHAR(255),
    protocol VARCHAR(20),
    size_bytes BIGINT,
    status VARCHAR(20) NOT NULL,
    error_message VARCHAR(1024),
    requested_by_user_id BIGINT NOT NULL,
    grabbed_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_acquisition_job_status ON acquisition_job(status);
