# #196 — проверки оригиналов личного архива

Дата: 07.11.2026. База: `2d9101dd`. Только синтетические аккаунты и разрешённые синтетические PDF/GP5. Реальный пользовательский корпус не переносился в репозиторий и не читался.

[Контракт и матрица main → #196](../development/issue-196-private-catalog-originals.md).

## Автоматические проверки

| Проверка | Результат / свидетельство |
|---|---|
| PUT своей строки + своего PDF и GP5; повтор PUT | PASS — `MusicCatalogOriginalHttpPostgresTest`, реальные транзакции PostgreSQL 17.6, security filter + CSRF |
| Скачанные PDF/GP5 побайтно равны загрузке | PASS — тот же тест вызывает существующий защищённый GET |
| Гость: привязка и скачивание; запись чужого аккаунта; чужой файл; несовместимая пара material/asset ID | PASS — 401/404, включая одинаковые sourceFileId двух аккаунтов и строку, существующую только у Alice |
| Нет CSRF | PASS — 403 до привязки |
| Путь / URL / дополнительные поля / неверные типы | PASS — 400; принимаются только два UUID |
| Без оригинала | PASS — UNAVAILABLE / original=null, включая отдельную строку рядом с привязанными |
| READY → DELETING / REJECTED; производный PDF вместо оригинала | PASS — ссылка перестаёт быть доступна; новая привязка отклоняется |
| Чужая ссылка, внедрённая прямо в БД | PASS — чтение скрывает UUID чужого материала и возвращает UNAVAILABLE |
| Библиотека выключена | PASS — `MusicCatalogOriginalControllerTest`, 503 и отсутствие вызова repository |
| Квоты и права при привязке | PASS — charged_files остаётся 2 после повторных привязок, rightsStatus не меняется |
| Существующие ограничения загрузки и хранилище | PASS — `LibraryOriginalControllerTest`, `LibraryOriginalPostgresTest`, `LibraryOriginalHttpPostgresTest`, `LibraryObjectStoreTest`: размер, квота, число файлов, PDF-сигнатура, расширения GP, path traversal, идемпотентность, байты, private download, recovery |
| Миграция на старых строках | PASS — `MusicCatalogOriginalMigrationPostgresTest`: отдельная схема V1–V34, запись до V35, upgrade; NULL-ссылка, прежние size/rights/visibility/path сохранены; физическое удаление asset обнуляет FK без удаления строки |
| Регрессия поиска и владения | PASS — `MusicCatalogRepositoryPostgresTest`, `MusicCatalogControllerTest` |
| Фронтенд | PASS — `npm test`: 247/247, без пропусков; `npm run build`: typecheck + production build |
| Реальный браузер | Выполняется отдельным CI `Private catalog originals browser`; окончательные ссылки и результат фиксируются в PR |

31 целевой Java-тест выполнен успешно без пропусков (девять классов выше). В этой среде Mockito запущен через premain `-javaagent` из установленного Maven artifact, PostgreSQL использует TCP без Unix-сокета. Первая локальная попытка Chromium остановилась до сценария из-за запрета Unix-сокетов среды; это не считается браузерным PASS. В CI браузерный тест не допускает пропуск: проверяются JUnit XML и `result.json`.

## Воспроизведение

В одноразовой PostgreSQL с настроенными `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`:

```bash
cd backend
GUITAR_MENTOR_TEST_POSTGRES=true ./mvnw -B -ntp \
  -Dtest=MusicCatalogControllerTest,MusicCatalogOriginalControllerTest,MusicCatalogRepositoryPostgresTest,MusicCatalogOriginalHttpPostgresTest,MusicCatalogOriginalMigrationPostgresTest,LibraryOriginalControllerTest,LibraryOriginalHttpPostgresTest,LibraryOriginalPostgresTest,LibraryObjectStoreTest test
```

Реальный браузер (Node 22.16+, Java 21, PostgreSQL 17):

```bash
cd frontend
npm ci
npx playwright install --with-deps chromium
cd ../backend
GUITAR_MENTOR_TEST_POSTGRES=true GM_TEST_REAL_BROWSER=true ./mvnw -B -ntp \
  -Dtest=MusicCatalogOriginalBrowserPostgresTest test
```

Java harness создаёт отдельные временные аккаунты и storage-root, запускает реальный Spring Boot на случайном loopback-порту, затем Node/Vite/Chromium. Локальный логин, CSRF, backend, БД, загрузка, привязка и download не подменяются. После сценария тест удаляет свои аккаунты/файлы. По желанию путь Chromium задаётся через `GM_TEST_BROWSER_EXECUTABLE`.

План браузерного теста: загрузить PDF и GP5 в «Мои материалы» → открыть архив → убедиться в отсутствии download у непривязанной строки → привязать каждый оригинал → скачать и сравнить SHA-256 и имя → проверить 390px без горизонтального overflow → отдельный аккаунт и гость не получают метаданные/байты. Evidence: `artifacts/catalog-196/browser/{result.json,browser.log,desktop.png,mobile.png}`, CI artifact `catalog-196-browser`.

## Саморевью

Проверены границы ownership на обеих сторонах связи, запрет клиентских путей/URL, role/state/format, блокировка удаления на время привязки, отсутствие повторного списания квоты, удаление FK и сохранение старых записей. UI не использует размер офлайн-индекса для скачивания и не принимает произвольный URL. Запросы отменяются при размонтировании; ответы и Blob download защищены проверкой текущего actor. Схема старых миграций, загрузчик, хранилище, выдача и публикации не переписаны.

В ходе проверки тестового upgrade исправлено восстановление search_path при возврате соединения в pool; затем вместе с migration test успешно выполнены существующие `LibraryOriginalPostgresTest`. Production-контракт из-за этого не менялся. Формального независимого ревью или пользовательской приёмки этот отчёт не заменяет.
