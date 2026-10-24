# Проверки #151 и #152 после #150 —25.10.2026

Версия1.0. Дополнение к26TC-LM без перенумерации. **14TC-151 и12TC-152: все NOT_RUN.** [Full151](../specifications/UX-02-04-media-tools.md), [Standard152](../specifications/UX-02-05-results-and-acceptance.md). База анализа4426becd. Готовая150 не доказывает прохождение остатка151/152.

При исполнении указать exact app/doc SHA, backend/flags, OS/browser/viewport/locale/zoom, fixture/live, actual result и PASS/FAIL/BLOCKED/NOT_RUN, приложить обезличенные доказательства. Анализ исходников не является независимым тестовым отчётом.

## TC-151

| ID | AC-151 | Действия и ожидаемый результат | Статус |
| --- | --- | --- | --- |
| TC-151-01 | 01 | Черновик File/comment/key и активная запись: переключить record/file, изменить размер, открыть score/description/fullscreen/menu. Тот же экземпляр и stream, draft не потерян, принятый150 layout сохранён. | NOT_RUN |
| TC-151-02 | 01 | При существующем File запросить mic; deny/no-device/cancel/late permission после unmount. Прежние File/comment/key сохранены, поздние tracks остановлены, busy изменён только для своего K. | NOT_RUN |
| TC-151-03 | 01,02 | Новая запись с пустым/over-limit/error результатом, затем валидный Stop. Невалидный результат не удаляет draft; валидный заменяет attachment/key, comment сохраняется; Stop не делает POST. | NOT_RUN |
| TC-151-04 | 02 | Отменить picker, выбрать неверную замену, повторно выбрать тот же файл, удалить вложение. Старый валидный File жив после cancel/error; revokeURL/reset input выполнены при принятой замене/delete; comment сохранён. | NOT_RUN |
| TC-151-05 | 03 | Double click/Enter, ошибка POST, retry без изменений, затем изменение comment/File. Replay использует прежние payload/key; изменённое тело получает новый key. Неопределённый сетевой итог не объявлен rollback. | NOT_RUN |
| TC-151-06 | 03 | Upload201, затем history GET500/slow. Upload success и очистка draft сохранены; отдельная stale history с retry GET, без второго POST. Multipart/CSRF прежние. | NOT_RUN |
| TC-151-07 | 03,07 | При pending upload/capture принудительно сменить actor/K; доставить поздние success/error/finally. Нет чужого File, очистки нового draft или снятия нового busy; private buffers освобождены. | NOT_RUN |
| TC-151-08 | 04 | Sample play → draft play → mic request; попытаться play в requesting/recording/stopping. Другой player pause до mic, no auto-resume; разрешённый метроном продолжает. | NOT_RUN |
| TC-151-09 | 04 | Sample HEAD200 → GET404/play rejection/error, смена E и late event, retry. Честный unavailable, только текущий E, нет второго assigned player/current fallback/ярлыка synthesis без источника. | NOT_RUN |
| TC-151-10 | 05 | E3/8 eighth100 и6/8 с реальной единицей; inline input/±20–240/invalid, затем header/preset. Один engine, текущие config/BPM/meter/unit согласованы; pending не выдан за active. | NOT_RUN |
| TC-151-11 | 05 | Настроить accents/strong/weak/mute/volumes/subdivision/BPM; stop/start/reopen/minimize/PDF/resize/background; сменить E. Same E сохраняет config/phase без Stop, новый E не наследует старый clock; подписки сняты. | NOT_RUN |
| TC-151-12 | 06 | Метроном → recorder → tuner; затем idle tuner → recorder. Запись под click работает; отказ tuner не трогает recorder/clock; успешный tuner останавливает clock, recorder вытесняет tuner. | NOT_RUN |
| TC-151-13 | 07 | Busy capture/upload/decision → Back/logo/menu/F5; dirty → Cancel discard; затем leave/logout. Guards работают; Cancel сохраняет File/comment/key; реальный leave/logout освобождает ресурсы без late resurrection. | NOT_RUN |
| TC-151-14 | 08 | Upload capabilities=false/failure, default/light, RU/EN,360px,200%zoom и клавиатура. Local recording доступна, server submit запрещён, видны Stop/tabs/status/error. Fake stream не равен physical mic PASS. | NOT_RUN |

