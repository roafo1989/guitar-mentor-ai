# AUTH-03 / FT-016-06 — вход, регистрация и новый логотип (#198)

| Версия | Дата, Europe/Moscow | Изменение |
| --- | --- | --- |
| 1.0 | 25.10.2026 | Full-анализ постановки дизайнера и выбранного владельцем нового логотипа; подготовка к самостоятельной разработке. |

## Паспорт и источники

**Full · Фронтенд · задача198 → US-016/#147 → EPIC-001/#53.** БТ0.21: BT-012/013; ТЗ0.29: FT-016-06, совместимость FT-007-01/02/05; NFR-001/004/007/008, SEC-002/003/005/006. Full выбран из-за нескольких состояний auth, асинхронных переходов, CSRF и одноразовых ссылок, а не сложности оформления. API, auth-policy и БД не расширяются. Автор анализа — Codex; выполнена самопроверка, независимое ревью не заявлено. Владелец выбрал задачу и новый логотип к проработке; это не визуальная приёмка будущего кода.

Исходники проверены на **main80a306a0a703758553444a6223451aee1fa4502a**: AuthGate.vue, auth.ts, auth-link.ts, oauth-bridge-ticket.ts, router/index.ts, App.vue, design-tokens.css/main.css/light-minimal.css; LocalAuthController, LocalCredentialService, SecurityConfiguration, LocalOAuthBridgeController; существующие auth-security.test.mjs. Документы подготовлены поверх analysis/system-analysis-standard@1f11bdc. Документационная ветка содержит старую копию runtime: **реализацию начинать от актуального main, не от её frontend**.

