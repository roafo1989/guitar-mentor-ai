# GM-EB01 0.8B: original preview distribution

| Version | Date | Change |
| --- | --- | --- |
| 1.0 | 2026-10-27 | Restore original generated PDF/MP3 from DEV-06 package for #237 |

Source: `guitar-mentor-dev06-preview-media-v08B-4bars.zip`, saved 2026-10-18, 537385 bytes.
Archive SHA-256: `b1d5e90af279847325d79b58f5b20dd08d37e3777c49e4ae3f7bf407579571e2`.
Source identity: ChatGPT artifact `libfile_b4e510c7cb548191a2aac9bb5be0defd`.
Archive README: revised PDF has 12 bars, four per system, three systems on one A4 page;
MP3 is the existing synthesized 0.8B demo. Extracted bytes are unchanged.
PDF was visually inspected before publication approval: one A4 page, 12 bars, matching footer/version/license.
Audio probe: 36.440816 seconds. No replacement music was generated.

| Classpath file | Bytes | SHA-256 |
| --- | ---: | --- |
| score_v08B.pdf | 69738 | c879a7a89c8766dfa0b0859220e0cfc182d7793880c911b77cc708d05a6da3d2 |
| preview_v08B.mp3 | 488975 | 01db204253a4a76670f0a82e461d14808790ec35386812e36323e2603a5f7afa |

Rights: generated for this project, synthesized audio, no imported performer recording.
Project `music/etudes/GM-EB01/metadata_v08B.json` and original PDF footer declare CC BY 4.0.
Credit: Guitar Mentor AI, “Вечерний блюз” / GM-EB01 0.8B (2026).
License: https://creativecommons.org/licenses/by/4.0/ . No musical alterations.
Existing `ACCEPTED_FOR_MVP_PROTOTYPE` review and pending instrumental validation remain;
shipping these assets grants no new pedagogical certification.

Install: ordinary Git checkout and Maven package. CI uses the same Maven resources.
No workstation ZIP, network media download or personal learner recording is needed.
`PublishedMediaIntegrityTest` freezes bytes; `GeneralRegressionHttpPostgresTest`
checks every advertised current resource through GET and HEAD/content type.
Published assets must not be overwritten: publish future revisions under new paths/version.
