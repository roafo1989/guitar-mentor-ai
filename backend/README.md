# Guitar Mentor AI — Java backend (DEV-01)

**Состояние на 19.10.2026:** main 270238f содержит профиль, анкету, диагностические сессии/задания, каталог и аудиопопытки с техническим worker/ffprobe (Flyway V1–V9). Музыкального AI, TTL, авторизации и Telegram нет. #30 находится в отдельном PR #69. [Текущий статус и приёмка](../docs/project/current-state.md).

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

Ожидается `status=ok`, `service=guitar-mentor-ai` и `status=UP`. Flyway последовательно применяет V1–V9, создавая app_metadata, профиль/анкету, сессии/каталог и submission_attempts; существующие данные не требуется удалять. `/actuator/health` сообщает о состоянии БД, тогда как `/api/v1/system/ping` — лишь о доступности HTTP.

## Архитектурные договорённости

- Один Spring Boot backend; существующий Vue использует REST, будущий Telegram переиспользует те же application services/данные.
- Существующие слои: `domain` / `application` / `infrastructure` / `interfaces/rest`; правила и транзакции остаются на сервере.
- Схема БД развивается только Flyway-миграциями, Hibernate использует `ddl-auto: validate`.
- **Unit tests** могут запускаться без БД; CI дополнительно реально поднимает PostgreSQL и делает HTTP smoke test.
- Maven Wrapper `only-script` скачивает Maven при первом запуске, поэтому нужен интернет; отдельная установка Maven не нужна.

Если возникли ошибки или загрузка зависимостей прервана VPN/антивирусом, пришлите **текст ошибки без паролей**.

## Аудио и границы

Для технического worker нужен ffprobe (FFmpeg) в PATH либо GUITAR_MENTOR_FFPROBE. GUITAR_MENTOR_ENABLE_UPLOADS=false по умолчанию; включать true только в локальном тестовом сеансе. GUITAR_MENTOR_MEDIA_PATH задаёт приватный каталог, по умолчанию ./.local-media/attempts относительно backend. Лимиты: MP3/WAV/OGG/WebM, 20 MiB, 120 с. [Полная инструкция](../docs/development/dev-07-local-audio-submission.md).

PROCESSED не означает музыкальную оценку. Автоочистки ещё нет; политика #21 — 48 часов после всех проверок, PostgreSQL settings. Профиль UUID и HTTPS не заменяют авторизацию. При тестировании открытого #30 использовать его ветку, не считать Draft принятым main.

Обычный `mvnw.cmd test` выполняет unit-тесты без БД. Настоящие PostgreSQL integration-тесты #30 и независимые QA сценарии пока находятся в PR #69/#71, их результаты и команды отличаются от main. Результаты текущего CI и ограничения — в снимке проекта; его зелёный статус не подменяет ручную приёмку.
