# CAT-SHARED-01 — общий Work и авторские аранжировки

| Версия | Дата | Изменения |
| --- | --- | --- |
| 0.1 | 01.11.2026 | Уточнены идентичность Work, связь с авторскими материалами, права, snapshots, аудит, ошибки, конкуренция и границы split/merge для #204. |

**Статус:** технический контракт анализа #204; не является утверждением музыкальной эквивалентности или разрешением на публикацию материалов.

## 1. Решение и область

Один подтверждённый Work может иметь материалы/аранжировки от нескольких авторов. У каждого материала остаётся один владелец; публикация и права на файлы принадлежат конкретному материалу и его assets. Work — общая библиографическая/музыкальная идентичность, а не контейнер файлов и не владелец пользовательских данных.

Не создавать второй приватный музыкальный индекс. V27 `music_catalog_works`, `music_catalog_arrangements`, `music_catalog_source_files` остаются owner-scoped индексом импортированных метаданных. Он не является источником публичных Work, не даёт прав на файлы и не мигрирует автоматически в общий каталог.

В существующей библиотеке `library_materials` уже является авторским агрегатом с owner, revision, assets и публикациями. В доменной модели этот агрегат представляет авторскую Arrangement/Material. Отдельная дублирующая таблица Arrangement не вводится в #204: связь с Work добавляется как самостоятельная ассоциация материала. Если в будущем один материал будет содержать несколько самостоятельных аранжировок, это отдельное изменение модели.

## 2. Модель данных

### 2.1 Shared Work

Новая таблица `community_works`:

- `id UUID PRIMARY KEY`;
- `title`, `artist`, `normalized_title`, `normalized_artist` — общие метаданные;
- `identity_status`: `CANDIDATE | VERIFIED | RETIRED`;
- `created_by_account_id` — приватная ссылка для авторизации/аудита, никогда не возвращается в общих DTO;
- `created_at`, `updated_at`, `revision BIGINT`;
- необязательные библиографические поля добавляются только при наличии надёжного источника и отдельного требования.

Индексы поиска по normalized title/artist не уникальные. Название и исполнитель не являются уникальным ключом и не разрешают автоматическое объединение. Уникальный fingerprint по строкам не вводится.

### 2.2 Association

Новая таблица `library_material_work_links`:

- `material_id UUID PRIMARY KEY REFERENCES library_materials(id) ON DELETE RESTRICT`;
- `owner_account_id UUID NOT NULL REFERENCES accounts(id) ON DELETE RESTRICT`;
- `work_id UUID NOT NULL REFERENCES community_works(id) ON DELETE RESTRICT`;
- `created_at`, `created_by_account_id`;
- `revision BIGINT NOT NULL DEFAULT 1`.

Composite FK `(owner_account_id, material_id)` на уникальную пару владельца и материала в `library_materials` должна обеспечивать совпадение владельца (если текущая схема не имеет подходящего UNIQUE, добавить аддитивный UNIQUE после проверки дублей). Один материал связан максимум с одним Work; несколько материалов и владельцев могут ссылаться на один Work. Удаление Work каскадом не удаляет материалы. Связь удаляется только явной транзакционной командой.

### 2.3 Publication snapshot

`library_publications` получает nullable `work_id` (FK RESTRICT) как снимок связи на момент публикации. При publish/replace значение копируется из текущей association под блокировкой материала. Текущая association сама по себе не изменяет существующий snapshot.

Следствие: привязка уже опубликованного материала не переписывает старую публикацию и не добавляет её в публичный Work. Чтобы новая связь стала видна в общем каталоге, автор выполняет существующий withdraw → publish flow; новая публикация фиксирует Work. Withdraw/HIDDEN остаются в истории и не выдаются как действующие материалы.

### 2.4 Audit

Новая append-only таблица `community_work_events`: id, work_id, actor_account_id, action, previous_status/new_status, previous_work_id/new_work_id (nullable), reason_code, created_at, request_id. Не хранить токены, storage keys, пути, содержимое файлов, evidence прав или приватные метаданные материалов. Записи не удаляются CRUD-операциями.

## 3. Состояния и полномочия

- Любой admitted actor может создать CANDIDATE для сохранения неоднозначной идентичности. Кандидат виден создателю и системному OWNER для review, но не появляется в общем поиске и не может быть целью публичной привязки.
- Только существующая роль `OWNER` подтверждает Work (`CANDIDATE → VERIFIED`) после ручной проверки источников/идентичности. Никакого AI/music auto-approval.
- Только VERIFIED Work участвует в общем поиске и может быть выбран для association.
- RETIRED Work не предлагается для новых связей; существующие snapshots/links не удаляются автоматически.
- Общие метаданные Work редактирует только OWNER. Автор материала не получает право менять Work через владение Arrangement.
- Администратор не получает право читать или изменять чужой PRIVATE/DRAFT материал лишь потому, что подтверждает Work или модерирует каталог.

