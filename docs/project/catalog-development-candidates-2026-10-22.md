# Каталог: очередь #119 → #120 → #85

**Отмена 24.10.2026:** владелец отказался от US-010/#112 и задач #113–116 («Я передумал, они мне не нужны»). BT-020 и FT-010-01…04 сняты с действующего объёма; разработка, оценки и условия старта этой ветки больше не применяются. Основной интерфейс метронома #111 сохраняется. Ниже сведения о скинах оставлены только как история. Датированный аудит не пересчитывается задним числом.

| Версия | Дата | Изменение |
| --- | --- | --- |
| 1.1 | 22.10.2026 | Передача по main327b9a3 после #19: reuse exercise_criteria, точные контракты/ошибки, TC и команды. |
| 1.0 | 22.10.2026 | Из #84 выделены два технических результата; #85 подготовлена после их API. Скины, аудиоанализ и ИИ отложены. |

## Основания и готовность

База кода: main `327b9a38cb829fc07a4da00adbf6da36e2a2985f`. Родитель всех трёх задач — US-002/#56, эпик #53. БТ 0.10 / BT-002/004/009/012 → ТЗ 0.16 / FT-002-03. Автор — СА; выполнена самопроверка, независимого review нет.

Прочитаны V5/V12/V16, SkillMapRepository, TaskDtos, CI, изменения #19, Exercise, ExerciseView, ExerciseRepository, ExerciseController, ExerciseCatalogService, ExerciseMediaService, ExercisePreview.vue и metadata_v08B.json. Entity уже разделяет id/code/version/current, назначение хранит exercise_id. Однако DTO каталога/назначения не содержит учебных метаданных/длительности; V12 уже содержит version-specific exercise_criteria и practiceMinutes=30 для GM-EB01, а URLs и resource resolvers используют только code/current. При появлении новой current-версии ссылка из прежнего назначения может начать выдавать другой ресурс. Это обнаруженная граница контракта, не утверждение о потерянных данных.

В main только GM-EB01. Поэтому полный #84 с четырьмя новыми кодами ждёт #77–80, но технические схема/DTO и точная версия ресурсов могут поставляться отдельно. Новые #119/#120 извлекают этот объём из #84. Его прежние 6ч — историческая оценка до разделения; она не прибавляется к новым оценкам. #84 остаётся открытой для контента/новых кодов с отдельной переоценкой остатка.

| Порядок | Кандидат | Тип / профиль | Оценка остатка | Готовность |
| --- | --- | --- | --- | --- |
| 1 | #119 / R3-02-09A — метаданные | Java / Full | 8ч (6–12) | Можно выбирать, новые этюды не нужны |
| 2 | #120 / R3-02-09B — версия ресурсов | Java / Full | 8ч (6–12) | Можно выбирать; интеграцию общего DTO упорядочить после #119 |
| 3 | #85 / R3-02-10 — отображение во Vue | Фронтенд / Standard | 6ч (4–8) | Ждёт API #119/#120 |

#119/#120: Оценка / Выполнено. #85: Оценка / Ожидание (API); анализ и оценка завершены, зависимость блокирует старт. Сумма 22ч — выбранный остаток, не календарный прогноз. Подготовка не начинает код. #19/PR118 уже объединена в main327b9a3: LearningResultsPanel, SubmissionPanel, DiagnosticView, RU/EN. Сохранить её результат. Выбранная владельцем #109 продолжает свой цикл; тюнер #108 уже в main. #113 и зависимая #114 отложены. ИИ/аудиоанализ/PoC исключены, даже если написаны на Java.

## #119 — Аддитивные метаданные

Как ученик, я хочу понимать цель и порядок занятия до выбора задания. Добавить Flyway-миграцию, Entity/DTO и совместимые чтения. Старые V1…V16 не менять; следующий свободный номер миграции проверить по свежему main.

