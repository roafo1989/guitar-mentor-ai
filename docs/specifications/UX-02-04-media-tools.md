# UX-02-04 / #151 — довести координацию записи, примера и инструментов

Версия1.0 ·25.10.2026 · Full · Фронтенд · FT-016-04 / US016/#147 → #53. БТ0.21/BT-010/011/012/015/016, ТЗ0.30; AC-LM-08/09/10 уточнены AC-151-01…08. Источник — перепроверка после принятой150/PR189, main **4426becd8aa737903ff79d46720703cf5dd115a4**. Самопроверка СА, не независимое review; новые runtime-тесты в анализе не запускались.

## Пользовательский результат и фактическая база

Как ученик, я хочу слушать пример, записываться и заниматься под метроном, не теряя черновик и настройки. Сохраняются действующий workspace150, один экземпляр SubmissionPanel/MicrophoneRecorder на контекст и общий engine метронома. Повторная верстка записи, вкладок record/file, waveform, кнопок, карточек результатов и добавление inline метронома **уже не остаток151**: они в main после150. Темп/размер должны открываться из назначенного упражнения, не из макета. Новых продуктовых метрик/бюджетов не вводится.

Проверены реальные DiagnosticView/ExercisePreview/SubmissionPanel/MicrophoneRecorder, metronome-ui/engine/audio-session/TunerDialog, attempt.service, route guards и текущие tests. Existing main Java/frontend checks SUCCESS — доказательства базы, не прохождения151.

| Наблюдение по исходникам | Что довести в151 |
| --- | --- |
|В hero есть настоящий audio sample, но нет связи его player с началом записи; active recorder передаёт лишь busy/draft наверх|Явная координация sample и preview записи с микрофоном; pause/no late autoplay/ошибки|
|MicrophoneRecorder.emit(active,true) происходит до getUserMedia; SubmissionPanel.onRecordingActive сразу удаляет File/preview/requestId|Сохранить предыдущий draft при permission denied/cancel/пустой/ошибочной записи; заменить только валидным новым File|
|Inline BPM хранится отдельно; engine subscription обновляет только status, повторный helper может снова применить meterConfig|Один фактический config у engine; header/dialog/inline согласованы, stop/start не теряет пользовательские accents/tempoUnit|
|Upload retry использует requestId; backend при найденном task/requestId возвращает предыдущий результат без сравнения нового comment|Один ключ привязан к одному отправленному payload; изменение File/comment после ошибки — новое намерение, не replay старого ключа|
|Есть contextGeneration, но upload finally сбрасывает uploading без проверки контекста|Поздний ответ/finally старого actor/task не меняет новое состояние|

Это статические трассировки/незакрытые гарантии, а не протокол воспроизведённых runtime-дефектов. В151 входят исправление и meaningful tests, а не только косметика.

## Зависимости и границы

150 закрыта completed, PR189 merged: прежнее ожидание layout/reader снято,151 **Разработка/В очереди**. 104/108/109/111/125–127/141/145/182 переиспользуются, не повторяются.198 — независимая auth-ветка, не блокирует151; shared App/CSS проверяются на текущем main, WIP=1.152 получает готовую151 и затем интегрирует общий candidate с198.

Не входят: перерисовка принятого150, новый recorder/metronome engine, новые музыкальные assets/строй тюнера/скины, системный звук или запись выходного микса, AI assessment, backend/БД/API/права, публичный rollout, долговременное хранение File. Наличие inline метронома не означает завершённую151 целиком. Initial open/closed PDF и вкладки score/description в принятом workspace150 этой задачей не меняются.

## Компоненты и события

