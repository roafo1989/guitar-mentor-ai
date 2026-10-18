-- Preview metadata only. Keep raw MP3/PDF outside the relational database.
ALTER TABLE exercises
    ADD COLUMN preview_audio_resource VARCHAR(255),
    ADD COLUMN preview_pdf_resource VARCHAR(255);

UPDATE exercises
SET preview_audio_resource = 'exercises/GM-EB01/preview_v08B.mp3',
    preview_pdf_resource = 'exercises/GM-EB01/score_v08B.pdf'
WHERE code = 'GM-EB01' AND version = '0.8B';

-- Audio and PDF binary assets are locally installed test fixtures for now.
-- They must be placed under backend/src/main/resources/exercises/GM-EB01/
-- before running the backend (see docs/development/dev-06-media-preview.md).
