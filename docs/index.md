# Guitar Mentor AI — документация

- [#195: Full-анализ личного импортированного архива](project/CAT195-ANALYSIS-2026-10-25.md) — [ТЗ](specifications/CAT-LOCAL-03-private-archive-ui.md), [24TC](testing/TC-CAT195-private-archive-ui.md); передано на оценку, код не начат.

- [Повторная сверка25.10.2026: Light Minimal завершён, библиотека и новая очередь](project/board-audit-2026-10-25-late.md).

- [Полная сверка досок 25.10.2026: задачи, стори, эпики и зависимости](project/board-audit-2026-10-25.md).

**Актуализация25.10.2026 —151/152:** [проверка и остаток](project/UX02-REFRESH-2026-10-25.md).151 разблокирована после150,Full/8ч;152 Standard/8ч ждёт151 и общего candidate с198. ТЗ0.30,БТ0.21 без новыхFT. Новые14+12TC подготовлены, не исполнены.

**Новое25.10.2026:** [#198 — auth UX и новый логотип, готово разработчику](project/AUTH03-READINESS-2026-10-25.md): Full,8ч, Разработка/В очереди; [контракт](specifications/AUTH-03-auth-ui-and-branding.md),24TC,7точныхPNG и официальные Google assets. БТ0.21/ТЗ0.29. Это дополнение после вечерней сверки24.10, не новый полный аудит; код/QA198 не выполнены.

**Актуально на вечер24.10.2026:** [полный реестр104 карточек и очередь](project/board-audit-evening-2026-10-24.md). Приняты141/145/148/149/160/161/179/182; текущий фронт150→151→152, библиотека162→163→164. БТ0.20/ТЗ0.28. Старые датированные записи ниже — история, не команда повторно анализировать или реализовывать принятые задачи.

**[Light Minimal: стори147, очередь148–152](project/UX02-READINESS-2026-10-24.md)** — [концепция](design/light-minimal-v1.md), [точный UI-контракт](specifications/UX-02-light-minimal-handoff.md), [26TC](testing/TC-UX02-light-minimal.md);48ч, первая148.

**[Передача16 задач каталога к разработке: оценки, порядок и блокировки](project/CATALOG-READINESS-2026-10-24.md)** — P0первый,12задач оценены96ч,4pipeline ждутresearch; кодирование не начато.

**[Пользовательская библиотека и подготовка материалов — EPIC004/#154](project/CATALOG-ROADMAP-2026-10-24.md)**. Первый шаг: [личная загрузка готовых PDF/Guitar Pro](specifications/CATALOG-01-private-library.md), #155/#160–164; далее общий каталог, конвертация, интернет-поиск и аудио.

**[AUTH-02/#141: email, логин и пароль, единый UI авторизации](specifications/AUTH-02-email-password.md)** — принятая свободная регистрация и вход наряду с Google (#141/PR143); локальный SMTP/единый HTTPS принят в179, публичный rollout отдельно39/40.

**[Обязательный эталон оформления нот и TAB — MUS-NOTATION-01](specifications/MUS-NOTATION-01-score-tab-standard.md)**, утверждён24.10.2026. Исходный PDF предоставлен владельцем в чате24.10.2026.

**Отмена 24.10.2026:** владелец отказался от US-010/#112 и задач #113–116 («Я передумал, они мне не нужны»). BT-020 и FT-010-01…04 сняты с действующего объёма; разработка, оценки и условия старта этой ветки больше не применяются. Основной интерфейс метронома #111 сохраняется. Ниже сведения о скинах оставлены только как история.

**[Состояние проекта и датированные результаты сверки](project/current-state.md)**. Последние дополнения: [стори61: актуализация четырёх задач и порядок разработки —24.10.2026](specifications/US-007-access-analysis-2026-10-24.md), [актуальные доски/стори и единый блок входа —24.10.2026](project/board-audit-2026-10-24.md), [следующие три кандидата125/126/127 —23.10.2026](project/next-development-candidates-2026-10-23.md), [UX51: аудит, прототип и передача фронтенду —23.10.2026](specifications/UX-01-student-journey.md), [текущие кандидаты #119/#120/#85 —22.10.2026](project/next-development-candidates-2026-10-22.md), [тюнер: интерфейс и строи #108 — 21.10.2026](specifications/TOOL-03-tuner-interface-and-tunings.md), [гибкий метроном #104 — 21.10.2026](specifications/TOOL-02-flexible-metronome.md), [анализ четырёх задач — 20.10.2026](project/analysis-refresh-2026-10-20.md). Локальный стенд уже поддерживает принятую авторизацию email/password и Google; публичное развёртывание39/40 не подтверждено.

## Навигация и регламенты

[Задачи](https://github.com/users/roafo1989/projects/1/views/3) · [Стори](https://github.com/users/roafo1989/projects/1/views/4) · [Эпики](https://github.com/users/roafo1989/projects/1/views/5) · [Иерархия](https://github.com/users/roafo1989/projects/1/views/2)

- [Жизненный цикл задачи 1.4](project/development-workflow.md)
- [Стандарт спецификаций 1.0: Full / Standard / Design / Light](project/specification-standard.md)
- [Шаблон спецификации изменения](templates/change-specification.md)
- [Протокол настройки досок 19.10.2026](project/board-standard-migration-2026-10-19.md)
- [Поля и переходы Kanban](project/kanban-workflow.md)
- [Регламент бизнес-анализа 1.3](project/business-analysis-workflow.md)
- [Регламент системного анализа 1.6](project/system-analysis-workflow.md)
- [Регламент тестирования 1.2](project/testing-workflow.md)
- [Реестр иерархии 0.9](project/issue-hierarchy.md)

## Требования и спецификации

- [CORE-01: автономная разработка #125/#126/#127](specifications/CORE-01-autonomous-development.md) — Java/Vue,24ч, точные API/UI и решения без продуктовых TBD
- [16TC: история и ошибки](testing/TC-125-126-127-autonomous-handoff.md) — подготовлены, NOT_RUN

- [Текущие кандидаты119/120/85: каталог Java/Vue](project/catalog-development-candidates-2026-10-22.md) — контракт v1.1,22ч; criteria из V12, #85 ждёт API
- [Приёмка119/120/85:16TC и команды](testing/TC-119-120-85-catalog-handoff.md) — NOT_RUN

- [#114 — скин Tempo Dial](specifications/TOOL-06-tempo-dial.md) — отменено владельцем24.10.2026; историческая спецификация

- [#109 — метроном в фоновой вкладке](specifications/TOOL-02-background-metronome.md) — Full,28ч; AudioWorklet/lifecycle, реальные Chrome-проверки впереди
- [#113 — общий UI-controller метронома](specifications/TOOL-05-metronome-ui-controller.md) — отменено владельцем24.10.2026; историческая спецификация

- [#108 / TOOL-03 — интерфейс тюнера и выбор строя](specifications/TOOL-03-tuner-interface-and-tunings.md) — голова грифа/подсветка колка, Standard/Drop D/Open G; новая постановка после #98, макет/оценка впереди

- [#104 / TOOL-02 — гибкий метроном](specifications/TOOL-02-flexible-metronome.md) — размеры, акценты, индивидуальная громкость, подразделения/Tap/пресеты; v1.1,36 ч; база — принятая #87, запись/minimize/reopen сохраняются

- [Бизнес-требования 0.19](requirements/business-requirements.md) — два блока интервью, выбор репертуара и уверенная игра наизусть; ТЗ ещё не синхронизировано с BT-017/018
- [Бизнес-анализ и варианты](https://github.com/roafo1989/guitar-mentor-ai/blob/main/docs/requirements/discovery/business-analysis-2026-10-20.md) — v0.3, уточнение по двум блокам ответов 21.10.2026
- [Интервью заказчика](https://github.com/roafo1989/guitar-mentor-ai/blob/main/docs/requirements/discovery/customer-interview-2026-10-20.md) — v0.3, ответы BA-Q01–05/BA-Q21–24; следующий блок — занятие, запись и отзыв
- [Техническое задание 0.27](requirements/technical-specification.md) — 15 действующих US / 59 FT, НФТ/SEC и открытые вопросы
- [Системная спецификация 0.6](specifications/system-specification.md) — текущие API/данные и целевой остаток
- [MVP Scope & Acceptance 1.1](project/mvp-scope-v1.md)
- [Roadmap 1.1](project/roadmap-mvp-v1.md) — исходные оценки исторические, не текущий остаток
- [#14 — корпус, эталоны и методика аудиопроверки](specifications/MVP-R2-01-audio-feasibility.md) — техническая часть принята; независимая REAL-валидация остаётся в #97
- [#15 — диагностический PoC и validation-gate](specifications/MVP-R2-02-audio-poc.md) — подготовка v1.1 сохранена,32чтолько diagnostic; по решению владельца отложено вместе с аудиоанализом/ИИ
- [#95 — сохранение языка после F5](specifications/BUG-095-persist-interface-locale.md) — Standard
- [#18 — оценки, история и решения ученика](specifications/MVP-R4-01-learning-state-and-decisions.md) — Full; состояния, REST, миграции и конкуренция
- [#19 — UI результатов занятия](specifications/MVP-R4-02-lesson-results-ui.md) — Full; общий контракт с #18, полный следующий шаг зависит от #24
- [#30 — контракт storage и rollback](specifications/DEV-05B-media-storage.md)
- [#21 — TTL/очистка](specifications/MVP-R5-02-media-retention.md) — реализовано в main на срез 20.10; прежние статусы/часы в документе исторические
- [UC-01 — профиль](use-cases/UC-01-learner-profile-api.md)
- [UC-03 — опыт и цели](use-cases/UC-03-onboarding-experience-goals.md) — в main; полная ручная приёмка #11 требует подтверждения
- [UC-02 — диагностика](use-cases/UC-02-initial-diagnostics.md) — частичная реализация, целевой цикл открыт
- [UX урока](ux/lesson-workflow-skillspace-reference.md) · [карта fingerstyle/история первого этюда](project/r3-fingerstyle-diagnostic-brief.md)

## Архитектура и диаграммы

- [Архитектура/компоненты 0.4](architecture/system-architecture.md)
- [ADR-0001 — стек](adr/0001-java-vue-postgresql.md)
- [ADR-0002 — медиа и политика 48h](adr/0002-media-storage-and-retention.md) — runtime TTL уже в main на срез 20.10
- [ADR-0003 — оценка и решение ученика](adr/0003-diagnostic-feedback-and-student-decision.md)
- [Архитектура PlantUML](diagrams/system-architecture.puml) · [компоненты](diagrams/backend-components.puml)
- [Диагностические состояния](diagrams/diagnostic-session-states.puml) · [intake](diagrams/diagnostic-intake-sequence.puml)
- [Storage #30](diagrams/dev-05b-storage-sequence.puml) · [cleanup #21](diagrams/mvp-r5-02-cleanup-sequence.puml)
- Последовательности [US-001](diagrams/us-001-sequence.puml), [US-002](diagrams/us-002-sequence.puml), [US-003](diagrams/us-003-sequence.puml), [US-004](diagrams/us-004-sequence.puml), [US-005](diagrams/us-005-sequence.puml), [US-006](diagrams/us-006-sequence.puml), [US-007](diagrams/us-007-sequence.puml), [US-008](diagrams/us-008-sequence.puml), [US-009](diagrams/us-009-sequence.puml)

## Запуск и проверки

- [Рабочее место Windows](development/workstation-setup-windows.md) · [локальный HTTPS/Caddy](development/local-https-caddy-windows.md)
- [Backend](../backend/README.md) · [Frontend](../frontend/README.md)
- [DEV-01 — Java](development/dev-01-java-quickstart.md) · [DEV-02 — профиль](development/dev-02-learner-profile-quickstart.md)
- [DEV-04 — onboarding](development/dev-04-onboarding-quickstart.md)
- [DEV-05A — сессия](development/dev-05a-diagnostic-session-quickstart.md)
- [DEV-06 — каталог](development/dev-06-exercise-catalog-quickstart.md) · [установка учебных MP3/PDF](development/dev-06-media-preview.md)
- [DEV-07 / #20 — аудиопопытки, ffprobe, флаг и приёмка](development/dev-07-local-audio-submission.md)
- [#30 — запуск/приёмка в PR #69](https://github.com/roafo1989/guitar-mentor-ai/blob/07cc33b99e9cc6fdbf0c9cc7dae28c1a1fab87ac/docs/development/dev-05b-media-store-acceptance.md)
- [#30 — 25 кейсов и фактический отчёт, PR #70](https://github.com/roafo1989/guitar-mentor-ai/pull/70) · [исполняемые проверки, PR #71](https://github.com/roafo1989/guitar-mentor-ai/pull/71)

## Будущее развёртывание

[EPIC-002 #37](https://github.com/roafo1989/guitar-mentor-ai/issues/37) и [план PR #41](https://github.com/roafo1989/guitar-mentor-ai/pull/41): свободная стандартная регистрация по принятой141; публичный rollout только после серверных прав и go/no-go39/40. Локальное имя guitar-mentor не является интернет-публикацией.

## История и музыкальные материалы

- [Снимок переноса двух чатов 18.10](requirements/context-transfer-backlog-and-small-changes-2026-10-18.md) — исторический источник, не текущие статусы
- [Архив Python workstation](development/archive/python-workstation-2026-10.md) — не инструкция текущего приложения
- [GM-EB01: принятая v0.8B и история](../music/etudes/GM-EB01/README.md)
- [Анализ акустических блюзовых концовок](music/reference-analysis-acoustic-blues-endings.md)

**Последующее событие 19.10.2026:** #30 принята, PR #69/#70 объединены в main c521786; #14 выбрана для анализа и передана на оценку. Датированные снимки прежних этапов в документах сохраняют историю.


