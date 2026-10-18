# Guitar Mentor AI

**Guitar Mentor AI** — некоммерческий проект персонального ИИ-наставника для обучения игре на гитаре.

## Стек

- Backend: Java 21, Spring Boot 4.1.1, Maven Wrapper, Spring Data JPA, PostgreSQL 17, Flyway.
- Frontend (следующий этап): Vue 3, TypeScript, Vite, Pinia, vue-i18n, организация по мотивам JHipster.
- Telegram (позднее): ещё один интерфейс к тем же сервисам и базе.
- Рабочая среда: VS Code / Windows 10.

**[Дорожная карта MVP v1.0](docs/project/roadmap-mvp-v1.md)** · **[Архитектурное решение](docs/adr/0001-java-vue-postgresql.md)** · **[Настройка компьютера](docs/development/workstation-setup-windows.md)**

## DEV-01-JAVA — первый backend

Этот раздел относится к ветке `feature/dev-01-java-bootstrap`; до слияния PR в `main` Java-код будет отсутствовать.

- [Инструкция для первого Java запуска](docs/development/dev-01-java-quickstart.md)
- [README backend и команды PowerShell](backend/README.md)
- API: `GET /api/v1/system/ping`
- Health: `GET /actuator/health`

На данном этапе реализован только каркас приложения. **Пока нет** Telegram-интеграции, Vue-фронтенда, пользователей, уроков, ИИ-наставника, аудиоанализа. Для запуска нужна работающая PostgreSQL 17 и локальные переменные окружения с секретом вне Git.

## История проекта

Первоначальный экспериментальный Python-каркас: [PR #5](https://github.com/roafo1989/guitar-mentor-ai/pull/5), не использовать как основу Java. [Python-инструкция (архив)](docs/development/archive/python-workstation-2026-10.md) сохранена.
