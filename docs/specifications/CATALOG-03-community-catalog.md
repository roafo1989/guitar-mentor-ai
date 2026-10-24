# CATALOG-03 — публикация и поиск пользовательских материалов

| Версия | Дата (Europe/Moscow) | Изменение |
| --- | --- | --- |
| 1.0 | 24.10.2026 | Full-контракт US-012/#156, FT-012-01…04/#165–168 для оценки и последовательной разработки. |

Основание: БТ0.18/BT-021…023, ТЗ0.26, поручение подготовить #160–175 до разработки. Прочитана main d9a7c4b805bf48b72adeefb83b818c81a41eaa13. Это целевой контракт, не реализованный API. Дополняет [CATALOG-01](CATALOG-01-private-library.md), заменяет предварительные предложения раздела US-012 [CATALOG-02](CATALOG-02-evolution-roadmap.md). Самопроверка не независимое ревью. Код/CI/браузерные продуктовые проверки NOT_RUN.

## 1. Результат, участники и границы

Допущенный active Account публикует выбранную редакцию собственного материала; остальные допущенные аккаунты находят и открывают её в общем каталоге. Исходный draft остаётся личным. Anonymous/DISABLED не получают библиотеку/каталог. OWNER не читает чужие личные файлы, но может скрыть уже опубликованный материал по операторской причине. Публикация не создаёт `exercises`, назначений или музыкального одобрения.

Включены explicit publish/withdraw, immutable snapshot, текстовые теги, поиск/фильтры, ограниченное PDF/photo preview, необязательное аудио и его явная роль. Не включены OCR, GP-конвертер, внешний поиск, аудиорасшифровка, рейтинг/комментарии/подписки, публичная индексация без входа, распространение через public bucket, редактор музыки, персональные рекомендации. Отдельного редизайна приложения нет: используются принятые компоненты/токены текущего интерфейса.

Численные границы ниже — конфигурируемые инженерные defaults для первого инкремента. Они не обозначают одобренные владельцем размеры будущего каталога или гарантию пропускной способности.

[Последовательность publish/read/withdraw](../diagrams/catalog-publication-sequence.mmd).

## 2. Редакции и модель данных

Аддитивная миграция после P0, без переписывания старых Flyway:

| Объект | Обязательные данные и инварианты |
| --- | --- |
| library_materials | P0 owner/state/revision сохраняются; tags JSONB — уникальные нормализованные строки; max10, каждая1–32 code points, NFC/trim/lowercase(Locale.ROOT), без control chars. Порядок tags в DTO лексикографический. Пустой список разрешён. |
| library_assets | original immutable; добавляются role SCORE_ORIGINAL/PHOTO_ORIGINAL/ORIGINAL_RECORDING/SYNTHESIZED_PREVIEW, mediaType, durationMillis/width/height nullable, derivedFromAssetId nullable, processingState READY/REJECTED. Ссылки на активную редакцию, не замена файла по key. |
| catalog_publications | UUID PK, owner_account_id FK, material_id FK, source_revision bigint, state PUBLISHED/WITHDRAWN/HIDDEN, immutable metadata JSONB (title/type/author/tags), created_at/published_at, publication_revision bigint, hidden_reason nullable. Не более одной PUBLISHED публикации material (partial unique index). |
| catalog_publication_assets | publication_id + asset_id UNIQUE; snapshot role/format/name/hash/size; basis enum OWN_WORK/LICENSE/PUBLIC_DOMAIN/PERMISSION, evidence text1–2000, attribution text0–2000. Asset FK nullable ON DELETE SET NULL, immutable hash/name/rights остаются в snapshot. Проверка active references выполняется сервисом под row lock, не условным FK. Пока публикация PUBLISHED, bytes не удаляются; при удалении материала сначала снимается публикация. |
| catalog_audit_events | UUID, actor_account_id, publication_id, action PUBLISH/REPLACE/WITHDRAW/HIDE, occurred_at, reason_code; без сырых файлов/секретов. Запись в одной DB transaction с действием. |

