# UX-02-05 / #152 — реальные результаты и финальная проверка Light Minimal

Версия1.0 ·25.10.2026 · Standard · Фронтенд · FT-016-05 / US016/#147 → #53. БТ0.21/BT-010/011/012/015/016, ТЗ0.30, AC-LM-11/12 → AC-152-01…07. База main4426becd8aa737903ff79d46720703cf5dd115a4. Standard сохранён: существующие API/команды/права не меняются, дорабатываются представление/typed UI events и интеграционная проверка. Самопроверка СА не независимое QA.

## Факт и остаток

Как ученик, я хочу понимать, обработан ли файл, есть ли оценка и почему задание завершено. Принятая150 уже дала отдельную result-card, кнопку refresh и историю;151 не должна дублировать их,152 не строит новый scoring/AI. Источник истины — LearningResultsPanel со state/assessments/decisions/evidence и guard-ами125–127, не новый fetch-loop в sidebar/DiagnosticView.

В текущем main task banner содержит постоянный in-progress; sourceKind выводится сырым кодом, дата части истории через browser default locale. Глобальный assessmentState относится к latestAttempt, а пользователь может выбрать другой pinned attempt; эти уровни нельзя смешивать. Дополнить обработку initial/empty/error/stale/unknown и согласовать summary/banner с фактическими DTO. Существующие decision version/baseAssessmentId/requestId и выбранная попытка сохраняются.

## Зависимости, границы и этап

148/149/150 closed/completed,189 merged. **Разработка/Ожидание151 и финального candidate с198.** Реализацию ограниченного results mapping и подготовку fixtures можно делать после151 без ожидания окончания независимой198; закрытие152 и общего прогона147 требует интегрированных151+198, проверки exact candidate SHA и приёмки владельца. Не считать visual/auth acceptance198 автоматически пройденной в152 и наоборот. Одна основная разработка за раз.

Нет новых API, БД, assessment writer/провайдеров, автоматического плана24/ИИ, выдуманных процентов/streak/recommendations, изменения формы198 или composer/library. Контент музыкальных PDF не переделывается.151 отвечает media/draft/mic/metronome,198 — auth/brand и свой24TC;152 выполняет **интеграционные smoke этих частей**, а не повторно реализует их/исполняет каждую чужую матрицу на каждом viewport.

## Модель представления и тексты

| Фактическое состояние | RU / EN и действия |
| --- | --- |
|Initial GET pending, данных нет|«Загружаем результат…» / “Loading results…”; role=status. Нет преждевременного «оценки нет»|
|GET failed до данных|«Не удалось загрузить результат» / “Could not load results”, retry; не пустое/успешное состояние|
|Ранее загружено, refresh/detail/assessment failed|Сохранить явно устаревший контекст с «Не удалось обновить» / “Could not refresh”; state/detail/assessment fresh guards отключают решение; не выдать stale за authoritative|
|taskState ACTIVE|«В работе» / “In progress”; только допустимые server actions|
|COMPLETED_PASSED|«Завершено по результатам оценки» / “Completed based on assessment”; это не 100% всех навыков|
|COMPLETED_BY_LEARNER|«Завершено по вашему решению» / “Completed by your choice”; сохранить замечания/непроверенные навыки|
|latest NO_ATTEMPT и selection пуста|«Пока нет отправленных записей» / “No recordings submitted yet”; в допустимом состоянии можно записать/отправить|
|Selected UPLOADED/PROCESSING|«Файл обрабатывается» / “File processing”; не skill/recommendation PASS|
|Selected FAILED|«Не удалось обработать файл» / “File processing failed”; локализованная техническая причина/существующая история|
|Selected PROCESSED, assessment list успешно пуста|«Файл обработан. Музыкальной оценки пока нет» / “File processed. Musical assessment is not available yet”; отсутствие оценки не запрещает разрешённый backend COMPLETE|
|Selected assessment exists|Показать assessment именно selected attempt: recommendation,criteria,method/version/date,limitations. Не заимствовать latest assessment другого attempt|
|Unknown/malformed state|«Статус недоступен» / “Status unavailable”, retry; unknown не PASS/terminal по произвольному `!==ACTIVE`, не основание показать/запустить действие|

`TaskLearningView.assessmentState/latestAssessmentId` — summary **latestAttemptId**, не выбранной off-page попытки. Для выбранного attempt использовать successful authoritative detail + его assessments; при отсутствии fresh GET не интерпретировать пустой массив как NO_ASSESSMENT. Верхний summary о latest можно оставить только с явной подписью «Последняя отправка» и без смешения с selected result. Ни root/module, ни sidebar не запускают самостоятельный business request.

