-- #196. Existing index rows remain metadata-only until an explicit owner action.
ALTER TABLE music_catalog_source_files ADD COLUMN original_asset_id UUID
    REFERENCES library_assets(id) ON DELETE SET NULL;
CREATE INDEX idx_music_catalog_original_asset ON music_catalog_source_files(original_asset_id)
    WHERE original_asset_id IS NOT NULL;
