# LESSON-NEXT-01 — ручной выбор следующего занятия

| Версия | Дата | Изменение |
| --- | --- | --- |
| 1.0 | 25.10.2026 | По решению владельца из #24 выделен самостоятельный Java/Vue инкремент #216 без ИИ. |

**Full · FT-003-11 · #216 → US-003/#57 → EPIC-001/#53.** БТ 0.22: BT-006/007/012, AC-MAN-01…06. ТЗ 0.32. Объём согласован фразой «да, давай так сделай» после предложения ручного выбора с сохранением истории. Технические решения ниже — проектирование СА. Это целевой контракт, **не существующие endpoint и не результат тестирования**.

## 1. Проблема, результат и границы

После самостоятельного завершения пользователь остаётся на старом задании и не может выбрать другое. Завершение учебного task уже работает; nextStepAvailability в Java/Vue равен NOT_IMPLEMENTED. Требуется следующий доступный шаг без музыкального провайдера, LLM и автоматической программы.

Завершённое занятие → «Выбрать следующее задание» → каталог → preview → «Начать занятие» → новый task. Старое занятие, версии материалов, решения, попытки, оценки и замечания доступны в истории. Переход не подтверждает навык и не изменяет COMPLETED_BY_LEARNER на PASSED.

Входит: ручной каталог существующих Exercise, атомарный переход, краткая история занятий, восстановление текущего, просмотр старого. Не входят: #24 персональные рекомендации/ранжирование/LLM, #22/#23 оценка аудио, новый контент, расписание, доступ к metadata originals #196, превращение #163/#195 или общей пользовательской библиотеки в назначаемые упражнения. #211/#212 не реализуются повторно.

## 2. Фактическая база и архитектурное решение

Проверен main **c4bb865dc91db1b7c06ba29d7058cc1f32019d87**, включая PR214/#211 и PR213/#212. Перед разработкой перечитать latest main, особенно параллельный #165; docs branch содержит более старую runtime-базу и не служит стартом кода.

- V5: diagnostic_tasks.diagnostic_session_id UNIQUE, один task на diagnostic session; active session index также включает TASK_ASSIGNED.
- LearningService хранит отдельный task_learning_state: ACTIVE / COMPLETED_PASSED / COMPLETED_BY_LEARNER; result.nextStepAvailability = NOT_IMPLEMENTED.
- DiagnosticTaskService.assign не заменяет уже назначенное упражнение. DiagnosticSessionService.start/current возвращают существующую активную сессию.
- #211 добавил read-only current и общий resolver frontend. #212 стабилизировал learner hydration; сохранить guards actor/session.
- ExercisePublicationPolicy и ExerciseCatalogService уже ограничивают допустимый каталог и версии. Существуют preview и exact version resources.

**Решение:** не снимать UNIQUE task/session и не перепривязывать attempts. Для следующего занятия создать новую сессию с новым task; старую сессию перевести в новый технический статус ARCHIVED только в транзакции успешного перехода. Он означает «есть следующее занятие», а не музыкальную оценку. Текущий завершённый task до перехода остаётся в TASK_ASSIGNED session и доступен как current.

Обоснование и альтернативы: [ADR0005](../adr/0005-manual-lesson-transition.md). [Последовательность](../diagrams/manual-next-lesson-sequence.mmd).

## 3. Пользовательский сценарий и интерфейс