Рекомендации: PASS → «Критерии выбранной оценки выполнены» / “Assessment criteria met”; REVISE → «Есть над чем поработать» / “More practice recommended”; UNCERTAIN → «Для оценки недостаточно данных» / “Not enough evidence to assess”. Отображать реальное значение и его основания, не считать весь профиль прошедшим по одной рекомендации. Unknown recommendation/outcome/status → нейтрально, а не ternary PASS. Статусы criterion ASSESSED/UNCERTAIN/NOT_ASSESSED и outcome MEETS/NEEDS_WORK/null различаются; confirmedSkillLevel только из DTO: null=нет подтверждения,0 допустимое полученное число, не default всем навыкам.

Source HUMAN_REVIEW → «Ручная оценка» / “Human review”; VALIDATED_MEASUREMENT → «Проверенные измерения» / “Validated measurement”; unknown → «Источник оценки не определён» / “Assessment source unknown”. Нельзя называть любой sourceKind «ИИ». Показывать methodId/methodVersion/createdAt и limitations как provenance, без proof/token/внутренних credentials; неизвестный code допустим только escaped/plain-text техдеталь, не HTML. Дата форматируется текущим locale приложения через Intl, invalid date — neutral placeholder; часовой пояс браузера без нового выдуманного server timezone.

## Передача статуса в banner без второго owner

Сохранить events `selected-id/latest-id/decision-busy/terminal-state`. Добавить из LearningResultsPanel `learning-state:{learnerId,sessionId,taskId,taskState:TaskState|null,fresh:boolean}`; SubmissionPanel переизлучает в DiagnosticView. На loading/error без актуальных данных — null/freshfalse; при refresh ранее видимые данные могут остаться stale, но eventfreshfalse не разрешает действия. После success — точный status. Parent принимает только совпадающий K; отображает banner из этого state, а не неизменное «В работе». Для unknown/null neutral, без обновления private context чужим late event. Это UI event, не новый domain event/API/table; возвращение fullscreen/resize не создаёт второй LearningResultsPanel.

## Решение ученика, конфликты и история

Backend remains authority: availableActions + ACTIVE + свежие task/detail/selected assessment + отсутствие busy draft/pending/sending задают доступность. При допустимом отсутствии оценки/UNCERTAIN/REVISE ученик может COMPLETE по своему решению; не добавлять veto агента, обязательный PASS или auto-confirm skill. Сохранить существующее подтверждение COMPLETE, reason≤2000, frozen command `{requestId,attemptId,action,expectedVersion,baseAssessmentId,reason}`. Пока pending — не менять selected/command/reason в retry.

Decision success201 (или200 replay) → refresh authoritative state, история/замечания остаются.409 VERSION_CONFLICT/ASSESSMENT_CHANGED и другие существующие conflict codes → снять pending, reread, показать конфликт, **не повторять COMPLETE автоматически**. Network/5xx → manual retry того же frozen command/requestId, не новый UUID. Old response/finally после K/actor change не меняет новый sending/selection/banner. `COMPLETED_BY_LEARNER` не меняет SkillEvidence и не удаляет предыдущие assessments.

Следующее занятие: nextStepAvailability=NOT_IMPLEMENTED → «Следующее занятие пока недоступно» / “Next lesson is not available yet”, без активного auto-next/POST. Это ограничение не прячет history/существующую Home navigation. После terminal upload закрыт по прежнему backend; no hidden new lesson/AI calls.

## REST и НФТ

Reuse B=`/api/v1/learners/{learnerId}/diagnostic-sessions/{sessionId}/tasks/{taskId}`, UUID path обязательные/owned. GET B/learning → TaskLearningView, GET B/attempts/{attemptId}/assessments?cursor=… → Page<AssessmentView>, GET B/learning/evidence?cursor=… → Page<SkillEvidenceView>, GET B/decisions?cursor=… → Page<DecisionView>;200/items/nextCursor. GET B/attempts/{attemptId} → authoritative detail. POST B/decisions JSON:requestId/attemptId UUID,action COMPLETE|CONTINUE_PRACTICING,expectedVersion:number,baseAssessmentId UUID|null,reason:string|null≤2000;201 new/200 replay,409 conflict,401/403/404/5xx/network safe messages. Exact DTOs — existing learning.service.ts и Java LearningController; не добавлять percentages/source inference fields. New migrations/config/events/storage не нужны.

