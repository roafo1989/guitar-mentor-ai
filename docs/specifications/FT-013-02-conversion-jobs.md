# FT-013-02 — Задачи конвертации Guitar Pro

| Версия | Дата | Изменение |
|---|---|---|
| 0.1 | 31.10.2026 | Анализ после завершения FT-013-01/#169; подготовлен контракт первого инкремента 170-A. |
| 0.2 | 31.10.2026 | Уточнены PostgreSQL test harness, оценка 10ч и граница 170-A. |

## 1. Назначение и границы

Документ описывает production-контур конвертации оригинального Guitar Pro в приватные производные PDF (ноты + TAB) и WAV. Исследование #169 доказало только ограниченный subset:

- GP3 v3.00, GP4 v4.06, GP5 v5.10;
- простая одноголосная гитарная партия, 4/4, standard EADGBE, четвертные ноты;
- без rests, repeats, effects, tempo changes, capo и alternate tuning;
- source <=20 MiB; renderer: 512 MiB RAM, 1 CPU, 60 s, 100 MiB scratch, network disabled;
- PDF и WAV считаются успехом только вместе и из одной импортированной score revision.

GP7 same-writer round-trip, GTP, GPX, GP8 и все неиспытанные музыкальные конструкции не являются поддерживаемыми форматами.

Реализация не заменяет оригинал и не публикует производные автоматически. Производные остаются приватными файлами той же Library Material. Диагностический TTL 48 часов к личной музыкальной библиотеке автоматически не применяется.

## 2. Существующая архитектура, которую нужно переиспользовать

- API: `/api/v1/library/materials`; текущий владелец берётся только из server-side `CurrentActor.accountId()`.
- Оригиналы: `library_materials` + `library_assets`; исходные GP-файлы имеют role `SCORE_ORIGINAL`, формат и SHA-256.
- Хранилище: `LibraryObjectStore`, отдельный private root `./.local-media/library`, случайные UUID keys, SHA-256, bounded writes, POSIX 0700/0600. Не создавать второй storage adapter и не использовать diagnostic AttemptMediaStore.
- Квоты: `library_account_usage` уже содержит charged/reserved bytes/files; не обходить существующий механизм.
- Удаление: `LibraryDeletionWorker` и `LibraryAssetDeletionWorker` физически удаляют оригиналы. Их нужно согласовать с активными conversion jobs: удаление не может стереть source, пока job использует его.
- Публикация: `library_publications` — отдельное явное действие. Conversion job не создаёт публикацию и не меняет snapshot публикации.

## 3. Инкремент 170-A — Job API, состояние и DB lease

### 3.1 Таблицы

Создать отдельную таблицу `library_conversion_jobs`; не расширять `library_upload_operations` (это другой жизненный цикл).

Минимальные поля:

| Поле | Назначение |
|---|---|
| id UUID PK | ID задания |
| owner_account_id UUID | Владелец из CurrentActor |
| material_id UUID | Родительская личная карточка |
| source_asset_id UUID | ID оригинала; сохраняется как lineage snapshot |
| source_sha256 CHAR(64) | SHA-256 оригинала на момент старта |
| source_format VARCHAR(8) | GP3/GP4/GP5 |
| source_byte_size BIGINT | Зафиксированный размер оригинала |
| idempotency_key UUID | Ключ запроса, unique вместе с owner |
| request_fingerprint CHAR(64) | Защита от повторного ключа с другим payload |
| state VARCHAR(32) | Состояние job |
| stage VARCHAR(32) | Текущий этап worker |
| progress SMALLINT | 0..100, только для UI |
| tracks JSONB | Безопасный список обнаруженных дорожек |
| selected_track_index INTEGER | Выбор пользователя, не имя из запроса |
| cancel_requested BOOLEAN | Запрос отмены |
| attempt_count INTEGER | Число запусков |
| retryable BOOLEAN | Разрешён ли retry |
| lease_owner UUID / lease_until / heartbeat_at | DB lease активного worker |
| worker_slot SMALLINT | Один из двух глобальных слотов |
| pdf_asset_id UUID / audio_asset_id UUID | ID двух производных файлов после успеха |
| error_code VARCHAR(64) | Стабильный машинный код; без stack trace и локальных путей |
| revision BIGINT | Optimistic concurrency для действий UI |
| created_at / updated_at / started_at / finished_at | Аудит времени |

Ограничения:
- UNIQUE(owner_account_id, idempotency_key).
- CHECK для source_format, state, stage, progress 0..100, attempt_count >= 0.
- Индексы очереди по (state, created_at), lease expiry, owner/material.
- Частичный unique index на owner_account_id для активных job states; он обеспечивает не более одной незавершённой job на пользователя, включая ожидание выбора дорожки.
- `source_asset_id` хранится как lineage snapshot без FK на удаляемый asset: job history не должна блокировать удаление уже завершённой карточки. При создании source проверяется под lock; worker повторно проверяет source перед чтением. Активную job deletion workers обязаны отменить/дождаться до физического удаления source.

