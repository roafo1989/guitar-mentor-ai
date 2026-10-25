# CAT-LOCAL-03 / #195 — Интерфейс личного импортированного архива

| Версия | Дата (МСК) | Изменение |
| --- | --- | --- |
| 1.0 | 25.10.2026 | Full-анализ поверх принятого API194: навигация, поиск, карточка, границы данных, ошибки, конкуренция и приватность. |

## Паспорт и источники

Профиль **Full**: несколько запросов/состояний, приватные данные, смена сессии и интеграция с shell. Автор — Codex; выполнена самопроверка СА, независимое review не заявлено. Анализ завершён, следующий этап **Оценка / В очереди**. Код195 не написан, новая QA не выполнялась. Предварительные8ч не являются согласованной оценкой или выбором следующей разработки.

Трассировка: БТ0.21, BT-012/013/021/022/023 → ТЗ0.31, US-017/FT-017-03 → CAT-LOCAL-03/#195 → #192 → EPIC-004/#154. Применимы NFR-001/004/006/007/008, SEC-002/003/005/008. [MUSIC-01](MUSIC-01-shared-catalog-ownership.md), [Light Minimal](UX-02-light-minimal-handoff.md), [стандарт Full](../project/specification-standard.md).

База исходников — main **d310b344386b0323d8fa2df0b0d127e65b934d78**, включающий принятые194/PR206 и162/PR207. Сверены MusicCatalogController/Repository, V27, auth.ts/AuthGate, Router/App, i18n и API194 runbook. Runtime читать из main, а не из документационной ветки со старым приложением. PR208/#163 открыт отдельно, наблюдавшийся head6c230c5: меняет App.vue, router, RU/EN, Home и добавляет `/library`. Это кандидат, а не принятый интерфейс195.

## Пользовательский результат и границы

Как пользователь с импортированным личным индексом, я хочу найти произведение по названию/исполнителю и сравнить его аранжировки, чтобы понять, какие материалы есть в моём архиве. Числовой SLA поиска не выдумывается: скорость зависит от принятого offset API и локальной базы; измерения входят в будущую QA.

Уже реализованы194: account-scoped GET списка, Work detail и Arrangement detail; индекс193 и V27. **Все оригиналы сейчас UNAVAILABLE.** В195 входят Vue UI, безопасный transport/DTO, локализация/адаптивность, запросы и очистка состояния. Не входят импорт CSV, редактирование метаданных, upload/delete, публикация, объединение Work, скачивание, PDF/audio preview, конвертация, обучение/ИИ, новый Java API или миграция.

| Раздел | Задача | Данные и доступ |
| --- | --- | --- |
| Мои материалы `/library` |163|Личные загруженные пакеты, upload/edit/download/delete; отдельный `/api/v1/library/materials`|
| Личный архив `/music-archive` |195|Личный импортированный индекс, только чтение метаданных `/api/v1/music-catalog`|
| Общая библиотека |165/166/168/204|Явно PUBLISHED материалы всех admitted пользователей и общие Work; ещё не реализована этой задачей|

Нельзя снимать owner predicate194 ради общего доступа. Роль OWNER не условие видимости страницы: она доступна любому authenticated admitted аккаунту, но API возвращает только его индекс; у нового LEARNER обычно пусто. Нельзя подменять это демонстрационными данными. Личный архив не зависит от заполненности музыкального профиля/инструмента/Learner selection.

## Зависимости и решения до старта

| Зависимость | Факт | Решение |
| --- | --- | --- |
|194/PR206|closed/completed, merged2885652|Функциональный gate снят; API не переделывать|
|193/190|Приняты индекс и схема|Не импортировать повторно и не требовать пакет CSV для разработки UI|
|163/PR208|OPEN, параллельные изменения shell/routes/локалей|Не обязательный gate195. Брать последний main; при merge163 сохранить `/library`, его nav и тексты. Не копировать неподтверждённый runtime из PR208 без review|
|196|Бэклог/Ожидание оригиналов и storage|Не блокирует metadata UI. Кнопок открыть/скачать в195 нет|
|165–168/204|Общая модель отдельно|Не блокируют private UI. Ни доступа к чужим файлам, ни bulk publish|