| Поле JSON | Тип / отсутствие | Источник |
| --- | --- | --- |
| learningGoal | string/null, до4000 символов | Доверенный учебный контент |
| practiceInstructions | string/null, до8000 | Доверенный учебный контент |
| successCriteria | string[], всегда массив, [] если нет строк | Проекция exercise_criteria.observable_result по exercise_id, ORDER BY display_order, criterion_code; не Assessment |
| tuning | string/null, до64 | Явная текстовая метка строя |
| recommendedPracticeMinutes | integer/null | Существующая колонка; добавить в DTO без дублирующего estimatedPracticeMinutes |

Новые колонки exercises: learning_goal TEXT NULL с CHECK(char_length<=4000), practice_instructions TEXT NULL с CHECK(char_length<=8000), tuning VARCHAR(64) NULL. DEFAULT NULL, без backfill неизвестных значений. Они относятся к конкретной row/id/version. Пробельный authored string не записывается: использовать NULL; DB CHECK запрещает значение из одних пробелов. Чтение не усекает текст молча.

**Исправление v1.0:** новую колонку success_criteria/JSONB НЕ создавать. V12 уже хранит exercise_criteria с FK exercise_id. successCriteria — упорядоченные observable_result существующих строк без изменения текста/таблицы. Не вводить задним числом max20/max1000 для принятого контента. Чтение по UUID Exercise, включая старую назначенную версию, без подмешивания current. Для списка предусмотреть пакетное чтение критериев по IDs, без отдельного запроса на каждую строку. DTO assembler собирает Entity + criteria; repository не внедрять в статический record. Все ExerciseView/TaskView call sites обновляются согласованно.

Существующий recommended_practice_minutes INTEGER NULL с CHECK BETWEEN 10 AND 120 сохраняется; не заводить estimatedPracticeMinutes. Для GM-EB01=30. Новая миграция только добавляет три колонки/checks. V16 занят; V17 — кандидат, свободный номер выбирается перед реализацией. UNIQUE(code,version), partial unique current(code), FK diagnostic_tasks.exercise_id уже есть в V5 и не дублируются.

metadata_v08B.json не содержит новых цели/инструкции/строя. Они для GM-EB01 остаются null; критерии читаются из трёх строк V12, а не выдумывается. description доступен как UI fallback, но не становится автоматически утверждённой learningGoal. Не засевать GM-EB02–05 заглушками и не переписывать старую назначенную версию.

GET /api/v1/exercises с прежними instrumentCode/techniqueCode и GET /api/v1/exercises/{code} сохраняют методы/доступ/ошибки; response дополняется указанными полями. Такие же поля имеют все вложенные exercise DTO назначения. Старые клиенты игнорируют добавки, новый UI терпит отсутствие полей. Пример добавки GM-EB01: `{"learningGoal":null,"practiceInstructions":null,"successCriteria":["Равномерное ощущение долей без произвольных остановок","Басовая линия не прерывается на смене мелодических фраз","Мелодическая линия слышна поверх баса"],"tuning":null,"recommendedPracticeMinutes":30}`. Если строк criteria нет, вернуть []; JSON null для массива недопустим.

Публичного write/admin API, LLM, новой логики рекомендаций или редактирования нот нет. Значения поступают только из проверенной миграции/контента. Типы/длины проверяются миграцией и authoring tests; JSONB parsing отсутствует. Вывод текстовый, без HTML. Сохранить review/rights и фильтры существующего каталога.

Оценка8ч: миграция/Entity3, DTO/совместимость2, PostgreSQL/HTTP/unit и инструкция3. NFR-002/003/005/007/008; SEC-004/005/008. Попытки, оценки, TTL и авторизация не меняются.

## #120 — Точная версия ресурсов

Как ученик, я хочу после F5 получить те же ноты и пример, по которым назначено упражнение.

Новые read-only маршруты:
- GET /api/v1/exercises/{code}/versions/{version} → ExerciseView точной строки;
- тот же prefix + /score, /events, /preview/audio, /preview/pdf → ресурс этой строки, включая current=false.