Источник прав заполнен отдельно для каждого выбранного asset. Это заявление публикующего, а не автоматическая юридическая проверка. URL/название лицензии сами по себе не доказывают право; UI явно просит указать основание и атрибуцию. Материал без аудио публикуется. Asset без основания не включается автоматически; хотя бы один SCORE_ORIGINAL или PHOTO_ORIGINAL обязателен. Ограничение snapshot — до5 score/photo и до2 audio, всего≤7. Фото/аудио добавляются в draft, публикация происходит отдельным действием.

Редактирование title/type/author/tags и добавление media увеличивает material.revision; опубликованный snapshot не изменяется. Замена публикации явно создаёт новый UUID, атомарно WITHDRAWN старой + PUBLISHED новой. Старые URL закрываются. Не обещается история доступа другим пользователям. Оригиналы не копируются физически в shared bucket: snapshot ссылается на immutable keys, quota считается по уникальным физическим assets.

DELETE материала из P0 в одной transaction снимает активную публикацию и переводит материал в DELETING; новые выдачи сразу закрыты. Уже начатую/скачанную локальную копию отозвать невозможно. Worker удаления учитывает references; фоновая очистка attempts/scores остаётся отдельной.

## 3. API #165 — публикация

Все modifying endpoints используют общую CSRF-защиту. Ownership по CurrentActor.accountId. Ошибки чужой library карточки404; общедоступная здесь означает только admitted active accounts. JSON plaintext, unknown fields400, UUID в path, error shape/code/requestId как CATALOG-01. Каждое действие ограничено10/min/account;429+Retry-After. JSON публикации≤32KiB.

| Метод/путь | Контракт |
| --- | --- |
| POST `/api/v1/library/materials/{id}/publications` | If-Match material ETag обязателен; Idempotency-Key UUID обязателен. Body `{assetRights:[{assetId,basis,evidence,attribution}],replacePublicationId?:UUID}`. Проверить все assets READY/own/current и права до DB commit. 201 PublicationView +Location. Без audio разрешено. Текущая PUBLISHED без явного replace →409 CATALOG_ALREADY_PUBLISHED. |
| POST `/api/v1/library/materials/{id}/publications/{publicationId}/withdraw` | Только owner; header If-Match publication ETag. Body `{reason?:string<=500}`. 200 state WITHDRAWN. Повтор уже WITHDRAWN200 без второго эффекта. На HIDDEN —200 HIDDEN, не восстанавливать операторски скрытое. |
| POST `/api/v1/admin/catalog/publications/{id}/hide` | Только действующая роль OWNER из trusted actor; body `{reason:string1..500}`, If-Match. 200 HIDDEN; повтор HIDDEN200. Не разрешает скачивать private draft и не физически удаляет файл. Аудит обязателен. Автоматическое восстановление/appeal workflow не входит. |

If-Match отсутствует428, stale412; Idempotency-Key mismatch409, in progress409+Retry-After3. Политика replay24h как P0: успешный повтор того же запроса200, withdrawal/hidden после успеха не возвращает активные ссылки (возвращается актуальное состояние). Для admin hide key не нужен: действие идемпотентно по публикации/состоянию, audit не дублируется. Publish disabled503, missing/foreign404, unavailable asset409 CATALOG_ASSET_NOT_READY, incomplete rights400 CATALOG_RIGHTS_REQUIRED. На endpoint публикации клиент не задаёт owner/state/music approval.

PublicationView: id, materialId (только owner view; у остальных отсутствует), sourceRevision, title/type/author/tags, state, reviewStatus=UNREVIEWED, assignmentEligible=false, publishedAt, revision, files с защищёнными catalog content URLs/ролями и атрибуцией. Не возвращать email/login/owner internal ID/storage keys. Ссылки на источник — текст; UI допускает внешние HTTPS ссылки только после проверки URL, rel=noopener noreferrer, без автоматического fetch.