Контекст K={learnerId,sessionId,taskId}, музыкальный источник E=code@version. DiagnosticView владеет hero sample, SubmissionPanel — File/comment/requestId/history и preview, MicrophoneRecorder — потоком/MediaRecorder, общий engine — clock/config. Ни resize, ни смена вкладки record/file, ни PDF fullscreen/description не меняют ключ K и не размонтируют recorder. После смены K cleanup обязателен, старые callbacks отбрасываются. Допустим key=K у recorder для настоящей смены контекста, но не key=breakpoint/tab; каждый capture/run и его recorded event привязаны к исходному K.

Существующее событие `draft-state:{busy:boolean,dirty:boolean}` сохраняет shape и значение для родительского guard. Добавить `capture-state:{contextKey:string,active:boolean}` из SubmissionPanel в DiagnosticView: active охватывает requesting/recording/stopping, эмитится до запуска/ожидания mic, active=false после release; contextKey — сериализованный K, позднее событие другого K игнорируется. Использовать существующий MicrophoneRecorder active, не отправлять File/токены событиями. Добавить узкую координацию принадлежащих этому K sample/preview players (refs или scoped registration); не останавливать все audio всей страницы через общий DOM selector и не вводить второй persistent business store.

[Sequence координации](../diagrams/ux151-capture-sequence.mmd). Общая архитектура/размещение не меняются, новая deployment diagram/ADR/схема БД не нужна.

## Действия и состояния: обязательный контракт

### 1. Sample и прослушивание черновика

Ровно один version-pinned sample для assigned Light Minimal остаётся в hero; не возвращать дубликат под нотами. Legacy/catalog preview сохраняет свой действующий player. No autoplay. Playing sample останавливает preview черновика и наоборот; запуск/ошибка play() не запускают attempt. На capture request оба принадлежащих K player pause до getUserMedia; sample и draft нельзя снова воспроизвести во время requesting/recording/stopping (UI показывает недоступность, play-handler страхует programmatic play). Разрешённый метроном при этом **не останавливается**. После release можно воспроизвести только новым действием пользователя, не auto-resume.

При смене E/K, route leave, logout/unmount остановить player, abort/ignore поздние callbacks и очистить refs/objectURL. Mediaerror, play rejection или backend404 после успешного HEAD дают локализованное «Аудиопример недоступен»/“Audio example unavailable”, ручной retry для текущего E. Не показывать вечный spinner и не подставлять audio другой версии/current. Причина «синтез»/«живое исполнение» указывается только при подтверждённом источнике; неизвестный пример остаётся просто «Аудиопример».

### 2. Capture и замена вложения

K владеет одним действующим draft. Start record — явное действие, но **не удаление** предыдущего File/comment/preview/requestId. На requesting/recording/stopping старый draft остаётся, submit/replace/mode switch запрещены; busy=true защищает SPA navigation. На permission denied/no-device/cancel/start failure/пустом или превышающем лимит результате прежний draft возвращается неизменённым. На recorded(validFile) заменить attachment, освободить старый objectURL, создать preview/новый requestId, сохранить comment. Capture Stop создаёт только draft, никогда POST. Текущие120s, maxBytes из capabilities/fallback20MiB и поддержанные MediaRecorder formats сохраняются; meter отражает реальный mic RMS, не качество исполнения.

Локальная запись разрешена при недоступном server upload (принято150), submit остаётся capabilities.enabled/terminal guarded. Error capabilities — неизвестность, не доказательство разрешённой отправки. Delete attachment очищает File/preview/native input и меняет requestId, **comment сохраняет**; полный discard навигации/контекста очищает весь private draft. Отмена file picker и некорректная замена не удаляют прежний валидный File. Новый File создаёт новый ключ, тот же файл можно повторно выбрать после очистки input.

### 3. Отправка и retry

До POST зафиксировать snapshot `{K,File,comment,requestId}`; form-data Content-Type/boundary выставляет браузер, existing auth adapter добавляет CSRF. Double click/Enter не создают второй запрос. Успех HTTP201 очищает attachment/comment/native input/preview и создаёт новый requestId, даже если последующий refresh history упал; показывать отдельную ошибку history, не повторять POST автоматически. Error/rejected/неопределённый итог network timeout сохраняют draft; ручной retry **неизменённого payload** использует исходный requestId. Если после ошибки пользователь меняет File/comment — это новая отправка с новым requestId, нельзя посылать изменённое тело под прежним ключом. При неопределённом сетевом исходе честно предложить проверить историю перед новой отправкой; не обещать rollback/отсутствие результата на сервере.

