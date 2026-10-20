# #16 · FT-002-02 — тест-кейсы и протокол приёмки карты навыков и рекомендаций

**Дата:** 20.10.2026 · **Роль:** QA · **Объект:** [Issue #16](https://github.com/roafo1989/guitar-mentor-ai/issues/16), [PR #90](https://github.com/roafo1989/guitar-mentor-ai/pull/90), ветка `feature/issue-16-skill-map-recommendations`.

## 1. Объём и правила вердикта

Источник: [спецификация #16 v0.2](https://github.com/roafo1989/guitar-mentor-ai/blob/analysis/system-analysis-standard/docs/specifications/MVP-R3-01-skill-map-and-diagnostic-recommendations.md), AC-16-01…AC-16-15, [инструкция разработчика](../development/dev-16-skill-map-quickstart.md). Проверяются **только** карта навыков, истории SELF_REPORTED и read-only подбор для начальной диагностики; аудио-оценка навыков (#18), новые beginner-этюды (#17), план уроков (#24) вне объёма.

**Значения статусов:** `PASS-CI` — точный сценарий или существенная проверка доказаны тестом/HTTP smoke; `PARTIAL` — покрыта только часть AC, не трактовать как полную приёмку; `MANUAL` — нужен браузер на локальном стенде; `GATE` — проверка перед объединением веток. Приёмка пользователем и merge — самостоятельные решения.

**Проверенный SHA функционального/тестового кода:** `e7907ab26361344b74b821631bfd4fa5e197398e`. Последующий коммит в PR добавил только этот QA-документ. На момент проверки `main` содержит принятый #94 / M4A-AAC и продвинулся относительно исходной базы PR; новая GitHub Actions проверка события `pull_request` с checkout merge-ref прошла вместе с изменениями актуального `main`. PR остаётся OPEN; не подменять CI браузерной приёмкой.

## 2. Матрица приёмки (Given / When / Then)

| TC / AC | Предусловие и действие | Ожидаемый результат | Автоматическое покрытие и остаток |
| --- | --- | --- | --- |
| TC-16-01 / AC-16-01 | PostgreSQL до V12; применить миграцию; GET навыков | Девять устойчивых кодов, точные зависимости, таблицы без порчи прежних FK | **PASS-CI/PARTIAL:** Flyway bootstrap, девять навыков по HTTP; сверка всей структуры seed — по SQL |
| TC-16-02 / AC-16-02 | Внести PULSE → SUBDIVISION при существующей обратной дуге; вызвать recommendation | Ошибка целостности, рекомендации не выдаются | **PASS-CI:** `corruptPrerequisiteGraphFailsClosedForRecommendations`, unit graph cycle / unknown |
| TC-16-03 / AC-16-03 | Нет анкеты, есть пригодный 10-минутный BEGINNER exercise | RECOMMENDED beginner, UNKNOWN не превращается в FAIL | **PASS-CI (фикстура):** `noviceWithBeginnerFixtureGetsAccessibleExercise`; живой beginner-каталог появится в #17 |
| TC-16-04 / AC-16-04 | Нет анкеты или BEGINNER, каталог только GM-EB01 INTERMEDIATE; GET | 200 NO_MATCH/NO_BEGINNER_EXERCISE, task отсутствует | **PASS-CI:** Postgres service, real HTTP; **НЕ ПРОГОНЯЕТ ЗАКАЗЧИК** — отдельно GUI-автоматизация или риск |
| TC-16-05 / AC-16-05 | BLUES=EXCLUDE; есть BLUES и другой подходящий стиль | BLUES исключён из авто-подбора, вручную каталог доступен | **PASS-CI:** unit+Postgres EXCLUDE; **НЕ ПРОГОНЯЕТ ЗАКАЗЧИК** — UI-риск документирован |
| TC-16-06 / AC-16-06 | Два эквивалентных кандидата, BLUES=LIKE, ROCK=NEUTRAL | LIKE выше NEUTRAL, `PREFERRED_STYLE` только для LIKE | **PASS-CI:** `likedStyleBeatsNeutralWhenAllOtherSignalsMatch` (QA-тест) |
| TC-16-07 / AC-16-07 | 10 минут, кандидат 10 и 30 минут | Только 10-минутный допустим; при одном 30 — NO_TIME_FIT | **PASS-CI:** `shortAndLongCandidatesRespectTenMinuteHardLimit` и существующий duration test |
| TC-16-08 / AC-16-08 | Цели RHYTHM/INDEPENDENT_BASS и роли PRIMARY | Цель влияет на score, причины стабильны, результат детерминирован | **PASS-CI:** goal/tie-break и skill evidence unit; расширение каталога — #17 |
| TC-16-09 / AC-16-09 | Анкета заявляет FINGERSTYLE_MELODY; GET skills | MELODY selfReported=true, assessedLevel=0/NOT_ASSESSED; без PASS/MEASURED | **PASS-CI:** Postgres/HTTP + unit |
| TC-16-10 / AC-16-10 | PUT с FINGERSTYLE_MELODY, повтор идентичного PUT, затем убрать; history | Ровно CLAIMED/RETRACTED по порядку, без дубля на повтор, `confidence=null` | **PASS-CI:** HTTP, Postgres, keyset pagination, конкурентный PUT |
| TC-16-11 / AC-16-11 | Чужой learnerId / состояние COLLECTING_EVIDENCE / TASK_ASSIGNED; GET рекомендаций | 404 без выдачи чужих данных, 409 `DIAGNOSTIC_NOT_READY`/`TASK_ALREADY_ASSIGNED` | **PASS-CI:** дополнительный QA PostgreSQL test, HTTP 404/409 |
| TC-16-12 / AC-16-12 | Одновременно GET recommendation и два POST одного упражнения | GET не пишет; не более одного DiagnosticTask; GET даёт снимок либо 409 | **PASS-CI:** `concurrentGetAndTwoPostsCreateOnlyOneDiagnosticTask` + HTTP no-task test |
| TC-16-13 / AC-16-13 | Пустой каталог, неодобренные версии и повреждённые данные | 200 NO_MATCH при отсутствии допустимых упражнений; ошибка данных — безопасный отказ | **PARTIAL:** пустой каталог/DRAFT unit, graph corruption Postgres. Сломанные ресурсы версии отдельно не воспроизводились |
| TC-16-14 / AC-16-14 | READY_FOR_TASK, NO_MATCH, ошибка сети, F5, ru/en | Понятное сообщение, повтор, каталог вручную, никаких двойных задач | **MANUAL-PARTIAL:** только UAT-16-01; прочие UI-ветки не проверяются заказчиком и остаются за QA/автоматизацией |
| TC-16-15 / AC-16-15 | Заполненная БД V11 с GM-EB01, DiagnosticTask и анкетой; перейти к V12 | Flyway migrate+validate, прежние связи и данные сохранились | **PASS-CI:** `SkillMapMigrationUpgradePostgresTest` на PostgreSQL 17 |
| TC-16-16 / regression | INTERMEDIATE при единственных ADVANCED/CHALLENGE | NO_COMPATIBLE_EXERCISE; рискованный материал не выдаётся | **PASS-CI:** unit difficulty ceiling |
| TC-16-17 / rights | `ALL_RIGHTS_RESERVED` / `UNLICENSED` / неутверждённый статус | Никогда не рекомендуются автоматически | **PASS-CI:** negative allowlist tests |
| TC-16-18 / API | limit=4, неверный cursor, чужой профиль/сессия | 400/404, никакой утечки значений другой сессии | **PARTIAL:** HTTP invalid limit и чужой learner; cursor проверен на service уровне |
| TC-16-19 / evidence | OBSERVED со значением уровня 3 и confidence 0.850, отдельно SELF_REPORTED | Подтверждённый источник не смешивается с самооценкой; nullable confidence | **PASS-CI:** Postgres evidence/history |
| TC-16-20 / limit | 2+ упражнения одинакового веса; limit 1–3 | Строго limit, устойчивый tie-break | **PASS-CI/PARTIAL:** unit limits 0/4 и стабильная сортировка, явный HTTP limit=1 не прогнан |

## 3. Автоматические проверки и протокол

**Среда:** GitHub Actions Java 21 / PostgreSQL 17 / Flyway / Maven; GitHub Actions Vue build. Это **автоматизированный стенд, не браузер пользователя**.

- Контрольная базовая сборка [Java CI 39330780361](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39330780361): 94/94 JUnit, failures=0, errors=0, skipped=0; PostgreSQL V11→V12, backend boot, HTTP smoke — SUCCESS. [Vue CI 39330780579](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39330780579): SUCCESS.
- QA добавил **4 теста** в два существующих класса: isolated LIKE-vs-NEUTRAL, 10-vs-30 min, wrong owner/2x state errors, concurrent GET + 2 POST. [Java CI на QA-изменениях — SUCCESS](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39333223980): **111 total, 0 failures, 0 errors, 2 skipped**, Maven BUILD SUCCESS, PostgreSQL/Flyway V11→V12 и HTTP smoke PASS. [Vue frontend CI — SUCCESS](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39333223919). **Пропущены ровно 2 теста в `AudioDurationProbeTest`**, вне сферы #16 (FFprobe media checks; отдельная стадия CI исполнила HTTP media smoke). Все 15 тестов `RecommendationEngineTest` и 9 тестов `DiagnosticRecommendationPostgresTest` исполнены: 0 failures/errors/skipped.
- Новые искусственные ученики создаются только в CI DB, тест удаляет их после выполнения. Ни секретов, ни реальных ученических аудио/персональных данных в Git нет.

## 4. Минимальная пользовательская приёмка — только один smoke-сценарий

**Принцип:** владелец — заказчик, а не штатный тестировщик. Он подтверждает **один сквозной пользовательский сценарий**. Негативные случаи, API, лицензии, корректность алгоритма, конкуренция запросов, граф навыков, история, миграции и регрессии — ответственность QA/автотестов. Не просить владельца использовать Postman, DevTools, SQL, переключать пять профилей или часами исследовать интерфейс.

| ID | Что сделать владельцу | Что считаем успехом | Статус |
| --- | --- | --- | --- |
| **UAT-16-01** | На локальном стенде PR #90 открыть тестового ученика, увидеть рекомендацию GM-EB01 (30 мин), назначить, выполнить F5. | Исходная рекомендация видна, назначение создаётся однократно; после F5 то же упражнение сохраняется. | **PARTIAL**: сохранение уже назначенного задания после F5 — **PASS** (подтверждено владельцем 20.10.2026). Исходный экран рекомендаций и новое назначение **не проверены**: сессия была в TASK_ASSIGNED с историей попыток. |

**Ориентир по активным действиям владельца: 3–5 минут на готовом локальном стенде**, не обещание времени настройки окружения. Если нет подготовленного стенда/приложение не на ветке PR #90, это **блокер окружения**, а не дополнительный пользовательский тест: техподготовку выносим в работу разработчика/QA, не требуем Git-команд от владельца.

**Отчёт от владельца:** достаточно «прошло» или «не прошло»; при ошибке — один скриншот. Отдельный отчёт по каждому техническому требованию не нужен.

**Фактический прогон 20.10.2026:** backend Spring Boot на 8080 запущен, браузерная страница через `guitar-mentor` открылась, существующий тестовый профиль загружен из PostgreSQL. На `/diagnostics` была *ранее* назначенная задача с историей аудиопопыток; пользователь выполнил F5 и ответил **«сохранилось»**. Поэтому отмечаем **PASS исключительно для сохранения существующего назначения и восстановления его экрана после F5**. Сценарии initial RECOMMENDED, создание нового задания, предотвращение двойного назначения и содержимое раскрытого PDF не наблюдались; не приписывать им manual PASS. В соответствии с просьбой заказчика дополнительные ручные сценарии не назначать.

**Что остаётся вне пользовательского прогона (не считать ручным PASS):** UI-ветки NO_MATCH/EXCLUDE, второй профиль BEGINNER, переключение ru/en, искусственный сетевой сбой и Retry, детальная accessibility-проверка. Их предметная логика частично покрыта автотестами и проверкой сборки, но полный браузерный e2e для этих веток отсутствует. Документировать как **непройденные вручную остаточные риски**; приёмка заказчиком одного smoke-сценария **не равна** доказательству этих UI-веток. При обнаружении дефектов исправлять в #16, не превращать их в новую нагрузку на заказчика.

## 5. Гейты и порядок действий

1. **Выполнено:** сверить последний Java+Vue CI на QA SHA и GitHub PR merge-ref: обе SUCCESS; 111 Java total, 2 skipped вне #16. Автоматизированная часть в доказанном покрытии **PASS**.
2. **Минимальный ручной прогон завершён в доступном объёме:** F5 сохраняет ранее назначенное задание. `UAT-16-01` остаётся **PARTIAL** без наблюдения первой выдачи рекомендаций/нового назначения. По решению заказчика не запрашивать дальнейших ручных проверок; непокрытые GUI-ветки — ответственность QA/автоматизации либо явно принимаемый остаточный риск. При фактическом дефекте — возврат #16 в разработку.
3. Перед merge сверить текущую базу PR и `main`: GitHub Actions на pull_request выполнил 111 тестов в том числе по #94 в результате проверки merge-ref, но **после новых коммитов main** нужно повторить итоговый CI; не считать green на старом SHA бессрочной гарантией совместимости.
4. После всех PASS и **явного решения владельца** — merge и закрытие Issue. До этого PR/Issue остаются открытыми.

**Текущий QA-вердикт:** автоматическая часть **PASS в документированном объёме (CI 111 / 0 failures / 0 errors / 2 skipped по нецелевому AudioDurationProbeTest)**; единственный пользовательский smoke **UAT-16-01 PARTIAL**: сохранение ранее назначенного задания после F5 PASS, первоначальная рекомендация и создание задания не проверены вручную. Остальные GUI-ветки не имеют полного e2e-покрытия. Код-кейс AC-16-13 (отказ повреждённой версии ресурсов) покрыт частично и требует отдельной оценки при расширении каталога. Целевой Kanban: `Тестирование / В работе`; поля GitHub Project v2 требуется отдельно синхронизировать (этот документ и коммент Issue не изменяют значения полей Project).
