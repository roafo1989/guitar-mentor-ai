# FT-012-02 / #166 — общий поиск опубликованных материалов

Дата: 2026-10-25 · PR #218 · основа: реализованный publication lifecycle #165.

## Область задачи

GET `/api/v1/community/publications` доступен **только аутентифицированным допущенным** пользователям, независимо от owner публикации. Источник данных — строго `library_publications` со `state='PUBLISHED'` и `library_materials.state='READY'`; **никакой** прямой поиск по приватным `library_materials` или импортированному `music-catalog` корпусу не выполняется. GET detail и авторизованная выдача исходных файлов уже реализованы в #165, их политики не ослабляются.

HTTP параметры:

| Параметр | Правило |
|---|---|
| `q` | Необязательная строка до 100 символов; Unicode trim/lower, буквальный case-insensitive поиск подстроки по опубликованным `title` **ИЛИ** `author`; символы SQL LIKE `%`, `_`, `!` экранированы и не являются wildcard |
| `type` | Необязательный `COMPOSITION`, `ETUDE`, `EXERCISE`, `OTHER`, нечувствительно к регистру |
| `tag` | Необязательный повторяемый ключ, до 10 тегов, 1–40 Unicode букв/цифр/пробелов, `_` и `-`; trim/lower/deduplicate; **OR внутри списка тегов**, AND с остальными фильтрами |
| `hasAudio` | `false` по умолчанию, `true` требует соответствующего физическому исходному audio asset published snapshot; пока в #165 допустимы только PDF/GP, поэтому `true` корректно возвращает пустой список до расширения типов в #167 |
| `limit` | 1–50, по умолчанию 20 |
| `cursor` | Непрозрачный HMAC-SHA256 ключ страницы; привязан к нормализованным `q`, `type`, `tag`, `hasAudio`, `limit`; чужой scope, испорченная подпись или формат → 400 |

Результат: `{"items":[{"id","title","author","type","version","publishedAt","tags","hasAudio","detailUrl"}],"nextCursor":null}`. Сортировка строго `created_at DESC,id DESC`; nextCursor выдаётся только при наличии следующей страницы. `Cache-Control: no-store`. Нет totalCount, ownerId, rightsEvidence, storage key, локального пути, данных PRIVATE или скрытых snapshot.

Теги при публикации передаются необязательным полем `tags` в существующем owner-only POST `/api/v1/library/materials/{id}/publications`, например `"tags":["Fingerstyle","Blues"]`. Сохраняются в аддитивной Flyway V29 как нормализованные immutable значения, до 10 уникальных тегов на publication. Пустые/непереданные теги для существующих V28 публикаций имеют прежний idempotency fingerprint: неизменённый повтор POST того же старого запроса должен по-прежнему отвечать 200. При непустом наборе тегов fingerprint учитывает их нормализованный набор.

## Трассировка требований и тесты

- **AC-COM-03**: cross-account admitted search, q title/author, type, tags, strict AND между группами и OR внутри tags, hasAudio truthfully tied to original.
- **AC-COM-05**: DRAFT/PRIVATE, WITHDRAWN, HIDDEN, DELETING не видны ни в list, ни в detail/bytes и не образуют косвенные счётчики.
- **AC-COM-06**: signed cursor scope, стабильное keyset листание; нельзя передавать cursor при других фильтрах.
- Real PostgreSQL: `LibraryPublicationPostgresTest.sharedSearchFilterScopedPaginationAndRevocation` — два аккаунта, three publications, отдельный draft, фильтры, теги, две страницы, неправомерная подмена cursor, withdraw и 404 старого detail.
- Real Spring Security HTTP: `LibraryPublicationHttpPostgresTest.publisherAndReaderSeparatedAndWithdrawRevokesDirectUrl` — анонимный GET → 401, B видит A, затем revoke → пустой список.
- Negative client requests: 400 для неверного type, filter and cursor; 503 если library отключена.
- Полный `Java backend CI` выполняет тесты на PostgreSQL с Flyway V29; независимый `Vue frontend CI` — регрессию текущего клиента. Финальные результаты фиксировать по текущему SHA, не по старой успешной ветке.

## Границы и нагрузка

Индексы V29 поддерживают type/date, lookup тега; `ILIKE/LIKE '%substring%'` **не ускоряется B-tree** `lower(title)`/ `lower(author)`. Для крупного корпуса нужен отдельный benchmark с `EXPLAIN (ANALYZE,BUFFERS)` и, при необходимости, `pg_trgm` индекс (потребует проверки прав на extension). Нельзя объявлять требования по задержке поиска выполненными без измерения. Задача #204 — объединение разных аранжировок в один Work; #167 — аудио/фото; #168 — UI общего каталога; они не включены в данный PR. Импортированный музыкальный индекс не публикуется автоматически.

## Ручная приёмка

На фронтенде UI общего каталога до #168 отсутствует. Для проверки #166 достаточно защищённого HTTP API и тестов двух допущенных аккаунтов; никаких реальных файлов пользователя/массового импорта использовать нельзя. Пользовательская приёмка PR обязательна до merge; без неё #166 не закрывать.