Минимальный процесс реагирования: уже имеющийся канал поддержки/Issue с publication UUID, оператор hide с причиной, запись аудита; блокирующей предварительной модерации нет. Новый продуктовый workflow жалоб, сроки юридического ответа или автоматические правовые решения не обещаются. Операторский hide доступен API; отдельная admin-панель не нужна для #168.

## 4. API #166 — каталог, фильтры и выдача

| Метод/путь | Контракт |
| --- | --- |
| GET `/api/v1/catalog/materials` | q optional≤150 после trim, type optional enum P0, tags repeated≤10, hasAudio optional boolean, limit20/1–50, cursor optional. 200 `{items,nextCursor}`; только state PUBLISHED. |
| GET `/api/v1/catalog/materials/{publicationId}` | 200 PublicationView +ETag; withdrawn/hidden/unknown404 CATALOG_PUBLICATION_NOT_FOUND. Даже owner использует library route для управления снятым snapshot. |
| GET/HEAD `/api/v1/catalog/materials/{publicationId}/files/{assetId}/content` | Проверить actor и PUBLISHED на каждый запрос, membership snapshot и file READY. 200 bytes; для audio поддержать один byte-range206/Content-Range; некорректный/множественный Range416, HEAD без тела. no-store/nosniff; safe attachment filename для original, производные browser media с точным MIME. |

Поиск: q — одна нормализованная подстрока по title ИЛИ author; case-insensitive, NFC, trim, буквальные `%`/`_` экранировать, не wildcard язык. Пробелы внутри сохраняются, пустое q означает отсутствие q. Тип AND q AND hasAudio AND все tags. Теги свободные, не единый enum для жанра/техники/культурной категории. hasAudio=true — хотя бы один доступный READY audio в snapshot; false — ни одного, omitted — без фильтра. GP original сам по себе не audio. Если физический файл потерян, endpoint404 CATALOG_FILE_UNAVAILABLE; reconciliation обновляет READY→unavailable, без фиктивного hasAudio.

Порядок published_at DESC,id DESC. Cursor HMAC с version/actor/filters/order/lastPair, окно выдачи60min, несовместимые фильтры/actor400 CATALOG_INVALID_CURSOR. Новая публикация не приводит к повтору уже выданных строк; withdrawn строки исчезают. Не обещается зафиксированный total: counts и suggestions не входят в первый инкремент. SQL только bind parameters; limit применяется до построения файловых DTO, нет N+1. Индексы: partial `(published_at DESC,id DESC) WHERE state='PUBLISHED'`, material/owner/state, GIN tags, expression lower(title)/lower(author) с pg_trgm GIN (расширение устанавливает миграция при наличии стандартных DB прав; при недоступности — fail migration с runbook, не тихий fullscan). Фильтр hasAudio через indexed EXISTS READY association. EXPLAIN на синтетических10k publications, тест query combinations/limit/cursor. Цель engineering: p95≤500ms на указанном в отчёте local стенде20 последовательных запросов; это не внешняя SLA и не доказано анализом.

## 5. #167 — добавление фото/аудио и безопасный preview

POST `/api/v1/library/materials/{id}/files`: multipart metadata JSON `{role}`, repeated files, If-Match material +Idempotency-Key. 201 updated MaterialView/revision+1. P0 reserve/staging/manifest/commit/recovery и replay повторяются, не второй несовместимый storage pipeline. Role PHOTO_ORIGINAL — JPEG/PNG, ORIGINAL_RECORDING/SYNTHESIZED_PREVIEW — MP3/WAV; score originals добавлять этим endpoint не требуется. MIME/sniff/decode должны совпасть, не доверять расширению. role обязателен, GP/PDF нельзя выдать за audio. Body unknown fields400. Одна операция целиком успешна или ничего не добавлено. DELETE `/api/v1/library/materials/{id}/files/{assetId}` с If-Match→202; активный опубликованный reference409 CATALOG_ASSET_PUBLISHED, сначала withdraw/replace. Последний score/photo удаляется только через DELETE всего материала, иначе409 CATALOG_LAST_SCORE.