Блокирующих продуктовых вопросов по выбранному metadata scope нет. Предварительные технические решения в этом документе — результат СА, не новые ответы владельца. Формальная оценка/выбор в разработку выполняются по [Kanban](../project/kanban-workflow.md).

## UI и сценарии

### Вход и компоновка

- Добавить именованный lazy route **`/music-archive`**, имя `music-archive`. Весь route остаётся внутри существующего AuthGate. Не использовать `/library` и его компоненты для индекса.
- Light Minimal: ссылка **«Личный архив» / “Private archive”** в существующем account popover после профиля, только `auth.actor`; при переходе меню закрывается. Не добавлять ещё один постоянный пункт в уже заполненный основной nav. Legacy shell: обычная ссылка в nav только при actor, без нового дизайна header. В local prototype без actor пункт скрыт, прямой route показывает «Архив доступен после входа» и не делает запросы. Не включать фиктивную локальную авторизацию.
- Заголовок страницы «Личный музыкальный архив» / “Private music archive”; подпись «Сведения из вашего импортированного архива. Оригиналы пока недоступны в приложении.» / “Metadata from your imported archive. Original files are not available in the app yet.”
- Максимальная ширина1280px, gutters24px (до600px —16px), gap24px, существующие `--gm-*` tokens. На ≥1024px список и выбранная карточка — grid `minmax(280px,.9fr) minmax(0,1.1fr)`; ниже — одна колонка с карточкой после списка. Ни canvas, ни собственной модалки.
- Верхняя строка формы: label поиска, input, «Найти»/“Search”, «Сбросить»/“Reset”. Вторая строка — формат, разбор, сортировка:3колонки ≥768px,1колонка ниже. Контролы не переполняют320px/200%zoom. Названия/имена файлов переносятся, не расширяют layout.
- Список содержит title, artist и status идентичности Work; строка — семантическая кнопка с явным выделением выбранной. Нет числа файлов/аранжировок в списке: API этого не даёт. Не показывать вымышленные обложки, сложность или AI-рекомендации.
- При выборе Work загрузить карточку одним detail GET, focus heading (`tabindex=-1`) после результата; на mobile прокрутить к ней с учётом sticky header, без анимации при reduced-motion. «К результатам» возвращает focus/scroll к выбранной кнопке и не повторяет search. Desktop сохраняет список рядом. Закрытие карточки очищает detail, не результаты.

### Форма, поиск и страницы