1. На существующем workspace при свежем terminal learning state рядом с итоговым статусом показать основной CTA **«Выбрать следующее задание»**. История попыток и результаты остаются ниже. Пока state загружается/ошибочен, не разрешать создание следующего.
2. Для ACTIVE оставить текущий режим практики и самостоятельного завершения. Нельзя незаметно заменить активное задание; новое занятие разрешается после существующего COMPLETE. Никакой автозаписи COMPLETE по открытию picker.
3. Picker — отдельный route `/lessons/choose?from=<sessionId>` с контекстом завершённого занятия; authenticated через существующий AuthGate. F5 восстанавливает исходную сессию и её статус сервером, не по состоянию кнопки. Вход из Home для terminal current ведёт сюда с подписью «Выбрать следующее задание», а завершённое занятие остаётся по ссылке «Посмотреть результат».
4. Заголовок «Следующее занятие», краткий контекст предыдущего. Список карточек существующих упражнений; локальный поиск по title/code без сетевого запроса на каждую букву, без нечёткого/семантического обещания. Порядок стабильный code/version; пагинация UI по 20, если каталог больше страницы. Нет бесконечного append DOM.
5. В карточке: title, реальная цель, difficulty, meter, BPM с tempoUnit, рекомендуемая длительность только если имеется. Пустое nullable поле скрывается/«не указано», не выдумывается. Preview PDF/аудио существующими компонентами; PDF изначально свёрнут, новый музыкальный renderer не нужен.
6. Кнопка «Начать занятие» явно подтверждает выбор; для того же code/version — «Начать заново» и пояснение, что прежние результаты сохранятся. Повтор разрешён. Это не «переоткрыть» старый task. Отмена/Back возвращают предыдущее занятие без записей в БД.
7. Только LEARNING_REPERTOIRE подходящего instrument/technique, is_current и eligibility. DIAGNOSTIC_PROBE не выдавать как новый учебный репертуар. Отметки предпочтений EXCLUDE не использовать как автоматическое ранжирование: это ручной выбор, не рекомендация. Не расширять допускающие права каталога. Будущие рекомендации #24 обязаны учитывать EXCLUDE отдельно.
8. При пустом каталоге — «Пока нет доступных учебных заданий», retry и возврат; при отсутствии совпадений поиска — отдельное состояние со сбросом поиска. Не создавать макетные задания. Маленький реальный каталог не блокирует поставку: повтор доступного учебного упражнения допустим, наличие разных новых произведений не обещается.
9. После POST открыть текущий workspace; новый task ACTIVE, без попыток, старая история по прежнему task. Остановить preview/метроном и отпустить audio resources при переходе. Не переносить recording draft или файл предыдущего задания.
10. В области занятия добавить вторичную ссылку **«История занятий»** → `/lessons`. Таблица/список: title, версия, дата назначения, реальный статус; кнопка «Открыть». В header оставить существующее «Занятие», не плодить основные разделы. Историю показывать страницами по 20.
11. `/lessons/:sessionId` открывает выбранное занятие существующим workspace. Для ARCHIVED — read-only, с заметным «Завершённое занятие» и «Вернуться к текущему». Видны старые материалы/attempts/results/decisions. Не менять current/local selected key при чтении истории. Для текущего terminal занятия можно показать обычный CTA; для старого с successor — «Перейти к следующему занятию» по сохранённой связи, без нового POST.
12. Light Minimal tokens и существующий shell; RU/EN, 360px и 200% zoom, keyboard/focus. По завершении navigation фокус на heading. Pending кнопки disabled, status/alert читаемы screen reader. Не показывать «автоматическая выдача недоступна» как единственный выход: автоматический подбор остаётся недоступен, ручной CTA работает отдельно.

## 4. Данные, совместимость и миграция

Новая аддитивная Flyway migration, номер выбирается по latest main. Применённые V4/V5/V15 и исторические строки не переписываются.

- Расширить check constraint/enum DiagnosticStatus значением **ARCHIVED**. UNIQUE diagnostic_tasks(session) сохранить; active index исключает ARCHIVED и остаётся прежним для остальных статусов.
- Таблица `lesson_transitions`: `id uuid PK`, `learner_id FK`, `instrument_profile_id FK`, `request_id uuid`, `source_task_id FK UNIQUE`, `target_task_id FK UNIQUE`, `requested_exercise_code`, `requested_exercise_version`, `expected_source_version bigint`, `created_at timestamptz`; UNIQUE(learner_id, request_id). Source/target task подразумевают их session; в сервисе проверить одного владельца и профиль. Ни source, ни target не удалять/заменять.
- Transition хранит canonical request payload и оба результата для безопасного replay после рестарта. Одна source task имеет только одного successor. Повтор другой requestId с точно тем же source+payload возвращает существующий результат; другой выбор — конфликт, не ещё одна ветка.
- Старый task_learning_state, attempts, assessments, criterion snapshots, evidence и решения не изменяются переходом. Новому task выполнить существующий LearningRepository.initialize, новые критерии по выбранной версии. SkillEvidence не копируется как подтверждение нового task.
- Новая session сразу получает профиль текущего инструмента и TASK_ASSIGNED в той же транзакции, без повторной анкеты/intake/диагностики. Не копировать diagnostic evidence в новую session. Создание через внутренние domain operations, не обход публичных guards старого start.
- `SessionView.availableActions` для ARCHIVED — VIEW_TASK. Расширить все Java switch, TS enum/validators, маршруты и template guards; ARCHIVED с task отображается как история. Не отдавать клиенту неизвестный ему status без совместимого frontend.
- LearningView/DecisionResult.nextStepAvailability расширить: NOT_IMPLEMENTED (feature off), MANUAL_SELECTION (terminal current без successor), SUCCESSOR_EXISTS (есть transition). ACTIVE остаётся NOT_IMPLEMENTED с отсутствующим разрешением next. Отдельное optional `nextLesson` с `{sessionId,taskId}` только для SUCCESSOR_EXISTS; оба DTO одинаково определяют availability. availableActions решения COMPLETE/CONTINUE_PRACTICING не подменять командой next.
- `current` #211 должен возвращать единственную не-ARCHIVED session. Scoped browser key может указывать на старую ARCHIVED: resolver текущего обязан прочитать server current и заменить key только после успешной верификации. Ошибка current не означает отсутствие и не запускает Start. Explicit history route этот key не трогает.

