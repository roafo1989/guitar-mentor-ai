# Общая диагностика Guitar Mentor AI — 27.10.2026

| Версия | Дата | Изменения |
| --- | --- | --- |
| 1.0 | 27.10.2026 | Общая регрессия реализованных функций; дефекты и границы проверок |

**Координация:** #237. **Материалы диагностики:** PR #240, ветка
`qa/general-regression-2026-10-27`. Runtime приложения не изменён.

**Вывод:** обычная сквозная цепочка обучения проходит автоматическую проверку.
Утверждать «всё работает без ошибок» нельзя: воспроизведены ошибки смены аккаунта,
ошибки начальных экранов; есть ограничения поставки медиа и физической проверки.
P1-сценарии #238/#239 следует исправить до признания версии полностью проверенной.
Зелёные существующие тесты не покрывали эти гонки.

## Версии и методы

- База приложения: `main 0914a79b8fae2441cf0a735e158818a5a99bea18`.
- #235 / [PR #236](https://github.com/roafo1989/guitar-mentor-ai/pull/236) рассматривается
  отдельно: исправляет анкету и POST диагностики, не меняет auth.ts, skillMap/history.
  Его полный CI на `973ce7415941688280889606bda814673f76ec74` зелёный.
- Новый `GeneralRegressionHttpPostgresTest` исполняется в GitHub Actions на настоящей
  PostgreSQL 17.11 с security filters, MVC, JPA/JDBC, Flyway и временным хранилищем.
  OIDC principal синтетический, Google redirect не выполняется. Это не браузерный
  end-to-end тест. Запросы в одном учебном сценарии проходят внутри тестовой транзакции,
  откат проверяет изоляцию/очистку; долговечность разных транзакций и гонки дополнительно
  покрыты существующими PostgresIntegrationTest и #235.
- Node/Vue тесты запускаются локально; Chrome smoke — в CI, с замоканным backend.
  Алгоритмы тюнера проверяются синтетическими звуковыми сигналами.
- Production, чужие аккаунты и реальные записи не затрагивались.

## Найденные дефекты