Источники: [исходная постановка198](https://github.com/roafo1989/guitar-mentor-ai/issues/198), принятые141/PR143,179/PR180 и148/PR187; [Light Minimal](UX-02-light-minimal-handoff.md), [AUTH-02](AUTH-02-email-password.md). Новые PNG/ZIP выбраны владельцем25.10; [паспорт и точные файлы](../design/assets/brand-v1/README.md). [Google branding](https://developers.google.com/identity/branding-guidelines) проверен25.10.2026 (редакция страницы07.07.2026), [локальные официальные ресурсы](../design/assets/google-signin/README.md). Концепт не является официальным Google asset.

## Пользовательская ценность и границы

Как новый или вернувшийся пользователь, я хочу сразу выбрать понятный способ входа, чтобы попасть в приложение без лишней вкладки Google. Измеримый результат: от видимого login/register до начала Google OAuth **одна активация CTA вместо двух**; нет утверждения об измеренном росте конверсии.

Включено: единая auth-card, login/register/письмо/verify/forgot/reset/loading/session-expired/error; Google CTA и ошибки возврата; официальный знак G; новый знак Guitar Mentor AI, header и favicon; RU/EN, клавиатура/мобильная вёрстка/200%zoom, адресная security regression и документация.

Не входит: новый auth backend, GIS/One Tap/FedCM, дополнительные OAuth scopes/провайдеры, новый resend endpoint, отмена email verification, MFA, изменение password policy/TTL, linking/change-password redesign в PasswordSettings, публичный deployment, письмо с новым логотипом, PWA/install/социальные превью, музыкальная/ИИ функциональность. Работоспособность PasswordSettings/logout и существующего Google linking проверяется как регрессия. Дополнительную декоративную гитару не добавлять: файл не подготовлен и она не нужна для принимаемого результата.

## Зависимости и место в очереди

| Связь | Факт | Условие старта |
| --- | --- | --- |
|141/179|Приняты, функциональные local+Google/SMTP доступны в main|Повторно не реализовывать, сохранить контракты и guarded local bridge|
|148/149|Приняты shell/tokens/Home|Переиспользовать; отдельный Home-пункт не возвращать, главная через logo/name|
|150/151/152|Отдельный незавершённый поток занятия|Не функциональный блокер198. Общие App.vue/CSS сверять с последним main; WIP не более одной основной разработки|
|39/40/186|Внешние deployment/mail проверки отдельно|Не блокируют реализацию/изолированные тесты198 и не считаются завершёнными из-за неё|
|Новый logo/Google assets|Локально приложены и хешированы|Не искать файлы в чате и не просить владельца повторно выгружать|

Блокирующих продуктовых вопросов нет. Варианты, оставленные дизайнером, разрешены ниже. Если для соблюдения контракта разработчик обнаружит необходимость менять API/права/схему или выйдет за диапазон оценки — сначала уточнить технический остаток и переоценить его; не маскировать расширение scope оформлением.

## Интерфейс: точный контракт

На экране **одна форма**, login по умолчанию; переключение на регистрацию — нижняя текстовая ссылка, без панели из трёх равнозначных кнопок. Возврат — ссылка под register. Google-блок виден на обоих основных экранах: локальная форма → разделитель «или»/“or” → полноширинная «Продолжить с Google»/“Continue with Google” → ссылка переключения. Forgot/reset/verify не имеют вкладки Google.

| Элемент | Параметр реализации |
| --- | --- |
|Контейнер|Ширина border-box min(448px, 100%), горизонтальный margin/padding страницы16px; padding карточки32px при≥600px,24px ниже600; box-sizing:border-box; min-width:0|
|Расположение|Центр по горизонтали, начало по вертикали с padding clamp(24px,5vw,64px); обычный вертикальный scroll. Не фиксировать высоту/100vh и не прятать нижние действия клавиатурой|
|Цвет/границы|Существующие gm tokens: bg#f7f8f7, surface#fff, text#162331, muted#53636d, primary#28776d/hover#205f57, control-border#7c8987, danger#a42d32, focus#205f57. Радиус16px карточки,8px controls; сохранить существующий спокойный shadow|
|Типографика|Шрифт приложения; h1 clamp(24px,3vw,28px)/1.2, semibold; labels14/20, controls/body16/24. Обычный перенос по словам; убрать overflow-wrap:anywhere именно у h1/brand, не ставить nowrap всей карточке|
|Ритм|Заголовок→описание8px; блоки24px; label→input8px; поля16px; helper/error6–8px. Не использовать <br> для подгонки заголовка|
|Controls|Input и основные CTA min-height48px, width100%; icon-toggle44×44px внутри поля, справа достаточный padding; focus ring3px+offset2px; textarea/аккордеоны здесь не нужны|
|Google|Белый фон, рамка1px#747775, текст#1f1f1f Google Sans Medium14/20,48px, радиус8px. Слева12px, слот G20×20px object-fit:contain, за знаком10px, справа≥12px; не растягивать исходник200×204. Официальный G и font subset приложены. Текст центрировать в свободном пространстве без наложения на знак|
|Brand|Знак PNG128, CSS56×56 в карточке; PNG128, CSS40×40 в header (на узком экране допустимо32). Рядом live-text Guitar Mentor AI; subtitle локализован существующим i18n. Горизонтальный PNG — reference, не растягивать его в EN UI|

Новый auth styling работает в production secure UI **с обоими значениями существующего Light Minimal flag**. Для legacy не менять остальные экраны/глобальные токены ради auth. Локальный dev-прототип остаётся отдельным существующим режимом, не выдавать показ карточки в нём за security gate.

### Состояния и тексты RU/EN

| Состояние | Заголовок и основной текст | Действия |
| --- | --- | --- |
|Login|«С возвращением!» / “Welcome back!”; «Войдите в Guitar Mentor AI» / “Sign in to Guitar Mentor AI”|Email или логин / Email or username, пароль; «Войти» / “Sign in”; «Забыли пароль?» / “Forgot password?”; Google; «Нет аккаунта? Зарегистрироваться» / “New here? Create an account”|
|Register|«Создать аккаунт» / “Create an account”|Email, «Логин» / “Username”, пароль, повтор; требования; «Зарегистрироваться» / “Create account”; Google; «Уже есть аккаунт? Войти» / “Already have an account? Sign in”|
|Check mail после register202|«Проверьте почту» / “Check your email”; «Если регистрация доступна для этих данных, вы получите письмо с подтверждением. Проверьте входящие и спам.» / “If registration can proceed with these details, you will receive a verification email. Check your inbox and spam folder.”|«Вернуться ко входу» / “Back to sign in”; при необходимости «Изменить данные регистрации» / “Change registration details” — лишь возврат, не resend|
|Verify по корректной ссылке|«Подтвердите почту» / “Verify your email”|«Подтвердить email» / “Verify email” отправляет один POST. После200 — login и «Почта подтверждена. Теперь можно войти.» / “Email verified. You can sign in.”|
|Forgot|«Восстановить пароль» / “Reset your password”; «Введите email вашего аккаунта» / “Enter your account email”|Email, «Отправить ссылку» / “Send reset link”, возврат к login|
|Forgot после202|«Проверьте почту» / “Check your email”; «Если такой аккаунт существует, вы получите письмо с инструкциями.» / “If an account exists, you will receive reset instructions.”|Возврат к login, без признания адреса существующим/доставленным|
|Reset по ссылке|«Новый пароль» / “Choose a new password”|Новый пароль, повтор, требования; «Сохранить пароль» / “Save password”. После200 очищение actor/секретов и login, без автоматического входа|
|Ссылка недействительна|«Ссылка недействительна или истекла» / “This link is invalid or expired”|Никаких token echo. Для reset — «Запросить новую ссылку» → forgot; для verify — возврат к login/данным регистрации без обещания resend|
|Session expired|Login с уведомлением «Сессия завершена. Войдите снова.» / “Your session has ended. Sign in again.”|Понятные local/Google способы; не прятать их за общим заголовком «Добро пожаловать»|
|Первичная загрузка actor/ticket|«Проверяем вход…» / “Checking your sign-in…”|role=status, без мерцания чужой карточки/персональных данных; обработать сбой, не вечный spinner|

Для verify сохранить имеющийся безопасный ручной ввод через вторичное «Ввести код из письма» / “Enter code from email”, свернутый по умолчанию. Это **64 hex**, не новый шестизначный OTP. Если токен пришёл ссылкой, поле токена не появляется в DOM, значение не показывается в тексте. Для reset сохраняется аналогичный ручной fallback только при отсутствии link token. Токен из query вместо fragment не вводить.

Login: identifier required≤320, autocomplete=username; password autocomplete=current-password. Register: email type=email/required≤320/autocomplete=email; username3–64, lowercase ASCII после серверной нормализации, `[a-z0-9][a-z0-9._-]{2,63}` (регистр при вводе можно нормализовать без запрета валидного server input); password и confirm required12–128, autocomplete=new-password, **без trim**, совпадают. Helper «12–128 символов» / “12–128 characters”; username helper «3–64: латинские буквы, цифры, точка, _ и -; первый символ — буква или цифра» и эквивалент EN. Reset те же12–128 и совпадение. Не вводить требования верхнего регистра/спецсимволов, которых нет в backend. Show/hide — type=button, aria-pressed/доступное имя текущего действия; отдельно у каждого password поля, не отправляет форму и сохраняет value/selection/focus.

### Асинхронность и безопасные переходы

busy — один запрос на действие; повтор Enter/click не отправляет второй POST. Во время POST переходы режима отключены; переключение языка допустимо и не теряет введённое. После ответа снять pending; unmount/новый контекст/отмена не позволяют позднему ответу менять другую форму (generation guard/cleanup). Не ретраить register/login/verify/reset автоматически: нет idempotency key в этом API. Сетевой timeout после POST — неопределённый итог, показать безопасную ошибку и ручной повтор; не объявлять операцию отменённой на сервере.

При смене режима сбрасывать ошибки/результат операции, passwords/confirm/show flags; хранить email/login только в памяти текущего компонента. Не очищать email/login при обычной ошибке; после окончательного reset — existing clearActor/clear fields. При смене actor/logout/unmount очищать секреты/derived state, сохранить existing learner cleanup. Вход успешен только после успешного refreshActor и наличия actor; 200 login + отказ /me не показывает персональный slot или ложное «готово». После потери сессии защищённые экраны не остаются доступными.

OAuth CTA type=button вне local form submit: одна активация вызывает существующий login() один раз. Не требовать заполненного локального email/password для Google. Прямой путь и guarded local bridge остаются как в auth.ts. При навигации к Google очищать пароли, не переносить их/адрес в redirect. Pending исключает повтор; browser back/pageshow/bfcache возвращает работоспособный login с паролями скрытыми и очищенными, не зависает disabled.

Existing server failure markers `auth=denied|failed|google_failed|bridge_start_failed` распознать по allowlist, показать общее «Не удалось войти через Google. Попробуйте снова или войдите по email.» / “Could not sign in with Google. Try again or sign in with email.”, убрать только этот marker через history.replaceState, не выводить raw query/исключение. Не менять контракт callbacks, state/nonce, localhost beginUrl allowlist, session-bound one-time ticket. Existing ticket/email fragments удаляются helper-ами **до router snapshot**, consume один раз; неизвестные query не становятся HTML или сообщением ошибки. /methods — приватный endpoint настроек, **не anonymous discovery**; не использовать его для скрытия Google CTA. В стенде без настроенного Google локальная регистрация остаётся работоспособной; реальная Google-приёмка проводится на существующем настроенном стенде179, не обещается по одному UI fixture.

## Компоненты, API и данные

AuthGate владеет состоянием flow/несекретными полями/текущей операцией. PasswordField и GoogleSignInButton можно выделить внутри core/auth при полезном reuse; предпочтительны typed props/events, без отдельного глобального password store. Shared brand остаётся в App.vue либо маленьком BrandMark component, без второго Router/нового маршрута. auth.ts/AuthGate helpers, auth-link.ts, oauth-bridge-ticket.ts и router сохраняют security responsibilities. Новые библиотеки/сервисы/платёжные параметры не нужны. [Состояния](../diagrams/auth03-ui-states.mmd) и [sequence](../diagrams/auth03-signin-sequence.mmd); общая архитектура монолита не меняется, отдельная инфраструктурная схема/ADR не нужны.

Все пути same-origin; modifying /api используют существующий installed fetch/CSRF и credentials:same-origin. Не заменять его голым fetch/no-cors. Тела JSON; поля ниже string, обязательные/non-null, клиентские ownerId/accountId не добавляются.

| Метод/путь | Тело/назначение | Успех | Ошибки и UI |
| --- | --- | --- | --- |
|GET /api/v1/csrf|Без тела; существующий bootstrap headerName/token, не логировать|200|При недоступности не отправлять незащищённый POST|
|POST /api/v1/local-auth/login|identifier≤320, password; сервер проверяет credentials|200 SIGNED_IN, затем GET/me|401 INVALID_CREDENTIALS общий без email enumeration;429 RATE_LIMITED; network/5xx общий retry|
|POST /api/v1/local-auth/register|email≤320, login3–64, password12–128, confirmPassword (совпадение)|202 VERIFY_EMAIL|202 также при коллизии email/login: **не считать доказательством новой регистрации/доставки**.400 INVALID_INPUT;429;5xx/network|
|POST /api/v1/local-auth/verify|token64 lowercase hex; ссылка/ручной input|200 EMAIL_VERIFIED|400 TOKEN_INVALID,429; reused/expired безопасная ошибка|
|POST /api/v1/local-auth/request-reset|email required; не принимать token/ID аккаунта|202 IF_ACCOUNT_EXISTS_EMAIL_SENT|Ответ одинаков при неизвестном адресе и delivery failure, не подтверждает доставку;429/network/5xx|
|POST /api/v1/local-auth/reset|token64hex, password12–128; confirm проверяется UI|200 PASSWORD_RESET|400 TOKEN_INVALID,429; не раскрывать возраст/состояние чужой записи|
|GET /api/v1/me|Без тела, текущая сессия|200 accountId/learnerId/role|401/403 очищают auth по existing rules, business403 не превращать в logout|
|GET /oauth2/authorization/guitar-mentor|Существующий прямой OIDC redirect, без собственных returnUrl/scopes|Навигация к provider|Failure marker из существующего backend; не обходить state/nonce|
|POST /api/v1/oauth-bridge/start|{} плюс existing CSRF|200 {beginUrl:string}; строго existing localhost allowlist|Generic error/retry; direct путь не заменять bridge и наоборот|
|POST /api/v1/oauth-bridge/consume|ticket64hex из fragment/helper|200, затем refreshActor|Сбой/повтор/чужая сессия не допускает user; generic Google error|

Нет resend verification API: **не отображать фальшивую «Отправить повторно» и не использовать повтор register для resend** — существующая pending reservation отклоняется, даже если HTTP202. Новые openapi/events/таблицы/миграции отсутствуют: эта таблица документирует reuse, а не новое обещание backend.

## НФТ, безопасность и доступность

NFR-001: только статические ресурсы и существующий Vue/Java stack, без SDK/API стоимости. NFR-004: pending/error/success видимы, один Google-click, без auto retry/лишнего перехода. NFR-007/SEC-005: не логировать identifier/email/password/token/ticket/request body/raw redirect; допустимы action, generic outcome/status, версия без персональных данных. Новые analytics события не нужны. SEC-002/003/006: server actor/ownership,CSRF,Secure/HttpOnly/SameSite,issuer/state/nonce не ослаблять; регистрация свободная, verification обязательна. Оформление не означает разрешения публичного deploy.

Весь flow Tab/Shift+Tab/Enter, show/hide Space, явные labels, autocomplete, aria-describedby/helper/error, aria-invalid; ошибка формы role=alert, статус role=status, не дублировать aria-live на всей карточке и каждом сообщении. После смены режима focus h1(tabindex=-1), после invalid submit — первое невалидное поле; по show/hide фокус остаётся у toggle. Нативные input attributes дополняют server validation, не скрывают ошибки в недоступном tooltip.

Проверка на360/768/1440 CSSpx,320px для reflow,200%browser zoom, RU/EN, длинный email, software keyboard: без горизонтального scroll страницы, обрезанного submit/header, наложения toggle. Цель контраста обычного текста≥4.5:1, границ/focus≥3:1; ошибки не только цветом. reduced-motion без обязательных анимаций. Расчёт token pairs на белом: text15.92,muted6.23,primary white5.32,border3.63,focus7.40,danger7.00; Google text16.48,border4.53. Это расчёт цветов, **не computedCSS/visual PASS**.

## Приёмочные критерии и трассировка

| ID | Дано / Когда / Тогда | TC |
| --- | --- | --- |
|AC-AUTH03-01|Anonymous; открывает вход/регистрацию; одна карточка, понятный заголовок/форма и нижний переключатель, Google-tab отсутствует|01,02,03|
|AC-AUTH03-02|Login или register, поля пусты; Google-click/Enter; один OAuth старт без local submit, официальный branding, direct/bridge сохранены|04,05,06|
|AC-AUTH03-03|Валидные/ошибочные local credentials; submit; прежние сессии, /me и guards работают, поля/ошибки/повторы управляемы|07,08,09,10|
|AC-AUTH03-04|Register202 для новых/занятых данных; ответ; одинаковый check-mail без утверждения delivery; нет resend либо обхода verification|11,12|
|AC-AUTH03-05|Verify/reset link, в том числе reused/invalid и при logged-in actor; открытие; token scrubbed до Router, один consume, корректный отдельный экран/ошибка/очистка|13,14,15,16|
|AC-AUTH03-06|Recovery известного/неизвестного адреса и SMTP failure; запрос; одинаковый безопасный текст; reset очищает actor, не логинит автоматически|17,18|
|AC-AUTH03-07|Pending/поздний ответ/смена языка/back; действие; нет double POST, переноса секрета/зависшего Google pending|09,10,19,20|
|AC-AUTH03-08|Поле пароля и менеджер; ввод/toggle/смена режима; autocomplete верен, value не trim, layout/focus и очистка соблюдены|03,08,20,21|
|AC-AUTH03-09|Выбранные новые assets; auth/header/favicon; точный оригинал и пропорции/alt/live text/RUEN, логотип ведётHome без нового Home-пункта|22,23|
|AC-AUTH03-10|RU/EN, указанные размеры/zoom/keyboard/screen reader; все состояния; доступные controls/сообщения без overflow|21,24|
|AC-AUTH03-11|Actor/logout/disabled/CSRF/linking и fake redirects; регрессия; нет ослабления принятой141/179, секретов вURL/storage/logs/DOM link-token|06,07,13,16,19,23|
|AC-AUTH03-12|PR точного SHA; developer handoff; приложены CI, скриншоты и фактическая матрица, независимые/owner проверки не выдуманы|Все; приёмка владельца|

Все TC имеют префикс TC-AUTH03-; [полный набор](../testing/TC-AUTH03-auth-ui-and-branding.md). Их подготовка не исполнение; на момент передачи все24 **NOT_RUN**. Приёмка новой198 не объявляет старые141/179 неуспешными и не переоткрывает их.

## Установка, выпуск и откат

Новых config/env/defaults/migrations нет. Существуют VITE_GUITAR_MENTOR_AUTH_REQUIRED (secure dev), VITE_GUITAR_MENTOR_LIGHT_MINIMAL (legacy/opt-in), VITE_GUITAR_MENTOR_OAUTH_BRIDGE (direct/guarded bridge); **их текущие defaults и ограничения не менять**. После build изменение VITE требует пересборки, а не runtime toggle. Existing HTTPS/SMTP/OIDC окружение179 используется для настоящей интеграции. font/logo/G грузятся same-origin статически, без CDN/трекера/нового Google script, fallback шрифта при ошибке не ломает кнопку. Только ресурсы Google кнопки используют приложенный font subset, не весь UI.

Разработчик копирует точные brand files в frontend/public/brand/ и Google assets/licenses в frontend/public/vendor/google-signin/, favicon32/64 в versioned PNG paths и обновляет index.html (не кэшировать новый дизайн на старом URL favicon.png без version bump). Переходный старый favicon.png можно сохранить; не использовать его как header-brand. Исходники в docs/design не менять. При изменении текста Google CTA использовать полный исходный шрифт либо пересобрать subset для новых строк с лицензией; не придумывать G-глиф.

Порядок: актуальный main → feature-ветка → новые assets + UI/guards + meaningful mounted/browser tests → npm test/test:intake/test:tuner/build на обеих конфигурациях → CI и browser smoke → Code-review по текущему workflow → Тестирование/В очереди → независимые проверки и визуальная приёмка владельца → согласованный merge. Java HTTP/PostgreSQL security suite или существующий backend CI перепроверяется для integration regression, не писать заново backend в198. Наличие простого source regex test не заменяет mounted user interaction/реальную сеть. Не покупать инфраструктуру/публиковать сайт.

Smoke: anonymous login/register→Google one action; local register→checkmail→verify→login; forgot/reset→login; logout/session-expired; logo/favicons и правильный Home; authenticated profile/settings/занятие/тюнер/метроном без регрессии. Реальные токены/адреса скрыть в доказательствах. FixtureGoogle/SMTP и реальная проверка различаются в отчёте.

Rollback trigger: блокировка входа/утечка секрета/сломанный CSRF или критичный reflow. Ответственный разработчик: отменить feature commit/вернуть предыдущий frontend build и assets, проверить login/Google/logout/brand cache. DB rollback не нужен, формат сессий не меняется; откат UI не отменяет уже совершённые register/reset операции и не восстанавливает старый пароль.

## Оценка и передача

**8ч, диапазон6–10ч, уверенность средняя.** Auth layout/6 основных состояний3ч; pending/races/safe errors1.5ч; Google+brand+favicon1.5ч; meaningful component/browser/security regression1.5ч; PR/docs0.5ч. Это оценка работы одного исполнителя, не календарное обещание/факт. Независимое тестирование и ожидание owner acceptance отдельно. Задача сохранена цельной: единый принимаемый UX результата,8ч на верхней границе, без нового backend. При расширении больше10ч выделить независимый остаток до продолжения, не растить scope молча.

Анализ и оценка завершены по поручению владельца. Цель Project: **Разработка / В очереди, Full, Фронтенд, Задача,8ч**. Разработчик получает [чек-лист старта и готовности](../project/AUTH03-READINESS-2026-10-25.md); кодирование не начато этой подготовкой. Открытых вопросов владельцу, блокирующих самостоятельный старт, нет.
