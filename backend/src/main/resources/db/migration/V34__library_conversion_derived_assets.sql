-- FT-013-02 / #170-C: private PDF+WAV derivatives, lineage and durable quota reservation.
ALTER TABLE library_assets DROP CONSTRAINT IF EXISTS library_assets_role_check;
ALTER TABLE library_assets ADD CONSTRAINT library_assets_role_check
    CHECK (role IN ('SCORE_ORIGINAL','DERIVED_SCORE_PDF','PHOTO_ORIGINAL','ORIGINAL_RECORDING','SYNTHESIZED_PREVIEW'));
ALTER TABLE library_assets DROP CONSTRAINT IF EXISTS library_asset_role_format;
ALTER TABLE library_assets ADD CONSTRAINT library_asset_role_format CHECK (
    (role IN ('SCORE_ORIGINAL','DERIVED_SCORE_PDF') AND format='PDF'
        AND duration_millis IS NULL AND channels IS NULL AND sample_rate IS NULL
        AND width IS NULL AND height IS NULL AND preview_object_key IS NULL AND preview_byte_size IS NULL)
    OR (role='SCORE_ORIGINAL' AND format IN ('GTP','GP3','GP4','GP5','GPX','GP')
        AND duration_millis IS NULL AND channels IS NULL AND sample_rate IS NULL
        AND width IS NULL AND height IS NULL AND preview_object_key IS NULL AND preview_byte_size IS NULL)
    OR (role='PHOTO_ORIGINAL' AND format IN ('JPEG','PNG')
        AND media_type=CASE WHEN format='JPEG' THEN 'image/jpeg' ELSE 'image/png' END
        AND duration_millis IS NULL AND channels IS NULL AND sample_rate IS NULL
        AND width IS NOT NULL AND height IS NOT NULL AND preview_object_key IS NOT NULL AND preview_byte_size IS NOT NULL)
    OR (role IN ('ORIGINAL_RECORDING','SYNTHESIZED_PREVIEW') AND format IN ('MP3','WAV')
        AND media_type=CASE WHEN format='MP3' THEN 'audio/mpeg' ELSE 'audio/wav' END
        AND duration_millis IS NOT NULL AND duration_millis BETWEEN 1 AND 600000
        AND channels IS NOT NULL AND channels IN (1,2)
        AND sample_rate IS NOT NULL AND sample_rate BETWEEN 8000 AND 48000
        AND width IS NULL AND height IS NULL AND preview_object_key IS NULL AND preview_byte_size IS NULL)
);

ALTER TABLE library_publication_assets DROP CONSTRAINT IF EXISTS library_publication_assets_role_check;
ALTER TABLE library_publication_assets ADD CONSTRAINT library_publication_assets_role_check
    CHECK (role IN ('SCORE_ORIGINAL','DERIVED_SCORE_PDF','PHOTO_ORIGINAL','ORIGINAL_RECORDING','SYNTHESIZED_PREVIEW'));
ALTER TABLE library_publication_assets DROP CONSTRAINT IF EXISTS library_publication_asset_role_format;
ALTER TABLE library_publication_assets ADD CONSTRAINT library_publication_asset_role_format CHECK (
    (role IN ('SCORE_ORIGINAL','DERIVED_SCORE_PDF') AND format='PDF'
        AND duration_millis IS NULL AND channels IS NULL AND sample_rate IS NULL
        AND width IS NULL AND height IS NULL AND preview_object_key IS NULL AND preview_byte_size IS NULL)
    OR (role='SCORE_ORIGINAL' AND format IN ('GTP','GP3','GP4','GP5','GPX','GP')
        AND duration_millis IS NULL AND channels IS NULL AND sample_rate IS NULL
        AND width IS NULL AND height IS NULL AND preview_object_key IS NULL AND preview_byte_size IS NULL)
    OR (role='PHOTO_ORIGINAL' AND format IN ('JPEG','PNG')
        AND media_type=CASE WHEN format='JPEG' THEN 'image/jpeg' ELSE 'image/png' END
        AND duration_millis IS NULL AND channels IS NULL AND sample_rate IS NULL
        AND width IS NOT NULL AND height IS NOT NULL AND preview_object_key IS NOT NULL
        AND preview_byte_size IS NOT NULL AND preview_byte_size BETWEEN 1 AND 5242880)
    OR (role IN ('ORIGINAL_RECORDING','SYNTHESIZED_PREVIEW') AND format IN ('MP3','WAV')
        AND media_type=CASE WHEN format='MP3' THEN 'audio/mpeg' ELSE 'audio/wav' END
        AND duration_millis IS NOT NULL AND duration_millis BETWEEN 1 AND 600000
        AND channels IS NOT NULL AND channels IN (1,2)
        AND sample_rate IS NOT NULL AND sample_rate BETWEEN 8000 AND 48000
        AND width IS NULL AND height IS NULL AND preview_object_key IS NULL AND preview_byte_size IS NULL)
);

ALTER TABLE library_conversion_jobs
    ADD COLUMN output_reserved_bytes BIGINT NOT NULL DEFAULT 0 CHECK(output_reserved_bytes BETWEEN 0 AND 41943040),
    ADD COLUMN output_reserved_files SMALLINT NOT NULL DEFAULT 0 CHECK(output_reserved_files BETWEEN 0 AND 2),
    ADD COLUMN output_reservation_attempt SMALLINT NOT NULL DEFAULT 0 CHECK(output_reservation_attempt BETWEEN 0 AND 2),
    ADD COLUMN output_reservation_state VARCHAR(16) NOT NULL DEFAULT 'NONE'
        CHECK(output_reservation_state IN ('NONE','RESERVED','COMMITTED','RELEASED')),
    ADD CONSTRAINT library_conversion_output_reservation_consistent CHECK (
        (output_reservation_state='RESERVED' AND output_reserved_bytes=41943040 AND output_reserved_files=2)
        OR (output_reservation_state<>'RESERVED' AND output_reserved_bytes=0 AND output_reserved_files=0)
    );

CREATE TABLE library_conversion_output_lineage (
    job_id UUID PRIMARY KEY REFERENCES library_conversion_jobs(id) ON DELETE RESTRICT,
    owner_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT,
    material_id UUID NOT NULL REFERENCES library_materials(id) ON DELETE RESTRICT,
    source_asset_id UUID NOT NULL,
    source_sha256 CHAR(64) NOT NULL CHECK(source_sha256 ~ '^[a-f0-9]{64}$'),
    selected_track_index INTEGER NOT NULL CHECK(selected_track_index>=0),
    score_revision CHAR(64) NOT NULL CHECK(score_revision ~ '^[a-f0-9]{64}$'),
    pdf_asset_id UUID NOT NULL,
    audio_asset_id UUID NOT NULL,
    pdf_sha256 CHAR(64) NOT NULL CHECK(pdf_sha256 ~ '^[a-f0-9]{64}$'),
    audio_sha256 CHAR(64) NOT NULL CHECK(audio_sha256 ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK(pdf_asset_id<>audio_asset_id),
    CHECK(source_sha256=score_revision)
);
CREATE INDEX idx_library_conversion_lineage_material
    ON library_conversion_output_lineage(owner_account_id,material_id,created_at DESC);
