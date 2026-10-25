# FT-011-05 / #164 — независимый протокол сквозной приёмки личной библиотеки

Дата: 2026-10-25. RC: PR #210 (`test/164-library-end-to-end-acceptance`), основание `main` после merged #160–163. Статусы ниже относятся к наличию проверок и их реальному запуску, а не к обещанию результата.

## Схема тестирования

- Только отдельная приватная library storage root и тестовые аккаунты. Не использовать индексированный архив и реальные личные оригиналы.
- Java MockMvc + защищённый security chain + два OIDC identity + PostgreSQL + временный файловый каталог: `LibraryAcceptanceHttpPostgresTest`; запуск только при `GUITAR_MENTOR_TEST_POSTGRES=true`.
- Backend regression suite из #160–162: `LibraryOriginalHttpPostgresTest`, `LibraryOriginalPostgresTest`, `LibraryOriginalConcurrencyPostgresTest`, `LibraryDeletionWorkerPostgresTest`, `LibraryMaterialPostgresTest`, `LibraryObjectStoreTest`.
- Frontend mounted regression из #163: `library-mounted.test.mjs`, `private-library.test.mjs`; общие Chrome responsive tests не равны тесту личной библиотеки.
- Windows launcher `scripts/local-auth-unified.ps1`: стабильный секрет HMAC хранится DPAPI за пределами репозитория, берётся из `LOCALAPPDATA/GuitarMentorAI/local-auth-unified.json`; локальная private library включена по умолчанию с явным opt-out. Не требуется каждый запуск вставлять или отправлять секрет.
- Оценка безопасности: вход по session/OIDC, CSRF на POST/PATCH/DELETE, разделение owner account, GET no-store, SHA-256/байты оригинала, переиспользование upload idempotency key, состояние DELETING и очередь retry.

## Матрица TC-LIB-01…24

| TC | Проверка | Автоматическое основание | Отдельный Windows UAT |
| --- | --- | --- | --- |
| 01 | Открытие /library и навигация | Vue CI route contracts | NOT_RUN |
| 02 | Приватный пустой список | HTTP B list + Vue empty state | NOT_RUN |
| 03 | PDF upload 201 (multipart) | New HTTP acceptance | NOT_RUN |
| 04 | Переживает перезагрузку страницы | HTTP persisted GET | NOT_RUN |
| 05 | GP/GP3/GP4/GP5/GPX/GTP original | GP PostgreSQL tests | NOT_RUN |
| 06 | PDF original SHA-256 совпадает | New HTTP acceptance | NOT_RUN |
| 07 | GP original byte-exact | GP PostgreSQL tests | NOT_RUN |
| 08 | Личная карточка list/detail | New HTTP acceptance | NOT_RUN |
| 09 | Изменить metadata PATCH/If-Match | New HTTP acceptance | NOT_RUN |
| 10 | Конфликт ревизии 412 | New HTTP acceptance | NOT_RUN |
| 11 | CSRF unsafe requests → 403 | New HTTP acceptance | NOT_RUN |
| 12 | Другому account список пуст | New HTTP acceptance | NOT_RUN |
| 13 | Чужая карточка GET → 404 | New HTTP acceptance | NOT_RUN |
| 14 | Чужой файл GET → 404 | New HTTP acceptance | NOT_RUN |
| 15 | Чужие PATCH/DELETE → 404 | New HTTP acceptance | NOT_RUN |
| 16 | Неаутентифицированный GET → 401 | New HTTP acceptance | NOT_RUN |
| 17 | Скачать оригинал с no-store/nosniff | Original HTTP security tests | NOT_RUN |
| 18 | HEAD без body | Original HTTP tests | NOT_RUN |
| 19 | Удаление owner DELETE 202 | New HTTP acceptance | NOT_RUN |
| 20 | Сразу после DELETE GET файла →404 | New HTTP acceptance | NOT_RUN |
| 21 | Повтор DELETE идемпотентен | New HTTP acceptance | NOT_RUN |
| 22 | Физическое удаление + quota | Deletion worker PostgreSQL tests | NOT_RUN |
| 23 | Ошибка диска, retry, восстановление | Deletion worker PostgreSQL tests | NOT_RUN |
| 24 | Исходные media / ретенция / UI | Existing media regressions + Vue CI | NOT_RUN |

Статус выполнения автоматических колонок выставляется только после проверки **конкретного workflow-run последнего SHA PR #210**. Статусы Windows UAT не могут становиться PASS на основании CI.

## Минимальная локальная приёмка

Один сеанс под текущим разрешённым аккаунтом, только одноразовый PDF: открыть `https://guitar-mentor/library` → «Добавить материал» → выбрать PDF и загрузить → F5 → скачать и сравнить с оригиналом → изменить название → удалить через меню `⋯` → F5 (удалённая карточка не возвращается). Отдельно для полной #164 потребуется второй допущенный аккаунт либо автоматизированный HTTP контур CI для подтверждения изоляции, уже включён в тесты.

## Откат и эксплуатационные риски

- Никакого изменения схематических таблиц и данных пользователя ради теста: внешние объекты пишутся только во временную папку для CI; PostgreSQL-тест использует транзакционный rollback.
- Перезапуск процесса не симулируется внутри одного `MockMvc` контекста: persistent recovery проверяется другими PostgreSQL-тестами очереди и отдельным Windows UAT.
- Включение личной библиотеки относится только к защищённому локальному лаунчеру (loopback/Caddy). Нельзя применять те же defaults на публичном сервере.
- Автоматический тест не использует файл из пользовательского архива; тестовый PDF — фикстура.
- Issue #164 закрывать только после проверки CI и явной фиксации результатов пользовательского UAT/приёмки. Принятие UI #163 не равно завершению #164.
