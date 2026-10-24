-- #190. Separate private, metadata-only indexed corpus from user-uploaded library_assets.
-- Files are NOT available for download merely because their metadata is indexed.
-- No rows are imported by this migration.
CREATE TABLE music_catalog_works (
    owner_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    source_work_id VARCHAR(64) NOT NULL,
    title TEXT NOT NULL,
    artist TEXT NOT NULL DEFAULT '',
    normalized_title TEXT NOT NULL DEFAULT '',
    normalized_artist TEXT NOT NULL DEFAULT '',
    identity_status VARCHAR(32) NOT NULL CHECK (identity_status IN ('CANDIDATE','UNRESOLVED','VERIFIED')),
    grouping_method VARCHAR(64) NOT NULL,
    PRIMARY KEY (owner_account_id, source_work_id)
);
CREATE INDEX idx_music_catalog_works_search
    ON music_catalog_works(owner_account_id, normalized_artist, normalized_title);

CREATE TABLE music_catalog_arrangements (
    owner_account_id UUID NOT NULL,
    source_arrangement_id VARCHAR(64) NOT NULL,
    source_work_id VARCHAR(64) NOT NULL,
    name TEXT NOT NULL,
    identity_status VARCHAR(32) NOT NULL,
    tempo_raw TEXT,
    time_signatures_raw TEXT,
    track_count_raw TEXT,
    instruments_raw TEXT,
    string_counts_raw TEXT,
    measure_count_raw TEXT,
    note_count_raw TEXT,
    parser_status VARCHAR(40) NOT NULL,
    parser_source TEXT,
    PRIMARY KEY (owner_account_id, source_arrangement_id),
    FOREIGN KEY (owner_account_id, source_work_id)
        REFERENCES music_catalog_works(owner_account_id, source_work_id) ON DELETE RESTRICT
);
CREATE INDEX idx_music_catalog_arrangements_work
    ON music_catalog_arrangements(owner_account_id, source_work_id);

CREATE TABLE music_catalog_source_files (
    owner_account_id UUID NOT NULL,
    source_file_id VARCHAR(64) NOT NULL,
    source_arrangement_id VARCHAR(64) NOT NULL,
    relative_path TEXT NOT NULL,
    original_filename TEXT NOT NULL,
    source_group TEXT NOT NULL,
    extension VARCHAR(16) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    rights_status TEXT NOT NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE'
        CHECK (visibility = 'PRIVATE'),
    original_title TEXT,
    original_artist TEXT,
    raw_title TEXT,
    raw_artist TEXT,
    encoding_used TEXT,
    text_quality_flags TEXT,
    parse_error TEXT,
    -- No object key, downloadable URL or library_asset_id until separate secure ingestion.
    PRIMARY KEY (owner_account_id, source_file_id),
    UNIQUE (owner_account_id, relative_path),
    FOREIGN KEY (owner_account_id, source_arrangement_id)
        REFERENCES music_catalog_arrangements(owner_account_id, source_arrangement_id)
        ON DELETE RESTRICT
);
CREATE INDEX idx_music_catalog_source_arrangement
    ON music_catalog_source_files(owner_account_id, source_arrangement_id);

CREATE TABLE music_catalog_review_queue (
    owner_account_id UUID NOT NULL,
    source_file_id VARCHAR(64) NOT NULL,
    review_reasons TEXT NOT NULL,
    title TEXT,
    artist TEXT,
    parser_status VARCHAR(40) NOT NULL,
    PRIMARY KEY (owner_account_id, source_file_id),
    FOREIGN KEY (owner_account_id, source_file_id)
        REFERENCES music_catalog_source_files(owner_account_id, source_file_id)
        ON DELETE CASCADE
);
-- No public catalog endpoint / grants; access MUST be constrained by actor.accountId().