Late success/error/finally старого K/actor не очищает новый File и не снимает его busy. Existing pagination/latest/pinned fetch bounds и selection сохраняются. No localStorage/IndexedDB/URL/File persistence; после F5 draft не обещается восстановить.

### 4. Один метроном, синхронные controls

Inline controls из150 остаются между записью и результатом, расширенные настройки — существующий header dialog. Не создавать второй clock/AudioContext и не прятать настройки104. Рекомендованные bpm/meter/tempoUnit берутся из назначенного E; первый запуск нового E применяет их через existing helper, сохраняя предусмотренные volume/subdivision preferences. Нет выдуманного4/4/100 при неверных данных: ошибка, запуск невозможен; absent legacy tempoUnit не подписывать как явно заданную единицу автора.

Для того же E stop/start/minimize/reopen/resize/PDF не пересоздают config и не сбрасывают accents, сильные/слабые/mute, volumes, tempoUnit, subdivision или пользовательский BPM. Без Stop работающий/reopened engine продолжает phase. Правка BPM20–240 в inline обновляет этот engine и проверяет boolean result; локальное поле не становится ложным подтверждением применения. Изменение через header/preset отображается в inline из `getConfig()/getPendingConfig()/getState()` subscription, а не из старого ref(props.bpm). Различать recommendation metadata и фактическую текущую конфигурацию; если config staged — не выдавать его за уже звучащий. Meter и tempo unit показывать понятно (например3/8, ♪=100;6/8, dotted-quarter=60 при такой реальной единице).

Глобальный header без E не наследует выдуманный source; новый E не продолжает старый clock с чужим размером. Смена E останавливает его source, явный close останавливает, minimize оставляет running. Background сохраняет существующие гарантии109, без нового обещания работы при suspend OS/browser. Любая новая подписка снимается при unmount. Сохраняются tests104/109,20–240BPM и акценты в допустимых метрах.

### 5. Приоритет mic и навигация

Existing audio-session: recorder вытесняет tuner; tuner никогда не прерывает requesting/recording/stopping recorder. Если lease занят recorder, tuner показывает busy, не открывает второй getUserMedia и **не останавливает его метроном**. При реально запущенном tuner метроном останавливается как прежде; Standard/Drop D/Open G/Auto/Manual/headstock не меняются. Начало recorder не останавливает метроном.

SPA Back/logo/profile/menu во время capture/upload/decision busy блокируются existing guard; dirty discard требует существующего подтверждения, cancel оставляет draft/config. F5/tab-close — existing beforeunload, без обещания сохранения после принудительного закрытия. Logout/actor change останавливают mic/sample/preview/owned source, очищают private buffers; любой поздно полученный mic stream сразу stop. Никаких попыток обойти cleanup, чтобы сохранять данные после logout.

## REST, данные, ошибки

Reuse существующих endpoints, новых OpenAPI/events/БД/migrations нет. Base B=`/api/v1/learners/{learnerId}/diagnostic-sessions/{sessionId}/tasks/{taskId}`; все path UUID обязательные, ownership на сервере, same-origin+existing CSRF для POST.