1. При наличии actor выполнить первый запрос с пустымq, format/parserStatus пустые, sort=title, limit20, offset0. До200 показывать loading, не empty.
2. Держать отдельно `draft` формы и immutable `appliedQuery`. Поиск запускается только submit/Enter; нет запроса на каждую букву. Все3select применяются вместе с формой. Пока draft изменён, пагинация недоступна с подсказкой «Примените изменения поиска» / “Apply the search changes”. Исходные результаты можно просматривать, но не выдавать их за новую выборку.
3. Submit валидируетq, нормализует внешние пробелы, фиксирует appliedQuery, сбрасывает offset/selection и отменяет прежние list/detail. Reset немедленно восстанавливает defaults и первую страницу. Repeated submit того же запроса во время loading не запускает дубль.
4. `q` — буквальная case-insensitive подстрока title/artist, включая серверные normalized поля. Не обещать fuzzy/перевод/исправление опечаток; `%`/`_` не wildcard. Кириллица и латиница работают. Пустойq разрешён.
5. Форматы: Все, PDF, GP, GP3, GP4, GP5, GPX. **GP означает только расширение `.gp`, не все версии Guitar Pro.** GTP не добавлять: accepted API194 его не поддерживает, хотя upload163 — другой контракт.
6. Разбор: Все (`''`), «Файл разобран» / “Parsed file” (`PARSED`), «Только сведения» / “Metadata only” (`METADATA_ONLY`). Эти коды подтверждены тестами194. Не придумывать точный код ошибок парсера; неизвестные статусы могут встречаться и отображаются нейтрально. Фильтр применяется к Arrangement, а не к Work/file.
7. Сортировка: Название A–Я/title; Исполнитель/artist; Название Я–A/title_desc. Порядок и tie-breaker задаёт сервер; клиент повторно не сортирует.
8. Pagination — «Назад»/“Previous” и «Далее»/“Next”, **замена**20строк, не бесконечный append. Вперёд только по `nextOffset`, назад по посещённому offset при том же appliedQuery. `null` — конец; общего count/последней страницы/процентов API не даёт. Можно показать «СтраницаN», вычисленную из offset20. Нет скрытой фоновой предзагрузки.
9. На maxoffset200000 сервер может вернуть nextOffset200020: не отправлять запрещённый запрос. Показать «Достигнут предел просмотра. Уточните поиск.» / “Browsing limit reached. Refine your search.”; назад/новый поиск доступны. Это не полное число произведений.
10. Error pagination не продвигает подтверждённую страницу; retry повторяет тот же immutable query/offset. OFFSET не является snapshot: при повторном импорте возможны сдвиги между страницами. Не обещать дедупликацию/неизменяемую выдачу, не исправлять данные удалением дублей на клиенте.

### Карточка и честность данных

Work detail возвращает **все** его аранжировки, независимо от фильтров поиска. Явная подпись «Все аранжировки произведения; фильтры применялись к поиску» / “All arrangements for this work; filters applied to search”. Клиент не скрывает остальные версии без пояснения и не делает N+1 запросов к arrangement endpoint.

Work: title/artist/identity. Каждая Arrangement: name/identity/parserStatus, исходные tempo/timeSignatures/instruments/trackCount/measureCount/noteCount. Это nullable строки из парсера; не преобразовывать `"0"`/сложный темп в вымышленный BPM или подтверждённую сложность. Null/blank → «Не указано» / “Not specified”. Work blanktitle → «Без названия» / “Untitled”, blankartist → «Исполнитель не указан» / “Artist not specified”.

File: исходное имя, формат, размер, права, статус и **«Оригинал недоступен» / “Original unavailable”**. Имя показывать как plain-text basename (отрезать оба separator`/`/`\\`, убрать control chars), без hyperlink. Размер0 разрешён как метаданные; форматироватьB/KiB/MiB по текущему locale. Кнопки скачать/открыть/PDF/audio/назначить отсутствуют, а не выглядят работающими. Нельзя использовать имя файла как URL или читать локальный путь.

| Значение | Представление |
| --- | --- |
| Work CANDIDATE | «Предварительная группировка» / “Provisional grouping” |
| Work UNRESOLVED | «Сопоставление не уточнено» / “Identity unresolved” |
| Work VERIFIED | «Сопоставление подтверждено» / “Identity confirmed”; **не** проверена музыка/права |
| Parser PARSED | «Файл разобран» / “Parsed file”; не равно музыкальной приёмке |
| Parser METADATA_ONLY | «Только сведения» / “Metadata only” |
| Другие identity/parser | «Статус не распознан» / “Unknown status”, без утверждения success/failure |
| Rights UNKNOWN/неизвестное/пустое | «Права не подтверждены» / “Rights unconfirmed” |
| Rights COURSE_PRIVATE (включая lower-case) | «Личное использование; публикация не подтверждена» / “Private use; publication unconfirmed” |
| Иные rights | «Сведения о правах требуют проверки» / “Rights metadata needs review”; не выдавать LICENSE/PUBLIC_DOMAIN за разрешение приложения на публикацию |

Для bounded DOM —20аранжировок на локальной странице; выбор аранжировки раскрывает20files на локальной странице. Эти страницы работают с уже полученным detail, не делают новых API. Нет молчаливого усечения массива: у пользователя есть локальные Previous/Next и реальное количество элементов detail. Смена Work сбрасывает локальные страницы.