Capabilities при community-media-enabled дополнительно возвращает role-specific allowedExtensions, maxFilesPerMaterial=7, maxPhotosAndScores=5, maxAudio=2 и media limits; P0 create по-прежнему принимает только PDF/GP и до5 originals. При выключенном media flag новый upload/preview503, P0 контракт не меняется.

Начальные caps:20MiB aggregate/request, до2 новыхfiles/request,≤7files/material, photo≤5MiB/20MP и каждая сторона≤12000px, audio≤20MiB/10min, WAV PCM16 mono/stereo 8–48kHz, MP3 mono/stereo 8–48kHz. Ограничения клиента из capabilities; account bytes/files/reservations учитывают оригиналы и derivatives, P0 квоты не увеличиваются автоматически. При insufficient quota409, unsupported415, decoder invalid400, duration/dimensions превышены413. Для WAV bounded parser сверяет RIFF/chunk lengths, MP3 — bounded metadata parser с pinned dependency/без native command. Валидация новых медиа выполняется до READY, не доказывает музыкальную связь с нотами. Аудио остаётся необязательным.

Изображение: оригинал сохраняется неизменённым. Preview — JPEG/PNG, decode с pixel/memory limits и re-encode без EXIF; не отдавать SVG/HTML. Worker без сети, лимит1operation/account, global2, timeout30s, heap512MiB, output≤5MiB; переполнение/неподдержанный decoder → REJECTED, original не публикуется/preview не готов. Эти параметры — config, worker отключаем feature flag. Production parser версии фиксируются lockfile/SBOM, не latest/CDN.

PDF preview: только canvas-render в изолированном sandbox iframe с `allow-scripts`, без `allow-same-origin`, forms/popups/navigation/downloads. Родитель после authorisation получает bounded arrayBuffer существующим authenticated endpoint и передаёт bytes через MessageChannel только этому iframe; opaque origin не принимается как доверенный произвольный sender, проверять exact WindowProxy/port+одноразовый nonce. Iframe не получает URLs/cookies/tokens и имеет CSP `default-src 'none'; connect-src 'none'` с локально bundled scripts/worker по явным hashes/blob rule; никакого сетевого CDN. PDF.js pinned в frontend lockfile, отключены eval/scripting, forms/actions/attachments/XFA/annotation links; UI только canvas+выбор страницы/zoom. Render≤1page одновременно,≤100pages/20MiB document,≤8MP canvas, timeout10s/page; пароль/лимит/ошибка → понятный fallback download unchanged original. Input original не обязан MUS-NOTATION-01. При logout/deselection cancel/destroy loading task и iframe/bytes, старый async не восстанавливает экран. Эта изоляция и CSP — проверяемый AC, не предположение, что библиотека безопасна сама по себе.

MP3/WAV — обычный HTML audio controls после защищённой выдачи, no autoplay; blob URL отозвать при смене/выходе. Bounded20MiB позволяет загрузку blob без streaming; Range сервером всё равно поддерживается для browser direct audio и seek. Источник ORIGINAL_RECORDING/SYNTHESIZED_PREVIEW подписан явно. Заявленная роль пользователя не означает технически доказанный синтез/совпадение с score.

GET `/api/v1/library/materials/{id}/files/{assetId}/preview` и соответствующий `/api/v1/catalog/materials/{publicationId}/files/{assetId}/preview`: только sanitized photo derivative;200 JPEG/PNG,404 если отсутствует, не возвращать оригинал под видом sanitized. PDF canvas использует `/content` с теми же проверками доступа; отдельный server PDF renderer не вводится. Preview metadata `{available,kind,reasonCode}` в FileView. Публикация photo разрешена только после READY sanitized preview; GP может публиковаться как скачиваемый original без обещания PDF.

## 6. #168 — UI и сценарии

Раздел «Материалы»: вкладки «Мои»/«Общие»; P0 элементы сохраняются. Общая выдача: строка q, type, до10 текстовых tag chips, «Есть аудио», сброс, следующая страница; URL/state содержит только фильтры, не приватные файлы. Debounce300ms, AbortController +request generation guard; после logout очищаются caches/selection/blobs. Не использовать client-side фильтрацию первых20 вместо серверного поиска.