## 5. REST-контракт

Все новые персональные ответы `Cache-Control: no-store`, тот же session auth/CSRF и server-side ownership. Account/role из body не принимать. 401 без actor; чужой learner/session/task → существующий безопасный отказ ActorFilter/404 ресурса, без данных о владельце. DTO не включают raw fs paths, токены или медиа bytes.

### 5.1 Каталог

Переиспользовать GET `/api/v1/exercises?instrumentCode=ACOUSTIC_GUITAR&techniqueCode=FINGERSTYLE`, существующий ExerciseView и version-pinned resources. UI фильтрует LEARNING_REPERTOIRE; сервер повторно проверяет тип/профиль/eligibility при POST. Не менять существующую выдачу диагностических probes другим клиентам. Нет зависимости от shared library #165/#168.

### 5.2 Создание следующего

`POST /api/v1/learners/{learnerId}/diagnostic-sessions/{sessionId}/tasks/{taskId}/next-lesson`

```json
{
  "requestId": "UUID",
  "expectedVersion": 3,
  "exerciseCode": "GM-EB01",
  "exerciseVersion": "0.8B"
}
```

requestId UUID; expectedVersion безопасное целое >=0 из свежего LearningView; code/version валидируются существующим ExerciseVersionResourceService, длины БД 50/20. Не принимать произвольные URI. Неизвестные поля не управляют ownership/state. JSON > установленного server request limit отклоняется; больших объектов/медиа здесь нет.

201 и Location нового session при создании; 200 при exact replay. Ответ `{transitionId, sourceSessionId, sourceTaskId, session: SessionView, task: TaskView}` с совпадающими task.sessionId/session.id. Идемпотентность долговечна, не in-memory cache. Replay возвращает те же ID, даже если следующее занятие позднее завершено/архивировано; клиент после ответа читает server current, не объявляет старый replay result новым текущим.

| HTTP / code | Смысл и действие UI |
| --- | --- |
| 400 INVALID_NEXT_LESSON_REQUEST | Неверные UUID/поля/версия/размер. Данные занятия не меняются. |
| 404 RESOURCE_NOT_FOUND | Нет принадлежащего source ресурса либо выбранное упражнение не разрешено/не существует. |
| 409 SOURCE_TASK_NOT_COMPLETED | Source ACTIVE; обновить состояние и вернуться к практике. |
| 409 LEARNING_VERSION_CONFLICT | expectedVersion устарела; перечитать state, выбор не отправлять автоматически. |
| 409 EXERCISE_VERSION_CHANGED | code существует, но current version уже другая; перечитать preview и явно выбрать снова. |
| 409 NEXT_LESSON_ALREADY_EXISTS | Другая цель уже создана для source; показать переход к фактическому successor/current. |
| 409 IDEMPOTENCY_KEY_REUSED | requestId уже связан с другим canonical payload; не создавать новый key для обхода. |
| 503 LEARNING_DISABLED | Существующий learning.enabled=false; CTA недоступен с честным объяснением. |
| Network/5xx/invalid response | Результат не определён; безопасный явный retry с тем же requestId/payload. Не считать, что transaction не состоялась. |

Проверять ownership до replay. Exact replay существующего transition допустим после проверки владельца до проверки изменившегося current/version; иначе потерянный ответ не восстановится. При изменении выбора после неопределённого исхода сначала перечитать successor/current. Все конфликты оставляют old history неизменной.

### 5.3 История

`GET /api/v1/learners/{learnerId}/lessons?instrumentCode=ACOUSTIC_GUITAR&limit=20&cursor=...`

`{items:[{sessionId,taskId,exerciseCode,exerciseVersion,title,assignedAt,taskState,isCurrent}],nextCursor:string|null}`. Только реально назначенные tasks данного владельца/профиля, включая существующие до migration. No total. limit1…50, default20; cursor max512, opaque base64url с owner/profile/order tuple, строгая проверка. Keyset `(assigned_at DESC,task_id DESC)`, limit+1, запрос всегда owner-scoped независимо от cursor. Защита cursor не заменяет ownership. Нет N+1 по task/state/exercise. Nullable/missing state — честное «Статус недоступен», не ACTIVE/PASS по умолчанию.

Открытие строки использует существующие scoped session/task/learning/attempt endpoints; GET session доступен и для ARCHIVED. В response learning.nextLesson вернуть successor при наличии. Сам просмотр никогда не создаёт transition. Error cursor400 локально сбрасывается только по явному действию «Начать список сначала».

## 6. Транзакции, гонки и безопасность