## Компоненты, API и состояния

[Компоненты](../diagrams/cat195-components.mmd) · [Sequence и отмена](../diagrams/cat195-search-sequence.mmd).

Предлагаемые файлы: `frontend/src/entities/music-archive/MusicArchiveView.vue`, `music-archive.service.ts`, `music-archive.model.ts`; тесты transport + mounted component. Распределение внутри каталога можно уточнить без смены поведения. Использовать Pinia/learner store для владения **нельзя**: accountId берётся из auth, Learner selection не разрешение. Нового глобального private cache нет.

### Фактический REST194 (не проект нового сервера)

Все методы GET, same-origin, credentials same-origin, ответ no-store; Content-Type JSON. Нет body, пользовательского accountId, CSRF на чтении, внешних хостов или секретного query. Повтор GET безопасен, автоматический retry не вводится.

| Метод/путь | Вход | Выход |
| --- | --- | --- |
|`/api/v1/music-catalog/works`|q/format/parserStatus/sort/limit/offset|`{items:Work[],limit:number,offset:number,nextOffset:number|null}`|
|`/api/v1/music-catalog/works/{id}`|nonblank sourceWorkId ≤64 UTF-16 units|`{work:Work,arrangements:[{arrangement:Arrangement,files:SourceFile[]}]}`|
|`/api/v1/music-catalog/arrangements/{id}`|sourceArrangementId≤64|`{arrangement,files}`; существует, **не требуется** для обычного UI195|

| Query | Сервер194 | UI195 |
| --- | --- | --- |
|q|string default`''`,≤150 Java/JS UTF-16 units; ISO controls запрещены|input maxlength150 плюс проверка вставки/программного значения; regex controls U+0000–001F и007F–009F; без обрезания молча|
|format|`''/gp/gp3/gp4/gp5/gpx/pdf`, server lowercases|fixed select, lowercase wirevalue; `.gp` не family|
|parserStatus|`''` либо `[A-Za-z0-9_]*`,≤40, case-sensitive|только подтверждённые choices выше|
|sort|title/artist/title_desc, defaulttitle|fixed select|
|limit|integer1..50, default20|фиксированный20|
|offset|long0..200000, default0|только подтверждённые безопасные offsets пагинации|

`URLSearchParams` для query; id не UUID, не приводить к числу. Фиксированный API prefix + `encodeURIComponent(id)` один раз. Одиночные`.`/`..`, control/blank/слишком длинные IDs отбрасываются как invalid response, чтобы не нормализовать path в иной endpoint. Смешивать id Work/Arrangement нельзя.

| DTO | Поля / nullability |
| --- | --- |
|Work|id/title/artist/identityStatus:string; title/artist могут быть пустыми; id непустой≤64|
|Arrangement|id/name/identityStatus/parserStatus:string; tempo/timeSignatures/instruments/trackCount/measureCount/noteCount:string\|null|
|SourceFile|id/name/format/rightsStatus/parserStatus/originalAvailability:string; sizeBytes:number safe integer≥0|

Проверять структуру и каждый элемент, без TypeScript cast вместо validation. Для Page: items≤20, limit20, offset совпадает с requested; nextOffset=null либо integer`offset+limit`, в том числе граничный >200000, который UI не запрашивает. Detail.work.id совпадает с requested; id элементов внутри массива уникальны. Новые незнакомые поля игнорируются и **не используются как URL/actions**. Все SourceFile.originalAvailability сейчас строго UNAVAILABLE; иное значение — контрактная ошибка и никаких действий с файлами. Не удалять некорректные строки из ответа незаметно.

Пример **синтетического fixture**, не реальные данные архива:

