# Guitar Mentor AI — документация

**[Состояние проекта и датированные результаты сверки](project/current-state.md)**. Последние дополнения: [гибкий метроном #104 — 21.10.2026](specifications/TOOL-02-flexible-metronome.md), [анализ четырёх задач — 20.10.2026](project/analysis-refresh-2026-10-20.md). Приложение работает локально, без авторизации.

## Навигация и регламенты

[Задачи](https://github.com/users/roafo1989/projects/1/views/3) · [Стори](https://github.com/users/roafo1989/projects/1/views/4) · [Эпики](https://github.com/users/roafo1989/projects/1/views/5) · [Иерархия](https://github.com/users/roafo1989/projects/1/views/2)

- [Жизненный цикл задачи 1.3](project/development-workflow.md)
- [Стандарт спецификаций 1.0: Full / Standard / Design / Light](project/specification-standard.md)
- [Шаблон спецификации изменения](templates/change-specification.md)
- [Протокол настройки досок 19.10.2026](project/board-standard-migration-2026-10-19.md)
- [Поля и переходы Kanban](project/kanban-workflow.md)
- [Регламент бизнес-анализа 1.3](project/business-analysis-workflow.md)
- [Регламент системного анализа 1.6](project/system-analysis-workflow.md)
- [Регламент тестирования 1.1](project/testing-workflow.md)
- [Реестр иерархии 0.9](project/issue-hierarchy.md)

## Требования и спецификации

- [#104 / TOOL-02 — гибкий метроном](specifications/TOOL-02-flexible-metronome.md) — размеры, акценты, индивидуальная громкость, подразделения/Tap/пресеты; v1.1,36 ч; база — принятая #87, запись/minimize/reopen сохраняются

- [Бизнес-требования 0.6](requirements/business-requirements.md)
- [Техническое задание 0.11](requirements/technical-specification.md) — 9 US / 32 FT, НФТ/SEC и открытые вопросы
- [Системная спецификация 0.6](specifications/system-specification.md) — текущие API/данные и целевой остаток
- [MVP Scope & Acceptance 1.1](project/mvp-scope-v1.md)
- [Roadmap 1.1](project/roadmap-mvp-v1.md) — исходные оценки исторические, не текущий остаток
- [#14 — корпус, эталоны и методика аудиопроверки](specifications/MVP-R2-01-audio-feasibility.md) — техническая часть принята; независимая REAL-валидация остаётся в #97
- [#15 — диагностический PoC и validation-gate](specifications/MVP-R2-02-audio-poc.md) — Full; диагностический инкремент не подменяет #97
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

[EPIC-002 #37](https://github.com/roafo1989/guitar-mentor-ai/issues/37) и [план PR #41](https://github.com/roafo1989/guitar-mentor-ai/pull/41): только приглашения, после auth/серверных прав и go/no-go. Локальное имя guitar-mentor не является интернет-публикацией.

## История и музыкальные материалы

- [Снимок переноса двух чатов 18.10](requirements/context-transfer-backlog-and-small-changes-2026-10-18.md) — исторический источник, не текущие статусы
- [Архив Python workstation](development/archive/python-workstation-2026-10.md) — не инструкция текущего приложения
- [GM-EB01: принятая v0.8B и история](../music/etudes/GM-EB01/README.md)
- [Анализ акустических блюзовых концовок](music/reference-analysis-acoustic-blues-endings.md)

**Последующее событие 19.10.2026:** #30 принята, PR #69/#70 объединены в main c521786; #14 выбрана для анализа и передана на оценку. Датированные снимки прежних этапов в документах сохраняют историю.


