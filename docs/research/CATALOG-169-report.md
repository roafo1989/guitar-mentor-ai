# CATALOG-169 — Guitar Pro → нотный PDF/TAB + синтезированный аудиопример

| Версия | Дата | Изменение |
|---|---|---|
| 0.2 | 2026-10-31 | Зафиксирован реальный GitHub Actions/Docker run, проверены PDF/WAV и измерения; решение ограничено доказанным subset. |

## 1. Решение

**GO — только для технически доказанного subset: GP3 v3.00, GP4 v4.06 и GP5 v5.10 с простой одноголосной гитарной партией в 4/4, standard EADGBE, четвертными нотами, без rests/repeats/effects/tempo changes/capo и альтернативных строев.** Для каждого формата alphaTab импортировал независимый синтетический fixture, извлёк 20 событий, совпавших с отдельно заданным эталоном. На GP5 построены PDF нот/TAB и WAV из той же импортированной score revision. Ограниченный Linux container завершился с exit 0 без OOM.

Это **не GO для общего Guitar Pro conversion feature** и не музыкальная приёмка. GTP, GPX, GP8, сложные музыкальные конструкции, негативные/security cases и Windows host execution остаются NOT_RUN. GP7 прошёл только same-writer round-trip alphaTab exporter → alphaTab importer и не включён в supported subset.

Оригинальный GP остаётся immutable и доступен для скачивания при любом исходе. Хранение GP в #161 не является доказательством конвертации. #40 и #167 не изменялись.

**Gate #170 не снимается автоматически.** Можно готовить/оценивать первый ограниченный инкремент для GP3/4/5; production implementation — только после явного принятия владельцем такого subset. До production exposure обязательны adversarial и расширенные musical tests, Windows deployment check и полноценная Java→container orchestration. Неподтверждённые форматы не показывать как доступные.

## 2. Входные требования и документы

- CATALOG-04-research-gates.md и CATALOG-03-community-catalog.md прочитаны по ссылкам из #169 в историческом commit 1c87b972f34b20b97bf4baf0a8330c193a85954b; ожидаемые пути отсутствуют в текущем main.
- MUSIC-01-shared-catalog-ownership.md прочитан из текущего main (blob 434255f9466c1a0d7a2deb6a6983f93e0b950ba4). GP original остаётся отдельным immutable asset; derived outputs не должны заменять original или публиковаться автоматически.
- AGENTS.md не найден в корне текущего main и историческом commit.
- MUS-NOTATION-01.md не найден в текущем main, историческом commit или поиске точного имени в репозитории. Поэтому нельзя утверждать полное соответствие отсутствующему каноническому документу/эталонному PDF. Визуально проверены требуемые свойства: стандартный нотный стан сверху, шестилинейная TAB снизу, горизонтально совмещённые события, четыре полных такта в первой системе и остаток во второй; гитарная нотная октава на 12 полутонов выше sounding pitch.
- Ветка исследования создана от main SHA e4d6789dcd4e0633bb72b23b3eda8e4e7c54d0b8.

## 3. Сравнение кандидатов

| Критерий | alphaTab 1.8.4 | TuxGuitar 2.1.1 | PyGuitarPro 0.11 |
|---|---|---|---|
| Роль | Importer, score model, SVG engraving, MIDI/SoundFont synth | Java desktop editor/player, importer/exporter | Независимый writer/reader fixtures GP3/4/5 |
| Headless | Официальный Node.js low-level API + SVG документированы; audio в прототипе потребовал custom silent output/offline adapter | Desktop приложение и batch converter; поддержанный headless CLI/API с нужным layout+audio контрактом не доказан | Headless Python library, не app runtime |
| ОС | Node.js кроссплатформенный; фактический run Linux only | Официальные Windows/Linux пакеты; headless execution не доказан | Pure Python; fixture generation на Linux CI |
| Форматы по upstream | GP3/4/5, GPX/GP6, GP7 .gp; GP8 в newer docs не считается доказанным для pinned 1.8.4 | .gtp GP1/2, GP3/4/5, GPX, GP7 | GP3/4/5 read/write |
| События | Note/Beat/Voice/Bar/Staff, string/fret/realValue/displayValue | Богатая Java-модель, headless integration не доказан | string/fret/realValue/duration |
| Вёрстка | barsPerRow, barCountPerPartial, standard notation + TAB, SVG | PDF export; точный 4-bars/system headless контроль не доказан | Нет renderer |
| Аудио | MIDI + SoundFont; Node offline output через custom adapter | WAV/AU/AIF в desktop workflow; headless export не доказан | Нет renderer |
| Лицензия | MPL-2.0; notices font/SoundFont отдельно | LGPL; third-party inventory обязателен | LGPL-3.0-only; fixtures only |
| Эксперимент | GP3/4/5 PASS; GP7 same-writer round-trip PASS | Не выполнялся | Fixture self-check GP3/4/5 |

