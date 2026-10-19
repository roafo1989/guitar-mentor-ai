# #16 · FT-002-02 — тест-кейсы и протокол приёмки карты навыков и рекомендаций

**Дата:** 20.10.2026 · **Роль:** QA · **Объект:** [Issue #16](https://github.com/roafo1989/guitar-mentor-ai/issues/16), [PR #90](https://github.com/roafo1989/guitar-mentor-ai/pull/90), ветка `feature/issue-16-skill-map-recommendations`.

## 1. Объём и правила вердикта

Источник: [спецификация #16 v0.2](https://github.com/roafo1989/guitar-mentor-ai/blob/analysis/system-analysis-standard/docs/specifications/MVP-R3-01-skill-map-and-diagnostic-recommendations.md), AC-16-01…AC-16-15, [инструкция разработчика](../development/dev-16-skill-map-quickstart.md). Проверяются **только** карта навыков, истории SELF_REPORTED и read-only подбор для начальной диагностики; аудио-оценка навыков (#18), новые beginner-этюды (#17), план уроков (#24) вне объёма.

**Значения статусов:** `PASS-CI` — точный сценарий или существенная проверка доказаны тестом/HTTP smoke; `PARTIAL` — покрыта только часть AC, не трактовать как полную приёмку; `MANUAL` — нужен браузер на локальном стенде; `GATE` — проверка перед объединением веток. Приёмка пользователем и merge — самостоятельные решения.

**Текущая кодовая база при начале QA:** PR #90 HEAD `e7907ab26361344b74b821631bfd4fa5e197398e` (добавлены QA-тесты). `main` уже содержит отдельно принятый #94 / M4A-AAC и продвинулся относительно базы PR. Ветка #90 ещё не объединена. Дата/точный SHA тестирования и актуальные CI выводятся из ссылок ниже; не подменять CI ручными проверками.

## 2. Матрица приёмки (Given / When / Then)

| TC / AC | Предусловие и действие | Ожидаемый результат | Автоматическое покрытие и остаток |
| --- | --- | --- | --- |
| TC-16-01 / AC-16-01 | PostgreSQL до V12; применить миграцию; GET навыков | Девять устойчивых кодов, точные зависимости, таблицы без порчи прежних FK | **PASS-CI/PARTIAL:** Flyway bootstrap, девять навыков по HTTP; сверка всей структуры seed — по SQL |
| TC-16-02 / AC-16-02 | Внести PULSE → SUBDIVISION при существующей обратной дуге; вызвать recommendation | Ошибка целостности, рекомендации не выдаются | **PASS-CI:** `corruptPrerequisiteGraphFailsClosedForRecommendations`, unit graph cycle / unknown |
| TC-16-03 / AC-16-03 | Нет анкеты, есть пригодный 10-минутный BEGINNER exercise | RECOMMENDED beginner, UNKNOWN не превращается в FAIL | **PASS-CI (фикстура):** `noviceWithBeginnerFixtureGetsAccessibleExercise`; живой beginner-каталог появится в #17 |
| TC-16-04 / AC-16-04 | Нет анкеты или BEGINNER, каталог только GM-EB01 INTERMEDIATE; GET | 200 NO_MATCH/NO_BEGINNER_EXERCISE, task отсутствует | **PASS-CI:** Postgres service, real HTTP; **MANUAL** для отображения |
| TC-16-05 / AC-16-05 | BLUES=EXCLUDE; есть BLUES и другой подходящий стиль | BLUES исключён из авто-подбора, вручную каталог доступен | **PASS-CI:** unit+Postgres EXCLUDE; **MANUAL** для ручного выбора |
| TC-16-06 / AC-16-06 | Два эквивалентных кандидата, BLUES=LIKE, ROCK=NEUTRAL | LIKE выше NEUTRAL, `PREFERRED_STYLE` только для LIKE | **PASS-CI:** `likedStyleBeatsNeutralWhenAllOtherSignalsMatch` (QA-тест) |
| TC-16-07 / AC-16-07 | 10 минут, кандидат 10 и 30 минут | Только 10-минутный допустим; при одном 30 — NO_TIME_FIT | **PASS-CI:** `shortAndLongCandidatesRespectTenMinuteHardLimit` и существующий duration test |
| TC-16-08 / AC-16-08 | Цели RHYTHM/INDEPENDENT_BASS и роли PRIMARY | Цель влияет на score, причины стабильны, результат детерминирован | **PASS-CI:** goal/tie-break и skill evidence unit; расширение каталога — #17 |
| TC-16-09 / AC-16-09 | Анкета заявляет FINGERSTYLE_MELODY; GET skills | MELODY selfReported=true, assessedLevel=0/NOT_ASSESSED; без PASS/MEASURED | **PASS-CI:** Postgres/HTTP + unit |
| TC-16-10 / AC-16-10 | PUT с FINGERSTYLE_MELODY, повтор идентичного PUT, затем убрать; history | Ровно CLAIMED/RETRACTED по порядку, без дубля на повтор, `confidence=null` | **PASS-CI:** HTTP, Postgres, keyset pagination, конкурентный PUT |
| TC-16-11 / AC-16-11 | Чужой learnerId / состояние COLLECTING_EVIDENCE / TASK_ASSIGNED; GET рекомендаций | 404 без выдачи чужих данных, 409 `DIAGNOSTIC_NOT_READY`/`TASK_ALREADY_ASSIGNED` | **PASS-CI:** дополнительный QA PostgreSQL test, HTTP 404/409 |
| TC-16-12 / AC-16-12 | Одновременно GET recommendation и два POST одного упражнения | GET не пишет; не более одного DiagnosticTask; GET даёт снимок либо 409 | **PASS-CI:** `concurrentGetAndTwoPostsCreateOnlyOneDiagnosticTask` + HTTP no-task test |
| TC-16-13 / AC-16-13 | Пустой каталог, неодобренные версии и повреждённые данные | 200 NO_MATCH при отсутствии допустимых упражнений; ошибка данных — безопасный отказ | **PARTIAL:** пустой каталог/DRAFT unit, graph corruption Postgres. Сломанные ресурсы версии отдельно не воспроизводились |
| TC-16-14 / AC-16-14 | READY_FOR_TASK, NO_MATCH, ошибка сети, F5, ru/en | Понятное сообщение, повтор, каталог вручную, никаких двойных задач | **MANUAL:** UI-M1…UI-M5, Vue build сам по себе не равен e2e |
| TC-16-15 / AC-16-15 | Заполненная БД V11 с GM-EB01, DiagnosticTask и анкетой; перейти к V12 | Flyway migrate+validate, прежние связи и данные сохранились | **PASS-CI:** `SkillMapMigrationUpgradePostgresTest` на PostgreSQL 17 |
| TC-16-16 / regression | INTERMEDIATE при единственных ADVANCED/CHALLENGE | NO_COMPATIBLE_EXERCISE; рискованный материал не выдаётся | **PASS-CI:** unit difficulty ceiling |
| TC-16-17 / rights | `ALL_RIGHTS_RESERVED` / `UNLICENSED` / неутверждённый статус | Никогда не рекомендуются автоматически | **PASS-CI:** negative allowlist tests |
| TC-16-18 / API | limit=4, неверный cursor, чужой профиль/сессия | 400/404, никакой утечки значений другой сессии | **PARTIAL:** HTTP invalid limit и чужой learner; cursor проверен на service уровне |
| TC-16-19 / evidence | OBSERVED со значением уровня 3 и confidence 0.850, отдельно SELF_REPORTED | Подтверждённый источник не смешивается с самооценкой; nullable confidence | **PASS-CI:** Postgres evidence/history |
| TC-16-20 / limit | 2+ упражнения одинакового веса; limit 1–3 | Строго limit, устойчивый tie-break | **PASS-CI/PARTIAL:** unit limits 0/4 и стабильная сортировка, явный HTTP limit=1 не прогнан |

## 3. Автоматические проверки и протокол

**Среда:** GitHub Actions Java 21 / PostgreSQL 17 / Flyway / Maven; GitHub Actions Vue build. Это **автоматизированный стенд, не браузер пользователя**.

- Контрольная базовая сборка [Java CI 39330780361](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39330780361): 94/94 JUnit, failures=0, errors=0, skipped=0; PostgreSQL V11→V12, backend boot, HTTP smoke — SUCCESS. [Vue CI 39330780579](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39330780579): SUCCESS.
- QA добавил 4 теста в два существующих класса: exact LIKE-vs-NEUTRAL, exact 10-vs-30 min, other-owner/2x state errors, concurrent GET + 2 POST. [Java CI нового HEAD 39333223980](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39333223980); [Vue CI нового HEAD 39333223919](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39333223919). **Итог последнего Java CI должен быть проверен до финальной приёмки.**
- Новые искусственные ученики создаются только в CI DB, тест удаляет их после выполнения. Ни секретов, ни реальных ученических аудио/персональных данных в Git нет.

## 4. Ручные кейсы (передаются владельцу, строго минимальные)

> Выполнять **на локальном стенде из PR #90**, а не на неизменённом `main`. Убедиться, что backend/frontend запущены, PostgreSQL содержит V12. Перед `git switch` выполнить `git status` и сохранить собственные несохранённые изменения; тестировать на вымышленных учениках, без реальных аудиозаписей.

| ID | Действия в браузере | Ожидаемое наблюдение | Протокол |
| --- | --- | --- | --- |
| UI-M1 | Создать отдельного тестового ученика: ACOUSTIC_GUITAR/FINGERSTYLE, INTERMEDIATE, 45 мин, RHYTHM, BLUES=LIKE. «Диагностика» → «Начать» → «Продолжить без записи» | Рекомендация «Вечерний блюз в ми» / GM-EB01 v0.8B, 30 мин, PULSE/BASS_PULSE/MELODY, объяснение; каталог под ним; **task ещё не назначен** | **Не выполнено, нужен браузер владельца** |
| UI-M2 | На этапе READY_FOR_TASK нажать F5, посмотреть рекомендации, затем зайти в профиль, сделать BLUES=EXCLUDE и вернуться | После F5 выбор стабилен; при EXCLUDE появляется понятный NO_MATCH и возможность открыть каталог вручную | **Не выполнено, нужен браузер владельца** |
| UI-M3 | Создать **нового** тестового ученика NONE/BEGINNER с тем же инструментом, запустить диагностику до READY_FOR_TASK | GM-EB01 не предлагается, NO_BEGINNER_EXERCISE; каталог остаётся доступен вручную | **Не выполнено, нужен браузер владельца** |
| UI-M4 | Вернуться к ученику INTERMEDIATE, восстановить BLUES=LIKE; нажать «Назначить» на рекомендации, обновить страницу F5 | Один GM-EB01, статус TASK_ASSIGNED, ноты/просмотр доступны; повторного задания нет | **Не выполнено, нужен браузер владельца** |
| UI-M5 | На этапе READY_FOR_TASK переключить ru/en; отключить сеть в DevTools и повторить запрос рекомендаций, затем восстановить | Подписи/причины локализованы, сетевой сбой не маскируется как NO_MATCH, доступен «Повторить», каталог не потерян, клавиатурой можно нажать кнопки | **Не выполнено, нужен браузер владельца** |

**Ручная приёмка является отдельным решением владельца.** Попросить сообщить ID упавшего теста, фактическое поведение и, при необходимости, скриншот. Не считать молчание/отсутствие отчёта PASS.

## 5. Гейты и порядок действий

1. Дождаться и сверить последний Java CI на QA HEAD: без этого нельзя объявить автоматическую часть окончательно пройденной.
2. Выполнить UI-M1…UI-M5 локально. Если дефект — записать воспроизведение/ожидаемое/фактическое и вернуть **задачу #16** в `Разработка / В очереди`, не заводя дубликат без нужды.
3. Перед merge учесть, что `main` продвинулся после базы PR #90 (в том числе #94 M4A/AAC). Проверить интеграцию с актуальным main и повторный CI на конечном объединении. Успех прошлых CI на старом дереве не означает релизную совместимость.
4. После всех PASS и **явного решения владельца** — merge и закрытие Issue. До этого PR/Issue остаются открытыми.

**Текущий QA-вердикт:** автоматическая база проверена; новые тесты ожидают окончательной фиксации CI, браузерная приёмка не выполнена. Целевой Kanban: `Тестирование / В работе`; поля GitHub Project v2 требуется отдельно синхронизировать (этот документ и коммент Issue не изменяют значения полей Project).