code/version обязательны, регистр значим, trim/нормализации версии нет. Code: `[A-Za-z0-9][A-Za-z0-9._-]{0,49}`, version: `[A-Za-z0-9][A-Za-z0-9._-]{0,19}` — соответствуют VARCHAR(50/20) V5; `0.8B` допустимо. Точные `.`/`..`, slash/backslash, percent после одного штатного URL decode, пробелы и схемы URL запрещены. Дошедший до controller некорректный сегмент →400, неизвестная валидная пара →404. HTTP-server может отклонить traversal/encoded slash кодом400/404 до controller; это фиксируется отдельно, application JSON от контейнера не обещается. Никогда не подменять отсутствующую версию current. Старые code-only маршруты сохраняются как current-алиасы.

Единый DTO assembler строит четыре version-qualified URL из code/version данной строки. Вложенное назначение формируется по сохранённому exercise_id. Current-список также выдаёт URL конкретной версии. UI использует URL ответа, не конструирует code-only заново.

Repository добавляет lookup code+version. V5 уже обеспечивает UNIQUE(code,version), partial unique current(code) и FK назначения; для #120 новая миграция/индекс не нужны. Назначения не перепривязываются к current.

MIME остаются прежними: application/vnd.recordare.musicxml+xml, application/json, audio/mpeg, application/pdf. GET/HEAD согласованы. Неустановленный/null/небезопасный ресурс →404 с безопасным кодом, без classpath/SQL. previewAudioUrl/previewPdfUrl остаются null, если row path=null; непустой URL не гарантирует наличие файла, HEAD/GET могут вернуть404. scoreUrl/eventsUrl всегда строка точной версии. Resource path берётся из доверенной row; сохраняются exercises-prefix и защита traversal/backslash. Никакого произвольного чтения filesystem/network или обхода будущего access-control.

Для новых маршрутов единая JSON ошибка: {"code":"EXERCISE_NOT_FOUND","error":"Exercise not found"}. Коды:400 INVALID_EXERCISE_REFERENCE;404 EXERCISE_NOT_FOUND (нет пары);404 EXERCISE_RESOURCE_NOT_FOUND (нет/небезопасен ресурс);500 EXERCISE_RESOURCE_READ_FAILED (непредвиденный I/O). error — фиксированный безопасный текст, не exception.getMessage: INVALID_EXERCISE_REFERENCE → "Invalid exercise reference"; EXERCISE_NOT_FOUND → "Exercise not found"; EXERCISE_RESOURCE_NOT_FOUND → "Exercise resource not found"; EXERCISE_RESOURCE_READ_FAILED → "Unable to read exercise resource". HEAD возвращает тот же status/content-type без тела; fallback запрещён и при500. Handler ограничить ExerciseController, не менять Diagnostic/Learning API. Старые code-only routes сохраняют существующие формы успешного ответа/ошибок; общий safe resolver допустим, глобальная смена ошибок не входит.

GET/HEAD без body, новых query/header нет; Accept по MIME ресурса, application/json для detail. Доступ тот же локальный MVP без auth; новые URLs не дают доступа к попыткам/данным ученика. GET/HEAD идемпотентны; UI не повторяет POST назначения ради чтения. Cache-Control:no-cache для новых detail/ресурсов; immutable cache/ETag не вводятся. В поставке нельзя перезаписывать используемые resource bytes/path версии: новая редакция = новая row/version + ресурс. Не удалять файлы назначенных версий; нового write/delete API нет.

Две версии для теста — искусственные фикстуры с разными байтами. Дано assignmentV1, затем currentV2: старое назначение и его четыре ресурса остаются V1, legacy code-only выдаёт V2. Если PDFV1 отсутствует, вернуть404, не PDFV2. Новое реальное произведение не требуется.

Оценка8ч: lookup/routes/validation3, DTO/resource resolution2, PostgreSQL/HTTP two-version tests/docs3. NFR-002/003/005/007/008; SEC-004/005/008. Контент каталога не попадает под TTL пользовательских записей.

## #85 — Представление метаданных и версии

До/после назначения отображать полезные цель, инструкцию, критерии, длительность, строй, версию и права. Расширить TypeScript Exercise model и существующий ExercisePreview/соответствующие wrappers. Не создавать второй учебный экран или повторять решение/историю #19.