| Приоритет | Задача | Результат воспроизведения | Граница вывода |
| --- | --- | --- | --- |
| P1 | [#238](https://github.com/roafo1989/guitar-mentor-ai/issues/238) | POST с данными A, ожидающий CSRF, отправляется в nativeFetch после смены actor на B | Подтверждена отправка в JS; реальное изменение данных другого аккаунта не проверялось и не утверждается |
| P1 | [#239](https://github.com/roafo1989/guitar-mentor-ai/issues/239) | Поздний `/me` возвращает auth.actor=A после logout/login B | Подтверждена ошибка состояния интерфейса; обход серверной авторизации не заявляется |
| P2 | [#241](https://github.com/roafo1989/guitar-mentor-ai/issues/241) | Новый собственный learner без instrument: skills=404, lessons=404; UI показывает ошибку вместо начального состояния | PostgreSQL/security/MVC; сохраняется после #235, поскольку соответствующие сервисы не менялись |
| P2 | [#243](https://github.com/roafo1989/guitar-mentor-ai/issues/243) | Чистая сборка публикует GM-EB01 с двумя preview URL, оба возвращают 404 | PostgreSQL/security/MVC, 2 из 8 ресурсов недоступны; локальный media package документирован, наличие файлов на PILOT неизвестно |
| P3 | [#242](https://github.com/roafo1989/guitar-mentor-ai/issues/242) | При t('attempt.microphoneBusy') RU и EN показывают технический ключ | Словари и реальный vue-i18n; условная ветка отказа microphone lease, физический пользовательский путь не проверен |

Известный до этой диагностики дефект #235: bare learner также получает 404 анкеты
и POST начала диагностики. Его не выдаём за новый; отдельный готовый PR #236.

## Матрица сценариев

`PASS` всегда относится к указанному методу и среде. `BLOCKED` означает, что
результат нельзя подтвердить без внешней системы/устройства. Часть строк объединяет
несколько существующих тестов; число строк не равно числу тестовых методов.

| ID | Сценарий | Фактический результат | Метод / доказательство |
| --- | --- | --- | --- |
| GR-01 | Local регистрация, подтверждение email, вход, запрет повторной проверки | PASS в автоматике | LocalAuthHttpPostgresTest, LocalCredentialPostgresTest; тестовый SMTP |
| GR-02 | Восстановление/смена пароля и отзыв прежней версии доступа | PASS в автоматике | LocalCredentialPostgresTest, SecurityHttpIntegrationTest |
| GR-03 | Создание Google account, отсутствие неявного объединения по email | PASS в автоматике | InvitationServicePostgresTest, LocalCredentialPostgresTest, новый HTTP journey |
| GR-04 | Настоящий Google redirect и доставка письма реальному получателю | BLOCKED | Нет живой сессии Google/почтового ящика в тестовом стенде |
| GR-05 | Linking Google/password к одной учётной записи | PASS в автоматике | LocalCredentialPostgresTest; синтетические IdP/SMTP |
| GR-06 | Смена аккаунта при незавершённых `/me` и CSRF | FAIL | Два исполняемых auth-races.repro.mjs; #238/#239 |
| GR-07 | Сохранение предпочтений → анкета → повторное чтение | PASS | Новый GeneralRegressionHttpPostgresTest, сравнение полного JSON |
| GR-08 | Пустая анкета и старт диагностики до preferences | FAIL в main; PASS в кандидате #236 | #235 и его 8 новых PostgreSQL/HTTP тестов |
| GR-09 | Сводка навыков и история до создания instrument profile | FAIL | GENERAL_QA_INITIAL_OBSERVATION; #241 |
| GR-10 | Несохранённый черновик, ошибка GET, retry, поздние ответы другой формы | PASS в имеющихся mounted тестах | onboarding-recovery.component, profile-account.component; это не защита auth.ts из GR-06 |
| GR-11 | F5/потеря browser key/архивный key → текущая диагностика без POST | PASS | 9 current-diagnostic тестов; включены в штатный CI этим PR |
| GR-12 | Повторный start → одна session; continue intake → назначение задания | PASS | Новый PostgreSQL/HTTP journey и DiagnosticSessionServiceTest |
| GR-13 | Чужие learner/session/task и CSRF | PASS в проверенных маршрутах | Новый journey, SecurityHttpIntegrationTest, ownership inventory |
| GR-14 | Каталог: опубликованные metadata, version-pinned XML/JSON/PDF/audio | FAIL поставки GM-EB01: PDF/audio=404; остальные 6 URL=200 | Новый обход 2 упражнений / 8 URL, #243 |
| GR-15 | WAV upload → повтор requestId → одна попытка → page read | PASS | Новый journey: настоящий multipart, временное хранилище и 0.1-секундный WAV |
| GR-16 | MP3/OGG/WebM/M4A, неверный формат, превышение длительности | PASS в существующей автоматике | SubmissionAttempt* tests; live HTTP smoke предыдущего CI #236 |
| GR-17 | Завершить по решению ученика без оценки | PASS | Новый journey: COMPLETED_BY_LEARNER, повтор decision, запрет новой попытки |
| GR-18 | Самостоятельное завершение не подтверждает навык | PASS | Новый journey: нет OBSERVED/MEASURED записей; LearningRules/LearningPostgres tests |
| GR-19 | Следующий урок → архив старого → активный новый; replay и history | PASS | Новый journey; NextLessonPostgresIntegrationTest проверяет независимые транзакции/гонки |
| GR-20 | Реальная автоматическая музыкальная оценка/LLM/транскрипция | Не реализовано в полном объёме; не регрессионный FAIL | Открытые #22/#23/#159; техническую обработку не называем оценкой игры |
| GR-21 | Личная библиотека: create/list/detail/edit/delete, original, чужой account | PASS в автоматике | LibraryAcceptanceHttpPostgresTest, LibraryOriginal*, frontend library-mounted |
| GR-22 | Backend публикации/поиска/отзыва и межаккаунтное чтение опубликованного | PASS в автоматике | LibraryPublication* tests; общий UI #168 отдельно и не объявлен готовым |
| GR-23 | Личный музыкальный индекс, фильтры, приватность и импорт | PASS в автоматике | MusicCatalog* tests, 9 Python importer-тестов |
| GR-24 | TTL, сбои IO/БД, отсутствие удаления истории | PASS в моделируемых сценариях | MediaCleanupServiceTest, MediaRetentionPolicyTest, EvidenceMediaCleanupTest; не наблюдение реального production таймера 48 часов |
| GR-25 | Метроном: размер/акценты/темп/пресеты/остановка/фокус | PASS в автоматике | metronome*.test, Chrome keyboard/responsive smoke; физическую точность часов устройства не измеряли |
| GR-26 | Тюнер: Standard/Drop D/Open G, октавы, обертоны, отсутствие ложного green | PASS для синтетических сигналов | 40 tuner-тестов, чтение lifecycle микрофона |
| GR-27 | Реальный микрофон телефона, permission prompts, шум комнаты | BLOCKED | Нужны устройство/гитара и разрешение браузера; unit-тесты этого не заменяют |
| GR-28 | RU/EN, 360/768/1440, 200% reflow, deep links | PASS в Chrome smoke с mock API; один ключ отсутствует | CI frontend; #242 |
| GR-29 | Flyway чистая установка | PASS в PostgreSQL | 31 миграция успешно валидирована/применена; дублирования V29 в main сейчас нет |
| GR-30 | Реальный PILOT: версия JAR/UI, DNS/HTTPS, DEV→PILOT/rollback/restore | BLOCKED | Нужны фактические процессы/настройки ноутбука; чтение репозитория не подтверждает deployment |

## Фактический итог CI и доказательства

Код новых тестов проверен на commit `70be8fe05d5498eb06a5d125d0bb2d61e091951e` (runtime приложения равен main
0914a79b). Последующие изменения этого отчёта не считаются повторным исполнением тестов.

- [Java backend CI](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/40469387734)
  — **FAIL**: всего 364 теста, 359 PASS, 1 FAIL, 4 SKIP. Единственное падение — новый
  тест недоступных preview GM-EB01 (#243); ошибка не замаскирована. В новом классе:
  сквозная цепочка PASS, initial read-only side effects PASS (отдельно записаны 404),
  целостность рекламируемых ресурсов FAIL. Все прежние Java-тесты прошли или сохранили
  прежние skips. Package и live HTTP smoke в этом прогоне не выполнялись, поскольку
  verify остановился на найденном дефекте.
- [Vue frontend CI](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/40469387723)
  — SUCCESS: 309 тестов (212 + 57 + 40), сборки, audit, четыре Chrome smoke.
- `auth-races.repro.mjs` — **2 FAIL из 2**, намеренно не включён в обычный npm test:
  это репродукторы открытых #238/#239; нельзя считать frontend полностью исправленным
  на основании зелёного CI. После исправления их необходимо включить в обязательный CI.
- Реальный vue-i18n со словарями RU и EN вернул `attempt.microphoneBusy` в обоих языках.
- Flyway валидировал 31 миграцию; ручная проверка версий не нашла дублей.

Проверка дополнительных тестов первоначально потребовала исправить сам QA helper
для multipart builder Spring 7 и указать обязательные параметры каталога. Эти два
тестовых дефекта устранены до приведённого итогового прогона; они не зарегистрированы
как ошибки приложения.

## Покрытие и запуск

На исходном main локальный запуск **всех** найденных frontend тестов дал 309 PASS,
0 FAIL. На кандидате #236 — 311 PASS (два дополнительных теста #235).
Штатные команды main до этой работы пропускали 9 current-diagnostic.test.mjs;
в этом PR добавлено их исполнение, без изменений runtime.
Python importer: 9 PASS, 0 FAIL.

Из `frontend`, Node >=22.12, после `npm ci`:

```sh
node --experimental-strip-types --test tests/*.test.mjs src/shared/tuner/*.test.mjs
npm run build
```

Репродуктор auth гонок (из корня, после установки frontend dependencies):

```sh
node --experimental-strip-types --test docs/testing/regression-2026-10-27/auth-races.repro.mjs
```

**Ожидаемый текущий результат — два FAIL**, это подтверждение #238/#239, не готовое
исправление. Используется настоящий auth.ts, подменён только native fetch; тесты
не обращаются к пользовательским аккаунтам или внешнему серверу.

Backend: Java 21, отдельная QA PostgreSQL; `GUITAR_MENTOR_TEST_POSTGRES=true`,
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.
В `backend`: `./mvnw --batch-mode verify` (Windows: `.\mvnw.cmd --batch-mode verify`).
Не использовать production БД. Новые тесты не должны становиться зелёными путём
скрытия отказавших URL или замены ожидаемого 200 на 404.

## Предлагаемая очередь

1. #238 и #239 — защита auth-контекста до отправки и до применения асинхронного ответа;
   после исправления включить репродукторы в основной CI.
2. #235 вместе с #241 — полный пользовательский вход в профиль/анкету/историю.
3. #243 — воспроизводимая поставка медиа; затем локализация #242.
4. Повторить затронутую регрессию, затем короткий UAT на реальном телефоне и проверку
   версии backend/frontend на PILOT. До этого отчёт не является одобрением выпуска.

Матрица и Issues фиксируют результаты диагностики, не приёмку продуктовых исправлений.
Поля Project через коннектор недоступны; изменение самой доски не заявляется.
