# UX-02 — передача Light Minimal в разработку

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

## 6. FT-016-04 / #151 — запись, аудио и инструменты (Full)

Один `SubmissionPanel` на контекст `{learnerId, sessionId, taskId}`, один `MicrophoneRecorder`, один global metronome singleton и tuner dialog. Не размещать отдельные экземпляры для desktop/mobile через v-if. Для layout использовать CSS grid/slots: resize, открытие PDF/fullscreen или меню не меняют component key, не вызывают unmount и не чистят File/comment/requestId/history. Разделение upload/results на slots допускается только с сохранением одного владельца состояния и существующих событий.

| Состояние/событие | Ожидаемое поведение |
| --- | --- |
| Idle, нет файла | Start и выбор файла доступны при capabilities enabled; submit disabled; причина недоступности понятна |
| Permission pending/denied/no input | Существующая диагностика микрофона; доступны file upload и retry, нет выдуманного уровня сигнала |
| Recording | Видны timer/input level/Stop; recorder mic lease приоритетнее tuner; duration120s/byte caps сохраняются |
| Preview ready / выбран File | Запись отличается от sample; play/replace/discard/submit доступны по состоянию; удаление явное, objectURL освобождается |
| Uploading/processing/error | Busy/draft-state передаются вверх; double submit запрещён, context guard сохранён; ошибка сохраняет File/comment/idempotency key; transport processing не musical PASS |
| Upload success | File/native file input/comment/preview очищаются, создаётся новый requestId; history/latest/pinned selection сохраняют текущую семантику |
| Route leave/Back/header logo | В busy guard запрещает переход; при dirty запрашивает discard, отмена сохраняет ввод; F5/tab close предупреждает beforeunload |
| Logout/account change | Существующий auth cleanup останавливает медиа и очищает private context; сохранение черновика после logout не обещается |

Метроному передавать `openExerciseMetronome(trigger,code@version,bpm,meter,tempoUnit)` из реального Exercise. Проверить новый probe3/8 с eighth=100 и обычные4/4/6/8; accents/volume/subdivision/presets104 и фон109 сохраняются. Header без assignment не подставляет BPM концепта. Reopen/minimize той же композиции сохраняет phase/темп, обычное close останавливает. Запись под метроном разрешена: показать совет использовать наушники, не останавливать его автоматически при Start recorder.

Тюнер сохраняет approved headstock, Auto/Manual, Standard/Drop D/Open G и существующий mic lease. Запуск тюнера останавливает метроном. Тюнер не прерывает активную запись: сообщает busy. Start recorder может остановить tuner через acquireAudio. Справа достаточно компактного фактического состояния и кнопок существующих dialogs; новые дубли полных панелей не нужны. Subscription cleanup обязателен. Placeholder целевой ноты не изображает достоверный сигнал.

Audio sample: один HTML player назначенной редакции, no autoplay. Late error даёт unavailable; смена task/route/logout останавливает player. Если тип примера достоверно неизвестен, не называть его живым исполнением или синтезом по догадке. При Start recorder sample player останавливается **без остановки разрешённого метронома**, чтобы случайно не записать образец вместо ученика. Остановка sample не отправляет attempt. Автоматическая музыкальная оценка не добавляется.

События `SubmissionPanel`: `draft-state:{busy,dirty}`; `LearningResultsPanel`: selected-id/latest-id/decision-busy/terminal-state. Они и guards DiagnosticView сохраняются. Screenshot не доказывает реальную запись микрофона: в QA различать actual device и fake MediaStream. Новых API, provider, постоянного хранения File/localStorage нет.

**AC-LM-08:** запись/preview/replace/submit/error/retry/input reset работают; resize/PDF/dialog не уничтожают draft. **AC-LM-09:** meter/tempoUnit/minimize/reopen/фон сохранены, запись под метроном возможна, mic priority честный. **AC-LM-10:** sample относится к назначенной версии, отличается от записи, no autoplay; navigation/logout/history guards и async cleanup работают.

## 7. FT-016-05 / #152 — результаты, доступность и общая проверка (Standard)

Существующий LearningResultsPanel остаётся единственным владельцем selected attempt/assessment/decision. Допускается слот/компактное summary справа, но не второй независимый fetch-loop. История, критерии, замечания и ограничения доступны. Completion by learner не означает подтверждённый навык. Unknown API field/state даёт neutral unavailable, а не процент по умолчанию0/100.

| Источник | Что показываем | Что не следует из данных |
| --- | --- | --- |
| TaskLearningView.taskState | ACTIVE/COMPLETED_PASSED/COMPLETED_BY_LEARNER с локализованной причиной | Процент освоения, streak, общий уровень |
| assessmentState NO_ATTEMPT/NO_ASSESSMENT/AVAILABLE | Сначала отправьте запись / оценки пока нет / реальная выбранная оценка | Готовый ИИ-совет при отсутствии assessment |
| AssessmentView sourceKind/method/version/recommendation/criteria/limitations | PASS/REVISE/UNCERTAIN с основаниями, provenance и uncertainty | Техническая обработка не PASS; human/fixture нельзя назвать ИИ-анализом |
| SkillEvidenceView и CriterionResult | Существующие доказательства/ограничения; уровень только с основанием по контракту |0/null не означает, что пользователь ничего не умеет |
| nextStepAvailability=NOT_IMPLEMENTED | Следующий шаг пока недоступен; доступные действия из availableActions | Активная кнопка автоматического следующего задания |

