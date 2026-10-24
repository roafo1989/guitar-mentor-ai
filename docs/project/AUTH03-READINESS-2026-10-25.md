# #198 — передача разработчику, 25.10.2026

**Разработка / В очереди · OPEN · Full · Фронтенд · Задача ·8ч (6–10ч).** Родитель147, EPIC53. Анализ и оценка завершены по поручению владельца. Это готовность к коду, не начало реализации/новая приёмка. Самопроверка СА не независимое review.

## Один принимаемый результат

Вместо трёх одинаковых кнопок login/register/Google — одна понятная форма входа/регистрации, самостоятельный одношаговый Google CTA, явные verification/recovery states; единый выбранный новый logo в карточке/header/favicon. Функциональный auth contract141/179 не переоткрывается. Новый устойчивый ID FT-016-06 в US016, БТ0.21/ТЗ0.29; Google/local доступ остаётся безопасным.

## Что читать и брать

1. [AUTH-03: полный контракт, UI/state/API/errors/SEC/rollback/12AC](../specifications/AUTH-03-auth-ui-and-branding.md).
2. [Новые7PNG, SHA-256 и правила применения](../design/assets/brand-v1/README.md), manifest.json. Исходники в repo, Library/чат для старта не нужны.
3. [Google G, шрифт/лицензии/провенанс](../design/assets/google-signin/README.md). Это официальные assets, не логотип приложения, GIS не подключать.
4. [24 проверочных сценария](../testing/TC-AUTH03-auth-ui-and-branding.md), все NOT_RUN до реализации.
5. [Состояния](../diagrams/auth03-ui-states.mmd), [sequence](../diagrams/auth03-signin-sequence.mmd), принятая141/179 и соответствующие исходники main.

## Вход в работу без уточнений владельцу

- Создать feature-ветку **из актуального main**, начальная проверенная база80a306a. Не брать frontend из analysis/system-analysis-standard: это документационный пакет со старой runtime-копией.
- Сверить App.vue/AuthGate/assets/router с изменениями текущих PR150/151 перед правкой. Они не функциональные prerequisites198; не откатывать чужую принятую работу. WIP=1 сохраняется, не запускать198 одновременно с основной незавершённой разработкой без команды.
- Использовать existing secure auth и оба пути login(): direct Google OIDC и guarded local bridge179. Проверить локальный stand config, не менять redirect URIs/scopes/сертификаты/secrets ради оформления.
- Скопировать static brand/google/font+licenses в frontend/public согласно AUTH03; обновить card/header/index favicon. Генерировать новый рисунок, искать SVG-замену либо новый макет не требуется.
- Реализовать states/формы/компоненты, mounted/browser checks и regression. Нет необходимости писать новый backend/DB/API/OpenAPI. Private /methods не anonymous discovery. Resend недоступен: кнопки нет, повтор register не resend.
- Выполнить frontend npm test, npm run test:intake, npm run test:tuner, npm run build с текущими Node>=22.12 и npm ci; typecheck/build default+Light Minimal+secure auth, integration regression существующего Java CI. Для локального прогона auth в dev включить VITE_GUITAR_MENTOR_AUTH_REQUIRED=true, иначе существующий bypass скроет card.
- PR с `Refs #198`, не Closes/Fixes до приёмки. Приложить точный SHA, что изменилось, results/ограничения, скриншоты RU/EN desktop/mobile/zoom, инструкция fixture/live QA, rollback. Затем review/тестирование по регламенту; нельзя закрывать198 по зелёному CI.

## Снятые неоднозначности

| Вопрос | Принятое решение |
| --- | --- |
|Старый или новый логотип?|Последний прямой запрос владельца: новый. Фраза «сохранить текущий» из исходной198 заменена ссылкой на7точных PNG|
|Какой login h1?|«С возвращением!» / “Welcome back!”, регистрация «Создать аккаунт» / “Create an account”|
|Табы или ссылки?|Одна форма, нижние ссылки переключения; третьей вкладки Google нет|
|GIS или Spring redirect?|Существующий login()/server OIDC/bridge, официальные static assets, без новой SDK|
|Письмо отправлено?|202 — только общий безопасный результат; check-mail условный, delivery не утверждается|
|Resend?|ВAPI нет; не добавлять неработающий CTA и не имитировать повторным register|
|Горизонтальный PNG и EN?|Reference: содержит RU raster tagline. ВUI знак+live text/localized subtitle|
|Флаги и rollout?|Новых флагов нет. Auth работает в обоих current Light Minimal modes; public39/40 отдельно|
|Где токены?|В existing one-shot memory helpers; fragments scrub до Router, не DOM/link/input для пришедшего link token|

## Оценка и ограничения доказательств

| Работа | Часы |
| --- | --- |
|Card/layout/login/register/verify/forgot/reset|3|
|Pending/race/cleanup/error/Google-return states|1.5|
|Google official assets/brand/header/favicon|1.5|
|Component/browser/security regression|1.5|
|Документация/PR|0.5|
|Всего|8|

Диапазон6–10ч, средняя уверенность; независимое QA и owner acceptance отдельно. Верхняя граница целевой задачи выбрана из-за одного цельного UI результата без backend. При появлении нового API/права/миграции или выходе за10ч сначала пересмотреть scope/декомпозицию. Старые141/148часы не учитываются повторно.

В анализе проверены: источники main, реальные статусы/поля API, отсутствие resend, все7оригиналов/размеры/прозрачность/совпадение отдельных PNG и ZIP, хеши и font glyph coverage RU/EN, ссылки/AC→TC, математический контраст8пар. Не проверены: будущий runtime/CI/UI/Google/почта/визуальная приёмка198 — всё NOT_RUN. Независимое review не имитируется.

После добавления198 у147 **шесть** дочерних задач. Закрытые148/149 не переоткрываются. Состояние остальных задач147 сохраняется на момент их живой проверки; добавление198 само по себе не принимает/не закрывает150–152. Это delta25.10, не ретроспективная правка вечернего аудита24.10 и не полный новый аудит всех карточек.
