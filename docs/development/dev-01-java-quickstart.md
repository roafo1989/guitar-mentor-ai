# DEV-01-JAVA — первый запуск и проверка

Это первая реализация Java-каркаса после отказа от Python как основного backend.

## Получение исходников (Windows PowerShell)

Убедитесь, что в корне Git репозитория нет локальных изменений: `git status`. Старый Python-код при необходимости хранится в `D:\Development\guitar-mentor-python-backup`.

```powershell
Set-Location D:\Development\guitar-mentor-ai
git fetch origin
git switch main
git pull --ff-only
git status
```


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

## Состояние после последующих инкрементов

В main уже есть сущности, REST API, PostgreSQL V1–V9 и Vue. Этот документ проверяет исходный каркас, не приёмку полного приложения; [текущее состояние](../project/current-state.md).

Архитектура: [ADR-0001](../adr/0001-java-vue-postgresql.md).