Создать `library_conversion_worker_slots` с ровно двумя строками (slot 1 и 2), job_id, lease_owner, lease_until. Claim слота и job выполняется одной DB-транзакцией с `FOR UPDATE SKIP LOCKED`. Это обеспечивает глобальный предел 2 даже при нескольких экземплярах приложения; JVM Semaphore недостаточен.

### 3.2 State machine

| State | Значение |
|---|---|
| QUEUED | Ожидает worker |
| RUNNING | Worker выполняет stage |
| AWAITING_TRACK_SELECTION | Импорт завершён, требуется выбор одной из нескольких гитарных дорожек; global slot освобождён |
| CANCEL_REQUESTED | Пользователь запросил отмену выполняющегося процесса |
| SUCCEEDED | Оба валидных output asset сохранены |
| UNSUPPORTED | Версия/конструкция не входит в подтверждённый subset |
| FAILED | Невосстановимая ошибка |
| TIMED_OUT | Достигнут жёсткий лимит времени |
| CANCELLED | Отмена подтверждена, процесс остановлен и временные файлы удалены |

Stage (отдельное поле): VALIDATING_SOURCE → IMPORTING_SCORE → RENDERING_PDF → SYNTHESIZING_AUDIO → VERIFYING_OUTPUTS.

Переходы:
- QUEUED → RUNNING → (AWAITING_TRACK_SELECTION или SUCCEEDED);
- RUNNING → CANCEL_REQUESTED → CANCELLED;
- RUNNING → FAILED / UNSUPPORTED / TIMED_OUT;
- AWAITING_TRACK_SELECTION → QUEUED после валидного выбора или CANCELLED;
- FAILED/TIMED_OUT → QUEUED только через явный retry и только при retryable=true;
- terminal states неизменяемы, кроме retry, создающего новую attempt в рамках того же job ID.

Если обнаружена ровно одна гитарная дорожка — её можно выбрать автоматически. Если гитарных дорожек несколько — обязательно запросить выбор пользователя. Если гитарных дорожек нет — UNSUPPORTED/NO_GUITAR_TRACK. Нельзя молча брать track index 0.

### 3.3 REST API

Все endpoint owner-scoped; при отсутствии доступа возвращать 404, не раскрывая существование чужих UUID.

- `POST /api/v1/library/materials/{materialId}/conversion-jobs` — создать job; header `Idempotency-Key: UUID`, `If-Match` с revision карточки; body содержит только `sourceAssetId`. Ответ 202 + job representation + Location.
- `GET /api/v1/library/materials/{materialId}/conversion-jobs/{jobId}` — состояние, stage, progress, безопасный список дорожек, выбранная дорожка, retryable/errorCode и ссылки на PDF/WAV только после SUCCEEDED. ETag по job revision.
- `POST .../{jobId}/track-selection` — body `{ "trackIndex": n }`, обязательный `If-Match`; доступно только в AWAITING_TRACK_SELECTION, index должен присутствовать в tracks JSON.
- `POST .../{jobId}/cancel` — queued/awaiting отменяется сразу; running получает CANCEL_REQUESTED и становится CANCELLED только после остановки процесса/очистки scratch.
- `POST .../{jobId}/retry` — только для FAILED/TIMED_OUT с retryable=true; обязательный If-Match. Новый запуск увеличивает attempt_count.

Не принимать ownerAccountId/learnerId из тела или query. Не возвращать object_key, локальный путь, stack trace, stdout/stderr или произвольные сообщения Node.

### 3.4 Idempotency и конкурентный доступ

Fingerprint вычисляется из версии схемы, materialId, sourceAssetId, source SHA-256 и параметров конвертации. Повтор того же owner/key/fingerprint возвращает исходную job; тот же key с другим fingerprint → 409 IDEMPOTENCY_KEY_REUSED. Retry — отдельное действие над существующим job, а не повторный POST.

Создание job транзакционно:
1. взять lock material row и проверить owner + READY;
2. взять lock source asset и проверить принадлежность, SCORE_ORIGINAL, READY, GP3/4/5, размер и SHA;
3. проверить idempotency;
4. проверить активную job пользователя;
5. сохранить source snapshot и QUEUED.

Worker claim: DB transaction → свободный worker slot FOR UPDATE SKIP LOCKED → queued job FOR UPDATE SKIP LOCKED → назначить lease и slot → RUNNING. Heartbeat продлевает lease. Потерянный lease не должен позволить двум процессам одновременно публиковать output; commit результата требует совпадения lease_owner и revision.