```json
{"work":{"id":"work-demo-yesterday","title":"Yesterday","artist":"The Beatles","identityStatus":"CANDIDATE"},"arrangements":[{"arrangement":{"id":"arr-demo-gpx","name":"Demo arrangement","identityStatus":"UNRESOLVED","parserStatus":"PARSED","tempo":"76","timeSignatures":"4/4","instruments":"Acoustic Guitar","trackCount":"1","measureCount":null,"noteCount":null},"files":[{"id":"file-demo-gpx","name":"Yesterday.gpx","format":".gpx","sizeBytes":12345,"rightsStatus":"unknown","parserStatus":"PARSED","originalAvailability":"UNAVAILABLE"}]}]}
```

### Отказы

| Ответ / ситуация | Поведение |
| --- | --- |
|200 items0, appliedQuery без критериев|«В вашем архиве пока нет произведений» / “Your archive has no works yet”; сведения об отдельном импорте, без кнопки ложного upload|
|200 items0 после критериев|«Ничего не найдено. Измените запрос или сбросьте фильтры.» / “No matches. Change the search or reset filters.”|
|400 MUSIC_CATALOG_INVALID_QUERY|Локализованная ошибка параметров, исправление формы; не повторять автоматически|
|401 /403 ACCOUNT_NOT_ADMITTED актуальной сессии|Действующий authenticated fetch очищает actor и AuthGate закрывает страницу; данные195 немедленно очищаются|
|Прочий403|Очистить private results/detail, показать «Нет доступа» / “Access denied”; не утверждать пустой архив|
|404 detail, MUSIC_CATALOG_NOT_FOUND|«Материал не найден или недоступен» / “Item not found or unavailable”; список остаётся, без различения чужой записи/несуществующей|
|404 list|Ошибка недоступного сервиса/API, не empty; возможно несовместимый backend|
|409/422/429/5xx/сеть|Честная общая ошибка/занятость, ручной retry; сохранить draft/appliedQuery/последний подтверждённый offset, не старые данные как свежий результат|
|200 HTML/невалидный JSON/DTO/MIME|INVALID_RESPONSE с локализованным текстом, без вывода server body/stack|
|Timeout/response size|Явное сообщение «Ответ не получен вовремя» / “Request timed out” либо «Карточка слишком велика» / “Response is too large”; retry/уточнение поиска. Не показывать частичный успех|
|Abort при смене контекста|Без error toast; поздние success/error/finally игнорируются|

UI lists: loading / ready / empty / error / access-unavailable; detail: none / loading / ready / not-found / error. При новом применённом поиске старые результаты и detail сразу сбрасываются; при ошибке detail список не исчезает. Старая selection не относится к новой выборке.

### Конкуренция и auth

- K — текущий actor object/session generation + mounted route, не learnerId. Отдельные sequence counters/AbortControllers для list и detail. Любая очистка при actor/logout/unmount увеличивает generation **до** результатов promise; watch actor выполняется синхронно. Даже если повторно вошёл тот же accountId, старая сессия не владеет новой страницей.
- На каждый запрос snapshot actor object + request sequence + query/selectedId. Проверка перед commit success, error и finally. В новом поиске отменять и list, и detail; в новой selection — detail. Пока session/actor неизвестен, запросов нет. Ошибка отправленного раньше запроса не снимает busy текущего.
- Использовать установленный `window.fetch`, не обходить auth/CSRF/session wrapper. Он на базе main пока не проверяет владельца позднего401 перед clearActor: это найденный **code-level риск**, не воспроизведённый runtime FAIL. В195 входит узкая защита adapter: захватить actor object в начале /api запроса, применять401/ACCOUNT_NOT_ADMITTED reset только если actor всё ещё тот же. refreshActor заменяет объект, clearActor сбрасывает его; A→B и logout→login A покрыть regression. Same-context401 продолжает закрывать сессию. Не менять правила допуска, backend identity или OAuth.
- Независимо от global wrapper локальные запросы195 не сохраняют результаты другогоK. Ordinary403 не должен глобально разлогинивать actor. Авторизация сервера остаётся последней проверкой доступа.

## НФТ, безопасность и эксплуатация

