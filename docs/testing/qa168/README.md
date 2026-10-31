# #168 — общий UI материалов: реализация, ревью и приёмка

| Версия | Дата | Изменение |
|---|---|---|
| 1.0 | 31.10.2026 | Общий поиск, публикации, media UI, ALL-tags SQL fix, матрица и QA |

[PR #258](https://github.com/roafo1989/guitar-mentor-ai/pull/258). Контракт — верхний блок #168 «Готовность к разработке — 31.10.2026». Перед кодом учтён main 7b940366 поверх базы b9f922bb, повторная сверка main 202925f1 (только пустой файл чужой #170, не изменён). AGENTS.md отсутствует в repo и родительских каталогах. Применены development-workflow.md/kanban-workflow.md; исторические блокировки/URL не используются.

## Поведение

В существующем `/library` по умолчанию «Мои», рядом «Общие». URL содержит только режим/фильтры. Серверный поиск: q/type/все tags/hasAudio, debounce300ms, opaque cursor, «Показать ещё». Смена фильтра отменяет запрос и сбрасывает карточку/пагинацию; guards защищают от поздних ответов. Retry чтения не вызывает mutation.

Своё управление открывается названием строки «Мои»: private material + owner history, статусы личный/published revision/изменённый черновик/withdrawn/hidden. Сохранение никогда не публикует. Native dialog требует явного выбора файлов, per-asset basis/evidence/атрибуции и подтверждения доступа admitted пользователям. Provenance выводится из basis, исключая несовместимые пары. Evidence не отображается другим.

Обновление явно делает withdraw → publish с объяснённым промежутком. При частичном сбое остаются точный статус, форма и ключ для идентичного ручного retry. Изменение payload даёт новый ключ. Конфликты версии не повторяют mutation; HIDDEN не восстанавливается.

Фото/аудио добавляются только к существующему material. Capabilities задают роли/форматы/лимиты; создание остаётся PDF/GP. Показывается indeterminate progress до серверного ответа, без выдуманных процентов. Отдельные сообщения для quota/last score/published-reference/unavailable/limits. Media off сохраняет исходную библиотеку.

ScoreReader один: exercise descriptors сохранены, добавлены exact material/asset и publication/asset same-origin маршруты. Sandbox/CSP/nonce/WindowProxy/MessageChannel/ограничения сохранены. PDF.js print intent устраняет зависание requestAnimationFrame у iframe ниже viewport; проверено реальным Chromium на существующем проектном score_v08B.pdf. Фото — sanitized preview, оригинал скачивается отдельно; audio controls без autoplay с явной ролью. GP скачивается с честным сообщением об отсутствии конвертации.

При смене карточки/режима/аккаунта/logout/unmount отменяются запросы, очищаются данные, останавливается audio, отзываются blob URLs, снимается viewer. Private PDF/GP CRUD, архив и Light Minimal сохранены. Новых миграций нет. Production/PILOT/реальные данные/секреты/флаги стенда не использовались. #204/#169/#170/#40/#245 не изменялись.

## Блокер полного соответствия

**Принятый backend не поддерживает публикацию подмножества source files.** `LibraryPublicationController.publish` требует `payload.assets.size()==всех library_assets` и права для каждого файла, включая уже добавленное необязательное аудио. Подмножество READY assets отклоняется `PUBLICATION_INVALID_RIGHTS`. UI явно требует отдельный выбор/права на все файлы; публикация материала без аудио работает, но исключить существующее аудио нельзя.

Серверный lifecycle не расширен: пользователь разрешил только точечный search predicate fix. Для полного соответствия нужна резолюция владельца: разрешить минимальную поддержку выбранного READY-подмножества (с обязательным score/photo, правами и regression) либо принять ограничение «публикуется весь набор». До решения полный #168 не объявляется PASS, PR draft, merge/закрытие не выполняются.

## Матрица критериев

| Критерий | Реализация | Доказательство / остаток |
|---|---|---|
| AC168-01 | Private PDF/GP CRUD, архив, прежний путь | 240 frontend regression + 57 intake/component; fixtures обновлены до реального FileView #167; Chromium private edit/view |
| AC168-02 | Server filters ALL tags, cursor, abort/guards/reset/empty | Service + Chromium stale-search/cursor/empty; PostgreSQL A-only/B-only/A+B×2 с limit1 и opaque cursor |
| AC168-03 | Управление только своим material, чужое чтение snapshot | Existing PostgreSQL/MockMvc rights + новый независимый TCP HTTP A/B/guest |
| AC168-04 | Rights, явность, no-audio, partial withdraw→publish, стабильный key/revisions | Unit + Chromium + live replay; **PARTIAL: выбор подмножества source assets, см. блокер** |
| AC168-05 | Capabilities, photo/audio upload/delete/limits/flag off | Chromium DOM + mock HTTP: роли, фото/аудио, count, published-reference; unit quota/last-score mapping; существующие #167 PostgreSQL media/security tests в CI |
| AC168-06 | Один sandbox reader, exact routes, sanitized photo, audio cleanup, GP | Negative URL/foreign-ID unit; Chromium private/common PDF canvas, sandbox, photo и audio teardown; exercise runner в CI |
| AC168-07 | Разные 401/404/409/412/413/415/429/503, Retry-After, account cleanup | 7 service/error tests; Chromium read-only retry и смена auth actor с остановкой audio; private component race regression |
| AC168-08 | RU/EN,360/768/1440,200%,keyboard,labels/status | Chromium screenshots, Escape/focus restore/Tab. HTTP browser-прогона **mocked**, не независимые серверные сессии |
| AC168-09 | CommunityLibraryLiveHttpPostgresTest | **Настоящий TCP HTTP/PostgreSQL**, RANDOM_PORT, отдельные HttpClient/CookieManager A/B/guest, local login+CSRF, synthetic upload, publish→B read→draft edit→snapshot stable→withdraw→B404 detail/bytes. Не MockMvc |

## Проверки и точный SHA

Финальные SHA/CI URLs и результаты зафиксированы в итоговых комментариях #168/PR258 после создания commit; комментарии не меняют проверенный SHA. Здесь один browser-result.json и выбранные скриншоты; tracked229/232 не перезаписываются.

Локально: `npm test` 240 PASS; `npm run test:intake` 57 PASS; `npm run test:community` 7 PASS; `npm run build` typecheck+Vite PASS; `npm run test:community-browser` реальный Chromium с mock HTTP PASS; Java21 package/test compilation PASS. Прежний CDP exercise runner локально ограничен socket() policy; его штатный прогон выполняет CI. Отдельная локальная Playwright/pipe проверка прежнего exercise harness прошла.

Промежуточный Java CI c79e742a, run41240570341: PostgreSQL17, **403 tests/0 failures/0 errors/4 прежних skips**, новый LIVE HTTP и ALL-tags regression PASS, Spring Boot/Flyway HTTP smoke PASS. Необходимые проверки повторяются на финальном SHA, итог фиксируется в комментариях.

Команды разработчика/CI (владельцу повторять не требуется):

```sh
cd frontend
npm ci
npm test
npm run test:intake
npm run test:community
npm run build
CHROME_PATH=/usr/bin/google-chrome npm run test:community-browser
npm run test:score-browser
```

Java: штатный java-ci.yml, Java21/PostgreSQL17, GUITAR_MENTOR_TEST_POSTGRES=true, `./mvnw --batch-mode verify`. Только isolated QA DB. Новый HTTP test включает security.local-http=true только в своих test properties при loopback binding для реальных cookie jars; production конфигурации сохранены.

## Ревью и исправления

Self-review точного diff (не независимое human review):

- ANY→ALL, исправлено OR-ожидание, добавлена отдельная PostgreSQL cursor regression.
- Реальные DTO/FileView и parent+asset pinned routes; некорректные данные отклоняются.
- Исправлены offscreen PDF scheduling и сравнение descriptor по значению вместо identity inline object.
- Исправлены сброс media-формы при refresh той же карточки, fallback фокуса после исчезновения trigger, сохранение формы/key после частичного сбоя.
- Private CRUD/download получили AbortSignal; переход на другую карточку во время private mutation запрещён.
- No-content не принимается как Material/Publication; download проверяет byteSize.
- Новые модули отформатированы. Playwright pin — только dev dependency; runtime зависимостей не добавлено.

Результаты принадлежат одной qa168 папке. Ненужные failed screenshots удалены. PowerShell-скрипты, второй каталог, второй viewer, Work merging и конвертер не создавались.