**Выбор:** alphaTab 1.8.4 — минимальный перспективный importer/renderer доказанного subset. PDFKit 0.17.2 + svg-to-pdfkit 0.1.8 собирают SVG в PDF. Java остаётся основным application layer; Node — явно внешний renderer/container. TuxGuitar остаётся кандидатом для .gtp, но не выбранным runtime. Покупки Guitar Pro и платных сервисов не было.

Официальные upstream источники:
- alphaTab Node.js/headless: https://docs.alphatab.net/docs/guides/nodejs
- alphaTab importers/data model: https://www.alphatab.net/docs/introduction
- alphaTab formats: https://www.alphatab.net/docs/category/formats/
- alphaTab layout settings: https://www.alphatab.net/docs/reference/types/displaysettings/
- alphaTab MPL-2.0: https://alphatab.net/docs/contributing
- TuxGuitar file formats 1.6.6: https://www.tuxguitar.app/files/1.6.6/desktop/help/file_formats.html
- TuxGuitar platform/LGPL: https://www.tuxguitar.app/files/devel/desktop/help/about.html
- TuxGuitar releases: https://github.com/helge17/tuxguitar/releases
- PyGuitarPro license/scope: https://github.com/Perlence/PyGuitarPro/blob/master/pyproject.toml
- PyGuitarPro read/write: https://pyguitarpro.readthedocs.io/en/stable/pyguitarpro/quickstart.html

## 4. Закреплённый toolchain и лицензии

| Компонент | Версия | Назначение | Лицензия/ограничение |
|---|---|---|---|
| Node.js | 22.16.0, node:22.16.0-bookworm-slim | Isolated renderer | Docker base pinned |
| npm | 10.9.2 | Lockfile/install | Complete graph in package-lock.json |
| alphaTab | 1.8.4 | Import/model/SVG/MIDI/SoundFont | MPL-2.0 |
| PDFKit | 0.17.2 | PDF assembly | MIT |
| svg-to-pdfkit | 0.1.8 | SVG→PDF | MIT |
| Python | 3.12.10 | Fixture generation only | Не production runtime |
| PyGuitarPro | 0.11 | Independent GP3/4/5 fixture writer/parser | LGPL-3.0-only; fixtures only |
| attrs | 25.3.0 | PyGuitarPro dependency | MIT |
| Bravura | alphaTab 1.8.4 bundled OTF | Music glyphs | SIL OFL 1.1; notice сохранён |
| SONiVOX SoundFont | alphaTab 1.8.4 bundled SF2 | Synth audio | Apache-2.0; notice сохранён |
| Java | Project env 21.0.12.1; CI host reported 17.0.21 | Existing app layer/future orchestration | Java не запускался внутри renderer container; ProcessBuilder integration NOT_RUN |

Никаких платных сервисов, коммерческой Guitar Pro установки, приватной пользовательской библиотеки или чужих музыкальных fixtures не использовалось. CI build host скачивает pinned dependencies; conversion container работает без сети. При production distribution нужно сохранять MPL/LGPL/OFL/Apache notices и повторять license inventory при изменении lockfile.

## 5. Fixtures и provenance

