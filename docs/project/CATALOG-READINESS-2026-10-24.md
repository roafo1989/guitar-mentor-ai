# Передача #160–175 к разработке

**Обновление состояния, вечер24.10.2026:** [Вечерняя сверка 24.10.2026](board-audit-evening-2026-10-24.md).160/PR183 и161/PR185 приняты и merged (backend scope);155 принято2/5.162 Разработка/В очереди — зависимость161 снята,163 ждёт162,164 ждёт163. Существующие manifest/upload/orphan recovery из161 переиспользовать, не реализовывать повторно в162.96ч и P0=42ч ниже — прежние оценки выбранного объёма, не фактические или оставшиеся часы.170/172/174/175 сохраняют Анализ/Ожидание и research gates; ИИ не запускается. Следующие таблицы отражают исходную передачу.

| Версия | Дата (Europe/Moscow) | Основание |
| --- | --- | --- |
| 1.0 | 24.10.2026 | Владелец: «проведи все эти задачи до разработки». Проработаны все16 задач; подготовка/оценка не запускают кодирование. |

База: main `d9a7c4b805bf48b72adeefb83b818c81a41eaa13`, документы анализа `ec9ada0e79d640f5857d2ef668a9cbce7a50e0de`; БТ0.18 → ТЗ0.26. В исходной публикации для последующих этапов оставались незакрытые параметры, часы новым задачам ещё не назначались. Настоящий анализ уточняет контракт P0, полностью задаёт общий каталог и ограничивает исполнимые исследования. Текст ниже — целевая матрица полей; запись полей проверяется на Project, а не считается выполненной публикацией Markdown.

## 1. Решение и границы готовности

**12 задач оценены и выбраны в разработку:**160–168,169,171,173. Для них есть ограниченный принимаемый результат; у зависимых задач **Разработка/Ожидание**, единственный свободный первый шаг **#160 — Разработка/В очереди**. Это не12 одновременно начатых задач. Ни код, ни продуктовые тесты, ни deployment не выполнялись. На task#164 «разработка» означает очередь самостоятельной проверки после реализации, не кодирование вместо QA.

**#170/#172/#174/#175 — Анализ/Ожидание.** Нельзя честно завершить их подготовку до результатов converter/provider/audio исследований. Для каждого заданы ответственный, проверяемое условие снятия и место следующей декомпозиции; у владельца сейчас нет обязательных ручных действий. Их estimates не выдуманы и не включены в итог96ч. NO-GO исследования не закрывает соответствующую продуктовую задачу как реализованную.

Поручение владельца позволяет выполнить анализ/оценку/выбор очереди. Оно не является музыкальной приёмкой, результатом ещё не проведённого benchmark или разрешением тратить деньги/публиковать private originals. Все Issues остаются OPEN.

## 2. Рабочие оценки и зависимости

Оценки — часы одного исполнителя по текущему контракту, не сроки календаря и не измеренная скорость ИИ. Включают реализацию, developer checks, документацию/PR и ожидаемый небольшой цикл замечаний; отдельная независимая сквозная проверкаP0 учтена164, не дублирует unit tests. Старые оценки84/119/120/141 не включаются. 1рабочийдень=6ч. Предшественник означает готовый совместимый API/accepted result на доступном checkout; main merge не подменяет приёмку. Самопроверка анализа не независимое ревью.

