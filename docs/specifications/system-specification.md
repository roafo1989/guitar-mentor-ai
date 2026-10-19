# Guitar Mentor AI — Системная спецификация

| Версия | Дата | Что поменялось |
| --- | --- | --- |
| 0.5 | 19.10.2026 | REST и данные сверены с main 270238f; отделены код #30 в PR от проекта #21, отражены независимые проверки. |
| 0.4 | 19.10.2026 | Добавлена спецификация #21: финализация, TTL 48h, состояния и восстановление; ТЗ 0.5 / БТ 0.3. |
| 0.3 | 18.10.2026 | Уточнены #20/#30/#21 по main 270238f; остаток #30 определён в отдельном контракте; основа ТЗ 0.4. |
| 0.2 | 18.10.2026 | Обновлена база на БТ/ТЗ 0.2; учтена главная и навигация US-009. |
| 0.1 | 18.10.2026 | Первая карта компонентов, текущих REST-контрактов, целевых данных и правил надёжности на базе ТЗ 0.2. |

**База:** [ТЗ 0.6](../requirements/technical-specification.md), БТ 0.4. Сверка исходников: main `270238f8fdb0dec5075039a9bdd9483b85177305`; #30 — отдельный PR #69 `07cc33b`. [Доказательства и текущая приёмка](../project/current-state.md). Таблицы ниже различают существующие контракты и целевой объём.

## 1. Компоненты и данные

| Компонент / данные | В main | Остаток |
| --- | --- | --- |
| Vue / REST / Application / Domain / Persistence | Профиль, анкета, диагностика, каталог, отправка и история попыток | Полный учебный цикл, оценка, решение ученика, персональный план |
| Learner / InstrumentProfile / MusicPreference | V2; предпочтение не подтверждает навык | Account и серверные права — #38/#42 |
| LearningBackground | V3, одна анкета инструмента; нулевой опыт допустим | Полная ручная приёмка #11 не подтверждена |
| DiagnosticSession | V4: COLLECTING_EVIDENCE → READY_FOR_TASK → TASK_ASSIGNED; одна активная сессия инструмента | Полный целевой цикл UC-02 не реализован |
| Exercise / SkillCode / DiagnosticTask | V5/V6, версия GM-EB01 v0.8B; ссылки на учебные ресурсы | ≥5 этюдов/метроном #17; правила подбора/карта навыков #16 |
| SubmissionAttempt | V7–V9: task/requestId/номер/дата/комментарий, media key/формат/размер, технический статус, SHA-256, длительность, processing timestamps/attempts | Assessment/StudentDecision/SkillEvidence #18, безопасный TTL #21 |
| LocalAttemptMediaStore | Один приватный каталог, случайные ключи; байты вне PostgreSQL/Git | Порт AttemptMediaStore и rollback guard написаны в PR #69, не в main |
| SystemSetting / media deletion lifecycle | В main отсутствуют | #21: system_settings, финализация, AVAILABLE/DELETE_PENDING/DELETED, cleanup и orphan scan |
| Audio / AI / OIDC adapters | Музыкальный анализ, LLM и авторизация отсутствуют | #14/#15/#31/#22/#23, будущий интернет-пилот US-007 |

MediaAsset остаётся концептуальной моделью UC-02/ADR. #30 не создаёт вторую таблицу/хранилище; #21 расширяет существующие метаданные submission_attempts. Просмотр материалов каталога и временные файлы ученика — разные контуры.

## 2. Существующие HTTP-контракты

Префикс всех путей ниже — `/api/v1`. UUID в URL проверяет связность ресурсов, но не является авторизацией.

| Метод / путь | Назначение | ФТ |
| --- | --- | --- |
| POST `/learners`; GET `/learners/{learnerId}` | Создание/чтение профиля; UC-01, LearnerController | FT-001-01 |
| PUT `/learners/{learnerId}/preferences` | Атомарная замена списка предпочтений | FT-001-01 |
| GET/PUT `/learners/{learnerId}/instruments/{instrumentCode}/background` | Анкета; GET без анкеты возвращает 204; UC-03 | FT-001-03 |
| POST `/learners/{learnerId}/diagnostic-sessions` | Начать/восстановить по JSON instrumentCode | FT-002-01 |
| GET `/learners/{learnerId}/diagnostic-sessions/{sessionId}`; POST `…/{sessionId}/continue` | Состояние / продолжение без собственной записи | FT-002-01 |
| GET/POST `/learners/{learnerId}/diagnostic-sessions/{sessionId}/tasks` | Получить/назначить exerciseCode; 204 до назначения; повтор не дублирует | FT-002-03 |
| GET `/exercises`; GET `/exercises/{code}` | Каталог / описание | FT-002-03 |
| GET `/exercises/{code}/preview/audio`, `…/preview/pdf`, `…/score`, `…/events` | Учебные MP3/PDF/MusicXML/JSON; неустановленные MP3/PDF дают 404 | FT-002-03 |
| GET `/learners/{learnerId}/diagnostic-sessions/{sessionId}/tasks/{taskId}/attempts/capabilities` | enabled, maxBytes=20971520, formats=[mp3,wav,ogg,webm] | FT-004-01 |
| GET `…/tasks/{taskId}/attempts` | История AttemptView, без media key и физических путей | FT-004-01 |
| POST `…/tasks/{taskId}/attempts` (multipart) | file, UUID requestId, необязательный comment ≤2000 символов; 201; повтор taskId+requestId возвращает прежнюю попытку | FT-004-01 |
| GET `/system/ping`; GET `/actuator/health` без `/api/v1` | HTTP-доступность / состояние приложения и БД; не приёмка полного MVP | FT-006-02 |