Пустые поля получают короткое «не указано» либо скрываются без пустых рамок; description — допустимый fallback. Критерии не выглядят как выполненные checkbox или результаты AI-проверки. RU/EN для подписей, исходный учебный текст не переводится выдуманным содержанием.

Использовать pinned URL из API. При смене code@version повторно проверить media; generation/AbortController либо эквивалент отбрасывает старые HEAD/callback. Ошибка404 не сбрасывает sessionId, назначение, файл/попытку и не переводит на current. PDF по умолчанию свёрнут, плеер прежний, без дублирования ASCII-TAB. Текст выводится безопасным binding без v-html.

Сохранить exerciseKey, метроном и recording lifecycle. Поставка #19 уже в main327b9a3. Сохранить LearningResultsPanel/SubmissionPanel, блокировку повторного решения, пагинацию/ошибки истории и запрет действий во время draftBusy. Критерии в preview — учебные ориентиры, они не заменяют assessment/status/outcome. На360 CSSpx/desktop/keyboard/200%zoom длинный текст не обрезает действия.

Оценка6ч заменяет прежние4ч, не прибавляется: model/render2, media/version/race2, tests/accessibility/RUEN/docs2. Standard; NFR-004/008, SEC-005/008. Старт после API119/120.

### Состояния UI и границы интеграции

| Состояние | Отображение и действие |
| --- | --- |
| Metadata ready | Цель (или description fallback без двойного текста), инструкция с переносами строк, критерии обычным списком; версия/права и минуты/строй при наличии. Заголовки RU/EN. |
| Metadata unknown | Отсутствующие goal/instructions/tuning/minutes скрыть; successCriteria отсутствует/null от старого API → []; description остаётся. Версию/author/license не заменять выдуманными значениями. |
| Media checking | При любом изменении code@version ИЛИ preview URLs сразу сбросить старую доступность; отменить/обесценить предыдущие HEAD, показать существующий текст загрузки. |
| Media ready | Только URL текущего props; при смене exercise key остановить/размонтировать прежний audio/iframe и снова свернуть PDF. |
| Media unavailable | null/404/network/ошибка audio после успешного HEAD → локализованный fallback; новый запрос — при смене источника или remount. Дополнительный retry UI не требуется. |
| Unmount | Отменить HEAD/обесценить callbacks и сохранить действующий leaveMetronomeExercise cleanup; без записи в уничтоженный component. |

Новый TS-контракт допускает отсутствие новых полей для совместимости со старым API: optional nullable strings/minutes и optional successCriteria; нормализовать в read model. Старые ссылки во время обновления допускаются лишь для совместимости — при приёмке #85 backend #120 обязателен. Ни HEAD, ни смена preview props не меняют sessionId/taskId/attemptId, draft/file и решения. Никакого нового localStorage. Не подставлять tuning=Standard на основании того, что рядом открыт тюнер.

## Приёмка и будущие TC

