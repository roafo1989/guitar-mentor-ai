# MVP-R4-01 — Оценки, история и решение ученика

| Версия | Дата | Изменение |
| --- | --- | --- |
| 1.0 | 20.10.2026 | Актуализирован остаток #18: совместимая модель состояний, REST, транзакции, evidence, миграции и AC. |

## Паспорт, источники и ценность

**Full**: несколько состояний, миграции и конкурирующие команды. Issue [#18](https://github.com/roafo1989/guitar-mentor-ai/issues/18), US-003/#57, EPIC-001/#53, FT-003-01. БТ 0.4 / BT-005/006/007/008; ТЗ 0.9; [ADR-0003](../adr/0003-diagnostic-feedback-and-student-decision.md), [UC-02](../use-cases/UC-02-initial-diagnostics.md). Применимы NFR-001–005/007, SEC-001/005/008; SEC-002 — будущая авторизация #38, а проверка всей цепочки принадлежности нужна уже локально.

Как ученик, я хочу сохранить оценку, замечания и своё решение независимо друг от друга, чтобы повторить упражнение либо закончить работу с замечаниями и продолжить обучение.

## Реализовано и остаток

Срез main `da7488c97136a02ffd69051d04dbea0cd6c0638c`: `DiagnosticSession`, `DiagnosticTask`, версионный `Exercise`, `SubmissionAttempt`, upload/list, технические состояния UPLOADED/PROCESSING/PROCESSED/FAILED, #21 TTL, #16 навыки/criteria и append-only `learner_skill_signal_history` уже есть. `AttemptDtos.from()` возвращает `assessmentAvailable=false`. `DiagnosticStatus` имеет только COLLECTING_EVIDENCE/READY_FOR_TASK/TASK_ASSIGNED; отдельного учебного завершения нет. В V12 предусмотрены критерии и SELF_REPORTED/OBSERVED/MEASURED, но система не получает подтверждённые навыки из анкеты.

Не пересоздавать попытки, профиль, диагностическую сессию, хранилище, TTL, каталог или граф навыков. Добавить состояние работы над назначенным `DiagnosticTask`, неизменяемые версии оценок, результаты критериев и решения ученика; сохранить текущие API. Реальные аудиометрики/допуск метода — #22 после #15/#97/#31, объяснение LLM — #23, интерфейс — #19, последующий план и выдача следующего занятия — #24. Отдельный публичный «кабинет преподавателя» или свободная запись оценок из браузера не вводятся.

Архитектура/данные — [схема](../diagrams/issue18-learning-components.mmd), порядок транзакции — [последовательность](../diagrams/issue18-decision-sequence.mmd). Все новые имена ниже — согласуемый технический контракт этой спецификации, не утверждение о существующих endpoint.

## Раздельные состояния и переходы

| Ось | Значения и правило |
| --- | --- |
| Техническая попытка | Существующие UPLOADED/PROCESSING/PROCESSED/FAILED; PROCESSED означает лишь техническую проверку |
| Оценка критерия | ASSESSED с outcome MEETS/NEEDS_WORK; UNCERTAIN/NOT_ASSESSED с outcome=null и reasonCode |
| Рекомендация оценки | PASS / REVISE / UNCERTAIN; рассчитывается сервером из результатов и применимости, не из текста LLM |
| Работа над заданием | ACTIVE / COMPLETED_PASSED / COMPLETED_BY_LEARNER; хранится отдельно от DiagnosticStatus |
| Команда ученика | CONTINUE_PRACTICING / COMPLETE; фактический результат COMPLETE определяет сервер |

- CONTINUE_PRACTICING сохраняет решение и ACTIVE; новая запись создаётся существующим upload, предыдущие попытки/оценки не стираются. Наличие оценки не обязательно.
- COMPLETE с актуальным PASS по всем обязательным критериям и допустимым источником → COMPLETED_PASSED. Клиент не может прислать/навязать этот итоговый статус.
- COMPLETE после REVISE/UNCERTAIN или без оценки → COMPLETED_BY_LEARNER; сохраняются основание/неоценённые критерии и самостоятельный выбор. Рекомендация не даёт вето на переход.
- Для COMPLETE указывается существующая попытка этого задания; пропуск без единой попытки не вводится молча и остаётся отдельным продуктовым решением. Технический FAILED не превращается в PASS, но сам по себе не превращает решение ученика в подтверждение навыка и не отменяет право самостоятельного завершения.
- Текст причины необязателен; отсутствие текста сохраняется как reasonCode LEARNER_CHOICE, не выдуманная цитата. Оценка/версия, на которую ученик опирался, остаётся зафиксирована.
- Поздняя новая оценка не переписывает прежнее решение/его основания и не превращает COMPLETED_BY_LEARNER в COMPLETED_PASSED. История показывает, что оценка появилась позже. Закрытое задание не открывается GET или поздним worker; повторное открытие требует отдельного сценария, здесь не вводится.
- На terminal-задание новый upload возвращает 409 TASK_COMPLETED. Повтор старого успешного upload/requestId возвращает прежнюю попытку; проверка идемпотентного replay выполняется до запрета новой загрузки. Действующая обработка/TTL уже отправленных записей завершается независимо.

DiagnosticSession не получает фиктивные новые статусы «урок завершён»: TASK_ASSIGNED по-прежнему описывает факт назначения. UI читает отдельный taskState. #24 использует завершение для следующего занятия; автоматическое создание нового задания, перезапуск intake и новый подбор здесь отсутствуют.

## Данные и миграция

Аддитивная миграция с новым свободным номером **после проверки актуального main/открытых PR**; в срезе максимальная V14, номер V15 не резервируется этим анализом. Старые V1–V14 и их checksum не менять.

| Таблица/проекция | Обязательные поля и ограничения |
| --- | --- |
| task_learning_state | task_id UUID PK/FK diagnostic_tasks; state VARCHAR(32) NOT NULL CHECK по трём состояниям; version BIGINT NOT NULL DEFAULT 0; updated_at TIMESTAMPTZ NOT NULL; completed_at nullable, только terminal имеет время завершения |
| task_criterion_snapshots | PK(task_id, criterion_code), FK task; skill_code, label, observable_result, assessment_policy, display_order, captured_at NOT NULL; копия критериев конкретного exercise_id/version, а не ссылка на изменяемый текущий каталог |
| attempt_assessments | id UUID PK; attempt_id FK, revision INTEGER>0, source_operation_id UUID, method_id/version, source_kind, recommendation, created_at NOT NULL; UNIQUE(attempt_id,revision), UNIQUE(attempt_id,source_operation_id); предыдущие версии immutable |
| assessment_criterion_results | PK(assessment_id,criterion_code), FK assessment и проверенная привязка к snapshot задания; status/outcome, reason_code, evidence_text, method/source сведения; outcome только при ASSESSED, доказательство не содержит копию аудио |
| learner_decisions | id UUID PK; task_id и attempt_id FK; assessment_id nullable FK; request_id UUID; normalized_payload_hash; action, resulting_state, reason_code, optional reason_text, created_at; UNIQUE(task_id,request_id) |
| SkillEvidence read model | Связывает сохранённый criterion result с skill_code, assessment/attempt, методом и временем; не заменяет таблицу SELF_REPORTED и не приравнивает один результат критерия к уровню навыка |

Все ID создаются сервером кроме requestId/sourceOperationId; даты серверные UTC, в API ISO-8601. Текст reason_text ограничен 2000 символами по существующей конвенции комментария попытки; SQL/HTML не интерпретируются. Снимок критериев и ACTIVE-backfill создаются для существующих diagnostic_tasks без изменения exercise_id/attempt_number/requestId/медиа. Исторические задания не объявляются пройденными задним числом. При новом назначении snapshot/state записываются в той же транзакции.

Индексы: assessments(attempt_id,revision DESC); decisions(task_id,created_at DESC,id DESC), уникальные ключи выше. Assessment и criteria/decision сохраняются после удаления файла #21, FK не ссылаются на физический путь. Существующие каскады удаления попыток/заданий проверить: cleanup медиа не должен удалять эти таблицы.

### Источник оценки и навыки

Assessment writer — внутренний application service с типизированной командой, а не публичный POST из браузера. Источник HUMAN_REVIEW допускается только как подтверждённый авторизованный внутренний review, VALIDATED_MEASUREMENT — с допуском метода/версии/домена; отсутствие реального провайдера даёт пустой результат, не синтетическую оценку пользователю. DIAGNOSTIC_ONLY/LLM-текст без измерения не допускаются как подтверждённые результаты. DEMO используется только в явно отделённых тестовых fixtures и не записывает подтверждённые навыки обычного ученика.

В #18 SkillEvidence отображает основание и состояние критерия. **Переход, самооценка, один «хороший» критерий и PROCESSED не устанавливают уровень 1–3.** Не вставлять фиктивные MEASURED/OBSERVED level в V12: её CHECK требует level для таких записей, а правило перевода результата в уровень ещё относится к валидированному оцениванию/адаптации #22/#24. Контракт оставляет confirmedSkillLevel=null при отсутствии утверждённого evidence-policy. Самооценка, проверенный критерий и подтверждённый уровень различимы.

## REST-контракт v1 (проект для реализации)

Общий `T=/api/v1/learners/{learnerId}/diagnostic-sessions/{sessionId}/tasks/{taskId}`. Все path ID обязательны, тип UUID; ownership проверяется по learner → instrumentProfile → session → task → attempt/assessment, а не сравнением двух произвольных UUID. UUID не заменяет авторизацию; внешняя публикация до #38 запрещена.

| Метод | Путь | Результат |
| --- | --- | --- |
| GET | T/learning | 200 TaskLearningView, read-only; taskState/version, availableActions, latest decision, состояние наличия оценки; NO_ASSESSMENT — честное значение, не 404 для существующего задания |
| GET | T/attempts/{attemptId}/assessments | 200 {items,nextCursor}; версии оценки, критерии, method/source и ограничения; пустой items, если оценок нет |
| GET | T/decisions | 200 {items,nextCursor}; неизменяемая история решений |
| POST | T/decisions | 201 новая команда / 200 прежний успешный replay; DecisionResult со снимком результата и новой version |

Списки: limit — целое 1–100, default 20; cursor — непрозрачная серверная позиция, проверяется принадлежность текущему ресурсу; порядок assessments по revision DESC, decisions по created_at/id DESC. Неверный cursor/limit → 400 INVALID_PAGINATION. Эти лимиты — техническая граница предлагаемого API, не измеренный SLA. Дополнение AttemptView.assessmentAvailable должно отражать реальное наличие оценки, не менять старые поля/технические статусы.

Обязательная структура read DTO (вложенные объекты типизированы; nullable отмечено явно):

| DTO | Поля |
| --- | --- |
| TaskLearningView | taskId UUID; taskState enum; version integer>=0; availableActions массив команд (пустой для terminal); latestAttemptId UUID/null; assessmentState NO_ATTEMPT/NO_ASSESSMENT/AVAILABLE; latestAssessmentId UUID/null; latestDecision DecisionView/null; nextStepAvailability NOT_IMPLEMENTED |
| AssessmentView | id/attemptId UUID; revision integer>0; sourceKind HUMAN_REVIEW/VALIDATED_MEASUREMENT; methodId/methodVersion string; recommendation enum; createdAt UTC; criteria массив CriterionResultView; limitations массив строк |
| CriterionResultView | criterionCode/skillCode/label/observableResult из snapshot; status enum; outcome enum/null; reasonCode string/null; evidenceText string/null; confirmedSkillLevel integer 1–3/null (в этом инкременте null без утверждённого policy) |
| DecisionView | decisionId/taskId/attemptId UUID; assessmentId UUID/null; action enum; resultingState enum; resultingVersion integer>=0; reasonCode string; reasonText string/null; recordedAt UTC |

В TaskLearningView latest assessment относится к последней попытке по существующему attemptNumber; пустая оценка новой попытки не заменяется PASS старой. Для решения по иной выбранной попытке клиент читает её assessments и передаёт её baseAssessmentId. PASS допустим лишь при непустом наборе обязательных критериев, когда все они ASSESSED/MEETS с допустимым источником; хотя бы один допустимый NEEDS_WORK даёт REVISE; иначе UNCERTAIN. Отсутствующий или неоцениваемый обязательный критерий исключает PASS. Метод не может повысить recommendation произвольным текстом.

Тело POST (все поля, кроме reason, обязательны; baseAssessmentId допускает null при показанном отсутствии оценки):

```json
{
  "requestId": "06a8f8f4-827f-4eaa-bdb0-545b17820fa1",
  "attemptId": "743f59b2-43bb-4907-a9e4-e45d615d9f3b",
  "action": "COMPLETE",
  "expectedVersion": 0,
  "baseAssessmentId": null,
  "reason": "Продолжу и вернусь к этому позже"
}
```

`expectedVersion` — целое >=0 из TaskLearningView; action только из allow-list; reason string/null, trim, <=2000. taskId в body не дублируется. Result: `decisionId` UUID, `taskId` UUID, `taskState` enum, `version` integer, `assessmentId` UUID/null, `recordedAt` ISO timestamp; `nextStepAvailability=NOT_IMPLEMENTED` до готовности #24. Нельзя возвращать выдуманный URL следующего занятия.

Ошибки имеют безопасный JSON `{code}`: 400 INVALID_DECISION/REASON_TOO_LONG; 404 RESOURCE_NOT_FOUND для отсутствующей/чужой цепочки без утечки наличия; 409 VERSION_CONFLICT/ASSESSMENT_CHANGED/IDEMPOTENCY_CONFLICT/TASK_COMPLETED/NO_ATTEMPT; 503 LEARNING_DISABLED. Ошибка не записывает частичное решение и не закрывает задание. Непредвиденный 5xx не выдаёт traceback/пути/текст записи.

## Транзакции и конкуренция

На POST сначала ownership, затем блокировка session → task_learning_state, в том же порядке, что существующая запись попыток. Проверить успешный replay по requestId и payload hash **до** проверки изменившейся version/terminal. Равный payload → прежний DecisionResult без повторного изменения; другой payload того же ключа → 409. Для новой команды проверить version и что baseAssessmentId всё ещё соответствует показанной актуальной оценке выбранной попытки (включая null → появилась оценка). При устаревании вернуть 409, UI перечитывает данные; решение не принимается на основании неожиданно другой оценки.

В одной транзакции: проверка допустимости → insert immutable decision → update state/version → commit. Две конкурирующие новые команды одной version дают один успех и один 409. Assessment writer использует тот же порядок блокировок, отдельную дедупликацию sourceOperationId и создаёт новую revision; не обновляет старую и не переписывает решение. Откат транзакции не оставляет decision без соответствующего state. Подключение evidence и updated assessmentAvailable тестируются без копирования медиа.

## Настройки, наблюдаемость и откат

Предлагаемый switch `guitarmentor.learning.enabled`, boolean, default false, конфигурация приложения/локального deployment, изменение после restart. При false новые learning endpoints возвращают 503 LEARNING_DISABLED; старые profile/catalog/upload/list/cleanup работают. Включение на локальном QA после миграции, перед #19. Нет новых credentials или внешнего API. Логи — task/decision ID, version, transition и reasonCode; не reason_text, аудио, заметки оценщика или личные ответы.

Выключение switch останавливает новые learning-команды, но не снимает уже действующий terminal-guard с upload: закрытые задания остаются закрытыми. Для старых ACTIVE-заданий upload доступен по прежним правилам.

Smoke: новая/существующая БД, старый task/attempt сохранён, GET без оценки, controlled test assessment, REVISE → COMPLETE с сохранёнными замечаниями, replay, конфликт, TTL удаления исходника при доступной истории. Test assessment подставляется тестовой инфраструктурой, не публичным незащищённым route. При сбое переходов/потере истории владелец установки отключает learning, останавливает новые команды; если возвращается прежний backend, временно выключает upload, чтобы старый код не добавлял попытки в уже закрытые задания. Аддитивные таблицы и историю не удалять, Flyway вниз автоматически не запускать; восстановление БД — только из реального согласованного бэкапа. Уже удалённое #21 аудио откат не возвращает.

## Критерии приёмки

- **AC18-01:** Дано: существующие task/attempt. Когда: применена миграция. Тогда: ID/версии/история/медиа сохранены, progress ACTIVE, нет задним числом подтверждённых навыков.
- **AC18-02:** Дано: PROCESSED без музыкального провайдера. Тогда: NO_ASSESSMENT и assessmentAvailable=false, без PASS или баллов.
- **AC18-03:** Дано: две оценки одной попытки. Когда: записана новая revision. Тогда: обе читаются с критериями/методом, исходная не перезаписана.
- **AC18-04:** Дано: REVISE с незакрытым критерием. Когда: COMPLETE. Тогда: COMPLETED_BY_LEARNER, основание и замечания сохранены, level навыка не повышен.
- **AC18-05:** Дано: допустимый актуальный PASS всех обязательных критериев. Когда: COMPLETE. Тогда: COMPLETED_PASSED определён сервером, а не body клиента.
- **AC18-06:** Дано: попытка без оценки. Когда: COMPLETE. Тогда: самостоятельное завершение допустимо, NOT_ASSESSED не превращается в успех измерения; отсутствие попытки даёт NO_ATTEMPT.
- **AC18-07:** Дано: CONTINUE_PRACTICING. Когда: записана следующая попытка. Тогда: задача ACTIVE, прежняя история не исчезла.
- **AC18-08:** Дано: повтор requestId. Тогда: равный payload возвращает прежний результат, другой — 409, без дублей.
- **AC18-09:** Дано: две команды либо новая оценка между чтением и решением. Тогда: version/baseAssessmentId защищают основание, конфликт не оставляет частичную запись.
- **AC18-10:** Дано: чужой learner/session/task/attempt/assessment. Тогда: 404, нет доступа и побочного изменения.
- **AC18-11:** Дано: истёк TTL медиа. Тогда: оценки/решения/критерии и источник остаются доступны, отсутствующий файл не возвращается.
- **AC18-12:** Дано: terminal task и replay прежней загрузки. Тогда: replay безопасен; новая загрузка отклонена; поздняя оценка не меняет факт прежнего завершения.
- **AC18-13:** Дано: malformed source/criterion, текст LLM или DIAGNOSTIC_ONLY. Тогда: нет подтверждённого результата/уровня; подтверждение не создаётся клиентской командой.
- **AC18-14:** Дано: flag disabled / ошибка транзакции. Тогда: старые сценарии работают, новые команды управляемо недоступны, нет частичной истории.

## Готовность и следующий шаг

Анализ выполнен, контракт #19 определён. Для реализации доменной модели и тестов не нужно ждать музыкального PoC: отсутствие провайдера — штатное состояние. Полная продуктовая оценка/следующее занятие всё ещё зависят от #22/#23/#24; это не повод объявлять всю US-003 готовой. Следует переоценить оставшийся объём и рассмотреть пакеты: schema/snapshots; assessment history/internal writer; decisions/concurrency; evidence/API/regression. Прежние 32 ч — исторический ориентир до #16/#21/#68, не новая оценка. Декомпозиция на новые карточки не выполнена. Продуктовый код и runtime-тесты в текущем анализе не менялись/не выполнялись.
