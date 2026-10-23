# TC-125/126/127 — план проверки автономных кандидатов

Версия 1.0, 23.10.2026 Europe/Moscow. Основание: [CORE-01 v1.0](../specifications/CORE-01-autonomous-development.md), ТЗ 0.18, main b6485e4. Автор Codex; независимая проверка не выполнена. Все 16 сценариев ниже **NOT_RUN**: это подготовка требований, не отчёт о runtime.

## Стенд и фикстуры

Только отдельная тестовая PostgreSQL / schema, применить текущие Flyway без редактирования старых миграций. Java 21, текущий Maven wrapper, Vue/Node >=22.12.0. Создать синтетические learner A/B, их session/task, наборы 0/1/20/21/45/51 попыток с последовательными уникальными номерами и фиксированными submittedAt. Использовать repository/SQL fixtures тестов, не загружать 45 аудиофайлов. В разных попытках задать UPLOADED/PROCESSING/PROCESSED/FAILED, AVAILABLE/DELETE_PENDING/DELETED. Несколько assessments разных revisions — существующими тестовыми механизмами #18, без runtime обхода авторизации и без записи музыкальной оценки пользователю.

Один fixture содержит 45 строк task A, 2 строки task B и assessments для заранее известных UUID. Для курсора с пропусками удалить/не создавать номер18 только внутри другой disposable fixture. HTTP failure tests используют перехват fetch/mock responses; проверка Java ownership/pagination использует настоящий PostgreSQL. Fake timers для poll/hidden/visible; deferred promises для races. Применять существующий test harness либо минимально расширить его; не заменять проверку поведения чтением строк исходника.

| TC = AC | Шаги | Ожидаемый результат | Статус |
| --- | --- | --- | --- |
| CORE-B1 | На task A с 45 попытками вызвать /page без query, затем передать nextBeforeNumber дважды. Повторить с 0 и 20 строками. | Страницы 20/20/5: номера 45…26, 25…6, 5…1; cursors 26/6/null. Для пустой и ровно полной последней страницы hasMore=false. | NOT_RUN |
| CORE-B2 | Проверить limit 1/50, beforeNumber на пропуске; 0/51/empty/decimal/text/overflow, repeated query и before ≤0. | Валидная exclusive фильтрация; неправильный query даёт 400 INVALID_ATTEMPT_PAGE. Отсутствующий limit означает 20, присутствующий пустой limit ошибочен. | NOT_RUN |
| CORE-B3 | Получить первую страницу 45…26; добавить 46; запросить older <26 и refresh latest. | Older остаётся 25…6 без дублей, latest начинается с 46; нет offset-сдвига. | NOT_RUN |
| CORE-B4 | Подменить learner/session/task/attempt, включая attempt B в task A; unknown и malformed UUID; изучить headers. | Чужое и неизвестное дают одинаковый 404; malformed path даёт safe 400. Новые GET/capabilities no-store; error не содержит путей, stack или media_key. | NOT_RUN |
| CORE-B5 | Считать SQL для 0/1/20/50 items и capabilities; проверить assessmentAvailable для нескольких revisions и индекс V7. | На страницу ≤1 ограниченный select и ≤1 batch assessment сверх фиксированного ownership; capabilities не читает историю; значения boolean верны. | NOT_RUN |
| CORE-B6 | Проверить legacy GET с 45 строками, GET одной попытки; выключить uploads; выполнить прежние replay/terminal/TTL tests и искусственный DB fail. | Legacy DTO/порядок сохранены; чтение при uploads=false разрешено, новый POST запрещён; guards/TTL работают; safe 500 ATTEMPT_READ_FAILED. | NOT_RUN |
| CORE-U1 | Во Vue открыть 45 попыток, Older→Older→Newer→Latest; собрать HTTP и состояние модели. | Страницы 20/20/5 и правильные disabled controls; только page/detail; ≤20+2 DTO без накопления страниц. | NOT_RUN |
| CORE-U2 | Выбрать старую попытку, перейти на другую страницу, получить latestAttemptId вне страницы; вызвать detail 404. | Выбор, assessment и reason сохраняются; pin без дублей; 404 закрывает decision и показывает ошибку, не переключает выбор автоматически. | NOT_RUN |
| CORE-U3 | Уронить page/poll/detail и выполнить Retry; отдельно POST upload=200, следующий page=500. | Видимая локальная ошибка и последние данные; retry восстанавливает; успех upload сохранён, файл очищен один раз, повторный POST не запускается. | NOT_RUN |
| CORE-U4 | Задержать старые page/detail, сменить page/task или unmount; проверить selection при pending decision; fake timers hidden/visible. | Старые ответы отброшены; один poll in-flight, hidden=0, visible запускает одно чтение; pending requestId/baseAssessmentId не меняются. | NOT_RUN |
| CORE-U5 | Листать историю с file/comment, записью и метрономом; проверить freshness/409/revisions/evidence/decisions; RU/EN, 360px, 200% zoom, keyboard. | Черновики и инструменты сохранены, history не запускает POST; прежние guards и компоновка #51 сохранены; доступность и читаемость проверены. | NOT_RUN |
| CORE-E1 | В трёх legacy services вернуть HTML, stack, path, script и unknown code на 400/404/500. | Только фиксированные переведённые тексты; raw body не сохраняется в DOM/store/console; статус типизирован и при пустом/HTML body. | NOT_RUN |
| CORE-E2 | Проверить learner404, session404, catalog404, background204, task204; повторить network/500/bad JSON. | В двух scope-случаях удаляется только свой ключ; catalog сохраняет session; 204 — штатно пусто; остальные сбои сохраняют ID. | NOT_RUN |
| CORE-E3 | Ошибка GET и Retry; failed POST create/start/assign и PUT save; повторить при recorder/decision busy. | Повторяется только нужный GET; ноль автоматических write-повторов; ввод остаётся, busy снимается, явный повтор доступен согласно guards. | NOT_RUN |
| CORE-E4 | 200 с плохим JSON/DTO, valid DTO с nullable/additional metadata, AbortError и поздний ответ старого контекста. | INVALID_RESPONSE без raw body; совместимый DTO принят; abort и старый ответ не показывают ложную ошибку и не меняют новый context. | NOT_RUN |
| CORE-E5 | Показать ошибку RU, переключить EN; проверить Home #51 с restore:void и выполнить frontend regression. | Текст переводится без HTTP; Home читает структурный failure; intake/learning/tools не сломаны; строковые HTTP predicates заменены. | NOT_RUN |

## Команды реализации и протокол

Backend #125: из backend `./mvnw --batch-mode verify` с `GUITAR_MENTOR_TEST_POSTGRES=true` и test-only `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`. Пароли не сохранять в Git/Issue. Сверить java-ci.yml; skipped PostgreSQL tests не означают успешной интеграции. Добавить HTTP/SQL budget/regression tests в существующий CI.

Frontend #126/#127: из frontend `npm ci`, `npm run build`, `npm test`, `npm run test:intake`, `npm run test:tuner`. Новые component/service tests включить в npm script и CI. Node harness не означает браузерную QA. Browser QA с synthetic API: RU/EN, 360px/desktop, 200% zoom, keyboard, fail/retry, старая выбранная попытка. Записать фактический браузер, viewport и ограничения.

Для каждого PR: SHA ветки/main, команды, число tests/skip, CI URL, матрица PASS/FAIL/BLOCKED/NOT_RUN, дефекты и ограничения. Независимое review/QA и приёмка остаются отдельными фактами. Документационный анализ runtime не меняет; описанные будущие проверки ещё не запускались.