Кандидаты с одинаковыми нормализованными строками показываются как возможные совпадения только в приватном review-контексте. Никаких автоматических attach/merge и раскрытия чужого числа/наличия private материалов.

## 4. API-контракт

Все endpoint требуют активную сессию admitted actor; actor/account берётся только из `CurrentActor`. CSRF сохраняется для изменяющих запросов. Общие ответы — no-store. Идентификаторы владельцев и storage paths не возвращаются.

### 4.1 Общий поиск и detail

- `GET /api/v1/community/works?q=&limit=&cursor=` — только VERIFIED Work, для которых существует хотя бы одна действующая PUBLISHED snapshot с READY/доступными assets; выдача не включает counts, скрытые материалы или private candidates.
- `GET /api/v1/community/works/{workId}` — общие метаданные Work и только разрешённые PUBLISHED snapshots/авторские display fields, доступные через существующие publication services.
- Пустой Work без доступных публикаций не показывается в общем поиске/detail. Сам Work сохраняется для истории и последующего восстановления публикации.

### 4.2 Candidate/review

- `POST /api/v1/community/work-candidates` — создать candidate с title/artist и idempotency key; повтор с тем же ключом и fingerprint возвращает тот же candidate.
- `GET /api/v1/community/work-candidates/{id}` — только creator или OWNER; чужой candidate отвечает тем же 404, что отсутствующий.
- `POST /api/v1/admin/community/works/{id}/verify` — OWNER-only, If-Match Work revision, reason/source reference; атомарно меняет статус и пишет audit.

### 4.3 Attach/detach

- `PUT /api/v1/library/materials/{materialId}/work-link` — body `{ "workId": "..." }`, обязательны If-Match material ETag и Idempotency-Key. Только владелец своего READY material. Цель обязана быть VERIFIED. В одной транзакции lock material → lock Work → проверить revision/состояние/существующую связь → insert/update association → audit.
- `DELETE /api/v1/library/materials/{materialId}/work-link` — тот же owner scope и If-Match; удаляет только association и пишет audit. Не удаляет Work, material, publication или asset.
- Идентичный повтор PUT с тем же idempotency key/fingerprint — идемпотентный replay. Повтор с тем же ключом и иным fingerprint — 409. Иная существующая связь требует явного detach/reattach, не молчаливой перезаписи.

### 4.4 Correction / split / merge

- Исправление неверной связи: owner detach + attach к другому VERIFIED Work; каждая операция If-Match/idempotent и аудируется. Исторические публикационные snapshots остаются с прежним Work. Для обновления публичной связи автор явно перевыпускает публикацию через существующий flow.
- Split: OWNER-only административная операция только после dry-run плана. Создаёт новый Work ID, переносит только явно перечисленные текущие associations транзакционно, сохраняет прежние события и старые publication snapshots; не переписывает их. Материалы, которые нельзя однозначно распределить, остаются на исходном Work до ручного решения.
- Merge: разрушительный автоматический merge в #204 не реализуется. Допускается только dry-run/ручная процедура OWNER с проверкой конфликтов, сохранением обоих Work ID как RETIRED/redirect aliases, истории, snapshot ссылок и associations. Никаких DELETE/ON DELETE CASCADE. При конфликте — отказ без частичной мутации.
- Все административные операции требуют reason code и audit. Нет операции без If-Match и повторной проверки полномочий в транзакции.

## 5. Ошибки и конкурентность

| HTTP | Код | Условие |
| --- | --- | --- |
| 401 | UNAUTHENTICATED | Нет действующего actor |
| 403 | FORBIDDEN | Actor не имеет требуемой роли/доступа |
| 404 | RESOURCE_NOT_FOUND | Чужой private material/candidate, отсутствующий или недоступный ресурс — одинаковый ответ |
| 409 | WORK_NOT_VERIFIED | Candidate/retired Work нельзя привязать |
| 409 | WORK_LINK_CONFLICT | У материала уже другая связь |
| 409 | IDEMPOTENCY_KEY_REUSED | Тот же ключ с другим request fingerprint |
| 412 | REVISION_CONFLICT | If-Match устарел |
| 428 | PRECONDITION_REQUIRED | Нет обязательного If-Match |
| 400 | INVALID_REQUEST | Невалидные поля/формат |
| 503 | COMMUNITY_CATALOG_DISABLED | Если общий каталог отключён feature policy |

Все ошибки безопасные, без SQL/исключений/чужих IDs. В частности, поиск Work не меняет результат/ошибку в зависимости от наличия private draft другого пользователя.

Attach/detach, delete, publish/withdraw и HIDE сериализуются на одной строке material с `SELECT ... FOR UPDATE` (или эквивалентной условной revision mutation). Work verify/retire/split блокирует Work. Порядок блокировок в сценариях с несколькими Work — сортировка UUID, затем association/material; deadlock retry ограничен и идемпотентен. Уникальные constraints защищают один link на material и уникальный idempotency key. Любая ошибка откатывает и связь, и audit, и публикационную мутацию; физические файлы не затрагиваются этими транзакциями.

