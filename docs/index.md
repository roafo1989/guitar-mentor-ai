# Guitar Mentor AI — Оглавление

Документация ведётся в репозитории вместе с кодом: Markdown + исходники PlantUML. Приложение пока работает **только локально** и **без авторизации**.

## Управление проектом

- **[Стандарт разработки — от бэклога до приёмки](project/development-workflow.md)** — утверждён 18.10.2026: одна задача за раз, автономная реализация, независимый тестировщик, итоговое решение владельца.
- [Поля и переходы Kanban](project/kanban-workflow.md) — текущие значения Этап/Status и точные правила переходов.
- [Kanban-доска](https://github.com/users/roafo1989/projects/1/views/3) · [Реестр задач](https://github.com/users/roafo1989/projects/1/views/2)

## Сценарии и требования

- [UC-01 — Профиль ученика](use-cases/UC-01-learner-profile-api.md) — реализовано
- [UC-03 — Музыкальный опыт и цели](use-cases/UC-03-onboarding-experience-goals.md) — реализовано
- [UC-02 — Первичная диагностика](use-cases/UC-02-initial-diagnostics.md) — проектируется

## Архитектурные решения

- [ADR-0001 — Java, Spring Boot, Vue и PostgreSQL](adr/0001-java-vue-postgresql.md)
- [ADR-0002 — Временные медиа и сроки хранения](adr/0002-media-storage-and-retention.md)
- [ADR-0003 — Оценка агента и решение ученика](adr/0003-diagnostic-feedback-and-student-decision.md)

## Диаграммы PlantUML

- [Состояния диагностической сессии](diagrams/diagnostic-session-states.puml)
- [Последовательность начала диагностики](diagrams/diagnostic-intake-sequence.puml)

## Разработка

- [Установка инструментов](development/workstation-setup-windows.md)
- [Локальный адрес https://guitar-mentor (Windows 10, Caddy)](development/local-https-caddy-windows.md) — **подтверждено 18.10.2026**, настройка HTTPS, восстановление старого `learnerId`, результаты проверок и ограничения
- [DEV-01 — Java bootstrap](development/dev-01-java-quickstart.md)
- [DEV-02 — Профиль ученика REST](development/dev-02-learner-profile-quickstart.md)
- [DEV-04 — Анкета опыта и целей](development/dev-04-onboarding-quickstart.md)

## Развёртывание

- **Сейчас:** бесплатный локальный `https://guitar-mentor` только на Windows-компьютере; [инструкция и результаты проверки](development/local-https-caddy-windows.md).
- **Позднее:** интернет-доступ по домену, VPS, идентификация и приглашения; [DEPLOY-00 #37](https://github.com/roafo1989/guitar-mentor-ai/issues/37), [проект плана (PR #41)](https://github.com/roafo1989/guitar-mentor-ai/pull/41). Публичный запуск **запрещён**, пока не реализована серверная авторизация.

## Активная разработка

- [DEV-05A — DiagnosticSession и сценарий](https://github.com/roafo1989/guitar-mentor-ai/issues/29)
- [DEV-05B — StorageService и TTL](https://github.com/roafo1989/guitar-mentor-ai/issues/30)
- [SPIKE-01 — Достоверность анализа исполнения](https://github.com/roafo1989/guitar-mentor-ai/issues/31)
