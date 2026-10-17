# Guitar Mentor AI — Документация проекта

> Документация как код: Markdown/PlantUML в GitHub. Проект ведётся итерационно; утверждённые бизнес-принципы отделены от технических предложений.

## Концепция и требования
- [Vision & Scope — бизнес-цели и принципы](requirements/vision.md)
- Реестр требований и полноценные спецификации Use Cases — следующие итерации.

## Архитектура
- [ADR-001 — Разделение медиа и метаданных](architecture/adr/ADR-001-media-separation.md)
- [ADR-002 — Минимальные сроки хранения пользовательских медиа](architecture/adr/ADR-002-media-retention.md)
- [ADR-003 — Documentation as Code](architecture/adr/ADR-003-documentation-as-code.md)

## План реализации
- [MVP Backlog v0.1](backlog/MVP-v0.1.md)
- [DEV-01 — Создание Python-проекта](https://github.com/roafo1989/guitar-mentor-ai/issues/1)
- [DEV-02 — Telegram bot bootstrap](https://github.com/roafo1989/guitar-mentor-ai/issues/2)
- [DEV-03 — SQLite и миграции](https://github.com/roafo1989/guitar-mentor-ai/issues/3)

## Разработка
- Исходный код: `src/guitar_mentor/` (DEV-01).
- Тесты: `tests/` (DEV-01).
- Команды локального запуска — в корневом README после DEV-01.
- Не добавлять реальные `.env`, токены, локальные БД, пользовательские медиа или аудиозаписи в Git.

## Правила ведения документации
Изменения — через Pull Requests; актуальность спецификаций проверяется одновременно с изменениями реализаций. Для архитектурных решений создаётся ADR. При необходимости подключим локальную сборку MkDocs без переноса материалов.