| Issue / результат | Диапазон, ч | Рабочая оценка, ч | Состав остатка | Старт / условие снятия ожидания |
| --- | --- | --- | --- | --- |
| #160 модель+metadata API |6–10|8| schema/repository3; read/edit/cursor3; actor/validation tests2 | Свободна; auth38/42 уже в main,141 не блокирует |
| #161 upload/download |10–16|12| bounded stream/manifest4; idempotency/quota4; protected content/contract tests4 | #160 готов, migration/DTO доступны |
| #162 deletion/recovery |6–12|8| durable delete3; upload/deletion recovery3; fault regression2 | #161 durable manifest/storage реализованы |
| #163 Vue «Мои материалы» |6–12|8| upload3; list/edit/download/delete3; states/RUEN/keyboard2 | #160–162 API готовы; mocks допустимы раньше, не выдаются за интеграцию |
| #164 сквозная проверка P0 |4–8|6| API/двухаккаунтность2; browser2; fault/reports2 | #160–163 candidate и доступный PostgreSQL/auth стенд |
| #165 immutable publication |6–12|8| snapshot/revision3; rights/withdraw/hide/audit3; tests2 | P0#164 проверен и первая стори принята для дальнейшего шага |
| #166 поиск общего каталога |4–8|6| SQL/filter3; cursor1; plans/tests2 | #165 published-only schema/API готовы |
| #167 photo/audio/preview |10–18|12| boundedmedia5; preview isolation/range3; recovery/quota/security tests4 | #165 и P0storage готовы; по очереди после166 |
| #168 общий каталог Vue |8–14|10| publication3; filters2; media3; browser2 | #166/#167API готовы, P0UI не регрессирует |
| #169 GP research |4–6|6| sources1; fixtures1; prototype2; measurements/report1; gate/decomposition1 | P0 доступен; следующий приоритет после156; никаких платных/неразрешённыхfiles |
| #170 converter pipeline | — | — | Unknown remaining implementation: Java job/worker/Vue, после169 разделить | Gate169: measuredformats/layout/toolchain/licenses/caps +контракты/оценка |
| #171 web search research |4–6|6| sources1; corpus1; contract/probe2; report1; gate/decomposition1 | Каталог156 и исследование169; доступ к официальным источникам, budget0 |
| #172 поиск/импорт pipeline | — | — | Unknown provider-dependent остаток, после171 разделить | Gate171 +готовая170 для GP ветки; реальныеadapter/rights/cost/SSRF evidence |
| #173 audio feasibility |4–6|6| sources1; corpus/rubric1; baseline2; report1; gate/decomposition1 | P0 и169; по продуктовому приоритету после internet stage; budget0/private inputs не отправлять |
| #174 arrangement pipeline | — | — | Unknown quality/engine-dependent остаток, после173 разделить | Gate173 ARRANGEMENT +renderer PDF/audio, privacy/resource/TTL contracts |
| #175 transcription mode | — | — | Unknown fidelity/selection остаток, после173/174 уточнить | ОтдельныйTRANSCRIPTION gate173 +sharedpipeline174 и новыеAC/оценка |

Итоги без двойного сложения родителей: **P0=42ч (32–58), P1=36ч (28–52), три research timebox=18ч (12–18), оценённая часть=96ч (72–128)**. Это **не оценка всего эпика**:170/172/174/175 ещё не оценены. Researchtimebox не оплачивает implementation pipeline. Уверенность P0 средняя, P1 ниже из-за renderer/mediaизоляции, researchestimate оценивает исследование, не его успешность.

#161/#167/#168 больше целевого дня: даны принимаемые внутренние work packages по2–5ч, но оставлены цельными Issues, чтобы не раздробить гарантии upload/quota/recovery, security media или один законченный пользовательский сценарий. По регламенту декомпозиция свыше8ч желательна, не обязательна. При новых неизвестных/превышении верхней границы — переоценка и отдельные принимаемые инкременты до продолжения, без скрытого роста. Новые задачи не создаются на каждую таблицу/настройку.

## 3. Контракты, проверки и полнота