| AC | Дано / Когда / Тогда |
| --- | --- |
| AC-CAT-A1 | Старая БД и назначения → additive migration → ID/version/current/history прежние, новые колонки null, прежние критерии/30мин сохранены, нет фиктивных кодов |
| AC-CAT-A2 | Каталог/detail/assigned DTO → чтение → единые типы/null/[]/minutes, критерии не Assessment |
| AC-CAT-A3 | Повторный deploy/старый клиент → чтение → Flyway не повторяет backfill, старый UI работает, права/фильтры не ослаблены |
| AC-CAT-A4 | Неверные trusted metadata/длины/пробельные строки → DB/authoring tests → нарушение выявлено, HTML не исполняется, нет SQL в ответе |
| AC-CAT-A5 | GM-EB01/V12 и версия без критериев → DTO → три исходных observable_result в прежнем порядке либо [], без новой копии JSONB |
| AC-CAT-A6 | Две версии с разными criteria/minutes и несколько строк каталога → list/detail/assignment → данные именно выбранного UUID; пакетное чтение критериев |
| AC-CAT-B1 | AssignmentV1/currentV2 → F5 и четыре GET/HEAD → назначение/ресурсы V1; legacy current-алиасы V2 |
| AC-CAT-B2 | Missing PDFV1/unknown pair → GET/HEAD →404 без fallback, существующие ресурсы имеют корректный MIME |
| AC-CAT-B3 | Traversal/encoded slash/backslash/oversize → запрос →400/безопасный404 до resource read, нет filesystem/network обхода |
| AC-CAT-B4 | Существующие строки/unique pair → миграция/lookup → нет дублей пары, назначения не обновлены на current |
| AC-CAT-B5 | HEAD каждого существующего exact ресурса → ответ → тот же status/MIME что GET, тела нет, no-cache; POST назначения не повторяется |
| AC-CAT-B6 | Ошибка чтения/небезопасный path → HTTP → безопасный404/500 с контрактным code, нет path/SQL и подмены current |
| AC-CAT-UI1 | До/после назначения, RU/EN, известные/пустые поля → render → правильные версия/права/fallback, без выдуманных целей или выполненных критериев |
| AC-CAT-UI2 | PDF collapsed/MP3404 → preview/lesson → понятный fallback, session/attempt сохранены |
| AC-CAT-UI3 | HEAD дляV1 ещё pending → переход наV2 → позднийV1 проигнорирован, URL относятся только кV2 |
| AC-CAT-UI4 | Mobile/desktop/keyboard/zoom/длинный текст → действия доступны, PDF/metronome/recording не регрессируют |

Все runtime AC — NOT_RUN. Требуются реальные unit/PostgreSQL/HTTP tests и meaningful two-version fixtures, Vue typecheck/build/component tests. Статический анализ не является музыкальной или UX-приёмкой.

## Поставка и откат

Порядок119→120→85; каждый результат проходит отдельный PR/ревью/приёмку. Новых env, секретов и localStorage ключей нет. Разработчик сверяет свежий main, миграции и изменения #18/#19, фиксирует команды/SHA и фактические результаты CI.

Smoke: после119 GM-EB01 читается с null goal/instructions/tuning, тремя критериями и30мин; после120 exact0.8B ресурсы/HEAD и unknown404; после85 назначение/F5/PDF/нетMP3/длинный текст. Логи содержат route/reason/status, без пользовательского аудио, частных путей и SQL.

Trigger отката: несовместимый API, неправильная версия ресурса или потеря сессии/попытки. Владелец установки возвращает предыдущую совместимую пару frontend/backend; аддитивные колонки автоматически не удаляются. Назначения и историю не переписывать. Старые code-only ссылки вернут ограничение current — это известная граница отката, не обещание восстановления файлов.

[Схема компонентов](../diagrams/catalog-metadata-and-versions.mmd) · [чтение назначенной версии](../diagrams/catalog-assigned-version-sequence.mmd) · [матрица TC и команды](../testing/TC-119-120-85-catalog-handoff.md). Backend/БД/ресурсы остаются локальными; auth, интернет-публикация, LLM и аудиоконвейер не входят.

## Передача разработчику

Первой брать #119 по этому контракту v1.1; #120 интегрируется после #119, #85 — после принятых API обеих задач. #120 не имеет внешнего контентного блокера, но до слияния #119 не вести параллельную конкурирующую сборку DTO. Выбранный порядок соблюдает одну основную задачу в разработке. Ручная приёмка и независимое QA не включены в 22ч. Уверенность оценки средняя; изменение данных/объёма требует уточнить остаток, а не притворяться, что диапазон — календарный срок.

Блокирующих продуктовых вопросов у #119/#120 нет. У #85 внешний gate — API119/120 с точными URL и полями, проверенные по TC. Реальная musical редактура/#77–80 и BA вопросы BT-017/018 не решаются этой тройкой. По регламенту подготовка фиксирует Оценка/Выполнено для119/120 и Оценка/Ожидание API для85; выбор конкретной задачи в разработку и исполнение — следующий шаг владельца.

Статическая самопроверка: свежая база/DTO consumers/уникальность/criteria source проверены по исходникам; документационные ссылки/матрица/типы согласованы. Независимый review анализа не проводился. Runtime TC NOT_RUN — продуктовый код в этом пакете не менялся.
