# CATALOG-01 — личная библиотека готовых PDF/Guitar Pro

Версия1.0 · 24.10.2026 · **Full-анализ P0 подготовлен к оценке**, автор — СА; независимое review не проводилось. БТ0.18/BT-021/022 → ТЗ0.25/US-011/#155 → FT-011-01…05/#160–164; EPIC-004/#154. Решения ниже — конкретизация минимального объёма по последнему прямому приоритету владельца. Оценки/разработка/QA не выполнялись.

## 1. Ценность, факты и границы

Как допущенный пользователь, я хочу добавить собственный готовый PDF или файл Guitar Pro, найти его после возврата, получить оригинал и удалить, чтобы хранить материалы для занятий в приложении. Цель P0 — самостоятельная поставка, не ожидающая публикации, конвертера или модели. Готовность: весь цикл доступен через Vue, файлы не теряются и не раскрываются другому аккаунту.

Исследована main `d9a7c4b805bf48b72adeefb83b818c81a41eaa13`: Java21/Spring Boot4.1.1/PostgreSQL/Vue3, #38/#42 merged. `CurrentActor.accountId` — владелец, UUID Learner не идентификатор владельца библиотеки. #141 добавляет второй метод входа отдельно; P0 достаточно работающего Google. Новый UI следует существующим токенам и согласованному дизайну, без параллельного редизайна приложения.

Существующий `exercises` — версионированные проверенные задания с обязательными musical/event resources и FK назначений; `ExerciseController` читает classpath. Он **не подходит** для произвольного личного PDF. `DocumentMediaStore` и `EvidenceMediaCleanup.reconcileDocumentOrphans` удаляют файлы без diagnostic evidence-связи. Поэтому библиотеке нужен отдельный root и таблицы: reuse их helper/pattern допустим, reuse папки/TTL/службы без разделения запрещён. Общая multipart-конфигурация main:20MiB/file и32MiB/request. Бинарные оригиналы хранятся в закрытой файловой области, карточки/связи/контрольные суммы — в PostgreSQL; «загрузить в базу» означает сохранённую запись приложения, а не обязательный BYTEA.

**P0 включает:** название/тип/необязательный автор,1–5 готовых оригиналов PDF/Guitar Pro, личный список/карточку/редактирование метаданных, скачивание, удаление, квоты, идемпотентность и recovery. **Не включает:** общий доступ, публикацию, изображения/аудио, inline-preview, парсинг нот, конвертацию, перевёрстку исходника, OCR, web fetch/LLM, назначение/оценку упражнения, теги/полнотекстовый поиск, автозаполнение информации из файла. В P0 PDF скачивается как оригинал; просмотр внутри карточки относится к US-012. Это намеренное ограничение первой поставки, не удаление целевого AC-022-02.

Форматы хранения: `.pdf`, `.gtp`, `.gp3`, `.gp4`, `.gp5`, `.gpx`, `.gp`. «GTP» пользователя трактуется как семейство Guitar Pro с видимым точным расширением. Формат хранения ≠ поддержка конвертации. GP принимается как **неисполняемый непроверенный оригинал**; музыкальная валидность/открываемость/соответствие MUS-NOTATION-01 не обещаются. Никакой импортёр или декомпрессор не запускается на HTTP пути P0. Для PDF проверять заголовок `%PDF-` и ограниченный preflight; не рендерить/исполнять на сервере и не изменять bytes. Зашифрованный оригинал можно хранить, но пароль не запрашивается и расшифровка/preview не обещается. Данный контракт не разрешает обход ограничений исходника.

## 2. Компоненты и доступ

`LibraryController` → `LibraryService`/`LibraryUploadService` → `LibraryRepository` + отдельный `LibraryObjectStore`; `LibraryRecovery` обрабатывает durable pending/deletion. `CurrentActor` берётся только из доверенного request context. Все новые endpoints включены в security route inventory; анонимный=401, не admitted/DISABLED=403/401 по действующему auth-контракту. Чужой material/asset и несуществующий UUID дают одинаковый404 `LIBRARY_MATERIAL_NOT_FOUND`/`LIBRARY_FILE_NOT_FOUND`. Роль OWNER не даёт читать чужие личные материалы. CSRF на POST/PATCH/DELETE/multipart, cookies/TTL/logout из общей security foundation.

