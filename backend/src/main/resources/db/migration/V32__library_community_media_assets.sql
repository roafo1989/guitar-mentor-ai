-- FT-012-03/#167: additive media metadata and derivative references; V25/V28/V31 remain immutable.
ALTER TABLE library_assets DROP CONSTRAINT IF EXISTS library_assets_format_check;
ALTER TABLE library_assets ADD CONSTRAINT library_assets_format_check
    CHECK (format IN ('PDF','GTP','GP3','GP4','GP5','GPX','GP','JPEG','PNG','MP3','WAV'));
ALTER TABLE library_assets
    ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'SCORE_ORIGINAL'
        CHECK (role IN ('SCORE_ORIGINAL','PHOTO_ORIGINAL','ORIGINAL_RECORDING','SYNTHESIZED_PREVIEW')),
    ADD COLUMN media_type VARCHAR(64) NOT NULL DEFAULT 'application/octet-stream',
    ADD COLUMN duration_millis BIGINT,
    ADD COLUMN channels SMALLINT,
    ADD COLUMN sample_rate INTEGER,
    ADD COLUMN width INTEGER,
    ADD COLUMN height INTEGER,
    ADD COLUMN preview_object_key VARCHAR(255) UNIQUE,
    ADD COLUMN preview_byte_size BIGINT,
    ADD COLUMN processing_state VARCHAR(16) NOT NULL DEFAULT 'READY',
    ADD COLUMN last_storage_check_at TIMESTAMPTZ,
    ADD CONSTRAINT library_asset_processing_state CHECK (processing_state IN ('READY','REJECTED','DELETING')),
    ADD CONSTRAINT library_asset_media_dimensions CHECK (
        (width IS NULL AND height IS NULL) OR
        (width BETWEEN 1 AND 12000 AND height BETWEEN 1 AND 12000 AND width::bigint*height<=20000000)),
    ADD CONSTRAINT library_asset_media_duration CHECK (duration_millis IS NULL OR duration_millis BETWEEN 1 AND 600000),
    ADD CONSTRAINT library_asset_media_audio CHECK (
        (channels IS NULL AND sample_rate IS NULL) OR
        (channels IN (1,2) AND sample_rate BETWEEN 8000 AND 48000)),
    ADD CONSTRAINT library_asset_preview_size CHECK (preview_byte_size IS NULL OR preview_byte_size BETWEEN 1 AND 5242880),
    ADD CONSTRAINT library_asset_preview_role CHECK (
        (role='PHOTO_ORIGINAL' AND preview_object_key IS NOT NULL AND preview_byte_size IS NOT NULL
            AND width IS NOT NULL AND height IS NOT NULL)
        OR (role<>'PHOTO_ORIGINAL' AND preview_object_key IS NULL AND preview_byte_size IS NULL));

ALTER TABLE library_publication_assets DROP CONSTRAINT IF EXISTS library_publication_assets_format_check;
ALTER TABLE library_publication_assets ADD CONSTRAINT library_publication_assets_format_check
    CHECK (format IN ('PDF','GTP','GP3','GP4','GP5','GPX','GP','JPEG','PNG','MP3','WAV'));
ALTER TABLE library_publication_assets
    ADD COLUMN role VARCHAR(32) NOT NULL DEFAULT 'SCORE_ORIGINAL'
        CHECK (role IN ('SCORE_ORIGINAL','PHOTO_ORIGINAL','ORIGINAL_RECORDING','SYNTHESIZED_PREVIEW')),
    ADD COLUMN media_type VARCHAR(64) NOT NULL DEFAULT 'application/octet-stream',
    ADD COLUMN preview_object_key VARCHAR(255),
    ADD COLUMN preview_byte_size BIGINT,
    ADD COLUMN duration_millis BIGINT,
    ADD COLUMN channels SMALLINT,
    ADD COLUMN sample_rate INTEGER,
    ADD COLUMN width INTEGER,
    ADD COLUMN height INTEGER;