- Request budget **15s** и decoded body limit **2MiB** — технические защитные defaults СА, не SLA скорости сервера. Abort timer и stream reader ограничивают загрузку до JSON.parse; Content-Length advisory, не замена подсчёта bytes. Читается UTF-8 JSON. Caller abort не превращается в timeout. Константы service, без нового env/БД параметра; изменение требует build. При превышении нет fallback к скачиванию оригинала. Реальная тяжёлая карточка может потребовать отдельной backend pagination после измерений, не неограниченного UI.
- Одна текущая list и одна detail operation, максимум20rendered arrangement rows и20file rows на локальной странице. Нагрузочный fixture проверяет bounded DOM. Оптимизация PostgreSQL не входит в195.
- Не persist q/filter/selection/results ни в localStorage/sessionStorage/IndexedDB, ни в URL query/hash, ни в analytics/service worker. F5/выход с route очищают состояние и при следующем входе начинают default query. Тем самым поисковые строки не попадают в browser history. API GET query остаётся неизбежным; не добавлять access-log/console логирование q/имён.
- Только Vue text binding, без v-html; query/metadata не HTML, href или команды. Нет прямых file paths, object keys, абсолютных API URL, токенов в данных страницы.
- RU/EN для всех статусов/ошибок/лейблов; original title/artist не переводить. Locale change не перезапрашивает API и сохраняет форму/selection, форматирует числа текущим locale.
- Labels, role=status для loading/счётчика локальной страницы, role=alert для ошибок; aria-busy, корректные disabled, focus-visible; не объявлять всю большую карточку live-region. Tab/ShiftTab/Enter/Escape существующего меню, semantic buttons, контраст tokens. Проверить1440/1180/1024/768/360,320reflow/200%zoom и reduced-motion без декартового умножения каждого теста на все размеры.
- Не нужен новый backend, migration, broker, SDK, paid service, entitlement/OWNER-only gate, mic lease или media TTL:195 не создаёт/меняет файлы и не использует микрофон. MUS-NOTATION-01 не требует генерации PDF здесь, потому что PDF не отображаются/не выпускаются.
- Допустимы технические logs только категории endpoint(list/detail), status/failure-kind и elapsedMs без payload/query/title/filename/accountId; по умолчанию специальных product analytics нет. В owner report — SHA/среда/сценарий/статус, скриншоты без cookies/PII. Использовать существующий ApiFailure с безопасными локальными reason TIMEOUT/RESPONSE_LIMIT, не показывать произвольный server message и не расширять generic code allowlist без необходимости.

## Критерии приёмки

| AC / FT-017-03 | Дано | Когда | Тогда | TC |
| --- | --- | --- | --- | --- |
| AC-195-01 / вход |Authenticated admitted actor либо prototype без actor|Открыть link/direct route, F5/Back|Правильный route/account menu; первый GET только с actor, нет fake данных/лишнего learner gate|01–03|
| AC-195-02 / поиск |Пустой/кириллический/граничныйq|Submit/Enter/Reset|Буквальная строка,≤150безcontrols, defaults/selection/offset согласованы|04–05|
| AC-195-03 / фильтры |Разные форматы/статусы/порядки|Применить форму|Точные query194, GPнеfamily/GTPнет, parsercase preserved, серверный порядок|06–07|
| AC-195-04 / страницы |nextOffset/null/ошибка/200000|Next/Previous/retry|20строк, запрос только подтверждённой страницы, нет выдуманногоtotal и offset>200000|08–10|
| AC-195-05 / детали |Work с несколькими Arrangement/File|Выбор/возврат/локальные страницы|Один work GET, все версии пояснены, bounded DOM, сохранён поиск/focus|11–12|
| AC-195-06 / достоверность |UNAVAILABLE, null/blank/неизвестные метаданные|Отобразить карточку|Нет file actions/выдуманных BPM/оценки/разрешения публикации, безопасные fallback|13–14|
| AC-195-07 / отказы |HTTP/network/HTML/shape/oversize/timeout|Запрос/ручной retry|Правильный error vs empty, нет partial/fake success и rawbody|15–16|
| AC-195-08 / конкуренция |Медленные старые list/detail|Новый запрос/selection/уход|Только актуальныйK/sequence изменяет данные/busy/error|17–18|
| AC-195-09 / доступ |A→B, logout→login A и401/403|Поздний ответ/сменаactor|Старые данные не видны, старый401 не сбрасывает новую сессию, актуальный401 всё ещё блокирует|19–21|
| AC-195-10 / UX |RU/EN, keyboard/mobile/zoom|Навигация/locale/resize|Доступные labels/focus/layout, без горизонтального overflow и новых fetch при locale|22–23|
| AC-195-11 / совместимость |Актуальный main,163 candidate при интеграции|Smoke/rollback|`/library`/Home/auth/score/tools не потеряны;195 не делает POST/DELETE и выключается откатом frontend|24|