У существующего loopback opt-out нет доверенного Account — библиотека закрыта, никакого fallback на learnerId из браузера. Привязка к новому password login141 делается через общий CurrentActor, не через OidcUser в library layer.

## 3. REST и данные (см. также [OpenAPI](../api/library-v1.openapi.yaml))

| Метод/путь | Запрос | Успех и ограничения |
| --- | --- | --- |
| GET `/api/v1/library/capabilities` | нет |200: enabled, extensions, лимиты, charged/reserved bytes/files; требует active actor. При disabled возвращает enabled=false, остальные операции503. |
| POST `/api/v1/library/materials` | multipart: `metadata` JSON, повторяемый `files`; header `Idempotency-Key` UUID |201 после READY, Location и полная карточка; повтор завершённого того же payload200 + `Idempotency-Replayed:true`. На успехе нет частичной карточки без обещанных файлов. |
| GET `/api/v1/library/materials` | `limit` default20,1…50; `cursor` nullable |200 `{items,nextCursor}`; только READY своего actor, порядок created_at DESC,id DESC; cursor непрозрачный с версией/actor/sort и подлинностью, неверный/чужой400. Offset и unlimited full list отсутствуют. |
| GET `/api/v1/library/materials/{materialId}` | UUID required |200 полная карточка, ETag `"revision"`; чужая/DELETING/отсутствующая404. |
| PATCH `/api/v1/library/materials/{materialId}` | JSON title/type/author + обязательный If-Match `"revision"` |200, revision+1; absent If-Match428, stale412 `LIBRARY_REVISION_CONFLICT`. Не меняет originals/owner/visibility. |
| GET/HEAD `/api/v1/library/materials/{materialId}/files/{assetId}/content` | оба UUID required |200 attachment; GET exact bytes, HEAD тот жеstatus/type/size без тела. PDF `application/pdf`, GP `application/octet-stream`; no-store/nosniff, safe Content-Disposition. Никаких прямых object keys/публичных URLs. |
| DELETE `/api/v1/library/materials/{materialId}` | обязательный If-Match для READY |202 `{id,state:DELETING}`; durable задача удаления. Уже DELETING повтор202, завершённое удаление/отсутствующее404; эффекты идемпотентны. Missing If-Match428/stale412. |

DTO create: `title` required1…150 после trim; `type` required EXERCISE/ETUDE/COMPOSITION/OTHER; `author` nullable0…150 после trim (пусто→null). Содержимое plaintext, без HTML. Unknown fields запрещены; client ownerId/visibility/review/paths не принимаются. Метаданные ≤16KiB. Неизвестный тип/невалидный UUID/limit/курсор=400. Для PATCH минимум одно из трёх полей, nullable разрешён только author; пустой patch400.

MaterialView: id(UUID), title, type, author(nullable), state=READY, visibility=PRIVATE, reviewStatus=UNREVIEWED, revision(integer>=1), createdAt/updatedAt(RFC3339UTC), files[]. FileView: id, originalName(без path/CRLF), format (PDF/GTP/GP3/GP4/GP5/GPX/GP), byteSize, sha256(lowerhex64), validationStatus (PDF_HEADER_CHECKED или ORIGINAL_UNVERIFIED), downloadUrl (owner-checked endpoint). Нет storageKey/account identity/секретов; sha256 не заявляет музыкальную проверку. List использует тот же MaterialView, max50×5 bounded metadata; бинарные данные не в JSON. Аудио не выдумывать.

Пример create metadata: `{"title":"Моя пьеса","type":"COMPOSITION","author":null}`. Пример error: `{"code":"LIBRARY_QUOTA_EXCEEDED","message":"Library quota exceeded","requestId":"opaque-id"}` — UI локализует по code, не выводит stack/path. 401/403 из общей security могут иметь только code.

