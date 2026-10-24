# US-007 — актуализация и вход в анализ

**Сверка после реализации, вечер24.10.2026:** [Вечерняя сверка 24.10.2026](../project/board-audit-evening-2026-10-24.md).38/42/141/179 приняты; стандартная регистрация свободная, обязательное приглашение отменено владельцем. Локальный HTTPS/SMTP принят, guitar-mentor.ru зарегистрирован на парковке; публичные VPS/deploy39 и go/no-go40 не подтверждены.186 ждёт остаточных DNS/mail/защиты доменного аккаунта.61 принято4/7 и остаётся OPEN. Ниже исходный анализ перед реализацией: старые предпосылки/ожидания больше не блокируют принятые результаты.

**Новый способ входа24.10.2026:** [AUTH-02/#141](../specifications/AUTH-02-email-password.md) добавляет email+логин+пароль наряду с Google. Только приглашения, общие Account/Learner/сессии/права, подтверждение почты и восстановление. Старое ограничение «только OIDC/без хранилища паролей» заменено этим расширением. Full-анализ впереди; существующие PR132/139 не принимаются и не переписываются этим запросом. Порядок38→42→141→39→40;141 является пятой задачей стори61, новая ФТ FT-007-05. Исторические количества и Google-only условия ниже относятся к прежнему объёму.

| Версия | Дата Europe/Moscow | Изменение |
| --- | --- | --- |
| 1.0 | 24.10.2026 | Перепроверены #38/#42/#39/#40 по актуальному main; уточнены границы, порядок, критерии и вопросы анализа |

Основание: поручение владельца начать работу над стори61, проверить актуальность постановок, перевести задачи в анализ и указать последовательность разработки. Автор: Codex, системный анализ. Независимое review не проводилось. **Это актуализация входа в анализ, а не завершённый Full-дизайн и не разрешение на публичную публикацию.**

БТ0.10 / BT-013 → ТЗ0.20 / US-007 / FT-007-01…04; стори #61, эпик #37. NFR-001/002/003/004/005/006/007, SEC-001…008 по применимости. База: main `395decd18ea09ab6d7f155d56ded1ab66a079c31`. ID и четыре существующие задачи сохранены, дубли не создаются. Ценность: приглашённый ученик входит с другого устройства, работает со своими данными; владелец управляет допуском без открытой регистрации.

## 1. Что подтверждено и что устарело

| Объект | Факт / поправка |
| --- | --- |
| Backend | Java21 / Spring Boot4.1.1 / PostgreSQL; в pom нет Spring Security/OAuth2 starter, нет Account/Identity/SecurityFilterChain. Текущая проверка learner→session→task подтверждает связи UUID, не личность пользователя |
| Вход | Один внешний OIDC IdP + Spring Security + серверная сессия; приглашения — правило допуска поверх входа, не альтернативная аутентификация. Свои пароли, второй IdP, админ-панель и email-рассылка не включаются |
| Новые ресурсы | В main уже есть intake evidence68, learning state/assessments/decisions18/19, skill history и pinned exercise resources120. Старого перечня Learner/Attempt/Media недостаточно |
| Активные изменения | PR128/#125 добавляет page/detail истории; PR129/#126 — потребителя; PR130/#127 меняет безопасные ошибки,127 не реализует login/session/CSRF; PR131/#85 — метаданные/preview. До кода38 перечитать принятый итог этих PR, не переоценивать готовое |
| Internal writer | LearningAssessmentAuthorizer содержит локальную credential-проверку HUMAN_REVIEW/measurement, не пользовательский login. Внешний профиль не должен превращать эти токены в обход principal/ownership или право владельца менять чужие оценки |
| Медиа/TTL | V10/system_settings: media.retention_after_processing_hours=48; media.max_unprocessed_age_hours=48; cleanup interval60min. EvidenceMediaCleanup использует ту же policy. У40 устарело processed+24h: заменить действующими динамическими значениями, без возврата к24h и без новой TTL-реализации |
| Форматы | Прежние upload limits:20MiB/120s; MP3/WAV/OGG/WebM/M4A-AAC. Ноты и собственные примеры из68 также входят в приватность. Учебный медиакаталог отделён от временных файлов учеников |
| Развёртывание | Сейчас bind127.0.0.1; health/info exposed, details never; рабочего internet auth нет. Локальный hosts/Caddy и DNS alias уже документированы — не считать их публичным release |
| Источники плана | PR41 содержит `docs/deployment/internet-deployment-plan-v1.md` v1.1. Использовать как исторический архитектурный источник; этот документ — актуальное дополнение. Домен/IdP/VPS/бюджет там ещё не выбраны |

Реализация пяти музыкальных этюдов, автоматического аудиоанализа и AI не является условием написания auth. Защищаются реально существующие функции и будущие routes по deny-by-default. Новые музыкальные провайдеры не создаются в61.

## 2. Порядок и устранение циклических зависимостей

| Порядок разработки | Задача | Результат и условие передачи дальше |
| --- | --- | --- |
| 1 | #38 / FT-007-01 | Единый security foundation: Account/Identity/ownership, OIDC/session/CSRF, Vue auth-state. Известные identity допускаются, неизвестные fail closed. На отдельном тестовом стенде доступны заранее созданные fixtures двух accounts; публичная регистрация не открывается |
| 2 | #42 / FT-007-02 | Приглашения/CLI, bootstrap владельца, атомарный admission неизвестной identity через действующее приглашение, контролируемая привязка локального Learner и отзыв. Использует модель/principal/admission extension38, не создаёт второй security layer |
| 3 | #39 / FT-007-03 | Воспроизводимая защищённая staging-поставка одного origin с DNS/HTTPS, private backend/DB, proxy и rollback. Интернет-доступ для учеников ещё не включается автоматически |
| 4 | #40 / FT-007-04 | Backup/restore, лимиты/наблюдаемость, полная security/ops regression и документированный go/no-go. Внешний допуск только после решения владельца по результату |

**Порядок анализа:** сначала общий контракт38↔42, параллельно входные решения39 (origin, регион, бюджет, инфраструктура), затем уточнение40. Это не поручение выполнять четыре разработки параллельно. Доменные имена/redirect нужны раньше физического deploy, поэтому выбор параметров39 не ждёт окончания42. Анализ40 по backup/limits начинается заранее, чтобы не выяснить нехватку диска после установки.

38 не требует готовой invitation-функции для своей проверки: неизвестная identity получает отказ, известные accounts задаются в disposable test fixture. 42 подключает согласованный admission interface. Рабочее создание первого OWNER и приглашений находится в42; его не дублировать административным HTTP API38. Изолированные fixtures не служат production bootstrap или публичным обходом. Общие таблицы и extension контракт согласовать до оценки38/42.

## 3. #38 — актуальный предмет Full-анализа

Включено:

- Spring Security/OIDC Authorization Code через одного IdP; issuer/audience/state/nonce/signature проверяются стандартным стеком. Backend-managed session; никакого JWT в localStorage или собственного password store.
- Общие accounts(ACTIVE/DISABLED,OWNER/LEARNER), account_identities(unique issuer+subject), owner binding личного Learner. Одна identity/внутренняя account-ссылка не определяется email после привязки. Роль OWNER сама по себе не даёт читать/оценивать чужие записи.
- Server-side current actor и проверка ACTIVE/access version на каждом запросе. Клиентский learnerId остаётся параметром ресурса с обязательной проверкой владельца; сервер не выбирает identity по localStorage.
- Единый общий интерфейс admission для42; неизвестная identity без допуска отклоняется. Данные issuer/subject только из проверенного OIDC результата.
- Session fixation protection, HttpOnly/Secure/SameSite, idle/absolute timeout, logout, CSRF для всех меняющих запросов включая multipart и logout. Vue получает/обновляет CSRF после входа/выхода; один SameSite не заменяет CSRF.
- Authenticated current-user contract (например `/api/v1/me`) и безопасный CSRF bootstrap; точные path/schema/status/allowlist/redirect — результат анализа, не текущий существующий API. Защита API401 JSON без HTML redirect; чужой объект безопасный404, запрет действия403; тексты не раскрывают наличие чужих данных.
- Login/logout/expired session во Vue RU/EN; current learner восстанавливается по серверному account. При logout/account switch очищаются чужие store/cache/local selections и останавливается capture. Dirty draft/recording требует явного UX, нельзя молча терять файл или автоматически повторять POST после нового login.
- Production security profile fail closed при отсутствующей конфигурации. Локальный режим не ослабляет production профиль; не менять loopback и не открывать интернет ради проверки. Поведение local/prod/QA, настройки и startup validation задать явно.
- Отдельно определить external-mode политику внутреннего writer. Устаревшие локальные shared credentials не становятся пользовательской identity; неподтверждённые методы/критерии не получают оценочные полномочия из факта login.

**Обязательная матрица routes:** learner create/read/preferences; onboarding background; diagnostic start/get/continue/tasks; recommendations, skills/history; evidence capabilities/list/detail/upload; attempt capabilities/list/upload и page/detail после125; learning/assessments/decisions/evidence; учебный каталог и все versioned/legacy GET/HEAD media; system/ping; Actuator и error/static/auth paths. Для каждого: anonymous/A-owner/B-other/DISABLED/CSRF/method и no-store. Нельзя оставить анонимный read рядом с защищённым POST. Общий учебный каталог не привязан к Learner, но правило authenticated/public надо записать отдельно, а не открывать весь `/api/**`.

До оценки закрыть: schema/constraints/migration/backfill, границы read/write transactions, admission interface, exact `/me` и CSRF DTO/errors, session store/TTL, bootstrap profile и политика существующих данных, allowlist routes, anonymous/forbidden/expired UX. Учитывать фактические migrations V1–V17 и свежие merge; номер следующей миграции выбирать по актуальному main. Не переписывать V2/старые migrations.

AC38-1: два accounts и anonymous не получают чужие ресурсы по подменённым UUID во всех методах. AC38-2: неизвестная/disabled identity не получает application access. AC38-3: state/nonce/issuer и session/CSRF негативные случаи отклонены. AC38-4:401/expiry/logout/account switch не дают утечку контекста или write-autoretry. AC38-5: deny-by-default покрывает новые page/detail/evidence/assessment/HEAD и Actuator; локальный стенд не выставлен наружу.

## 4. #42 — актуальный предмет Full-анализа

- Только защищённая серверная CLI для invite create/list/revoke, account disable/enable, bootstrap первого OWNER и контролируемого связывания существующего локального Learner. Не создавать email-рассылку/админ-HTTP API/страницу публичной регистрации.
- Invitation: issuer+проверяемый email, expires_at, PENDING/ACCEPTED/EXPIRED/REVOKED; default TTL7дней из исходной карточки. Способ нормализации email, уникальность действующих приглашений, enum/status persistence и команды требуют точного контракта до оценки. Не удалять `+tag`/точки произвольно.
- Новый вход: только email_verified=true от выбранного IdP и точное соответствие политике приглашения. Уже связанный issuer+subject используется независимо от последующего изменения email. Unverified/missing email/чужой issuer/expired/revoked/нет invite — отказ без подробной утечки.
- Транзакция одноразового потребления приглашения + создание Account/Identity/Learner/binding; уникальность issuer+subject и владения Learner, обработка гонки двух входов и rollback без частичной учётной записи.
- Не привязывать все существующие local Learners по похожему display name или email. Оператор явно указывает UUID и проверенную identity; зафиксировать dry-run, блокировку двойной привязки, сохранение учебной истории и аудит без секретов. Не терять единственный OWNER при disable/revoke без проработанного восстановления.
- Отзыв действует со следующего запроса уже открытой сессии (механизм account status/access version из38). Отличать revoke неиспользованного invite от disable уже активированного account; не удалять чужую историю или медиа побочным действием.

AC42-1: валидное приглашение активируется один раз, без него новый пользователь не создаётся. AC42-2: expired/revoked/unverified/wrong issuer и гонка активаций дают предсказуемый безопасный результат. AC42-3: email change не подменяет identity. AC42-4: account disable немедленно отказывает действующей session, сохранённые данные не удаляются. AC42-5: bootstrap/migrate local Learner — явные контролируемые операции, нет takeover/двойной привязки/секретов в журнале.

## 5. #39 — актуальный предмет Full-анализа

Недорогой Linux VPS, один origin, Caddy80/443, Spring backend и PostgreSQL приватны. Собранный Vue отдаётся production-поставкой; Vite5173 не выставлять наружу. Существующий Windows hosts/Caddy не переустанавливать как новый сервер. Сначала выбрать публичный origin для OIDC registration; реальные DNS/порты/credentials пока не изменять.

Анализ определяет: домен/DNS/VPS/регион/предел бюджета, версия Linux и способ запуска (systemd либо Compose, без навязывания нового runtime), размеры по тестовой нагрузке, build/static asset packaging/SPA fallback, deploy manifest и версия, environment и secret storage, доступ оператора и trusted proxy. Forwarded headers принимать только от доверенной границы; не верить клиентскому X-Forwarded-Host/Proto при построении redirect. Auth/API ошибки не должны превращаться в index.html или кешироваться как static.

Подготовку config/scripts можно вести по согласованным placeholders до аренды VPS; фактическая проверка DNS/TLS/phone microphone требует выбранного стенда. Staging закрыт дополнительным доступом и синтетическими данными до общего go/no-go. Нельзя считать публикацию выполненной по наличию примерного Caddyfile или использовать `--insecure` как PASS сертификата.

AC39-1: fresh deploy воспроизводим по версии/инструкции, Vue и API/auth работают на одном origin. AC39-2: HTTPS/redirect/callback/cookies и SPA fallback проверены,5173/8080/5432 недоступны извне, forwarded spoof не подменяет origin. AC39-3: нет секретов/медиа в build/log/repo, backend/DB минимально привилегированы. AC39-4: documented rollback восстанавливает совместимое приложение без открытия анонимного доступа; staging не объявлен public launch.

## 6. #40 — актуальный предмет Full-анализа

Шифрованный off-server backup структурированной PostgreSQL, ограниченная ротация, контролируемый доступ; реальные записи и временные ноты/аудио не архивируются. Сохранить необходимые structured metadata/history/settings/Flyway; определить секреты/PII и шифрование, а не утверждать, что любой DB dump не содержит персональных данных. Проверить restore в изолированном стенде; не восстанавливать dump в пользовательскую БД. Backup destination, RPO/RTO/retention/budget задаются по выбранному стенду, без выдуманного SLA.

Проверить существующий TTL48h/48h по DB settings на attempts и evidence/audio/scores; не реализовывать21 заново и не обещать вернуть удалённые файлы. После restore старые AVAILABLE metadata могут ссылаться на отсутствующие originals — предусмотреть безопасную reconciliation без выдуманного восстановления медиа. Общие учебные assets и их версии сохраняются по120, не смешиваются с TTL learner media.

Пределы upload20MiB/120s уже есть; дополнительные per-account/IP rate/concurrency/disk thresholds и место их enforcement распределить с38/39 до разработки. Reverse proxy size limit должен учитывать multipart overhead, а не случайно сделать допустимый20MiB файл невозможным. Health не раскрывает детали/секреты; задание backend auth rule и proxy exposure распределены38/39,40 проверяет итог и alarms.

Сквозная проверка: invited/uninvited, два ученика и подмена всех IDs, expiry/CSRF/logout/disable, HTTPS microphone и резервная загрузка, новые history/media routes, лимиты/переполненный диск, backup failure/restore, update/rollback. Секреты/token/session IDs и оригинальные медиа не попадают в logs. AI/audioanalysis production-провайдеров сейчас нет: подтвердить отсутствие внешней отправки/расхода; не требовать реализовать AI ради старого пункта «ошибкаAIAPI». Будущая интеграция сохраняет отдельный SEC-007 gate.

AC40-1: backup и изолированный restore проверены, raw media не включены, права/шифрование/ротация задокументированы. AC40-2: dynamic TTL48h/48h и post-restore missing files дают честную историю без утечки/потери structured data. AC40-3: лимиты, диск/backup alerts и logs проверены на отказах. AC40-4: двухпользовательская security+mobile HTTPS regression выполнена на точном RC SHA; непроверенные кейсы видимы. AC40-5: go/no-go с известными ограничениями и rollback представлен владельцу; публикация не выполняется до его решения.

## 7. Реестр решений анализа — пока не закрытые вопросы

| ID | Что требуется определить | Где / влияние |
| --- | --- | --- |
| A61-01 | Один IdP, audience/client registration, email_verified/claims/discovery, redirect и доступность для приглашённых |38+42; решение до оценки итогового login; секреты только в защищённой конфигурации |
| A61-02 | Idle/absolute TTL, session store и значения cookie/CSRF/logout, поведение после restart |38; конкретные значения пока не утверждены |
| A61-03 | Account schema/admission interface/OWNER bootstrap/модель переноса существующего Learner и право на internal writer |38+42; закрыть общий контракт до реализации обеих, без неявной роли teacher/admin |
| A61-04 | Public origin, домен/VPS/регион/месячный предел, админ-доступ и способ запуска |39; origin нужен заранее для38, инфраструктурные затраты/выдача credentials не делаются этим анализом |
| A61-05 | Backup destination/key handling, retention/RPO/RTO, disk thresholds, rate/concurrency quotas |40 совместно38/39; не выдавать стартовые гипотезы за согласованную эксплуатационную норму |

Владельцем продуктовых/затратных решений остаётся Роман; СА готовит конкретные варианты и trade-offs в следующем анализе. Эта задача не требует немедленного ввода credentials или покупки сервера. Архитектурные детали и предложения можно прорабатывать локально с fixtures; неизвестные параметры не делают публичную поставку разрешённой. Общий Q-003 ТЗ не закрыт формальным переносом карточек.

## 8. Дальнейшая подготовка и проверка

Профиль всех четырёх задач — Full: новые trust boundaries, персональные данные, миграции, concurrency и восстановление. Текущий результат **Анализ / В очереди**: scope refresh готов, подробный системный анализ выбирается следующим шагом38. Стори61 и эпик37 переходят в Разработка/В работе как активный цикл анализа; это не старт product coding/deploy.

Прежние предварительные диапазоны сохраняются как история, не как подтверждённая новая оценка:38=36–60ч,42=12–24ч,39=16–32ч,40=16–32ч. Они не суммируются в обещание срока. Пакет38 слишком велик для стандартной задачи4–6ч; после общего дизайна при оценке разобрать на принимаемые increments под той же61, без дублирования уже выделенной42. Новые задачи/часы сейчас не создаются.

Для каждого AC38/42/39/40 подготовить тест-кейсы «Дано/Когда/Тогда» с negative/конкурентными ветвями, REST/schema/settings tables, security matrix, актуальные component/sequence/schema diagrams, deployment smoke и rollback. Текущие перечисленные AC — сценарии входа в анализ, **все NOT_RUN**. Схема зависимостей: [исходник](../diagrams/us-007-delivery-order.mmd). Установка/откат этой актуализации — только документы/Project поля; binary/config/DB/DNS не менялись.

### Проверенные первичные технические источники

- [Spring Security: OAuth2/OIDC logout](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/logout.html) — различает local и provider logout; выбранный сценарий предстоит зафиксировать в38.
- [Spring Security: CSRF, SPA integration](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html) — учесть header/token lifecycle, в том числе после login/logout.
- [Spring Boot: Actuator endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html) — custom security требует явно определить management exposure/access.
- [Caddy: reverse_proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy) и [request matchers](https://caddyserver.com/docs/caddyfile/matchers) — доверенная proxy boundary, без доверия произвольным forwarded headers.

Источники просмотрены24.10.2026. Конкретные API/config привязать к реально разрешённым зависимостям Boot4.1.1 при подготовке38, а не копировать пример другой major-версии. Выбор IdP/VPS из этих справок не следует.