## TC-152

| ID | AC-152 | Действия и ожидаемый результат | Статус |
| --- | --- | --- | --- |
| TC-152-01 | 01 | Initial loading, GET failure, empty, retry. Нет раннего «оценки нет»; error отличим от empty, успех обновляет одного owner. | NOT_RUN |
| TC-152-02 | 01 | Selected UPLOADED/PROCESSING/FAILED/PROCESSED без assessment/unknown. Верные технические тексты; unknown не PASS, terminal или основание разрешить решение. | NOT_RUN |
| TC-152-03 | 01,02 | Off-page A с assessment, latest B без него; обратные fixtures и failed selected GET. Selected/latest не смешаны; failed empty array не NO_ASSESSMENT; pin и freshness guards сохранены. | NOT_RUN |
| TC-152-04 | 02,05 | PASS/REVISE/UNCERTAIN, HUMAN_REVIEW/VALIDATED_MEASUREMENT/unknown, null/0level, limitations/unknown outcome. Локализованные основания, нет выдуманных процентов/streak/AI-source/уровня. | NOT_RUN |
| TC-152-05 | 02 | Страницы assessment/evidence/decision, source/method/date, invalid date, RU/EN, long/XSS-like text. Привязка к попытке, Intl locale, escaped text; нет секретов/v-html. | NOT_RUN |
| TC-152-06 | 03 | ACTIVE → COMPLETED_PASSED/BY_LEARNER, refresh failure/unknown, late K event. Banner/summary/result из единого статуса, no fixed in-progress/old actor; freshness guards действуют. | NOT_RUN |
| TC-152-07 | 04 | Нет assessment либо UNCERTAIN/REVISE при allowed COMPLETE; reason/confirm/continue. Нет veto агента, student choice не skill PASS, remarks/history сохраняются. | NOT_RUN |
| TC-152-08 | 04 | Pending decision network failure → retry;409 version/assessment conflict; late finally после нового K. Frozen payload/key и ручной retry;409 refresh без autoComplete; нет чужого unlock/selection. | NOT_RUN |
| TC-152-09 | 04,05 | Terminal task, новая страница history, media TTL deletion, NOT_IMPLEMENTED. Нет нового POST/AI-next; история/оценки не исчезают после удаления media; upload закрыт по backend state. | NOT_RUN |
| TC-152-10 | 06 | Общий SHA с151+198: local/Google вход,verify/reset/settings/logout → Home/profile/onboarding → intake3states → assigned media/results/history. Общие пути работают; UAT198 не автоматический PASS152. | NOT_RUN |
| TC-152-11 | 06 | Матрица размеров/локалей/keyboard/reduced-motion/zoom из152, default/light, rollback. Stop/focus/labels/contrast доступны, нет overflow/обрезания; flagoff не отменяет серверные решения. | NOT_RUN |
| TC-152-12 | 07 | Сверить app/doc/design SHA, PR/CI/TC-LM и дополнения, fixture-vs-live mic/Google/HTTP, скриншоты и owner review. Отчёт честный, без фиктивных independent QA/приёмки/PROD. | NOT_RUN |

## Проверки и ограничения доказательств

151: реальный mounted parent/child workflow с controlled getUserMedia/MediaRecorder и HTMLaudio.play/pause, не regex-only. Проверять identity/config/phase engine и subscription count, а не наличие кнопки.152: DTO fixtures с различными latest/selected, конфликтами и поздними ответами, действительные actor/CSRF guards. Existing attempt-pagination/learning-qa-regression/metronome-recording/score-reader-contract полезны, но не покрывают автоматически новые кейсы.

Прогонять актуальные npm test, test:intake, test:tuner, test:score-browser, build; адресный Chrome workflow и real HTTPS/PostgreSQL smoke. Файлы/аккаунты для тестирования — разрешённые изолированные fixtures. Mock Google/SMTP/fake microphone не являются живой проверкой пользователя. Токены, личные письма и аудио не включать в Git/скриншоты.

При недоступной hardware/browser/provider среде — конкретный BLOCKED/NOT_RUN и условие снятия. После реализации нужны независимый QA и визуальная приёмка владельца. Дефекты по scope:151media,152mapping/gates,198auth; закрытие одной задачи не помечает прочие PASS.