| HTTP/code | Условие/поведение UI |
| --- | --- |
|400 LIBRARY_INVALID_METADATA / LIBRARY_INVALID_CURSOR / LIBRARY_INVALID_FILE | Валидация: исправить ввод, сохранить описание; пустой файл/опасное имя/неверный PDF header отклонены. |
|413 LIBRARY_UPLOAD_TOO_LARGE | Файл или сумма>20MiB; proxy/server413 не JSON тоже локализовать, не утверждать успех. |
|415 LIBRARY_UNSUPPORTED_FORMAT | Расширение вне allowlist; HTML/EXE не обслуживаются как active content. |
|409 LIBRARY_QUOTA_EXCEEDED / LIBRARY_UPLOAD_IN_PROGRESS / IDEMPOTENCY_KEY_REUSED | Превышение квоты, тот же key в работе, либо другой payload с key; у IN_PROGRESS Retry-After3, не запускать неконтролируемые повторы. |
|410 LIBRARY_UPLOAD_RESULT_DELETED | Результат с тем же key уже удалён; новая загрузка требует явного действия с новым key. |
|412 LIBRARY_REVISION_CONFLICT /428 LIBRARY_PRECONDITION_REQUIRED | Обновить карточку, сохранить локальный текст и предложить осознанно повторить. |
|429 LIBRARY_RATE_LIMITED | Retry-After, не auto-loop; запрос не создаёт новый upload key. |
|503 LIBRARY_DISABLED / LIBRARY_STORAGE_UNAVAILABLE | Не считать сохранённым; текст/выбор файла остаётся в текущем компоненте, без localStorage. |

Idempotency scope `(account_id,key)`: fingerprint нормализованных metadata + упорядоченных оригинальных имён + actual SHA256/bytes каждого файла. Идентичный replay не начисляет bytes повторно; mismatch409. Клиент один key на пользовательскую попытку; потеря HTTP ответа допускает только контролируемый повтор тех же файлов с тем же key. Completed record24h; после окна клиент не повторяет молча, предлагает проверить список перед новой загрузкой. Failed request возвращает единый terminal code; reservation/recovery обязаны завершиться до освобождения ключа. Не выдавать payload/наличие ключа другого actor.

## 4. PostgreSQL и жизненный цикл