synthetic-score.alphatex — оригинальная синтетическая партитура для #169: две гитарные дорожки, пять тактов 4/4, standard EADGBE, 20 четвертных событий Lead Guitar, tempo 96 BPM. expected-events.json отдельно задаёт bar/voice/string/fret/sounding MIDI/duration и displayMidi = midi + 12.

generate_fixtures.py создаёт GP3 v3.00, GP4 v4.06 и GP5 v5.10 через PyGuitarPro 0.11. Generator повторно читает каждый файл своим parser и сверяет события до импорта alphaTab. fixture-manifest.json содержит origin, permission, SHA-256, format/version, expected events file/hash. verify-fixtures.mjs проверяет hashes, размер до 20 MiB и арифметику событий.

GP7 .gp создаётся alphaTab exporter из той же AlphaTex source и импортируется тем же alphaTab. Это same-writer round-trip, не независимая совместимость. Его SHA находится в evidence/SHA256SUMS.txt; в supported subset он не включён.

Нет законно распространяемого валидного sample GP1/2 .gtp, GP6 .gpx или независимого GP8 .gp. Эти версии NOT_RUN.

## 6. Форматная матрица

| Формат | Разрешённый валидный sample | Результат | Допустимое утверждение |
|---|---|---|---|
| .gtp GP1/2 | Нет | NOT_RUN | Не заявлять |
| .gp3 GP3.00 | Synthetic PyGuitarPro | PASS | Только простой subset из раздела 1 |
| .gp4 GP4.06 | Synthetic PyGuitarPro | PASS | Только простой subset из раздела 1 |
| .gp5 GP5.10 | Synthetic PyGuitarPro | PASS | Только простой subset из раздела 1 |
| .gpx GP6 | Нет | NOT_RUN | Не заявлять |
| .gp GP7 | alphaTab-generated | PASS, same-writer round-trip only | Не включать в supported subset |
| .gp GP8 | Нет independent sample | NOT_RUN | Не заявлять |

## 7. Feature/correctness matrix

| Критерий | Статус | Доказательство / граница |
|---|---|---|
| GP3/4/5 source→events | PASS | 20 событий на формат совпали с независимым expected JSON |
| Струна/лад/sounding MIDI | PASS | TAB string numbering normalized (1=high E); pitch = open string + fret |
| Гитарная нотная октава | PASS | displayMidi = realMidi + 12 для всех 20 событий |
| Длительность/порядок | PASS | Все четверти; bar/voice/event order/duration совпали; gaps/rests отсутствуют |
| Tempo/meter | PASS | Один постоянный 96 BPM, только 4/4 |
| Multi-track | PASS, узко | Две guitar tracks; renderer явно выбирает Lead Guitar index 0; динамический выбор пользователем не реализован |
| Standard tuning | PASS | E2-A2-D3-G3-B3-E4 |
| PDF staff/TAB | PASS, визуальный prototype | 5-line staff над 6-line TAB, note columns aligned |
| 4 такта/system + остаток | PASS | Первая система 1–4, вторая 5 |
| Audio same revision | PASS, технически | WAV из imported GP5 и того же выбранного Lead Guitar track |
| Audio waveform | PASS, технически | Stereo PCM16 44.1kHz, 12.501043s, ненулевые samples |
| Human listening/music acceptance | NOT_RUN | Прослушивание недоступно; waveform не является музыкальной приёмкой |
| 3/4, 3/8, 6/8 | NOT_RUN | Нет fixtures |
| Rests | NOT_RUN | Нет fixture |
| Repeats/alternate endings | NOT_RUN | Семантика повторов не проверялась |
| Polyphony/multiple voices | NOT_RUN | Только voice 0, одна нота на beat |
| Tempo changes | NOT_RUN | Только constant tempo |
| Drop D/Open G/capo | NOT_RUN | Только standard tuning, capo 0 |
| Unsupported effects | NOT_RUN | Нет fixture, graceful degradation не проверен |
| Empty/corrupt/truncated | NOT_RUN | Negative fixtures не импортировались |
| Wrong extension/MIME mismatch | NOT_RUN | Upload validator вне prototype |
| ZIP bomb/decompression limits | NOT_RUN | GPX/GP7 ZIP preflight не тестировался |
| Exact 20 MiB boundary | NOT_RUN | Cap enforced; boundary stress не выполнялся |
| Windows host/Docker Desktop | NOT_RUN | PowerShell runner есть; фактический запуск Linux only |
| Java ProcessBuilder | NOT_RUN | App layer не оркестрировал renderer в этом эксперименте |