NFR-001/002/003/004/007/008, SEC-002/003/005/006: one owner, bounded history/selected pinned, actor cleanup/CSRF, без приватного контента в logs/URL/storage и без v-html дляAPI. Existing getter refresh не расширяется polling-петлёй sidebar. Dates/labels/RUEN/keyboard/reduced-motion, text contrast≥4.5 и focus≥3, targets≥44px; stale/error/uncertainty различимы без цвета. Общая архитектура неизменна; схема [gates](../diagrams/ux02-final-acceptance-gates.mmd) отражает интеграцию, отдельная схема БД не требуется.

## Интеграционный candidate и проверка

Фиксировать appSHA (с151 и198), doc/designSHA, frontend flags, backend version/config и подготовленные fixtures. Свободный email/Google, checkmail/verify/reset, brand/favicon после198; Home/profile/onboarding;3diagnostic states; assigned media/draft, error/retry, actual results/history/decision; global/inline metronome, tuner, score fullscreen. Full24TC198 — evidence её поставки;152 имеет отдельные auth-integration smoke на общем SHA. Runtime screenshot сам по себе не подтверждает physical mic, Google exchange или музыкальную оценку.

Матрица:1440×900/1180×820/1024×768/768×1024/360×800/landscape800×360, RU/EN;320 CSSpx reflow и200%browser zoom на критических узких формах; keyboard и reduced-motion. Full results state matrix автоматизировать на базовом desktop в обоих языках; representative ready/empty/error/pending+Stop на mobile/landscape/zoom. Не обещать Cartesian product каждого TC со всеми размерами/браузерами в8ч. Chrome/Edge Windows — целевые реальные проверки; Safari/physical mobile, если среды нет, отмечаются NOT_RUN/BLOCKED, не выдумываются. Mock assessments разрешены только в изолированных fixtures, не product seed/фальшивый success.

Существующие26TC-LM сохраняют ID и повторно исполняются по относящемуся diff; [дополнительные12TC-152](../testing/TC-UX02-media-results-refresh.md) дополняют mapping/конфликты/gates. Доказательства: SHA+среда+фактический result, developer checks отдельно от independent QA и визуальной приёмки владельца. Freshly written TC все NOT_RUN. Ошибка → конкретный дефект151/152/198 по владельцу scope, исправление и regression, не переоткрывать принятые задачи без доказанного остатка.

## Приёмка, выпуск и оценка

| AC | Результат | TC-152 |
| --- | --- | --- |
|AC-152-01 (LM11)|No-data/loading/error/stale/unknown/transport и assessment состояния честны|01,02,03|
|AC-152-02 (LM11)|Selected/off-page latest не смешаны, provenance/criteria/uncertainty/null/dates верны|03,04,05|
|AC-152-03 (LM11)|Banner из единого typed state, без stale actor и постоянного in-progress|06|
|AC-152-04 (LM11)|COMPLETE/continue/version/baseAssessment/retry/409 и history сохранены без skill auto-PASS|07,08,09|
|AC-152-05 (LM11)|NOT_IMPLEMENTED не заменён ИИ-next; нет выдуманных процентов/streak|04,09|
|AC-152-06 (LM12)|Общий151+198 candidate, auth/media/score regression, RUEN/reflow/a11y/rollback|10,11|
|AC-152-07 (LM12)|Отчёт/ограничения/device-vs-fixture и owner acceptance на точном SHA, без подмены independent QA|12|

Реализация из актуального main после готовой151, Refs152, meaningful component/browser tests + npm test/test:intake/test:tuner/test:score-browser/build default/light. Backend contract regression существующими suite/CI, production rollout39/40 отдельно. Новых env нет. Rollback при ложном результате/сломанных решениях/access regressions: предыдущий frontend build/commit, smoke task/selected/decision/history/actor; DB не откатывается, уже принятое сервером решение не исчезает от rollback UI. Flagoff меняет presentation, не отменяет серверные решения/общую event wiring автоматически.

**8ч, диапазон6–12, средняя уверенность, без изменения прежних8ч:** results/state/banner/provenance2; интеграционные browser/a11y/locale4; отчёт/инструкция/rollback2. Исполнение всех физических платформ/independent QA/ожидание владельца отдельно. При расширении backend/неизвестных или выходе за12ч декомпозировать остаток до кода. Нет нового budget на повторную реализацию198/151. Status остаётся Ожидание с указанными gates, readiness спецификации не закрывает152/147.

**Финальная сверка198:** открыт PR199, head9e1d243474e98975aa23256b2100dadd27b40b52, не merged. Это отдельный текущий auth-кандидат, не отсутствие кода и не доказательство приёмки.152 проверяет совместный candidate после его интеграции; изменения PR199 учитывать при merge общих App/CSS/auth guards, не откатывать их.