Нейтральный заголовок «Обратная связь» допустим без словаИИ, если оценка не модельная. Никаких stub recommendations, фиктивных API calls или случайной статистики. Серверный API не расширяется. Диагностические recommendations145 с reason codes остаются отдельными от assessment.

Матрица экранов: auth/register/verify/reset/settings; Home шесть next steps; profile/onboarding; три diagnostic states; assigned/recording/results/history; metronome/tuner/fullscreen. Viewports1440×900/1180×820/1024×768/768×1024/360×800 и landscape800×360; RU/EN,200%zoom, keyboard и reduced-motion. Фокус не скрыт sticky header, DOM order логичен, errors role=alert, loading role=status, полное username доступно. Текст и Stop не обрезаются фиксированной высотой. Проверку browser zoom не заменять масштабированием скриншота.

**AC-LM-11:** настоящие состояния данных/provenance/uncertainty/решение ученика сохранены, нет фиктивных процентов и ИИ-текста. **AC-LM-12:** auth/learning/media regression и a11y/locale/responsive матрица подтверждены, владелец видит снимки до/после для финальной визуальной приёмки.152 получает решение владельца, не принимает результат самостоятельно.

## 8. API, установка и откат

Новых внешних REST/events/таблиц/миграций **нет**. Сохраняются endpoints/models изученных frontend services: GET ресурсов по версии и POST attempt/decision совместимы до/после. Props/events описаны выше; схемы [components](../diagrams/ux02-light-minimal-components.mmd) и [score sequence](../diagrams/ux02-score-reader-sequence.mmd). Для простых Home/shell отдельная sequence не нужна.

Наследуются NFR-001/002/003/004/007/008: малая инфраструктура, согласованность повторов/истории, видимые состояния, безопасная диагностика и трассировка; SEC-002/003/004/005/008: изоляция акторов, auth/CSRF, bounded decode, отсутствие секретов в логах и недоверенный контент. Доступность/i18n конкретизированы AC-LM-01/02/12. Новых секретов/backend env/business settings нет. PDF limits — constants в reader, не DB; изменение требует build. Один сборочный rollback flag `VITE_GUITAR_MENTOR_LIGHT_MINIMAL`, boolean, defaultfalse: только точное значение `true` включает новый shell/layout/reader.148 вводит централизованный gate,149–152 его используют. Новые CSS selectors ограничены корневым `data-ui="light-minimal"`, чтобы импорт токенов/стилей не менял legacy при flagfalse. Нельзя продублировать business state/handlers в двух одновременно смонтированных деревьях. Удаление legacy flag после приёмки — отдельное решение.

Candidate: Node>=22.12, `cd frontend`, `npm ci`, `npm run build`. Версии lockfile точные, без CDN/fonts requests. Конкретные branch/SHA и flag для проверки записать в PR. Java API из main либо совместимого предшественника; security ради demo не отключать. Для скриншотов применять честно отмеченные synthetic fixtures или actual backend, без изменения пользовательских данных.

Диагностика PDF: code/стадия/generation без raw bytes/email/auth fragment; общий safe error UI. Новых trackers нет. Rollback trigger — потеря draft, auth regression, неуправляемый mic или недоступные ноты. Разработчик отключает flag в сборке или отменяет frontend commit, пересобирает bundle и проверяет profile/recording/PDF/tools. БД/записи не откатываются и не очищаются. Revert CSS не восстанавливает удалённые media. Ресурсы не получают новых публичных прав.

Для реализации: `npm run build`, `npm test`, `npm run test:intake`, `npm run test:tuner` плюс адресные component/browser кейсы [TC-UX02](../testing/TC-UX02-light-minimal.md). Сейчас они **NOT_RUN**: код этим анализом не меняется. Markdown/traceability/token contrast math — самопроверка аналитики, не CI или visual PASS.

## 9. Готовность и передача

[Оценки, порядок и связи](../project/UX02-READINESS-2026-10-24.md):148→149→150→151→152. У всех пяти задач конкретный принимаемый объём.148 — Разработка/В очереди, остальные — Разработка/Ожидание предшественников. Блокирующих продуктовых вопросов для ограниченного редизайна нет. Exact PDF.js version выбирает исполнитель150 по официальному registry/security на старте; пользователю не передаётся выбор библиотеки. При конфликте актуального main с контрактом зафиксировать diff/влияние до продолжения.

Подготовка147 не переставляет автоматически активные разработки в других чатах. Здесь создаётся очередь; кодирование, merge, deployment и закрытие Issues не выполняются. Принятие реализации требует нового UI и реальных проверок.
