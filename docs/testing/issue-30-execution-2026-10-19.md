# #30 — независимое исполнение тест-кейсов, 19.10.2026

| Версия | Дата | Изменение |
| --- | --- | --- |
| 1.2 | 19.10.2026 | Итоговая приёмка владельцем в 21:29 МСК и merge PR #69 в main bb172716. |
| 1.1 | 19.10.2026 | Завершён ручной прогон владельца на Windows/Chrome, 639186e: 25 PASS, 0 FAIL/BLOCKED; ожидается приёмка. |
| 1.0 | 19.10.2026 | Фактический независимый прогон: 23 PASS, 2 BLOCKED; 53 JUnit без ошибок/пропусков и 28 HTTP/browser вариантов PASS. |

## Результат и границы

**Тестирование завершено: 25/25 PASS, 0 FAIL, 0 BLOCKED.** После независимого прогона владелец 19.10.2026, 21:26 МСК подтвердил остаток на Windows 10 / Chrome: настоящий микрофон, установленные MP3/PDF и сохранение истории после перезапуска/F5 на `feature/issue-30-safe-media-store`, HEAD `639186e`. [Итоговый протокол в #30](https://github.com/roafo1989/guitar-mentor-ai/issues/30#issuecomment-6274311590). Продуктовых дефектов в выполненном объёме не выявлено. **Итоговая приёмка подтверждена владельцем 19.10.2026, 21:29 МСК** ([решение](https://github.com/roafo1989/guitar-mentor-ai/issues/30#issuecomment-6274357946)); [PR #69](https://github.com/roafo1989/guitar-mentor-ai/pull/69) объединён в main, merge `bb172716f9f722bb3e21ee711f479f675247b5f0`.

Проверяемое приложение: PR #69, SHA `07cc33b99e9cc6fdbf0c9cc7dae28c1a1fab87ac`. Прогон: [QA PR #71](https://github.com/roafo1989/guitar-mentor-ai/pull/71), head `b02325d3ddf5bfee57e9ba6d228cf1d9f8a1807b`, **только тесты/сценарии/workflow** поверх #69. Все 85 файлов кода/ресурсов backend/src/main и frontend/src побайтно сверены с целевым SHA. Продуктовый код #69 не исправлялся и не сливался в main.

Среда: отдельный Ubuntu GitHub runner, Java 21.0.12.1, PostgreSQL 17.11, FFmpeg/ffprobe, Node.js 22, настоящий Vue/Vite и Chromium через Playwright. В браузере использовались настоящие HTTP-запросы без mock API; единственная подмена — синтетическое аудиоустройство вместо физического микрофона. Данные и файлы — синтетические, изолированы от компьютера/БД/записей владельца.

## Доказательства

| Источник | Фактический результат |
| --- | --- |
| [Независимый workflow #4](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39231738937) | SUCCESS; новый запуск Maven verify: **53 tests, failures=0, errors=0, skipped=0**; Vue typecheck/build PASS; HTTP и браузер PASS |
| [Java CI #80](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39231738954) | SUCCESS на том же QA head: 53 JUnit и существующий HTTP smoke, включая реальные контейнеры и worker |
| [Vue CI #85](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39231739048) | SUCCESS на том же QA head |
| [Файлы независимых тестов](https://github.com/roafo1989/guitar-mentor-ai/pull/71/files) | Можно воспроизвести тот же набор; production diff не добавлен |
| Артефакт workflow `qa30-independent-evidence`, ID 12177581379 | Surefire XML, JSON результатов и boot/Vite logs; создан 19.10, хранение до 24.10.2026. Этот Markdown сохраняет сводку после истечения артефакта |

Сверены именно завершившиеся jobs и их логи. Пять реальных PostgreSQL integration-тестов прошли без пропусков: flush failure, late rollback, commit/idempotency и две новые HTTP/compensation проверки. Дополнительно прошли 4 IndependentStorageAcceptanceTest и 2 IndependentGuardAcceptanceTest. Остальные существующие тесты также прошли; общий результат — 53.

При настройке новых тестов были устранены две ошибки **тестового стенда**: неверная аннотация порта заменена на Spring property injection; health JSON сравнивался целиком, затем исправлена проверка HTTP 200 + status UP с допуском стандартных дополнительных полей. Ранние неуспешные QA-прогоны сохранены в истории, не считаются продуктовым FAIL #30. Итоговый прогон выше зелёный. Исходный readiness workflow приложения не изменялся.

## Результаты по кейсам

Подробные предусловия и шаги: [тест-кейсы](issue-30-media-storage-test-cases.md). PASS относится к указанной среде и выполненному сценарию; он не означает осмотр личных данных или реального оборудования владельца.

| Кейс | Итог | Фактическое наблюдение |
| --- | --- | --- |
| TC30-10 | PASS | Временные файлы: DELETED → ALREADY_ABSENT; соседний файл остался, его SHA-256 не изменился. |
| TC30-11 | PASS | Вся матрица null/empty/traversal/Unix и Windows absolute/segment/UUID/extension проверена для delete, resolveForInspection, verifyAndHash: INVALID_KEY; внешние и соседние контрольные байты сохранены. |
| TC30-12 | PASS | Реальные каталог с содержимым, symlink и Linux FIFO отклонены всеми тремя методами с INVALID_MEDIA. Цели и содержимое целы; отсутствующий валидный объект даёт MEDIA_MISSING при чтении и ALREADY_ABSENT при удалении. Пропусков нет. |
| TC30-13 | PASS | Детерминированный AccessDeniedException → DELETE_IO_ERROR; файл остался. После снятия отказа DELETED и повтор ALREADY_ABSENT; хеш соседа сохранился. |
| TC30-14 | PASS | Настоящий SQL CHECK вызывает PostgreSQL flush failure. Строка отсутствует, новый файл компенсирован; отдельный реальный HTTP-тест вернул 5xx вместо 201 и не оставил файла. |
| TC30-15 | PASS | Настоящий TransactionTemplate: после успешного saveAndFlush выставлен поздний rollbackOnly. После завершения внешней транзакции строки и созданного файла нет. |
| TC30-16 | PASS | Реальный commit сохранил одну строку и файл; повтор requestId сохранил те же ID/номер и один файл. |
| TC30-17 | PASS | STATUS_UNKNOWN не вызывает delete, пишет MEDIA_TRANSACTION_OUTCOME_UNKNOWN с requestId/фазой без приватного имени и stacktrace. |
| TC30-18 | PASS | Настоящий отказ SQL CHECK + spy-отказ delete: исходная цепочка исключений содержит ошибку PostgreSQL, не подменена IOException. Реальный HTTP ответ 5xx, строки нет; оставшиеся временные файлы наблюдаемы. Два безопасных события с requestId/фазой/кодом, без private marker/имени/комментария/stacktrace. |
| TC30-19 | PASS | Проверены отсутствие активной транзакции и отдельный RuntimeException регистрации callback. Компенсация вызывается; первичная ошибка регистрации сохранена, сбой удаления логируется безопасно. Thread-local очищен. |
| TC30-20 | PASS | HTTP: empty WAV / поддельный MP3 / TXT отклонены с INVALID_AUDIO_SIZE / INVALID_AUDIO_CONTENT / UNSUPPORTED_AUDIO_FORMAT. История и файлы не изменились. |
| TC30-21 | PASS | Unit: 0 и 20971521 отвергнуты до чтения; 20971520 сохранён. HTTP: валидный WAV с RIFF JUNK ровно 20971520 принят и PROCESSED; 20971521 отвергнут без строки/файла. Верхний регистр пользовательского UPPER.WAV также проверен. |
| TC30-22 | PASS | Реальные WAV: 120 с → PROCESSED; 125 с → FAILED/DURATION_EXCEEDED с сохранением истории. |
| TC30-23 | PASS | 2000 символов сохраняются, 2001 → COMMENT_TOO_LONG без побочных записей. Для чужого learner/session/task GET/capabilities/POST отклонены, чужая история не раскрыта и файл не создан. |
| TC30-24 | PASS | PASS: на своём назначенном задании владелец подтвердил воспроизведение установленного учебного MP3 и открытие/читаемость PDF. До назначения экран в этом ручном прогоне отдельно не открывался; существующее задание сохранено. Музыкальное содержание #17 не принималось повторно. [Протокол](https://github.com/roafo1989/guitar-mentor-ai/issues/30#issuecomment-6274311590) |
| TC30-25 | PASS | Реальный Chromium: RU/EN показывает честное сообщение о технической проверке и отсутствии музыкальной оценки. При переключении языка и F5 ID/порядок/число попыток сохраняются; приватных полей в панели нет. |
| TC30-01 | PASS | Проверены 85 файлов кода/ресурсов приложения: соответствуют 07cc33b. Общий AttemptMediaStore, один LocalAttemptMediaStore и источник MAX_BYTES; нет рабочего deleteQuietly, новых delete/download endpoints и изменений миграций. |
| TC30-02 | PASS | На реальном HTTP: enabled=true, maxBytes=20971520 и четыре формата; upload 201 и прежняя модель AttemptView. В JSON не добавлены физические пути/ключи. |
| TC30-03 | PASS | PASS: прежние №1–5 сохранены после обновления на 639186e и F5; владелец подтвердил. Прежний независимый прогон baseline → #30 с совпадением истории и SHA-256 файлов также PASS. [Протокол](https://github.com/roafo1989/guitar-mentor-ai/issues/30#issuecomment-6274311590) |
| TC30-04 | PASS | Реальный Chromium выбрал WAV и отправил его через Vue/HTTP: ровно одна новая попытка, native file input и комментарий очищены. Техническая обработка WAV отдельно подтверждена HTTP. Прослушивание записанного черновика выполнено в автоматической части TC30-06. |
| TC30-05 | PASS | Реальные WAV/MP3/OGG/WebM после ffmpeg проходят HTTP и worker до PROCESSED, с положительной длительностью, верным format и assessmentAvailable=false. |
| TC30-06 | PASS | PASS: владелец на Windows 10 / Chrome подтвердил настоящую запись, реакцию индикатора и слышимое прослушивание; одна явная отправка создала №6 WEBM 0.08 MB с комментарием «Тест #30 — микрофон» и статусом технически проверен. Черновик и поля очищены. Дополнительный перезапуск/F5 сохранность подтвердил. [Протокол](https://github.com/roafo1989/guitar-mentor-ai/issues/30#issuecomment-6274311590) |
| TC30-07 | PASS | PASS: владелец выполнил Ctrl+C / spring-boot:run и F5; новая №6 с комментарием и прежние №1–5 сохранены. Это дополняет независимый прогон новой JVM, SHA-256 файлов и реального Chromium. [Протокол](https://github.com/roafo1989/guitar-mentor-ai/issues/30#issuecomment-6274311590) |
| TC30-08 | PASS | Реальные перезапуски с false, без переменной (default false), затем true; при запрете POST=403/UPLOAD_DISABLED без новых строк/файлов. Chromium на настоящем false отключил file/comment/submit/microphone, сохранив историю. |
| TC30-09 | PASS | Повтор legacy requestId после обновления и после перезапуска возвращает прежние ID/номер, без новых строк/файлов и без удаления committed файла. |

В TC30-04 уточнён текст шага: выбор WAV/отправка проверены отдельно, а прослушивание **записанного** черновика — в TC30-06. Это уточнение существующего UI-сценария, не изменение продукта или новое требование к файловому плееру.

## Исполненные HTTP/browser варианты

Ниже — фактические JSON-события нового workflow. Это **28 вариантов**, не 28 дополнительных самостоятельных тест-кейсов и не замена 53 JUnit.

| Кейс | Вариант | Результат |
| --- | --- | --- |
| TC30-03 | baseline 270238f -> #30: exact history and file SHA-256 | PASS |
| TC30-02 | capabilities and no private response fields | PASS |
| TC30-09 | legacy requestId retry after upgrade | PASS |
| TC30-05 | wav | PASS |
| TC30-05 | mp3 | PASS |
| TC30-05 | ogg | PASS |
| TC30-05 | webm | PASS |
| TC30-22 | 120 seconds | PASS |
| TC30-22 | 125 seconds | PASS |
| TC30-20 | empty.wav | PASS |
| TC30-20 | invalid.mp3 | PASS |
| TC30-20 | file.txt | PASS |
| TC30-21 | 20971520 bytes HTTP | PASS |
| TC30-21 | 20971521 bytes HTTP | PASS |
| TC30-23 | 2000 character comment | PASS |
| TC30-23 | 2001 character comment | PASS |
| TC30-23 | foreign ownership chain 0 | PASS |
| TC30-23 | foreign ownership chain 1 | PASS |
| TC30-07 | new JVM same PostgreSQL and file root | PASS |
| TC30-09 | requestId retry after JVM restart | PASS |
| TC30-08 | explicit false | PASS |
| TC30-08 | actual uploads=false backend: browser controls disabled, history visible | PASS |
| TC30-08 | default disabled | PASS |
| TC30-08 | re-enabled | PASS |
| TC30-04 | Chromium file selection, explicit submit, cleared native input | PASS |
| TC30-06 | fake physical device: MediaRecorder, level>0, preview playback, explicit submit | PASS |
| TC30-25 | RU/EN truthful technical status, history survives language change/F5 | PASS |
| TC30-07 | real browser F5 restores same server history | PASS |

## Ручная проверка владельца — завершена

На Windows 10 / Chrome и ветке реализации `feature/issue-30-safe-media-store`, HEAD `639186e`, выполнено:

1. Прежняя история №1–5 сохранилась после обновления/F5.
2. Настоящий микрофон: запись, реакция индикатора, слышимое прослушивание; явная отправка создала ровно новую №6 с комментарием и техническим PROCESSED, поля очищены.
3. Установленный учебный MP3 воспроизводится, PDF открывается и читаем на имеющемся назначенном задании. Экран до назначения отдельно повторно не открывался; существующее задание сохранено.
4. Обычный перезапуск backend и F5 сохранили новую №6 и прежние №1–5.

Подтверждения: промежуточные наблюдения 21:19/21:21/21:23 и ответ владельца **«да, всё прошло успешно»** в 21:26 на последние пункты MP3/PDF и перезапуска. [Полный итоговый протокол](https://github.com/roafo1989/guitar-mentor-ai/issues/30#issuecomment-6274311590). Реальное аудио и скриншоты браузера в публичный Git не добавлялись. Непроверенных обязательных шагов в согласованном ручном остатке не осталось. Музыкальное содержание материалов #17 и полный учебный цикл этим не принимаются повторно.

## Приёмка и поставка

Владелец 19.10.2026, 21:29 МСК прямо подтвердил **«Задачу утверждаю. Можем мерджить и закрывать»**. [Зафиксированное решение](https://github.com/roafo1989/guitar-mentor-ai/issues/30#issuecomment-6274357946). Реализация [PR #69](https://github.com/roafo1989/guitar-mentor-ai/pull/69) объединена в `main`, merge `bb172716f9f722bb3e21ee711f479f675247b5f0`. Кейсы и этот отчёт публикуются в main через PR #70.

Исполнимые независимые проверки сохранены в [QA PR #71](https://github.com/roafo1989/guitar-mentor-ai/pull/71)/ветке `testing/issue-30-independent-execution`; их вспомогательный workflow не включается в поставку приложения автоматически. Повторный [Java CI на merge-коммите](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39303099866) и [Vue CI](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39303100156), фактическое закрытие Issue и завершение карточки фиксируются в [#30](https://github.com/roafo1989/guitar-mentor-ai/issues/30). Целевое состояние — Завершение / Выполнено; запись полей доски не заявляется без подтверждения. Приёмка #30 не закрывает #21/#58 и не начинает следующую реализацию.
