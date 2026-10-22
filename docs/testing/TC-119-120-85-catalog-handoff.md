# TC: передача #119 / #120 / #85 — каталог v1.1

Дата: 22.10.2026. Основание: [спецификация v1.1](../project/catalog-development-candidates-2026-10-22.md), ТЗ0.16/FT-002-03, БТ0.10. Исходная база main327b9a38cb829fc07a4da00adbf6da36e2a2985f. Это план для реализации, не отчёт о пройденном QA. Автор — аналитик, независимого исполнения/ревью нет.

Для всех кейсов ниже фактический результат: **не исполнялся**, статус **NOT_RUN**. Реализация119/120/85 ещё не начата. Проверяющий записывает SHA ветки, окружение, команды и фактические результаты отдельно; mocks/реальная PostgreSQL/HTTP/browser не смешиваются.

| TC / AC (FT-002-03) | Предусловия | Действия | Ожидаемый результат | Факт / статус |
| --- | --- | --- | --- | --- |
| TC-CAT-A1 / AC-CAT-A1 | Старая БД V16 с назначением GM-EB01 | Применить новую миграцию дважды через Flyway migrate/validate | Три nullable колонки; ID/FK/history неизменны; повтор не меняет данные | Не исполнялся / NOT_RUN |
| TC-CAT-A2 / AC-CAT-A2 | API #119; каталог/detail/назначение | Прочитать все три DTO пути | Одинаковые JSON типы, null для unknown, minutes integer30 | Не исполнялся / NOT_RUN |
| TC-CAT-A3 / AC-CAT-A3 | Старый клиент и предыдущая БД | Обновить backend; повторить чтение/каталог/рекомендации | Совместимость, фильтры/rights/назначение сохранены | Не исполнялся / NOT_RUN |
| TC-CAT-A4 / AC-CAT-A4 | Отдельная тестовая schema | Вставить слишком длинные/пробельные новые метаданные и допустимую границу | Неверное отклоняется, границы/NULL принимаются; plaintext HTML не исполняется | Не исполнялся / NOT_RUN |
| TC-CAT-A5 / AC-CAT-A5 | Три criteria GM-EB01 из V12 и row без критериев | Прочитать DTO и проверить схему | Исходный упорядоченный список или[]; колонки success_criteria нет | Не исполнялся / NOT_RUN |
| TC-CAT-A6 / AC-CAT-A6 | V1/V2 одного code, разные criteria/minutes, несколько code | Прочитать list/detail/старое назначение; наблюдать запросы критериев | UUID-верные данные; пакетная загрузка, без N отдельных criteria SELECT | Не исполнялся / NOT_RUN |
| TC-CAT-B1 / AC-CAT-B1 | Назначение V1, current переключён на V2; разные байты всех4ресурсов | F5; GET назначение и все4V1URL; GET legacy URLs | V1 остаётся V1, legacy выдаёт V2; назначение не обновляется | Не исполнялся / NOT_RUN |
| TC-CAT-B2 / AC-CAT-B2 | PDFV1 null/нетфайла; PDFV2 есть; неизвестная валидная пара | GET/HEAD missingV1 и unknown, проверить другие MIME | 404, без V2 fallback; остальные корректны | Не исполнялся / NOT_RUN |
| TC-CAT-B3 / AC-CAT-B3 | Новый route через реальный HTTP server | Проверить code51/version21, пробелы, encoded slash/backslash, dot/percent; затем0.8B | Некорректное400 либо server404 без чтения; валидный0.8B доступен | Не исполнялся / NOT_RUN |
| TC-CAT-B4 / AC-CAT-B4 | V5 UNIQUE/partial unique и назначениеV1 | Попытаться вставить дубликат пары и второй current в test schema; exact lookup | Ограничения работают; новая миграция/дублирующий индекс не создан; FK сохранён | Не исполнялся / NOT_RUN |
| TC-CAT-B5 / AC-CAT-B5 | Exact detail и4доступныхресурса | Сравнить GET/HEAD status/MIME/cache headers | 200/тотжеMIME/no-cache; HEAD без тела; назначение не создаётся | Не исполнялся / NOT_RUN |
| TC-CAT-B6 / AC-CAT-B6 | Unsafe stored path и управляемый I/O сбой в unit/HTTP fixture | Прочитать exactресурс | Безопасный404 EXERCISE_RESOURCE_NOT_FOUND /500 EXERCISE_RESOURCE_READ_FAILED; нет path/SQL/current | Не исполнялся / NOT_RUN |
| TC-CAT-UI1 / AC-CAT-UI1 | Новый и старый DTO, RU/EN, до/после назначения | Проверить metadata; отсутствующие/пустые поля; HTML как текст | Корректные fallback, version/rights/30мин, criteria обычнымсписком; нет fake checked/outcome | Не исполнялся / NOT_RUN |
| TC-CAT-UI2 / AC-CAT-UI2 | НетMP3/PDF и error после успешногоHEAD; active assignment/draft | Открыть preview, PDF, вызвать media error | PDF исходноcollapsed; fallbackлокализован; task/session/attempt/file сохранены | Не исполнялся / NOT_RUN |
| TC-CAT-UI3 / AC-CAT-UI3 | Управляемые pending HEADV1/V2 | Сменить code@version, затемURLтого же key; завершить старые HEADпоследними; unmount | Поздние callbacks игнорируются; доступность/источники толькоактуальные; cleanup | Не исполнялся / NOT_RUN |
| TC-CAT-UI4 / AC-CAT-UI4 | 360CSSpx/desktop/200%zoom/keyboard; #19 в main | Просмотреть длиннуюинструкцию; открытьPDF/метроном; запись; результаты/решение/история | Действия доступны; нет overflow перекрывающего UI; #19/draftBusy/lifecycle сохранены | Не исполнялся / NOT_RUN |