## 6. Удаление и жизненный цикл

- Удаление material A использует существующий owner-scoped soft-delete/withdraw/deletion worker. Association A удаляется или переводится в tombstone в той же транзакции; Work не удаляется.
- Материалы B и их bytes/publications не затрагиваются.
- Пустой Work сохраняется как VERIFIED/EMPTY в модели данных, но не выдаётся в общем поиске; после новой PUBLISHED связи снова появляется. Позднее физическое архивирование Work — отдельная retention policy, не каскадное удаление.
- WITHDRAWN/HIDDEN snapshots не участвуют в выдаче; история сохраняется. Отсутствующие bytes обрабатываются существующим media integrity contract и не превращаются в доступную публикацию.

## 7. Security/privacy

- Все проверки выполняются сервером; переданный accountId игнорируется/отвергается.
- В общем Work DTO запрещены creator_account_id, owner_account_id, private material IDs, private counts, file paths, storage keys, evidence прав, приватные source filenames и сведения о private associations.
- Link не меняет visibility, rights_basis, publication state, asset membership, preview или storage.
- Imported V27 corpus остаётся PRIVATE и не мигрирует в community Work автоматически.
- Публичная выдача опирается на snapshot.work_id и действующий Publication/Asset policy, а не на live association.
- Не менять ранее опубликованные snapshots при attach/detach, Work metadata update, split или merge.

## 8. Acceptance criteria

| ID | Критерий |
| --- | --- |
| AC204-01 | A и B связывают собственные разрешённые материалы с одним VERIFIED Work; C видит только их действующие PUBLISHED snapshots. |
| AC204-02 | A удаляет свой material; Work, B material, B publication и B bytes остаются. |
| AC204-03 | Чужой draft/material нельзя читать, связывать, менять, публиковать или удалять; отказ не мутирует БД. |
| AC204-04 | Совпадение title/artist даёт candidate, но не VERIFIED, attach или merge. |
| AC204-05 | Correction/split сохраняют event history, publication snapshots и права; destructive merge не входит в автоматический API. |
| AC204-06 | Параллельные attach/detach/delete/publish/withdraw/retry дают serializable согласованный исход, без duplicate links/orphans/partial mutation/500. |
| AC204-07 | Empty Work, withdrawn/hidden snapshots и missing assets ведут себя по разделу 6. |
| AC204-08 | Аддитивная Flyway миграция проходит на чистой PostgreSQL и на заполненной схеме до текущего head; применённые миграции неизменны. |
| AC204-09 | PostgreSQL-backed secured HTTP tests используют независимые test actors/CSRF; реальный TCP HTTP с отдельными cookie jars проверяется отдельно и не заменяется MockMvc. |
| AC204-10 | #168 contract совместим: существующие /library и Publication API не переписываются; если UI требует attach control, это отдельный минимальный follow-up в рамках согласованной зависимости. |

## 9. План реализации и оценка

Оценка оставшегося объёма после #165–#167: **24–30 ч** (backend + PostgreSQL migration + tests + docs; без разработки нового UI и без ручной UAT владельца). Целевая гранулярность регламента 4–6 ч требует последовательных принимаемых инкрементов, но Issue #204 остаётся единым полным результатом:

1. Shared Work schema, candidate/verify, audit and repository — 6 ч.
2. Owner-scoped attach/detach, If-Match/idempotency, concurrency — 6 ч.
3. Snapshot integration with existing publication lifecycle and published-only search — 6 ч.
4. Correction/split safety, security/HTTP/PostgreSQL tests, docs and CI hardening — 6–12 ч.

В объём не входят #168 UI reimplementation, #170 conversion, #40 DR, #245 residual QA, AI/OCR/external search, imported-index publication, destructive merge implementation and production/PILOT operations.

## 10. Откат

Миграция только аддитивная. Откат кода возможен без потери старых публикаций, если новые endpoint отключены. Down migration не выполняется автоматически: удаление Work links/events/snapshot.work_id после появления пользовательских связей уничтожило бы историю. Для аварийного возврата сначала остановить новые endpoint, сохранить данные и провести отдельную проверенную migration plan. Применённые Flyway migrations не редактировать.

## 11. Evidence / implementation notes

- Main baseline checked: `f114c41e2ae03e1f8c4f7f3eb49f6549b109811e`.
- Existing private index: V27, `MusicCatalogRepository`, `MusicCatalogController`; remains owner-scoped and metadata-only.
- Existing publication model: V28/V31/V32, `library_publications`, immutable snapshot and event lifecycle.
- #168 PR #258 has since been merged and #168 is closed. Its current main implementation is part of the baseline; #204 must integrate with the merged community publication search/media endpoints.
- No `AGENTS.md` exists in the recursive main tree as checked on 2026-11-01. Follow repository development workflow and security conventions.
- The former CATALOG-03 / CATALOG-02 links referenced in historical issues do not resolve on current main; do not treat that 404 as a blocker. MUSIC-01 and current implementation are the available source of truth.