CREATE TABLE library_asset_deletion_jobs (
    asset_id UUID PRIMARY KEY REFERENCES library_assets(id) ON DELETE RESTRICT,
    material_id UUID NOT NULL REFERENCES library_materials(id) ON DELETE RESTRICT,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','RETRY')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts>=0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error_code VARCHAR(64)
);
CREATE INDEX idx_library_asset_deletion_due ON library_asset_deletion_jobs(state,next_attempt_at);
CREATE INDEX idx_library_assets_material_role ON library_assets(material_id,role,processing_state);

-- Preserve correct MIME metadata for pre-existing PDF/GP assets and snapshots.
UPDATE library_assets SET media_type='application/pdf' WHERE format='PDF';
UPDATE library_publication_assets SET media_type='application/pdf' WHERE format='PDF';

ALTER TABLE library_assets
    ADD CONSTRAINT library_asset_role_format CHECK (
        (role='SCORE_ORIGINAL' AND format IN ('PDF','GTP','GP3','GP4','GP5','GPX','GP')
            AND duration_millis IS NULL AND channels IS NULL AND sample_rate IS NULL
            AND width IS NULL AND height IS NULL AND preview_object_key IS NULL AND preview_byte_size IS NULL)
        OR (role='PHOTO_ORIGINAL' AND format IN ('JPEG','PNG')
            AND media_type=CASE WHEN format='JPEG' THEN 'image/jpeg' ELSE 'image/png' END
            AND duration_millis IS NULL AND channels IS NULL AND sample_rate IS NULL
            AND width IS NOT NULL AND height IS NOT NULL AND preview_object_key IS NOT NULL AND preview_byte_size IS NOT NULL)
        OR (role IN ('ORIGINAL_RECORDING','SYNTHESIZED_PREVIEW') AND format IN ('MP3','WAV')
            AND media_type=CASE WHEN format='MP3' THEN 'audio/mpeg' ELSE 'audio/wav' END
            AND duration_millis BETWEEN 1 AND 600000 AND channels IN (1,2) AND sample_rate BETWEEN 8000 AND 48000
            AND width IS NULL AND height IS NULL AND preview_object_key IS NULL AND preview_byte_size IS NULL)
    );

ALTER TABLE library_publication_assets
    ADD CONSTRAINT library_publication_asset_role_format CHECK (
        (role='SCORE_ORIGINAL' AND format IN ('PDF','GTP','GP3','GP4','GP5','GPX','GP')
            AND duration_millis IS NULL AND channels IS NULL AND sample_rate IS NULL
            AND width IS NULL AND height IS NULL AND preview_object_key IS NULL AND preview_byte_size IS NULL)
        OR (role='PHOTO_ORIGINAL' AND format IN ('JPEG','PNG')
            AND media_type=CASE WHEN format='JPEG' THEN 'image/jpeg' ELSE 'image/png' END
            AND duration_millis IS NULL AND channels IS NULL AND sample_rate IS NULL
            AND width IS NOT NULL AND height IS NOT NULL AND preview_object_key IS NOT NULL AND preview_byte_size BETWEEN 1 AND 5242880)
        OR (role IN ('ORIGINAL_RECORDING','SYNTHESIZED_PREVIEW') AND format IN ('MP3','WAV')
            AND media_type=CASE WHEN format='MP3' THEN 'audio/mpeg' ELSE 'audio/wav' END
            AND duration_millis BETWEEN 1 AND 600000 AND channels IN (1,2) AND sample_rate BETWEEN 8000 AND 48000
            AND width IS NULL AND height IS NULL AND preview_object_key IS NULL AND preview_byte_size IS NULL)
    );

CREATE INDEX idx_library_assets_material_role_state
    ON library_assets(material_id,role,processing_state);
CREATE INDEX idx_library_publication_assets_media_role
    ON library_publication_assets(publication_id,role,asset_id)
    WHERE role IN ('ORIGINAL_RECORDING','SYNTHESIZED_PREVIEW');