Порядок для next: **learner row → owned source session → source learning state**. Существующие writers session→state сохраняют порядок; они не должны затем запрашивать learner lock. Перед добавлением проверить весь lock graph upload/decisions/start/next и PostgreSQL concurrency test. Выбор next сериализован с обычным start по learner lock; не создавать вторую active session в обход UNIQUE.

1. Проверить feature/actor/ownership, durable request replay и существующий source successor.
2. Под locks проверить current source, task terminal, expectedVersion, instrument/technique и выбранную current eligible LEARNING_REPERTOIRE версию. Не требовать наличия PASSED assessment: COMPLETED_BY_LEARNER достаточно.
3. Обновить source session в ARCHIVED и flush до вставки нового active session (из-за immediate unique index). Создать session/task, initialize learning, persist transition. Любой сбой откатывает весь шаг, включая ARCHIVED.
4. Ответ только после commit. После потерянного HTTP-response exact retry читает transition. Две вкладки: одна создаёт successor, вторая получает replay либо конфликт с ссылкой на созданное занятие; вторая ветка невозможна.
5. Одновременный COMPLETE/next/upload: same session/state locks; active upload не переносить. При terminal task новые попытки остаются запрещены как сейчас. Ни одного UPDATE attempt.task_id/source exercise_id.

Frontend: захватывать actor object/session generation, source task и request sequence; проверять success/error/finally, unmount/Back/actor switch. Abort GET при уходе; POST abort не доказывает rollback. Pending requestId хранится только в памяти текущего контекста до определённого исхода. После F5 потерянного POST source successor и server current позволяют восстановиться без нового создания. Без PII/query/body/tokens в логах/analytics/storage; обычные opaque route resource IDs допускаются, но не становятся доказательством доступа.

## 7. Приёмка

| AC | Проверяемый результат | TC |
| --- | --- | --- |
| AC-NEXT-01 | После обоих terminal states есть ручной CTA на workspace и Home; ACTIVE не заменяется | 01,02 |
| AC-NEXT-02 | Каталог/поиск/preview показывают только реальные подходящие учебные материалы; cancel без записи | 03,04,05 |
| AC-NEXT-03 | Явный выбор создаёт новый pinned task/session без intake; повтор упражнения допустим | 06,07 |
| AC-NEXT-04 | Старые решения/замечания/попытки/оценки остаются на месте, не выдаются за PASS | 08,09 |
| AC-NEXT-05 | Двойные/параллельные/потерянные запросы и откат не создают дублей | 10,11,12,13 |
| AC-NEXT-06 | История ограниченными страницами открывает старое занятие, не меняя current | 14,15 |
| AC-NEXT-07 | F5/login/current/key recovery корректны; поздний replay не возвращает current назад | 16,17 |
| AC-NEXT-08 | Версии/права/ошибки/feature flag не обходят серверные правила | 18,19,20 |
| AC-NEXT-09 | Actor switch, RU/EN, mobile/keyboard/audio lifecycle и регрессия защищены | 21,22,23 |
| AC-NEXT-10 | Миграция, совместимая поставка и откат проверены на PostgreSQL и runtime | 24 |

Все TC: [план](../testing/TC-LESSON-NEXT-01.md), **NOT_RUN**. Аналитическая сверка не независимое QA и не пользовательская приёмка.

## 8. Поставка, эксплуатация и откат

Порядок: (1) Java migration/next/history/idempotency tests; (2) Vue routes/picker/history/current recovery/DTO и mounted regression; (3) CI Java+PostgreSQL, Vue default и Light Minimal builds, текущие auth/library/media/learning tests, браузерный путь complete→choose→new→history; (4) PR, тестирование, приёмка. Ссылаться Refs #216, без premature auto-close. Оценка/декомпозиция до кода, не начинать реализацию этим документом.

Новый параметр конфигурации не нужен: reuse learning.enabled. ИИ/SMTP/S3 keys для перехода не требуются. Для production поставлять backend/frontend согласованным релизом: старый frontend не понимает ARCHIVED/new next availability.

Перед первым реальным переходом сделать согласованную backup PostgreSQL. После появления ARCHIVED/transition **нельзя просто откатить backend к старому enum**. Предпочтение — forward fix. Для emergency rollback: остановить writes, оставить совместимую модель/чтение ARCHIVED и отключить learning writes существующим flag либо вернуть согласованный backup с явно принятой потерей последующих записей. Не удалять новые занятия ради технического downgrade. Чистый rollback без данных проверяется отдельно на тестовой БД; миграции не удаляются/не редактируются после применения.

Открытых блокирующих продуктовых вопросов нет. Предварительно 8–12 ч, средняя уверенность; подтвердить остаток и возможное разделение Java/Vue на этапе оценки. Исторические 24 ч #24 не переносить сюда и не вычитать произвольное число: остаток персонального планирования переоценивается перед возобновлением.