Аддитивная Flyway-миграция после текущего main (номер назначить при разработке, #141 может добавить свою); существующие V1–V19 и exercises/diagnostic FK не менять. Таблицы:

| Таблица | Поля/ограничения |
| --- | --- |
| library_materials | id UUID PK; owner_account_id UUID NOT NULL FK accounts ON DELETE RESTRICT; title varchar150 NOT NULL; material_type varchar16 CHECK; author varchar150 NULL; state varchar16 READY/DELETING; revision bigint>=1; created_at/updated_at timestamptz NOT NULL. Private и unreviewed — константы P0, не формировать SQL полей visibility из клиента. Index(owner,state,created_at DESC,id DESC). |
| library_assets | id UUID PK; material_id UUID NOT NULL FK; object_key UUID/string safe UNIQUE; original_name varchar255 NOT NULL; format varchar8 CHECK; byte_size bigint>0; sha256 char64; created_at; index material_id. File bytes неизменяемы, не заменяются PATCH. |
| library_upload_operations | id UUID PK; owner_account_id FK; idempotency_key UUID; UNIQUE(owner,key); fingerprint char64 nullable до завершения stream; manifest JSONB с ключами/staging/digest, last_lease_at/lease_owner; state RECEIVING/COMMITTING/SUCCEEDED/FAILED; requested/reserved bytes/files; material_id nullable; expires_at/updated_at; terminal_code nullable. Terminal consistency CHECK; index(state,updated_at), owner/key. |
| library_account_usage | account_id PK/FK; charged_bytes/files, reserved_bytes/files bigint>=0; revision; row lock сериализует reserve/commit/delete. |
| library_deletion_jobs | material_id UNIQUE/FK; state PENDING/RUNNING/RETRY; attempts int; next_attempt_at; last_error_code безопасный. Допускается lease/lock для одного worker. |

Порядок upload: validation → короткая DB transaction reserve usage и operation (размеры из bounded multipart parser, не клиентского Content-Length; actual bytes при копировании перепроверяются) → bounded stream staging+digest (не держать DB lock во время IO) → fingerprint/replay compare → атомарный move каждого файла в immutable random key в library root → DB transaction создаёт READY материал+assets, переводит reservation вcharged и operation SUCCEEDED →201. Чужие имена не влияют на path. При ошибке response не говорит READY; referenced READY files никогда не удаляются компенсатором. Вторая операция по key не выполняет повторный physical commit; уже идущая409.

DB/FS не образуют одну ACID транзакцию. Durable upload manifest содержит все staging/final keys и owner/operation; recovery видит COMMITTING и либо завершает проверяемый commit, либо удаляет только не referenced keys и снимает reservation. Manifest/lease update и checks не допускают гонку recovery с активным stream. Сбой после DB commit до response восстанавливается по key. Неполная batch загрузка не публикуется частично. Failures before DB availability cannot create permanent keys.

Удаление: owner+If-Match → DB mark DELETING + job →202; с этой транзакции metadata/content скрыты. Worker удаляет assets; already absent=успех; charged quota снимается только после actual absence, затем DB rows/job удаляются. При DB failure после file delete retry сверяет отсутствие, не вычитает usage дважды. Operation stub хранит факт deleted для replay410 до своего24h окна. Откат удаления оригинала не обещается.

Recovery проходит только собственные keys/root, DB references проверяются повторно, лимит100кандидатов/cycle, lease и безопасная остановка при DB outage. Никакого обхода attempts/scores, директорий пользователя или symlink. Тесты обязательно инъецируют отказ между каждой парой критических шагов.

## 5. Хранение и конфигурация

Сохранённый материал хранится до удаления пользователем: ни private original, ни future published не подпадают под `media.retention_after_processing_hours`/`max_unprocessed_age_hours`48h. Staging/orphans — отдельная политика. Новый локальный filesystem adapter достаточно для P0; object storage/платный SaaS не требуются.

| Config key | Default / допустимое значение / изменение |
| --- | --- |
| guitar-mentor.library.enabled | false; boolean; env/config, restart. Capabilities enabled=false; данные не удаляются. Включение только при active auth. |
| guitar-mentor.library.storage-path | `./.local-media/library`; env/config restart; canonical root вне webroot и **не совпадает/не вложен/не включает** roots attempts/scores. Symlink root запрещён; невалидный config fail startup. Windows paths проверяются с учётом нормализации. |
| guitar-mentor.library.max-upload-bytes |20971520 (20MiB),1024…20971520; aggregate всех files/create и max каждого; restart. Существующий servlet max-file-size20MiB/max-request-size32MiB не увеличивать. |
| guitar-mentor.library.max-files-per-material |5,1…5; restart; хотя бы1. |
| guitar-mentor.library.max-account-bytes |209715200 (200MiB),≥max-upload-bytes; restart. Это технический стартовый limit минимального пилота, не бизнес-обещание или оплаченный объём. |
| guitar-mentor.library.max-account-files |100,≥max-files-per-material; restart; charge+reserve включают будущие удаления до physical absence. |
| guitar-mentor.library.max-concurrent-uploads |1/account, общий4; positive bounded≤4; restart. Rate limit отдельно10create/min/account,30/min/source, configurable до включения. |
| guitar-mentor.library.staging-grace-minutes |60,≥60; не применяется к active lease; restart. Reconcile interval10min, bounded100/cycle, не48h policy. |
| guitar-mentor.library.cursor-secret | минимум32случайныхbytes вenv, требуетсяприenabled=true; стабильныйключдляподписиcursor, restart; невалидныйstartupfail, ротация инвалидируетстарыеcursor400. |
| guitar-mentor.library.idempotency-hours |24,≥24; restart. Опубликованные материалы/ordinaryREADY не удаляются по этому таймеру. |

Все числовые значения — обоснованные инженерные defaults СА для существующего20/32MiB транспорта и малого пилота, не ответы владельца. Перед оценкой разработчик сверяет реалистичность на целевом диске; изменение больших квот требует решения по BT-009. Disk free/headroom failure=503 и rollback, не включать бесконечные повторы. Снижение лимита ниже already used не удаляет файлы, блокирует новые reservation. Невалидные numbers fail closed, не переходить к unlimited.

Безопасность: boundary/size controls streaming, extension allowlist + согласованная content classification (PDF check, GP original-unverified), клиентский MIME не считается доказательством. Не исполнять содержимое; GP—including ZIP-like container—в P0 не распаковывается. Serve attachment-only/octet дляGP, no-store/nosniff; Content-Disposition безопасно кодирует имя. Непроверенный файл нельзя называть антивирусно проверенным. Перед будущими preview/converter/publication дополнительный validation/sandbox gate. Общее scanner API можно расширить позже, без отправки private originals в публичный scanning SaaS.

Применимы NFR-001…008 и SEC-001…008 по доступу/совместимости/стоимости. Нет нового SLA: проверяются bounded memory (stream, не readAllBytes всего batch), concurrent reserve ≤квота, restart recovery, браузеры Chrome/Windows + responsive RU/EN; новые фактические P95 и throughput не выдуманы. Логи request/operation/material UUID, error code, byte counts; никакого содержимого/полного имени/email/storage path/auth tokens.

## 6. UI и проверки

`/library` — «Мои материалы», доступ из существующей навигации; карточки показывают title/type/author/files/bytes/дату и «Личный · Не проверено». Добавить → форма → выбрать1–5файлов → сохранить → карточка/список. Скачивание оригинала отдельной кнопкой; доуспеха не показывать сохранение. Empty объясняет первый шаг. При выбореGP пояснить, что преобразование появится отдельно; приPDF не требовать наличия TAB/4тактов, MUS применяется к будущему проектному экспорту.

Busy/progress/error/no permission/offline/disabled/stale edit/expired auth/deleting учтены. При failure сохранить введённый текст и текущие selected File, пока компонент смонтирован; после F5/перехода пользователь выбирает файл повторно, не писать его в storage. Logout/account switch очищает private list/selection/object URLs и отменяет fetch; late responses игнорируются. Delete confirmation называет материал и объясняет удаление оригиналов, до202 не скрывать как завершённый факт; после202 показать принятие удаления, не обещать возврат.

[TC-LIB-01…24](../testing/TC-CATALOG-01-private-library.md) — future NOT_RUN. Обязательные группы AC-LIB-01…08: сохранение/replay; own-only чтение; quotas/validation; edit/version; delete/recovery;48h изоляция; Vue/auth/responsive; regression/rollout. Полная матрица в тестовом документе. Каждый FT даёт принимаемый вклад; весь P0 принимается после5задач и общего сценария.

## 7. Установка, smoke, откат и handoff

Миграция additive, root создан с минимальными правами, config flag=false → deploy backend → проверить checks/security → включить на изолированном тестовом аккаунте → deploy Vue → upload PDF+GP/перезапуск/скачивание SHA/двухаккаунтный404/delete/retention smoke. Никаких реальных материалов в Git/CI; small synthetic fixtures + programmatically generated PDF допустимы.

Rollback trigger: утечка, потеря bytes, quota overrun, recovery deletesREADY. Ответственный разработчик/оператор: выключитьlibrary.enabled и UI feature, остановить worker/uploads, сохранить БД+library root как согласованную пару, откатить приложение; additive schema оставить. Не выполнять down migration/массовое удаление. Сверить старые упражнения/попытки/security, убедиться что originals лежат на месте; restore только из реально существующей копии с согласованной политикой, без обещания восстановить вручную удалённый оригинал. #40 backup/runbook дополнить scope библиотеки при её включении: политика медиа попыток не исключает автоматически persisted library originals из стратегии восстановления.

Порядок: #160 → #161 → #162 → #163 → #164. #163 может готовить UI mocks после frozen DTO, завершение послеAPI; #164 не подменяет developer tests. Анализ готов к **оценке**, часовые поля не назначены, к коду ещё нужна команда по процессу. Самопроверка артефактов не является независимым review; никаких runtime PASS. По сравнению с текущим приложением не меняются назначенные упражнения и их immutable версии.

## 8. Источники

БТ0.17 разделы12–13 + прямой приоритет владельца24.10 (БТ0.18); mainSHA и файлы перечислены выше. [OWASP File Upload](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html) — bounded upload/safe storage/auth; [Guitar Pro features](https://www.guitar-pro.com/c/14-guitar-pro-features) и [TuxGuitar formats](https://www.tuxguitar.app/files/devel/desktop/help/file_formats.html) — уточнение расширений семейства, не доказательство нашего конвертера.