Карточка показывает источник/тип, теги, UNREVIEWED «Пользовательский материал, не проверен для назначения», атрибуцию, assets/форматы, canvas/photo и optional audio. Нет видео/распознавания фото. При недоступном preview — сообщение+скачивание; у GP — явно «Оригинал Guitar Pro; конвертация пока недоступна». Нет ложного успеха при404/401/429/network.

Владелец из «Мои»: добавить media/теги, dialog публикации с выборомfiles и основанием каждого, ясное «доступно всем допущенным пользователям», кнопка publish; без отметки/выбора не отправлять. Состояния «личный», «опубликована редакция N», «черновик изменён», withdraw с подтверждением. Обновление draft не обновляет общую карточку. Withdrawal немедленно обновляет own UI и инвалидирует common selection; чужой уже открытый UI узнаёт при следующем запросе, не обещает push revocation. Безопасное отображение plaintext, RU/EN, клавиатура/focus restore/Escape, labels, narrow360px, 200%zoom и длинные названия. Дизайн берётся из принятой актуальной ветки на старте, API не зависит от ожидания нового макета.

## 7. Эксплуатация, отказы и откат

Flags `guitar-mentor.catalog.enabled=false`, `guitar-mentor.library.community-media-enabled=false`; при false503 на modifying/read catalog, capability/UI честно скрыты, исходный P0 продолжает работать. Настройки limits валидировать на старте, секрет cursor reuse безопасного отдельного purpose library secret. Метрики success/fail/withdraw/hide, duration/upload/rejected, bytes/jobs и quota; labels без title/email/rawURLs/контента. Аудит actor/action/id, не файлы. Backup #40 включает publication metadata+library objects как согласованную пару; withdraw не физическое delete. Удалённые bytes восстановимы только из существующей копии.

Сбой DB перед commit не публикует snapshot. Сбой response после commit replay не создаёт дубликат. Отказ storage не создаёт READY media. Рестарт media processing использует leases/manifest P0, orphan cleanup не удаляет referenced publication files. Missing file — понятная ошибка, incident/log, никакого204/пустого200. Publish/quota/edit/withdraw/delete concurrency тестируется минимум с двумя аккаунтами и двумя запросами owner.

Rollout: feature flags off → additive migration/integration tests → small synthetic corpus/двухаккаунтный smoke → enable в разрешённой среде. Rollback trigger — private leak, stale withdrawn download, bytes loss или unbounded decoder: отключитьcatalog/media flags, остановитьmedia worker, сохранить DB/root, откатить binary, оставить additive schema. Восстановление snapshot не должно снова открыть HIDDEN/WITHDRAWN. Никакого автоматического deploy/публичного bucket/платежа этим анализом.

## 8. Приёмка и готовность

AC-COM-01…06 сохранены, подробные Given/When/Then в [TC-CATALOG-02](../testing/TC-CATALOG-02-community.md). #165→#166→#167→#168; #167 дополнительно использует завершённый P0 storage/recovery. Каждая задача включает собственные developer tests, миграцию/конфигурацию/runbook затронутого слоя и PR; #168 — общий браузерный сценарий. Наличие spec не означает продуктовый PASS.

Осознанные ограничения: fixed extension/codec allowlist, account quota малого пилота, новые публикации без premoderation, нет OCR/GP/audio generation. Блокирующих продуктовых TBD для этого ограниченного инкремента нет. Разработка начинается после predecessor; оценка и статус — [план передачи](../project/CATALOG-READINESS-2026-10-24.md).

Источники для технической конкретизации: [OWASP File Upload](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html), [PDF.js canvas examples](https://mozilla.github.io/pdf.js/examples/), [Spring MVC range](https://docs.spring.io/spring/reference/web/webmvc/mvc-range.html). Это архитектурные решения СА на основе ограничений; первичные источники не подтверждают, что наш код уже реализован или защищён.
