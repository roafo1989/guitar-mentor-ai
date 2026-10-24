# TC-194 — Приватный API музыкального каталога

Основание: #194, PR #202, схема Flyway V27, спецификация `docs/development/issue-194-private-music-catalog-api.md`.

## Результаты уже выполненных проверок

| TC | Проверка | Фактический результат | Статус |
|---|---|---|---|
| TC-194-01 | Java compile, полный backend CI и PostgreSQL migration | Java backend CI на PR #202: success | PASS |
| TC-194-02 | Vue frontend regression | Vue frontend CI на PR #202: success | PASS |
| TC-194-03 | Репозиторий: две учётные записи, чужие work/arrangement/files недоступны | `MusicCatalogRepositoryPostgresTest.ownerIsolationAndFilesHaveNoPaths`, в CI success | PASS |
| TC-194-04 | Кириллический поисковый запрос и исполнитель | `unicodeLiteralSearchFormatParserAndPageBoundaries`, в CI success | PASS |
| TC-194-05 | Поиск без подстановки SQL-wildcards | `%` как буквальная строка в PostgreSQL-тесте | PASS |
| TC-194-06 | Формат GPX, parserStatus и границы offset-страниц | PostgreSQL-тест, в CI success | PASS |
| TC-194-07 | Отказ без `CurrentActor` и bad-input 400 | `MusicCatalogControllerTest`, в CI success | PASS |
| TC-194-08 | Ответы списка с `Cache-Control: no-store` | `MusicCatalogControllerTest`, в CI success | PASS |
| TC-194-09 | Изоляция чужой карточки произведения в контроллере | `MusicCatalogControllerTest`, в CI success | PASS |
| TC-194-10 | Источник отдаёт только filename, формат, байты, права и `UNAVAILABLE`, без Windows-relative path | Статический review DTO и repository SQL + PostgreSQL-тест | PASS (code-level) |
| TC-194-11 | HTTP без сеанса возвращает 401, чужой сеанс — 404, без контента | Не исполнялся отдельно против живой авторизации | NOT_RUN |
| TC-194-12 | Поиск в реальной базе (66 649 works): Yesterday, Ветер перемен, пустой q, сортировка | Нет доступа к локальному экземпляру пользователя | NOT_RUN |
| TC-194-13 | p50/p95 latency и EXPLAIN ANALYZE на реальном наборе 77 596 | Нет доступа к локальной PostgreSQL | NOT_RUN |
| TC-194-14 | Нет путей на D:, ссылок скачивания и утечки между сессиями в реальном JSON | Нет доступа к локальной сессии пользователя | NOT_RUN |
| TC-194-15 | Курс/неустановленные права не превращаются в публикацию или доступные медиа | На уровне кода все файлы `UNAVAILABLE`; реальный HTTP smoke ещё не выполнялся | PARTIAL |

## Локальные HTTP smoke проверки (после checkout PR #202 на тестовую ветку)

Запускать только в отдельном тестовом окружении после сохранения локальных незакоммиченных изменений. Требуется активная авторизованная сессия приложения (обычный браузер после входа), либо Postman с её cookie и соблюдением правил тестовых аккаунтов.

1. В браузере с выполненным входом открыть DevTools → Network и выполнить GET `/api/v1/music-catalog/works?q=Yesterday&limit=5` с учётом same-origin/proxy. Ожидается 200, `items`, `nextOffset` и `Cache-Control: no-store`.
2. Проверить `q=Ветер`, `format=gpx`, `parserStatus=PARSED`, `limit=1&offset=0`, затем `offset=1`. Ожидается корректная фильтрация и отсутствие повторов при неизменных данных.
3. Из `items[0].id` перейти на `/works/{id}`, затем на `/arrangements/{id}`. Ожидается метаданный ответ с `originalAvailability=UNAVAILABLE`; не должно быть `relativePath`, `downloadUrl`, `objectKey`, локальных путей.
4. В приватном окне без входа тот же GET → 401. Для другой реальной учётной записи ответ по ID чужого произведения → 404; собственный поиск не выдаёт чужие произведения.
5. Bad inputs: `limit=0`, `limit=51`, `offset=-1`, `sort=bogus`, `format=exe` → 400. Пустой `q` может выдавать каталог (ожидаемо).
6. После выхода повторить запрос — данные не должны быть доступны; браузерные кэши не должны сохранять личный каталог.

## PostgreSQL измерение на реальных данных (в pgAdmin, без персональных данных в отчёте)

Выбирать UUID собственного ACTIVE аккаунта. В pgAdmin Query Tool заменить плейсхолдер в каждой команде самостоятельно.

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT w.source_work_id, w.title
FROM music_catalog_works w
WHERE w.owner_account_id = '<OWNER_UUID>'
  AND (position(lower('Yesterday') in lower(w.title))>0
       OR position(lower('Yesterday') in lower(w.artist))>0)
  AND EXISTS (
    SELECT 1 FROM music_catalog_arrangements a
    WHERE a.owner_account_id=w.owner_account_id
      AND a.source_work_id=w.source_work_id)
ORDER BY w.normalized_title,w.normalized_artist,w.source_work_id
LIMIT 20 OFFSET 0;
```

Это близкая диагностическая выборка, **не точная копия всего SQL API**. При оценке производительности учитывать, что `lower(...) + position(...)` обычно требует сканирования значительной части работ, а `OFFSET` на глубоких страницах может быть дорогим. Записать `Execution Time` и план без названий личных файлов. Порог p95 согласовать после замеров, не выдумывать его заранее.

## Сводка

- CI/synthetic: PASS.
- Фактические HTTP/security проверки на работающем приложении и производительность на локальном полном корпусе: NOT_RUN.
- Запрет на публикацию оригиналов и отсутствие ссылок: code review PASS, end-to-end NOT_RUN.
- Решение о приёмке — после минимального live smoke и фиксации замечаний (при обнаружении).
