# Guitar Mentor AI — Java backend (DEV-01)

**Состояние:** начальный Spring Boot-каркас. Пока нет моделей ученика, Telegram-бота и ИИ-интеграции.

**Стек:** Java 21, Spring Boot 4.1.1, Maven Wrapper 3.3.4 (Maven 3.9.16), PostgreSQL 17, Flyway, JUnit 5/6 через Spring Boot test starter.

## Локальная проверка на Windows 10

1. Откройте проект в VS Code и создайте локальную БД `guitar_mentor`, роль `guitar_mentor_app` с паролем (см. [полную инструкцию](../docs/development/workstation-setup-windows.md)).
2. PowerShell: из корня проекта перейдите в `backend/`.
3. Сначала запустите **не требующие БД unit-тесты**:

```powershell
Set-Location .\backend
.\mvnw.cmd --version
.\mvnw.cmd test
```

4. Для **запуска приложения** задайте параметры в **текущем терминале**. Следующий пример вводит пароль без отображения в терминале:

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/guitar_mentor"
$env:SPRING_DATASOURCE_USERNAME = "guitar_mentor_app"
$secret = Read-Host "Пароль пользователя guitar_mentor_app" -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try { $env:SPRING_DATASOURCE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
Remove-Variable secret,ptr

.\mvnw.cmd spring-boot:run
```

Не запускайте Java-приложение без настроенной БД: оно **намеренно не стартует**, если не может применить миграции/подключиться к PostgreSQL. Пароль хранится в переменной окружения текущего PowerShell и наследуется запускаемыми дочерними процессами; после работы закройте терминал. Не публикуйте секреты в скриншотах, логах, файлах `application.yml` или коммитах.

5. Во **втором** терминале проверьте:

```powershell
Invoke-RestMethod http://localhost:8080/api/v1/system/ping
Invoke-RestMethod http://localhost:8080/actuator/health
```

Ожидается `status=ok`, `service=guitar-mentor-ai` и `status=UP`. Первая миграция создаст таблицу `app_metadata` и таблицу истории миграций Flyway. `/actuator/health` сообщает о состоянии БД, тогда как `/api/v1/system/ping` — лишь о доступности HTTP.

## Архитектурные договорённости

- Пока один Spring Boot backend; будущий фронтенд Vue и Telegram будут общаться с ним через единые REST/application services.
- Слои предметной логики `domain` / `application` / `infrastructure` будем добавлять **по мере появления реальных задач**, без пустых классов.
- Схема БД развивается только Flyway-миграциями, Hibernate использует `ddl-auto: validate`.
- **Unit tests** могут запускаться без БД; CI дополнительно реально поднимает PostgreSQL и делает HTTP smoke test.
- Maven Wrapper `only-script` скачивает Maven при первом запуске, поэтому нужен интернет; отдельная установка Maven не нужна.

Если возникли ошибки или загрузка зависимостей прервана VPN/антивирусом, пришлите **текст ошибки без паролей**.

## Известные границы

Каркас подготовлен как исходный код и вынесен на проверку в GitHub Actions. Успешную реальную сборку не следует считать подтверждённой, пока CI не завершится зелёным либо локальные тесты не будут запущены. См. [ADR-0001](../docs/adr/0001-java-vue-postgresql.md).
