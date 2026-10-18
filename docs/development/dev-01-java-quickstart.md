# DEV-01-JAVA — первый запуск и проверка

Это первая реализация Java-каркаса после отказа от Python как основного backend.

## Получение исходников (Windows PowerShell)

Убедитесь, что в корне Git репозитория нет локальных изменений: `git status`. Старый Python-код при необходимости хранится в `D:\Development\guitar-mentor-python-backup`.

```powershell
Set-Location D:\Development\guitar-mentor-ai
git fetch origin
git switch --track origin/feature/dev-01-java-bootstrap
git status
```

Если локальная ветка `feature/dev-01-java-bootstrap` уже существует, используйте `git switch feature/dev-01-java-bootstrap`, затем `git pull --ff-only`.

Проверьте наличие `backend/pom.xml` и `backend/mvnw.cmd`. В VS Code команда **Java: Configure Java Runtime** после импорта Maven проекта должна распознать JDK 21.

## Проверка

- `cd backend`
- `.\mvnw.cmd --version` — использует Maven, скачанный Maven Wrapper.
- `.\mvnw.cmd test` — unit-тесты без необходимости запуска PostgreSQL.
- Настроить `SPRING_DATASOURCE_PASSWORD` без отображения на экране по инструкции в [README backend](../../backend/README.md).
- `.\mvnw.cmd spring-boot:run` — запуск Spring Boot, PostgreSQL должен работать.
- Во втором PowerShell проверить `http://localhost:8080/api/v1/system/ping` и `http://localhost:8080/actuator/health`.
- В pgAdmin проверить наличие `app_metadata` и `flyway_schema_history`.

**Важно:** если CI GitHub ещё не завершён — сообщите результат локальной сборки. Не меняйте PostgreSQL-роль на администратора, не сохраняйте пароль в git.

## Что дальше

В отдельном PR добавим сущности, сервисы и REST API для реальных учебных сценариев. Затем Vue 3/TypeScript фронтенд в стиле JHipster: `entities`, `service`, `router`, `shared/model`, `i18n`.

Архитектура: [ADR-0001](../adr/0001-java-vue-postgresql.md).