## 8. PDF и audio inspection

PDF из CI artifact rasterized at 110 DPI и визуально просмотрен. Первая система содержит bars 1–4, вторая — bar 5. Пятилинейный нотный стан находится над шестилинейной TAB, ноты по горизонтали совпадают. На странице много свободного места под двумя системами: для пяти тактов это допустимо, но не является финальным layout design. Так как MUS-NOTATION-01 и исходный reference PDF не найдены, более сильное утверждение о conformance не делается.

WAV independently inspected: RIFF PCM, stereo, 16-bit, 44,100 Hz, 551,296 frames/channel, 12.501043s. Ожидаемая длительность 20 × 60 / 96 = 12.5s, разница ≈1.04ms. Peak 0.20776, RMS 0.03618, практически все samples ненулевые. Это техническая проверка; человеческого прослушивания и музыкального одобрения нет.

## 9. Reproducibility и resource measurements

- GitHub Actions run: https://github.com/roafo1989/guitar-mentor-ai/actions/runs/41222243331
- Job/check: synthetic-gp3-gp4-gp5-gp7, check ID 124599069703, commit 0289a0eead47fc262db42eb3d7a014861e7683c4, conclusion SUCCESS.
- CI artifact: catalog-169-evidence, artifact ID 13050537149, retention 14 days. Permanent compact evidence committed in docs/research/catalog-169/evidence/.
- Commands: python -m pip install -r scripts/research/catalog-169/requirements.txt; python scripts/research/catalog-169/generate_fixtures.py; node scripts/research/catalog-169/verify-fixtures.mjs; npm ci --prefix scripts/research/catalog-169 --omit=dev; docker build -f scripts/research/catalog-169/Dockerfile -t gm169-research .; ./scripts/research/catalog-169/run-linux.sh. Windows PowerShell runner supplied but not verified.

Container flags: --network none, --memory 512m, --memory-swap 512m, --cpus 1, --pids-limit 64, read-only root, /tmp tmpfs 100 MiB; only /out writable. No secrets, app DB or user originals mounted. Build/install stage occurs outside the network-disabled container.

| Metric | Measurement | Limit |
|---|---:|---:|
| Exit/OOM | exit 0, OOMKilled=false | PASS |
| Wall time | ≈1.56 s from CI timestamps | 60 s watchdog; not long-score stress |
| Memory peak | 68,505,600 bytes ≈65.33 MiB | 512 MiB cgroup cap |
| CPU quota | cpu.max 100000 100000 | 1 CPU; usage 975,729 µs; throttled 720,923 µs |
| Scratch | 4,096 bytes used | 100 MiB tmpfs |
| Network | none | Container no-network; build host remains trusted/network-enabled |
| Input size | Generated GP fixtures below 20 MiB | cap checked; exact boundary not stress-tested |
| Concurrency | Not measured/implemented | 1 job/account, global 2 future DB lease policy in #170 |

This is measured Docker container isolation, not a general OS sandbox. Docker daemon and host kernel remain trusted. docker stats had only a startup sample and is not used for peak; cgroup memory.peak is the source. Concurrency is not implemented in this research.

## 10. Errors, limits and one exact remaining experiment

The demonstrated subset is reproducible. The one missing experiment before widening the supported feature set is:

**Run one expanded synthetic conformance suite through the same pinned importer/renderer and isolated container. It must include valid fixtures for 3/4, 3/8, 6/8, rests, repeats, polyphony, tempo changes, standard/Drop D/Open G/capo and unsupported effects; plus empty, corrupt, truncated, wrong-extension and bounded ZIP-bomb inputs. Compare canonical event positions/durations/voices/tempo/repeat expansion, require deterministic rejection codes for invalid inputs, and verify PDF/audio are produced only from the same validated score revision.**

