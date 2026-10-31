# FT-012-02 / #166 — общий поиск опубликованных материалов

Актуализация: 2026-10-31 · Реализация #218 · исправление Flyway #234 · текущий main `da8f73386ee9efea66d683dba779d435ca6acc07`.

## Итог

Функциональность backend общего каталога реализована и принята владельцем ранее. PR #218 объединён в main. После интеграции обнаружена коллизия V29 с миграцией #216; исправление PR #234 также объединено. В текущем main миграции не конфликтуют:

- `V29__manual_next_lesson.sql` (#216) сохранена без изменения содержимого и checksum.
- `V30__unicode_account_logins.sql` сохранена.
- `V31__library_publication_search_tags.sql` содержит исходное содержимое миграции тегов из #166.
- GitHub compare для PR #234 показывает только rename `V29__library_publication_search_tags.sql` → `V31__library_publication_search_tags.sql`, 0 additions / 0 deletions / 0 changes.
- Подтверждённая ранее локальная `flyway_schema_history`: V28, V29 manual-next-lesson, V30 применены успешно; публикационная V29 отсутствовала. Для этой истории новая V31 является следующей миграцией; ручное изменение history и Flyway repair не требуются.

## Область API

GET `/api/v1/community/publications` доступен только аутентифицированным допущенным пользователям, независимо от владельца публикации. Источник — только `library_publications.state='PUBLISHED'` и `library_materials.state='READY'`. Поиск не обращается к приватным материалам или импортированному музыкальному каталогу.

| Параметр | Правило |
|---|---|
| `q` | До 100 символов; Unicode trim/lower; буквальный case-insensitive поиск подстроки по title ИЛИ author; LIKE-символы %, _ и ! экранируются |
| `type` | COMPOSITION, ETUDE, EXERCISE, OTHER; регистр не важен |
| `tag` | Повторяемый параметр, до 10 тегов; нормализация trim/lower/deduplicate; OR между тегами, AND с другими фильтрами |
| `hasAudio` | По реальному исходному audio asset в опубликованном snapshot; до поддержки аудиоформатов в #167 true возвращает пустой результат |
| `limit` | 1–50, по умолчанию 20 |
| `cursor` | HMAC-SHA256 signed keyset cursor, привязанный к нормализованным фильтрам и limit; неверный формат, подпись или scope → 400 |

Сортировка: `created_at DESC, id DESC`. Cursor выдаётся только при наличии следующей страницы. Ответ no-store и не раскрывает ownerId, rightsEvidence, storage key, локальные пути, PRIVATE или скрытые snapshot.

Теги передаются необязательным полем `tags` при owner-only публикации и хранятся как нормализованные immutable значения на revision. Старые V28 idempotency keys без тегов сохраняют совместимость.

## Тестирование и доказательства

### GitHub Actions PR #234

Проверен merge-ref PR #234 (`24fcad582a1db4bcf239bd1ab333d912d68e846d`) и фактические логи:

- Java backend CI, run [40397327791](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/40397327791): SUCCESS.
- Vue frontend CI, run [40397327938](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/40397327938): SUCCESS.
- Java job использовал PostgreSQL 17.11; Maven: 361 tests, 0 failures, 0 errors, 4 pre-existing skips; BUILD SUCCESS.
- Flyway в PostgreSQL проверил и применил миграции до v31; отдельные Spring contexts повторно валидировали полный набор миграций. Bootstrap приложения с PostgreSQL/Flyway и HTTP smoke завершился OK.
- `LibraryPublicationPostgresTest`: 4/4 PASS.
- `LibraryPublicationHttpPostgresTest`: 1/1 PASS.
- В логах CI не обнаружена ошибка Flyway или duplicate version.
- Vue CI: SUCCESS; frontend regression/typecheck/build/browser smoke пройдены. UI общего каталога не входит в #166.

### Реализация поиска (PR #218)

На исходном feature head `2c8a4345db7c9e66cbd63f7f4459fff5f16cb0ea` Java backend CI и Vue frontend CI были SUCCESS. PostgreSQL/Spring Security тесты покрывали cross-account admitted search, title/author, type, нормализацию тегов, AND/OR, LIKE escaping, keyset pagination, scope/tamper cursor, отзыв публикации, отсутствие PRIVATE/WITHDRAWN/HIDDEN и anonymous 401. В PR #234 повторно выполнен полный Java/PostgreSQL suite и HTTP smoke на актуальном merge-ref.

### Ограничения доказательств

- Подтверждённая локальная история schema_history получена ранее от владельца; подключиться к его локальной PostgreSQL из этой сессии и повторить upgrade непосредственно на ней невозможно. CI выполняет полный PostgreSQL 17 migration/bootstrap на чистой тестовой БД и проверяет upgrade migration suite до V31.
- Нагрузочный benchmark для крупного корпуса и EXPLAIN ANALYZE не выполнялись. B-tree индексы не ускоряют leading-wildcard substring; при необходимости pg_trgm следует оценивать отдельно.
- UI общего каталога — #168; аудиоформаты — #167; связь разных аранжировок с Work — #204. Они не входят в #166.
- `hasAudio=true` остаётся корректно пустым, пока #167 не добавит допустимые аудиофайлы.
- Ручной UI UAT не применим: UI каталога ещё не реализован. API и backend проверяются через PostgreSQL/Spring Security HTTP tests.

## Трассировка требований

- AC-COM-03: cross-account admitted search, q title/author, type, tags, hasAudio.
- AC-COM-05: исключение DRAFT/PRIVATE, WITHDRAWN, HIDDEN, DELETING из list/detail и косвенных данных.
- AC-COM-06: стабильная signed keyset pagination и scope-bound cursor.
- Безопасность: anonymous deny, admitted cross-account read, запрет приватного раскрытия и отзыв публикации.

## Итоговое решение

Функциональность #166 принята владельцем ранее; первоначальная реализация и миграционный конфликт устранены в main. PR #218 и PR #234 объединены. Повторный CI на исправлении успешен. Issue #166 может быть закрыта как выполненная. Production, реальные пользовательские данные, #227, #167, #168 и #204 не изменялись в рамках этой работы.
