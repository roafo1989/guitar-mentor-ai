# Guitar Mentor AI — Оглавление

Документация ведётся в репозитории вместе с кодом: Markdown + исходники PlantUML. Доступны локальный запуск и домашний HTTPS-пилот с авторизацией; подтверждения и ограничения пилота приведены в инструкции развёртывания.

## Управление проектом

- **[Стандарт разработки — от бэклога до приёмки](project/development-workflow.md)** — утверждён 18.10.2026: одна задача за раз, автономная реализация, независимый тестировщик, итоговое решение владельца.
- [Поля и переходы Kanban](project/kanban-workflow.md) — текущие значения Этап/Status и точные правила переходов.
- [Kanban-доска](https://github.com/users/roafo1989/projects/1/views/3) · [Реестр задач](https://github.com/users/roafo1989/projects/1/views/2)

## Сценарии и требования

- **[Бизнес-требования — версия в main и история изменений](requirements/business-requirements.md)**. [Редакция 0.9 в открытом PR #64](https://github.com/roafo1989/guitar-mentor-ai/blob/493f566cd886fdd0425c859555c54db312f10947/docs/requirements/business-requirements.md) — репертуарная цель и инициатива наставника; ТЗ требует синхронизации.
- [Бизнес-анализ: варианты и открытые решения](requirements/discovery/business-analysis-2026-10-20.md) — v0.3, выводы с учётом двух блоков ответов 21.10.2026.
- [Опросник заказчика](requirements/discovery/customer-interview-2026-10-20.md) — v0.3, ответы двух блоков; дальше — занятие, запись и отзыв.
- **[Регламент бизнес-анализа](project/business-analysis-workflow.md)** — чат БА, обработка идей, версии, критерии приёмки и связь с задачами.

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

- [Домашний пилот: ярлыки запуска, обновления и возврата версии](development/home-pilot-releases.md) — текущий выбранный вариант #39; инструкция и отдельная приёмка release tooling.

- Локальная разработка: `https://guitar-mentor` на Windows-компьютере; [инструкция](development/local-https-caddy-windows.md).
- Домашний интернет-пилот через Tailscale Funnel выбран вместо обязательного VPS. Авторизация требуется и для инструментов; [#39](https://github.com/roafo1989/guitar-mentor-ai/issues/39), эксплуатационная приёмка [#40](https://github.com/roafo1989/guitar-mentor-ai/issues/40). Перенос приложения на guitar-mentor.ru отложен.

## Активная разработка

- [DEV-05A — DiagnosticSession и сценарий](https://github.com/roafo1989/guitar-mentor-ai/issues/29)
- [DEV-05B — StorageService и TTL](https://github.com/roafo1989/guitar-mentor-ai/issues/30)
- [SPIKE-01 — Достоверность анализа исполнения](https://github.com/roafo1989/guitar-mentor-ai/issues/31)