Until that suite passes, those features remain NOT_RUN/disabled. Missing .gtp/.gpx/independent GP8 samples still require their own legal samples and separate format runs. No unbounded research is needed to retain the narrow GP3/4/5 result.

## 11. Proposal for #170 (not implemented here)

### API
- POST /api/v1/library/materials/{materialId}/conversion-jobs: immutable sourceAssetId, source ETag, Idempotency-Key, explicit trackId if known. Validate bytes and extension; never overwrite original.
- If multiple guitar tracks and no trackId, return TRACK_SELECTION_REQUIRED with safe track metadata. Require explicit POST .../{jobId}/track-selection; never silently choose first track.
- GET .../{jobId}: state/stage/progress/selected track/toolchain/error/retryable/source hash/score revision/derived asset IDs.
- POST .../{jobId}/cancel: idempotent cancellation; bounded retry only for transient errors.
- PDF/audio are private derived assets until explicit publish. Persist derivedFromAssetId, source SHA, toolchain version, score revision and selected track.

### States
QUEUED → VALIDATING → IMPORTING → AWAITING_TRACK_SELECTION → RENDERING_PDF → SYNTHESIZING_AUDIO → VERIFYING → SUCCEEDED.
Terminal alternatives: PARTIAL, FAILED, UNSUPPORTED, TIMED_OUT, CANCELLED. SUCCEEDED requires both validated outputs from one score revision. PDF-only/audio-only is not SUCCEEDED; PARTIAL is not auto-published.

### Errors
FILE_TOO_LARGE, EMPTY_FILE, INVALID_FORMAT, UNSUPPORTED_FORMAT_VERSION, TRUNCATED_FILE, CORRUPT_FILE, ARCHIVE_LIMIT_EXCEEDED, NO_GUITAR_TRACK, TRACK_SELECTION_REQUIRED, INVALID_TRACK_SELECTION, UNSUPPORTED_EFFECT, EVENT_VALIDATION_FAILED, PDF_RENDER_FAILED, AUDIO_SYNTHESIS_FAILED, RESOURCE_LIMIT_EXCEEDED, TIMEOUT, CANCELLED, TOOL_UNAVAILABLE, INTERNAL_ERROR.

### Increments / preliminary estimates
| Increment | Deliverable | Estimate |
|---|---|---:|
| 170-A | Java job schema/API, idempotency, track selection, states/errors/cancel, DB concurrency lease | 8–12 h |
| 170-B | Isolated Node/alphaTab worker for GP3/4/5 simple subset; hard caps/no network/watchdog | 8–12 h |
| 170-C | Private PDF/audio derived assets, lineage, recovery/cleanup, immutable original | 6–10 h |
| 170-D | Vue track selection/progress/terminal result/retry/cancel/error UX | 6–8 h |
| 170-E | Adversarial/expanded musical tests, resource/fault checks, Windows deployment, visual/audio QA | 8–12 h |

AI, audio recognition, external search, transcription and production queue broker are out of scope. Concurrency target remains 1 job/account and global 2, enforced by Java DB lease/claim.

## 12. Review checklist

- [x] main, #169/comments, #170, #161/#167, MUSIC-01 and historical CATALOG-03/04 inspected; #40/#167 unchanged.
- [x] Official candidates/licenses compared; TuxGuitar not preselected.
- [x] Synthetic fixtures, provenance, permissions, SHA-256 and versioned manifest committed.
- [x] Independent GP3/4/5 writer → alphaTab event parity executed.
- [x] PDF/WAV generated; resource-limited Linux container passed.
- [x] PDF visually inspected; audio bytes technically inspected.
- [x] Resource/isolation claims tied to cgroup/Docker evidence.
- [ ] Human listening/music acceptance — NOT_RUN.
- [ ] Full negative/complex musical corpus — exact experiment above outstanding.
- [ ] Windows host and Java ProcessBuilder integration — NOT_RUN.
- [ ] Independent human code review — not claimed.

**Decision: GO for GP3/4/5 simple subset only.** #170 remains gated for owner approval of that scope and remaining production-security/complex-corpus work. No merge or issue closure requested.