| Запрос | Контракт | Ожидание UI |
| --- | --- | --- |
|GET B/attempts/capabilities|200 {enabled:boolean,maxBytes:number,formats:string[]}|Полученные лимиты для server submit; recording доступен отдельно|
|POST B/attempts|multipart file:File(required1..maxBytes),requestId:UUID(required),comment:string(optional≤2000); formats mp3/wav/ogg/webm/m4a|201 AttemptView, включая idempotent existing result.400/413/UPLOAD_DISABLED/TASK_COMPLETED/401/403/network — безопасный message и нужный draft/retry|
|GET B/attempts/page?limit=20&beforeNumber=N|200 items/nextBeforeNumber/hasMore,limit≤50|Переиспользовать пагинацию125/126, не unbounded list|
|GET B/attempts/{attemptId}|200 authoritative detail;400/404/5xx|Оставить pinned/current selection, отметить stale/error|
|GET/HEAD exercise previewAudioUrl|Точный E/code/version изDTO;null/missing/GETerror допускаются|No current fallback, медиа не подтверждено одним HEAD|

Transport UPLOADED/PROCESSING/PROCESSED/FAILED не музыкальный assessment; это отдельная152. Персональные пути/секреты/raw server errors не логируются и не вставляются как HTML. Применимы NFR-001/002/004/007/008 и SEC-002/003/004/005/006; новых services/платежей/таймаутных SLA нет. Controls≥44px, keyboard/Tab/Space/Enter, RU/EN, labels/live status, errored state не только цветом, Stop виден при zoom200%/360px. Существующие валидированные лимиты/retention не ослабляются.

## Приёмка

| AC | Проверяемый результат | TC-151 |
| --- | --- | --- |
|AC-151-01 (LM08)|Один owner K, record/file/PDF/layout не теряют draft; permission/cancel/пустая запись не удаляют предыдущий File|01,02,03|
|AC-151-02 (LM08)|Валидная новая запись/замена/удаление, comment и objectURL/native input управляются корректно|03,04|
|AC-151-03 (LM08)|Upload/retry snapshot, ключ соответствует payload, успех отделён от history failure, late old K изолирован|05,06,07|
|AC-151-04 (LM10)|Sample и draft player координируются с capture, нет autoplay/чужой версии/необработанного playerror|08,09|
|AC-151-05 (LM09)|Inline/dialog — один config/clock,3/8/eighth100 и6/8,stop/start/reopen/presets сохраняют настройки|10,11|
|AC-151-06 (LM09)|Запись под метроном возможна; tuner не прерывает recorder и его clock, recorder вытесняет tuner|12|
|AC-151-07 (LM10)|Navigation/actor/logout/late stream cleanup сохраняют privacy/guards|07,13|
|AC-151-08 (LM08…10)|Legacy/Light,capabilitiesfalse,RUEN/mobile/zoom/keyboard и адресные checks подтверждены|14|

[Тестовая матрица](../testing/TC-UX02-media-results-refresh.md),14кейсов151: NOT_RUN до реализации. Написанный тест не PASS; source regex не заменяет реальное mounted действие и browser stream test. Physical mic отдельно от fake MediaStream; новый музыкальный анализ не нужен.

## Выпуск, rollback и оценка

Ветка из актуального main; реализовать ограниченный остаток, npm ci на Node≥22.12, npm test/test:intake/test:tuner/build default+light/secure, адресные mounted/browser tests, test:score-browser как регрессия150. Изолированный local HTTPS+PostgreSQL; public39/40 отдельно. Никаких новых config/env. Старый Light flag изолирует представление, **не отменяет общие исправления draft/media**; полный rollback — предыдущий frontend build/commit с проверкой sample/recording/upload/logout. Reload уничтожает локальный draft, schema rollback не нужен.

**Остаток8ч (6–10), средняя уверенность:** audio players/capture2; draft/retry/context2; metronome sync1.5; meaningful checks2; docs/PR0.5. Исходные10ч оценивали и уже выполненную150 компоновку; это новая оценка остатка, не заявление о потраченных/сэкономленных2ч. Independent QA и owner acceptance отдельно. При новом API/втором clock/выходе за10ч сначала пересмотреть scope. Код не начат этой актуализацией; после implementation → review/test по регламенту, Refs151 без auto-close, владелец принимает результат.