Полные [24сценария](../testing/TC-CAT195-private-archive-ui.md) подготовлены, **все NOT_RUN**. Это не protocol PASS.

## Установка, проверка и откат

1. После отдельного выбора в разработку создать ветку из последнегоmain, прочитать diff merged163/PR208, если он появился. Сохранить обе routes и независимые i18n namespaces (`musicArchive.*` против `library.*`), общий nav не переписывать. На базе d310 менять только frontend и связанные docs; V27/owner-scoped API194 нужны уже установленными.
2. Runtime deps уже есть:Vue/Router/i18n/Pinia. Новый пакет и env flag не нужны; существующий VITE_GUITAR_MENTOR_LIGHT_MINIMAL управляет shell. Добавить осмысленные service/mounted tests в npm scripts; existing `npm ci`, `npm test`, `npm run test:intake`, `npm run test:tuner`, default/LightMinimal typecheck+build и browser smoke обязательны на реализации.
3. Smoke на secure local origin с реальным backend: собственный индекс Yesterday/Ветер, formatGPX, detailUNAVAILABLE; второй аккаунт имеет только свой/пустой индекс, чужойid404; logout очищает, F5не сохраняетq; проверить имеющийся `/library` только если163 интегрирована. Не запускать повторный импорт и не требовать от владельца его приватные CSV. Google/SMTP не нужны для service fixtures, live auth берётся из уже настроенного стенда.
4. Rollback trigger: утечка/неверная auth-очистка, потеря routes/navigation или broken list/detail. Ответственный — разработчик/владелец выпуска. Откатить frontend candidate целиком к известному совместимому commit (в том числе узкий guard auth.ts), пересобрать/перезапустить и очистить открытые вкладки; проверить прежний Home/auth/library. Вернуть backend/DB не требуется:195read-only и не мигрирует. Изолированный откат общей auth-правки отдельно от страницы требует своих regression, не обещается автоматически. Уже загруженные в память/показанные метаданные невозможно «отозвать» по скриншотам пользователя, поэтому после инцидента требуется обычный разбор, а не очистка БД.

## Оценка, риски и handoff

**Предварительно8ч,6–12ч, уверенность средняя:** service/DTO/bounded transport1.5; list/detail/layout/navigation2; lifecycle/auth guard1.5; meaningful tests/a11y/regression2; docs/PR1. Нет повторного учёта194/163/196 и нет backend разработки. Independent QA/ожидание владельца не фактические часы реализации. Формальная оценка ещё не выполнена отдельной командой; поле часов не заполняется этой проработкой.

Риски: общий App/routes/i18n в163, response size у work без серверной пагинации, качество nullable/raw metadata, существующий late401 adapter reset. Решения/тесты выше; ни один не требует продуктового ответа до оценки. При выходе за12ч или расширении в originals/shared catalog пересмотреть объём до кода. Сохраняется одна cohesive195: отдельная задача на каждый UI-компонент не нужна. После реализации review→тестирование→приёмка; новую разработку или merge этот анализ не запускает.