`AttemptView`: id, taskId, number, submittedAt, status, comment, format, bytes, assessmentAvailable=false, processingStartedAt, processingFinishedAt, processingErrorCode, audioDurationSeconds. Связь learner/session/task проверяется сервером. Физический путь/key не возвращаются; download/delete endpoint пользовательских файлов нет.

Размер файла 1–20971520 байт; multipart request до 21 MB по конфигурации. Расширение/сигнатура проверяются при приёме, реальная длительность аудиопотока ≤120 секунд — асинхронно через ffprobe с timeout 12 секунд. Слишком длинная запись сохраняется в истории как FAILED/DURATION_EXCEEDED. Для запрещённых загрузок POST возвращает 403/UPLOAD_DISABLED; история читается. Для обработанных ResponseStatusException используется ограниченный JSON code, включая INVALID_AUDIO_CONTENT/UNSUPPORTED_AUDIO_FORMAT/INVALID_AUDIO_SIZE/COMMENT_TOO_LONG; превышение транспортного multipart-лимита обрабатывается Spring отдельно.

Будущие контракты собственных примеров/нот до назначения (#68), музыкальных оценок, решений, плана и приглашений детализируются до их реализации. Предварительные URL UC-02 не считаются существующим API.

## 3. Технический lifecycle и транзакции

- UPLOADED → PROCESSING → PROCESSED/FAILED. PROCESSED означает техническую проверку, не музыкальный PASS и не универсальное завершение всех будущих стадий.
- Worker при включённых загрузках опрашивает очередь каждые 3 с; прерванная обработка старше 5 минут возвращается в очередь максимум до 3 запусков, затем FAILED/PROCESSING_INTERRUPTED.
- Основной каталог по умолчанию `./.local-media/attempts` относительно working directory backend; путь можно задать GUITAR_MENTOR_MEDIA_PATH. Отключение загрузок не удаляет старую историю.
- main компенсирует исключение saveAndFlush через прежний адаптер. PR #69 переносит компенсацию на afterCompletion: rollback удаляет новый файл, commit сохраняет, UNKNOWN не удаляет вслепую. Ошибка компенсации наблюдаема и не подменяет первичную ошибку БД.
- Настройки TTL/планировщик пока отсутствуют. Целевой #21 защищает upload/worker/cleanup общим guard одного JVM, сохраняет историю, восстанавливает DELETE_PENDING и сверяет orphan. Долгая внешняя обработка потребует совместимого lease/heartbeat до её подключения.
- Assessment и решение ученика раздельны; переход вопреки REVISE не подтверждает навык. Эти правила ADR-0003 ещё требуют реализации #18/#19/#24.

## 4. Границы работ и проверок

| Объём | Текущее положение |
| --- | --- |
| #20 | Принята, PR #50 merged; повторно не оценивать/разрабатывать |
| #30 | [DEV-05B](DEV-05B-media-storage.md), исходная оценка реализации 8 ч (6–10); код PR #69, независимые проверки 23 PASS / 2 BLOCKED; приёмка владельца остаётся |
| #21 | [TTL](MVP-R5-02-media-retention.md), остаток 20 ч (16–26), без повторного учёта #20/#30; старт после приёмки/merge #30 и сверки main |
| #14/#15/#31/#22/#23 | Методика → эксперименты → применимость → рабочие измерения → объяснение; не считать один PoC несколько раз |
| #18/#19/#24/#51 | Модель/переходы → UI → план → UX-проработка; имеющийся upload не завершает учебный цикл |

Q-001 разрешён: 48 часов после всех проверок, unprocessed 48h, интервал cleanup 60 минут — целевые настройки #21, ещё не работающая очистка. Q-002 частично разрешён лимитами #20; общие квоты/бюджет/неподдерживаемые материалы остаются открытыми. Q-003: IdP/домен/VPS/регион/сессии ещё не выбраны.

Каждая проверка фиксирует ФТ/AC, версию требований, SHA приложения и тестов, среду, фактический результат и границы доказательств. Доступные тесты исполняются по [регламенту](../project/testing-workflow.md). Зелёный CI и закрытая Issue не заменяют неподтверждённую обязательную приёмку.