## Фикстуры и среда

Java21/Maven wrapper, PostgreSQL17; Node>=22.12/npm lockfile. Использовать отдельную тестовую БД/schema, как существующие Postgres tests: не переключать current и не вставлять synthetic versions в пользовательскую БД. Все критерии/байты V1/V2 намеренно различаются; тестовые PDF/MP3 не являются новым музыкальным произведением/поставкой. Проверка parser/server traversal требует настоящего HTTP server, одного MockMvc недостаточно.

Минимальный V1/V2 набор: два UUID с одинаковым code и разными допустимыми версиями, только одна current=true; diagnostic_tasks.exercise_id=V1; V1/V2 metadata/criteria/minutes и четыре resource paths/байты различаются. Отдельный вариант PDFV1 отсутствует при существующемPDFV2. Clean-up только собственной тестовой schema.

## Команды разработчику

Точные команды соответствуют текущим CI scripts. Перед запуском backend настроить SPRING_DATASOURCE_URL/USERNAME/PASSWORD для изолированной тестовой PostgreSQL тем же способом, что в .github/workflows/java-ci.yml. Секреты в отчёт не копировать. Не запускать интеграцию против личной/продуктивной БД.

Из backend:
```bash
GUITAR_MENTOR_TEST_POSTGRES=true ./mvnw --batch-mode verify
```
Проверить, что новые Postgres cases реально выполнены, а не SKIPPED. Миграционный upgrade case запускается с V16 и существующим назначением; clean install отдельно. Новые HTTP tests должны проверять тело/code ошибок GET и отсутствие тела HEAD. Аудиоисследования не являются предметом приёмки этой тройки.

Из frontend:
```bash
npm ci
npm run build
npm test
npm run test:intake
npm run test:tuner
```
Добавить осмысленные component/contract tests нового поведения в существующий runner, сохранить regression tests #19/метронома/тюнера. Отдельный скрипт допустим, если он включён в CI. Команды здесь ещё НЕ запускались в рамках подготовки, и зелёный старый main не является доказательством нового поведения.

Для локального smoke запустить приложение штатной инструкцией репозитория на feature-ветке и открыть Vue через Vite proxy. Backend на localhost:8080, проверить:
```bash
curl -i 'http://localhost:8080/api/v1/exercises?instrumentCode=ACOUSTIC_GUITAR&techniqueCode=FINGERSTYLE'
curl -i 'http://localhost:8080/api/v1/exercises/GM-EB01/versions/0.8B'
curl -I 'http://localhost:8080/api/v1/exercises/GM-EB01/versions/0.8B/score'
curl -i 'http://localhost:8080/api/v1/exercises/GM-EB01/versions/unknown'
```
Ожидания: metadata/criteria/30мин; detail200 pinnedURLs; scoreHEAD200/MusicXML безтела; unknown404 EXERCISE_NOT_FOUND. Отсутствующие физически PDF/MP3 дают404, их добавление в scope не входит. В браузере проверить назначение/F5, previewfallback, свёрнутыйPDF, RU/EN, долгуюинструкцию, сохранение результатов/решений #19.

## Gates передачи

Каждый PR ссылается на свою Issue через Refs, не Closes. Состав: реализация+необходимые миграции+тесты+документация; фактический SHA/CI/команды и ограничения. После исполнения разработчиком — Код-ревью/В очереди; после независимого review — Тестирование/В очереди. Самопроверка не заменяет review/QA/приёмку владельца. #85 разблокируется только после готовых API119/120; весь каталог пяти этюдов и музыкальная приёмка этой матрицей не закрываются.
