# UX-02 — передача Light Minimal в разработку

**Редакция1.1,25.10.2026:**151/152 перепроверены после принятой150 на main4426becd. [Актуальный остаток и оценки](../project/UX02-REFRESH-2026-10-25.md). §6/7 ниже заменены индивидуальными контрактами; код/QA оставшихся задач этой актуализацией не выполнены. Предыдущие dated notices отражают своё время.

**Дополнение25.10.2026:** новое представление auth и выбранный новый logo реализуются отдельно в [AUTH-03/#198](AUTH-03-auth-ui-and-branding.md), FT01606, parent147. Этим уточнён текущий header/card brand и будущая общая регрессия152; принятые148/149 не переоткрываются. Код/визуальная приёмка198 впереди.

**Сверка после приёмки148/149, вечер24.10.2026:**148/149 приняты,150 остаётся в разработке после замечаний;151/152 ожидают. Концепция определяет направление, последнее решение владельца убирает отдельный Home-link (переход через logo/name). [Текущий реестр](../project/board-audit-evening-2026-10-24.md).

| Версия | Дата (Europe/Moscow) | Изменение |
| --- | --- | --- |
| 1.0 | 24.10.2026 | Анализ стори147 и задач148–152, контракты компонентов, состояния, оценка и порядок реализации. |

**Источник:** утверждённое владельцем направление Light Minimal от24.10.2026, [оригинал дизайнера](../design/light-minimal-v1.md) из `design/light-minimal-v1@6bfe8c34b60ab35ed3f0d4824eb10ff58dd634b3`, PR153. БТ0.19/BT-010/011/012/015/016 → ТЗ0.27/US-016/FT-016-01…05 → стори147 → эпик53. Реальная база кода **main `68690b5110d609699b3af033c8956bfcc39fc155`**; ранее main менялся во время чтения, поэтому старый d9a7c4b не база этой постановки. Автор СА; самопроверка, независимого ревью реализации и продуктового PASS нет.

## 1. Пользовательская история и согласованные границы

Как ученик, я хочу видеть спокойный светлый интерфейс с понятным следующим действием и удобным пространством нот, чтобы сосредоточиться на игре и сдаче исполнения. Утверждено направление, а не завершённая реализация. Данные концепта, изображённые ноты, проценты, имя, BPM, streak, длительность практики и ИИ-текст не являются настоящими данными приложения.

Включены дизайн-токены, белый sticky header/навигация, Home, все состояния диагностики, назначенное задание с основным score viewer, существующие запись/плееры/метроном/тюнер/результаты, RU/EN и доступность. Без серверного изменения учебной логики, новых оценок ИИ, каталога всех пользователей, новых упражнений, парсинга/перевёрстки нот, GP-конвертации и пересмотра модели входа.

Принятый #51/PR124 остаётся принятым. Его полноширинные материалы были исправлением прежнего узкого PDF; новая явно утверждённая концепция разрешает две колонки **только при достаточной ширине**, с доминирующей партитурой, отдельным fullscreen и переходом к одной колонке. Старый узкий прототип #51 не восстанавливать. #119/#120/#85, #125–127, #104/#108/#109/#111, #141/#145/#182 не реализовывать заново: ниже перечислены сохраняемые контракты.

Библиотека пользователя #160 уже в main, #161/PR185 ещё отдельная незавершённая поставка. UI `/library` относится к #163, а общий каталог — к #165–168. Наличие backend metadata не делает маршрут библиотеки доступным. Задачи147 не блокируются этими поставками и не изображают несуществующий каталог.

## 2. Аудит фактических компонентов

| Узел в main | Существующий контракт и что меняется |
| --- | --- |
| `frontend/src/App.vue`, `assets/main.css` | Тёмный sticky header, logo→`/`, username из Pinia, RU/EN, logout pending/error, единые MetronomeDialog/TunerDialog и route/account cleanup. Меняется представление и доступная навигация, не auth/logout/CSRF. |
| `router/index.ts` | `/`, `/profile`, `/onboarding`, `/diagnostics`, unknown→`/`; захват email action link перед history. Не добавлять фиктивные `/learning`/`/tools`/`/library`. |
| `core/home/HomeView.vue`, `home-summary.ts` | Home summary — **frontend resolver**, отдельного endpoint home-summary нет. Читает реальные learner/onboarding/diagnostic/currentTask/learning; homepage не создаёт session/assignment. Bare Learner после public signup сначала на profile. |
| `entities/diagnostic/DiagnosticView.vue` | COLLECTING_EVIDENCE/READY_FOR_TASK/TASK_ASSIGNED, разделение probes/repertoire и доступные рекомендации. `draft-state`, `onBeforeRouteLeave` и beforeunload защищают ввод; assignment версия неизменна. |
| `entities/diagnostic/ExercisePreview.vue` | Данные Exercise, mediaIdentity включает code/version/URLs, HEAD advisory, повтор HEAD при открытии PDF, generation guards, audio preview, native iframe/fallback. Из main68690b сохранить исправление: PDF не ждёт задержанный audio HEAD. |
| `SubmissionPanel.vue`, `MicrophoneRecorder.vue` | File/comment/requestId, запись/preview/upload, controlled retry, очистка file input после успеха, пагинация истории и pinned selected attempt. Изменение компоновки не должно размонтировать экземпляры. |
| `LearningResultsPanel.vue`, `learning.service.ts` | Состояния задания, assessments/criteria/limitations/decisions/evidence, самостоятельное решение ученика, fresh detail guards. Нельзя заменить их декоративными ИИ-карточками. |
| `metronome-ui.ts`, `shared/audio/audio-session.ts`, tuner components | Один метроном; реальные meter/BPM/tempoUnit, reopen сохраняет playback, minimized indicator, recorder имеет приоритет над tuner, tuner не прерывает recording, tuner останавливает метроном. DSP не меняется. |

## 3. FT-016-01 / #148 — общие токены и shell (Design)

Цель — первый отдельный принимаемый инкремент. Подготовить `frontend/src/assets/design-tokens.css`, импортировать до компонентных стилей через текущий entrypoint; новые страницы используют семантические variables, не каскад из поздних глобальных override. Удалить/заменить конфликтующие правила header в main.css, не наращивать ещё один слой с большим specificity. Внутренние утверждённые оформления метронома/головы грифа сохраняются; новые скины не создаются.

| Токен | Начальное значение / назначение |
| --- | --- |
| `--gm-bg`, `--gm-surface` | #F7F8F7 / #FFFFFF |
| `--gm-text`, `--gm-muted` | #162331 / #62717D |
| `--gm-primary`, `--gm-primary-hover` | #28776D / #205F57, белый текст кнопки |
| `--gm-border`, `--gm-control-border` | #DDE5E4 декоративная граница / #7C8987 различимая граница интерактивного поля |
| `--gm-focus`, `--gm-danger` | #28776D / #A42D32; focus outline3px +offset3px, ошибка текстом и значком, не только цветом |
| Typography | system stack Inter/Segoe UI/system-ui/sans-serif; без загрузки webfont/CDN, body16px/line-height1.5, controls font inherit |
| Spacing/radius | 4/8/12/16/24/32px; radius8/12/16px; тень только лёгкая у overlay/card по назначению |

Токены — техническая конкретизация provisional палитры, не новое эстетическое направление. Проверить вычисленные CSS цвета всех состояний: normal text≥4.5:1, large text≥3:1, необходимые границы/focus≥3:1; disabled элементы не превращать в нечитаемый текст без необходимости. Декоративный `--gm-border` не использовать как единственный признак input. 44×44px — собственная цель для частых кнопок, а не утверждение, что WCAG AA требует44px. [W3C contrast](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html), [target minimum](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html).

Shell: белая surface, тёмный текст, зелёный active indicator+`aria-current=page`; logo/name слева, реальная навигация и identity/tools справа. На ширине≥1181px header в одну строку, имя с ellipsis и полным accessible name; ниже перенос/раскрываемое меню без горизонтального переполнения. Мобильная основная строка: logo, tools, menu; username/logout/language и links доступны в раскрытии. Menu — disclosure с button/aria-expanded/aria-controls, закрывается Escape и возвращает focus, DOM не содержит вторую копию активных routed views. Sticky не перекрывает anchor/focus: scroll-padding/scroll-margin учитывают высоту header. При200%zoom допускается адаптивный перенос вместо сокрытия действий.

| Видимый элемент | Реальное действие |
| --- | --- |
| Логотип/название приложения | RouterLink `/`; отдельный дублирующий пункт «Главная» / Home убран по последнему решению владельца в принятой148 |
| «Занятие» / Practice | RouterLink `/diagnostics`; выбран при `/diagnostics` |
| «Профиль» / Profile | RouterLink `/profile`; внутри профиля остаются доступными актуальные auth settings |
| «Опыт и цели» / Experience and goals | RouterLink `/onboarding`, допустим в дополнительном menu; flow не скрыт |
| Тюнер/метроном | Существующие кнопки открытия singleton dialog, не RouterLink/новый route |
| Библиотека | В первой версии147 отсутствует как активный пункт; добавится #163 при реально зарегистрированном route. Не использовать catch-all→Home как ложную доступность. |

На anonymous экране — logo+language+существующий AuthGate; приватная навигация только при `auth.actor || localPrototype`, согласно main. Свободная регистрация, email verification/reset/settings и Google из принятой141 не заменяются invite-only gate. Auth token fragments не попадают в telemetry/links. На первом шаге148 привести auth-формы и profile/onboarding к базовым токенам, не менять validation/submit/восстановление. Pending/error logout остаются видимы. Профиль Design обоснован неизменными API/правами и сохранёнными обработчиками, не числом строк.

**AC-LM-01:** после148 весь shell использует light tokens, logo ведётHome, sticky/username/tools/logout/language работают на1440/1180/768/360 и200%zoom. **AC-LM-02:** каждый видимый link открывает существующий route, selected state верен, keyboard/menu/dialog focus доступны; profile/onboarding/auth формы не регрессируют.

## 4. FT-016-02 / #149 — главная (Standard)

Структура: компактное приветствие с реальным learner.displayName либо нейтральным текстом → заголовок с одним главным CTA → фактическое текущее задание при наличии → вторичные карточки доступных Profile/Experience/Practice. Декоративная guitar-hero картинка не обязательна, исходный favicon/logo достаточен. Не выводить будто пользователь играл30мин, достиг уровня или практиковалсяNдней.

| Вход resolver / факт | CTA и поведение |
| --- | --- |
| Нет learner либо bare learner без ACOUSTIC_GUITAR | «Настроить профиль»→`/profile`; не запрашивать instrument background до настройки |
| Гитара есть, сохранённой выбраннойsession нет; onboarding отсутствует | «Указать опыт и цели»→`/onboarding` |
| Onboarding есть; session нет либо COLLECTING_EVIDENCE | «Продолжить диагностику»→`/diagnostics` |
| READY_FOR_TASK | «Выбрать задание»→`/diagnostics` |
| TASK_ASSIGNED + ACTIVE learning | «Продолжить занятие»→`/diagnostics`, фактические title/code/version |
| TASK_ASSIGNED + COMPLETED_PASSED/BY_LEARNER | «Посмотреть результат»→`/diagnostics`; не обещать автоматически следующее задание |
| Loading / ошибка восстановления | status/skeleton без actionable misleading CTA; error+manual Retry; не выдавать сетевую ошибку за отсутствие профиля |

Сохранить `resolveHomeSummary`, `selectedSessionKey`, guard от позднего ответа/смены actor. Только session GET404 инвалидирует выбранный session key; иной отказ не очищает выбор. Не вводить новый backend endpoint, polling или auto POST при mount. Текст реального recommendedPracticeMinutes — рекомендация, не накопленное время. Locale сохраняется существующим механизмом. Без нового домена хранения/профиля.

**AC-LM-03:** все шесть HomeNextStep и bare-learner signup правильно отображаются и ведут по реальному пути; изHome нет POST создания. **AC-LM-04:** loading/network/error/stale actor/unknown response безопасны, ручной retry не создаёт дублей и не очищает чужую selection.

## 5. FT-016-03 / #150 — занятие и score reader (Full)

**Граница актуализации25.10:** этот раздел сохраняет исходный контракт150. Для151/152 базой служит принятый workspace main4426becd: assigned Light Minimal открывает score сразу, legacy/catalog сохраняет прежний details.151/152 не меняют этот default и не переделывают reader; тест закрытого состояния начинается с ручного закрытия. Иные изменения reader не включены в остаток этих задач.

Layout при viewport≥1100px: общая ширина≤1560px, page gutters24px, grid `minmax(0,1fr) 320px`, gap24px; партитура получает больше60% рабочей ширины. При1100px слева около708px. При меньшей ширине — один столбец, page gutters16px наphone; рабочая область может масштабировать/прокручивать **собственный** score, но не body. Наdesktop right rail подчинён партитуре; его длинное содержимое не сжимает лист. Наmobile порядокDOM: title/goal → sample → score → recording/tools → feedback/progress. Fullscreen не меняет route/task, выход возвращает focus к trigger.

Все состояния DiagnosticView сохранены, не только TASK_ASSIGNED. READY_FOR_TASK продолжает различать short diagnostic probes и optional repertoire, показывать available recommendation/reason, no-match/catalog errors и metadata. В assigned header — title/code/version, реальный BPM/meter/tempoUnit, goal и optional recommended duration с явной подписью. Metadata/criteria можно компактно раскрыть, но они доступны до записи; AI-оценкой они не называются.

Вынести `ScoreReader.vue` (предлагаемое размещение `shared/score/`) и адаптировать ExercisePreview без копии business state. Prop contract: `exerciseKey:string=code@version`, `pdfUrl:string|null`, `scoreUrl:string|null`, `title:string`, `open:boolean`; emits `update:open(boolean)` и `viewer-state({state,reasonCode?})`. Состояния CLOSED/CHECKING/LOADING/READY/UNAVAILABLE/ERROR; local `page` integer1..pageCount, zoom50–250% шаг25 +FitWidth. Рекомендуемый initialFitWidth, page1. Конкретные имена TS типов допускается уточнить без изменения semantics.

**Initial PDF закрыт**, как ранее просил владелец. Открытие одной кнопкой/summary, не автоматическое скачивание тяжёлогоPDF при попадании наHome или выборе задания. Развёрнутый score занимает основную рабочую область; закрытый не оставляет гигантскую пустоту. Выбор «Сосредоточиться на нотах» раскрывает reader и открывает fullscreen в рамках того же пользовательского действия. Смена exerciseKey/actor сбрасывает page/zoom/open и уничтожает старый render; изменение ширины при том жеkey сохраняет выбраннуюpage/open, FitWidth пересчитывается, file/comment recording не затрагиваются. PDF никогда не заменяется псевдо-TAB из концепта.

### 5.1. Рендер, источник и ограничения

Native browser iframe текущего main не даёт приложению надёжные pagination/zoom/error events. Для обязательного контролируемого reader — локально bundled `pdfjs-dist` из официальногоregistry, exact version+matching worker в lockfile, license/SBOM и security check на дату реализации. В main этой зависимости нет. Lazy import только на открытие. Не внешняя SaaS/CDN, не native desktop installation. [Официальный API](https://mozilla.github.io/pdf.js/api/draft/module-pdfjsLib.html), [canvas examples](https://mozilla.github.io/pdf.js/examples/).

Parent получает bytes после проверки URL: same-origin, безuserinfo/hash/protocol-relative, только exact endpoint `/api/v1/exercises/{encodedCode}/versions/{encodedVersion}/preview/pdf` из текущегоExerciseDTO. GET сохраняет credentials same-origin и общий auth error handling; HEAD advisory повторяется при открытии, но успешный HEAD не считается успешным GET/render. Audio HEAD не блокирует PDF. NullURL→UNAVAILABLE;403/401→общийauth flow без отправкиcookiesвдругойorigin;404→missing+safe MusicXML/download fallback;429/5xx/network→понятная ошибка/manual retry. Не fallback на latest version, даже если прежний URL исчез.

Bounded fetch: limit20MiB actualbytes, не только Content-Length, abort after30s; Content-Type должен быть application/pdf (parameters допускаются), header preflight `%PDF-`. Viewer≤100pages, одинrender/page одновременно,≤8MP canvas,10s/page; лимит/пароль/corrupt→error+original download. Числа — инженерные пределы viewer, не новая квота библиотеки и не оценка качества нот. Retry только явный, старыйcontroller/render cancelled. Превышение размера листа или zoom не меняет исходные bytes и не обрезает музыкальные голоса: reader сообщает limit/предлагает native open.

Изоляция PDF: sandbox iframe `allow-scripts` без `allow-same-origin`, локальный минимальный canvas renderer/worker, CSP connect-src none, безForms/scripting/XFA/attachments/annotations/external links/eval. Родитель передаёт bounded bytes через nonce+MessageChannel, проверяет exact WindowProxy/port, iframe не получает URL/токены и не имеет доступа к auth cookies или parent DOM. Доверенные renderer/worker bundles передаёт parent из собственных статических assets, а не из содержимого PDF. Внутри opaque iframe worker создаётся из локального Blob доверенного bundle (worker-src blob:); он не загружает URL с auth cookies. Нужные fonts/CMaps также bundled и передаются без внешнего fetch. Их bytes не принимаются из произвольного postMessage. Разрешены только нужные CSP правила; если выбранной версии нужна внешняя загрузка — отключить/включить bundled ресурс, не ослаблять isolation. Host controls доступны с клавиатуры; результаты и page count приходят по типизированным сообщениям с той жеgeneration. Никакого произвольного HTML/postMessage вDOM. Это front-end security часть150, не зависимость от будущей167; общий helper допускается затемreuse в167 после отдельной проверки untrusted library PDFs.

Toolbar: previous/next (disabled на1/N), номерстраницы с диапазоном/error, zoom in/out/FitWidth, «Открыть PDF отдельно» сnoopener noreferrer, «Сосредоточиться»/закрыть. Fullscreen: Fullscreen API по gesture если поддерживается, иначе CSS fixed dialog до границviewport. Escape закрывает fullscreen, возвращает triggerfocus; backdrop не вызываетnavigation/не теряет submission. Focus trap только вfullscreen; header/menu не доступныподoverlay; при открытии другогоaudio dialog fullscreen корректно закрывается без уничтоженияtask, чтобыне получитьдвойнойtrap. Browser native fullscreen отказ→CSS fallback, не fake success. Кнопки имеютRU/ENlabel/aria и min44px.

### 5.2. Версии, музыка и жизненный цикл

Exercise.previewPdfUrl/previewAudioUrl/scoreUrl берутся из одной version-pinned DTO, code@version показан пользователю. Не читать events JSON ради рисования нот. Эталон [MUS-NOTATION-01](MUS-NOTATION-01-score-tab-standard.md): staff с длительностями и верной гитарной октавой, TAB, четыре полных такта на систему для проектных PDF. UI не переписывает готовый PDF; тест сравнивает отображение с оригиналом, а не заявляет новое музыкальное одобрение. User originals будущей библиотеки не обязаны этому эталону.

Close/unmount/logout/change key отменяют fetch, PDF loading/render task, worker/messages и освобождают buffers/blob URL. Поздние HEAD/GET/import/render предыдущего ключа не возвращают старую страницу. SSR неприменим: текущая Vue SPA, server renderer не добавляется. Backend resource response ради дизайна не меняется.

**AC-LM-05:** все diagnostic states сохранены, две колонки только при достаточной ширине, score>60%, mobile без body overflow. **AC-LM-06:** closed→open→ready/error, page/zoom/fullscreen работают на реальном PDF, controls доступны с клавиатуры/RU/EN, fallback сохранён. **AC-LM-07:** version identity, response guard, auth и sandbox соблюдены; оригинал неизменён, PDF не ждёт audio HEAD. Новые музыкальные материалы и их музыкальная приёмка в150 не входят.

## 6. FT-016-04 / #151 — координация media/tools (Full), актуально25.10

[Полный актуальный контракт151](UX-02-04-media-tools.md) имеет приоритет над первоначальной постановкой компоновки. Record/file tabs, одна панель записи, result-card и inline метроном уже приняты в150. Остаток — sample/preview/capture coordination, transactional draft приpermission/error/cancel, payload-bound retry key и context cleanup, единый engine config между inline/header/dialog.

**AC-LM-08:** один draft ownerK; permission failure не удаляет прежнийFile; accepted replacement/delete/upload success и late callbacks обрабатываются корректно. **AC-LM-09:** сохраняются настоящие meter/tempoUnit/accents/volumes/subdivision, stop/start/reopen sameE не теряют config; mic recorder выше tuner, запись под метроном допустима. **AC-LM-10:** exact-version sample и preview не играют во время capture, no autoplay; guards/logout/actor/stream/objectURL cleanup выполняются. Подробная трассировка AC-151-01…08 →14TC в новом контракте.

Прежнее ожидание150 снято: **Разработка/В очереди,8ч (6–10)**. Shared business/audio code работает и при flagoff; accepted workspace150, его inline placement, score/description и initial viewer state не изменяются под видом151. API/БД/права/ИИ не расширяются.

## 7. FT-016-05 / #152 — результаты и общий candidate (Standard), актуально25.10

[Полный актуальный контракт152](UX-02-05-results-and-acceptance.md). Существующий LearningResultsPanel остаётся одним owner/fetch-loop. Отличать selected attempt/assessment от latest summary, transport PROCESSED от musical assessment, student choice от skillPASS. Banner получает typed context-bound UI state, а не статичное in-progress; unknown/stale/error/source/date честно показаны. Existing command version/baseAssessmentId/requestId/409/retry/pinned history сохраняются.

**AC-LM-11:** реальные состояния/provenance/limitations, отсутствие выдуманных процентов/streak/ИИ; самостоятельное завершение ученика не повышает навык, no next lesson при NOT_IMPLEMENTED. **AC-LM-12:** общий candidate151+198 проверен на конкретном SHA, RU/EN/keyboard/reflow/zoom и auth/media/score/decision regression задокументированы с реальными ограничениями; финальная визуальная приёмка владельца отдельно. Подробные AC-152-01…07 →12TC в контракте152.

**Разработка/Ожидание151; завершение общей проверки также требует198.8ч (6–12)** сохраняются. Начать results/fixtures можно после151 независимо от завершения198; закрыть152/147 без общего candidate нельзя.24TCauth198 не переписываются в152, выполняются интеграционные smoke.

## 8. API, установка и откат

Новых внешних REST/events/таблиц/миграций **нет**. Сохраняются endpoints/models изученных frontend services: GET ресурсов по версии и POST attempt/decision совместимы до/после. Props/events описаны выше; схемы [components](../diagrams/ux02-light-minimal-components.mmd) и [score sequence](../diagrams/ux02-score-reader-sequence.mmd). Для простых Home/shell отдельная sequence не нужна.

Наследуются NFR-001/002/003/004/007/008: малая инфраструктура, согласованность повторов/истории, видимые состояния, безопасная диагностика и трассировка; SEC-002/003/004/005/008: изоляция акторов, auth/CSRF, bounded decode, отсутствие секретов в логах и недоверенный контент. Доступность/i18n конкретизированы AC-LM-01/02/12. Новых секретов/backend env/business settings нет. PDF limits — constants в reader, не DB; изменение требует build. Один сборочный rollback flag `VITE_GUITAR_MENTOR_LIGHT_MINIMAL`, boolean, defaultfalse: только точное значение `true` включает новый shell/layout/reader.148 вводит централизованный gate,149–152 его используют. Новые CSS selectors ограничены корневым `data-ui="light-minimal"`, чтобы импорт токенов/стилей не менял legacy при flagfalse. Нельзя продублировать business state/handlers в двух одновременно смонтированных деревьях. Удаление legacy flag после приёмки — отдельное решение.

Candidate: Node>=22.12, `cd frontend`, `npm ci`, `npm run build`. Версии lockfile точные, без CDN/fonts requests. Конкретные branch/SHA и flag для проверки записать в PR. Java API из main либо совместимого предшественника; security ради demo не отключать. Для скриншотов применять честно отмеченные synthetic fixtures или actual backend, без изменения пользовательских данных.

Диагностика PDF: code/стадия/generation без raw bytes/email/auth fragment; общий safe error UI. Новых trackers нет. Rollback trigger — потеря draft, auth regression, неуправляемый mic или недоступные ноты. Разработчик отключает flag в сборке или отменяет frontend commit, пересобирает bundle и проверяет profile/recording/PDF/tools. БД/записи не откатываются и не очищаются. Revert CSS не восстанавливает удалённые media. Ресурсы не получают новых публичных прав.

Для реализации: `npm run build`, `npm test`, `npm run test:intake`, `npm run test:tuner` плюс адресные component/browser кейсы [TC-UX02](../testing/TC-UX02-light-minimal.md). Сейчас они **NOT_RUN**: код этим анализом не меняется. Markdown/traceability/token contrast math — самопроверка аналитики, не CI или visual PASS.

## 9. Текущая передача —25.10.2026

148/149/150 закрыты completed.151 готова к разработке после снятия зависимости150;198 — отдельный подготовленный auth-кандидат;152 ждёт готовой151 и общего candidate с198 для финального прогона. У1476детей, закрыто3/6. [Актуальный реестр остатка](../project/UX02-REFRESH-2026-10-25.md). Ветка реализации из текущего main, не из documentation runtime-копии. WIP=1; уточнение ТЗ не начало кода/merge/деплоя/приёмки.