| Область | Источник результата | Приёмка и ограничения |
| --- | --- | --- |
| P0/#160–164 | [CATALOG-01v1.1](../specifications/CATALOG-01-private-library.md), [OpenAPI](../api/library-v1.openapi.yaml), ADR0004, component/ER/sequence | AC-LIB-01…08, TC-LIB-01…24; original bytes, actor isolation, no48hTTL, quota/replay/delete/fault; пользовательский original не переоформляется |
| P1/#165–168 | [CATALOG-03v1.0](../specifications/CATALOG-03-community-catalog.md) | AC-COM-01…06, [TC-COM-01…24](../testing/TC-CATALOG-02-community.md); immutable snapshots, filter semantics, optionalaudio, preview sandbox, withdrawal |
| #169 | [CATALOG-04§2](../specifications/CATALOG-04-research-gates.md) | Format/featurematrix, safe deterministic conversion, MUS-NOTATION-01/слуховыеchecks; без research не выбрать converter |
| #171 | [CATALOG-04§3](../specifications/CATALOG-04-research-gates.md) | Provider/source/cost/provenance contracts, ambiguity и SSRF vectors; documented≠executed |
| #173 | [CATALOG-04§4](../specifications/CATALOG-04-research-gates.md) | Separatearrangement/transcription рубрики, realvssynth corpus, constraints/GO; privatefiles не отправляются |
| Остальные4 | [Реестрgate/blockers](../specifications/CATALOG-04-research-gates.md#5-реестр-блокировок-на-24102026) | Ни принятых экспериментов, ни предрешённогоGO; анализ не выдается за музыкальнуюреализацию |

Для готовых ограниченных инкрементов заданы user value, boundaries, APIs/DTO/errors/If-Match, schema/migration, actors/CSRF/IDOR, failure/concurrency, config/caps, UXstates, rollout/rollback и AC/testmapping. #141/новые этюды/аудиоанализ диагностики не блокируютP0. Старые84/119/120не переоткрываются. BT025два режима сохранены; приоритет arrangement, default не навязан. Published community не approved educational `exercises`.

## 4. Порядок выполнения

Первый цельный релизный инкремент: **160→161→162→163→164→приёмка155**. Затем165→166→167→168→приёмка156. Далее169 и по его результатам подготовка170; затем171→подготовка172;173→подготовка174→подготовка175. Исследования могут использовать synthetic fixtures до полного backend, но автоматическая параллельная разработка/изменение приоритета не начинается этим поручением.

Каждый исполнитель читает актуальный issue body и спецификацию на pinned документационномSHA, сверяет main/predecessor SHA и номера миграций. Завершение кода: проверки/CI+PR+testinstructions→Код-ревью/Вочереди, после review→Тестирование, финальная приёмка отдельно. Ни одноIssueне закрывается из-за готовой оценки. Для исследования — тот же review/проверка воспроизводимости, а не автоматическое принятие musicalscope.

## 5. Итог проверки аналитических артефактов

Проверены текущие источникиmain/docsbranch, все16карточек и границы с84/119/120/141; добавлены конкретные P1contracts,48TC library/community и3research exitcontracts. Проверка Markdown localrefs, uniqueTC и YAMLinternalrefs фиксируется при публикации. Наличие текста/валидный YAML не доказывает совместимость реализации; runtime/CI/музыкальныеexperiments остаются **NOT_RUN**. Независимое ревью не имитируется.


## 6. Фактическая сверка Project после записи

24.10.2026 через GitHub UI записаны и прочитаны обратно все16 задач: #160 Разработка/В очереди/8ч; #161–169,171,173 Разработка/Ожидание с оценками из таблицы; #170,172,174,175 Анализ/Ожидание с пустым полем часов. Сумма числовых полей —96ч. Фильтр FT-01 на доске показывает16 карточек, из них4вАнализе и12вРазработке. Фильтр временный, сохранённые настройки вида не перезаписаны.

Эпик154 и стори155: Разработка/В очереди. Стори156–159: Разработка/Ожидание. Native parent edges21/21 подтверждены чтением GitHub API;22Issue OPEN и содержат обновлённые описания. Исходные14файлов обновления прочитаны с опубликованного commit и совпали с подготовленным содержимым. Это проверка аналитических артефактов/доски, а не запуск программных или музыкальных тестов. YAML/internalrefs/8операций/HEAD и48 уникальныхTC проверены; полный OpenAPI validator отсутствует, runtime/CI/music NOT_RUN.