### 3.5 Безопасность и жизненный цикл source

- Проверять владельца только по CurrentActor; чужая карточка/asset → 404.
- Формат по расширению не является доказательством: worker проверяет фактическое содержимое alphaTab importer.
- Оригинал не перезаписывается и не удаляется при fail/cancel/timeout.
- Удаление карточки/asset должно сначала запросить cancel активных jobs и дождаться освобождения lease; deletion workers не удаляют source, пока job находится в QUEUED/RUNNING/AWAITING_TRACK_SELECTION/CANCEL_REQUESTED.
- Временные файлы находятся в отдельном scratch; удаляются при любом terminal outcome. Diagnostic TTL не применяется к Library assets.
- PDF и WAV — приватные derived assets одной material; success только после проверки обоих. Partial success запрещён. Для PDF потребуется отдельная роль DERIVED_SCORE_PDF в #170-C; audio использует SYNTHESIZED_PREVIEW. Изменение role/check constraints не входит в 170-A.
- Квоты output резервируются в library_account_usage до сохранения производных; точный резерв определяется по фактическому размеру в пределах scratch cap, до записи в постоянный object store.

## 4. Инкременты после 170-A

- 170-B: Node/alphaTab worker в Docker, read-only source mount, no network, 512 MiB, 1 CPU, 60 s watchdog, 100 MiB scratch; Java ProcessBuilder с фиксированным executable/аргументами, без shell.
- 170-C: приватные derived assets, lineage, новая роль PDF, квоты, атомарное сохранение пары PDF+WAV, recovery и безопасное взаимодействие с deletion workers.
- 170-D: Vue UI выбора дорожки, progress, cancel, retry и результата/ошибок.
- 170-E: расширенные musical/adversarial tests, fault injection, Windows Docker Desktop и полная Java orchestration.

## 5. AC для 170-A

1. POST требует authenticated owner, sourceAssetId, UUID Idempotency-Key и актуальный If-Match; чужой материал не раскрывается.
2. Принимаются только GP3/GP4/GP5 оригиналы SCORE_ORIGINAL в READY; PDF/GTP/GPX/GP/другие роли отклоняются с детерминированным кодом.
3. Повтор запроса с тем же ключом и payload возвращает тот же job; с другим payload — 409.
4. На одного owner допускается не более одной незавершённой job; глобальный worker concurrency не более 2, проверяется на PostgreSQL, не JVM semaphore.
5. Несколько guitar tracks приводят к AWAITING_TRACK_SELECTION; неверный/устаревший trackIndex отклоняется; выбор дорожки меняет revision.
6. Cancel queued/awaiting действует сразу; running становится CANCELLED только после подтверждения остановки.
7. Retry возможен только для retryable terminal failure; unsupported/cancelled/succeeded не повторяются.
8. Lease heartbeat, expiry/reclaim и crash recovery не допускают двойной обработки/двойной публикации output.
9. Активная job защищает source от физического удаления; после terminal state deletion возобновляется.
10. Все API ответы owner-scoped; raw paths, object keys и stderr не раскрываются.

Тесты конкурентности и lease должны использовать PostgreSQL (тот же dialect/locking), а не полагаться только на H2.

## 6. Вне 170-A / открытые gates

Human listening, GP7 independent writer compatibility, GTP/GPX/GP8, сложные размеры, rests/repeats/polyphony/tempo changes/capo/alternate tuning/effects, corrupt/zip-bomb corpus, Windows host и production rollout не считаются пройденными. До production exposure обязательны 170-E и отдельная эксплуатационная приёмка.

## 7. Оценка и статус

**Оценка 170-A: 10 часов (диапазон 8–12ч), P2, Java backend.** Разбивка:

| Работа | Оценка |
|---|---:|
| Flyway schema, repository, DB worker slots и индексы/ограничения | 2ч |
| Job service + REST API, owner checks, idempotency, revision, track selection/cancel/retry | 2.5ч |
| Claim/lease/heartbeat/reclaim и координация удаления source | 2.5ч |
| PostgreSQL integration tests: race, idempotency, state transitions, lease expiry, cancel, delete guard | 2ч |
| Документация, CI и итоговая проверка | 1ч |
| **Итого** | **10ч** |

В main уже есть PostgreSQL integration test harness и CI service PostgreSQL 17 с флагом `GUITAR_MENTOR_TEST_POSTGRES=true`; новый брокер/очередь и новый тестовый сервис не нужны. Основной риск оценки — согласование lease с двумя deletion workers и гонки отмены/удаления. Output quota defaults уточнить в 170-C по фактическим ограничениям PDF/WAV.

**Статус: Оценка / В очереди.** Реализация не начата. Передача в разработку — отдельным решением после оценки.
