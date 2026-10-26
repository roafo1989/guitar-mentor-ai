-- #166: exact normalized tags on immutable publication revisions.
-- Existing publications receive no invented tags. Uploaded originals stay private until #165 explicit publication.
CREATE TABLE library_publication_tags (
    publication_id UUID NOT NULL REFERENCES library_publications(id) ON DELETE RESTRICT,
    tag VARCHAR(40) NOT NULL,
    PRIMARY KEY (publication_id,tag),
    CHECK(tag=lower(btrim(tag)) AND tag ~ '^[[:alnum:]][[:alnum:] _-]{0,39}$')
);
CREATE INDEX idx_library_publication_tags_filter ON library_publication_tags(tag,publication_id);
CREATE INDEX idx_library_published_type_cursor ON library_publications(material_type,created_at DESC,id DESC)
 WHERE state='PUBLISHED';
-- Deliberately do not add B-tree lower(title/author) indexes: leading-wildcard
-- LIKE '%query%' cannot use them. Benchmark pg_trgm separately before enabling it.
