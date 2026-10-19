# DEV-02-JAVA — Проверка первого бизнес-сценария (Windows 10)

## Подготовка локальной ветки

```powershell
Set-Location D:\Development\guitar-mentor-ai
git fetch origin
git switch main
git pull --ff-only
Set-Location backend
.\mvnw.cmd test
```


## Запуск backend

PostgreSQL должен быть запущен; Flyway сам выполнит миграции V1 и V2.

Перед запуском задайте параметры подключения к БД по [backend/README.md](../../backend/README.md), **не вставляя пароль в исходный код**. Затем:

```powershell
.\mvnw.cmd spring-boot:run
```

Backend без авторизации слушает только `127.0.0.1:8080` и **не предназначен для внешнего доступа**.

## Тест через HTTP

Откройте второе окно PowerShell.

```powershell
$base = 'http://127.0.0.1:8080/api/v1/learners'
$body = @{
  displayName = 'Student'
  instrumentCode = 'ACOUSTIC_GUITAR'
  techniqueCode = 'FINGERSTYLE'
  preferences = @(
    @{ styleCode = 'BLUES'; rating = 'LIKE' },
    @{ styleCode = 'ROCK'; rating = 'LIKE' },
    @{ styleCode = 'FUNK'; rating = 'EXCLUDE' }
  )
} | ConvertTo-Json -Depth 5

$created = Invoke-RestMethod -Uri $base -Method Post -ContentType 'application/json' -Body $body
$created | ConvertTo-Json -Depth 7

$learnerId = $created.id
$loaded = Invoke-RestMethod -Uri "$base/$learnerId" -Method Get
$loaded | ConvertTo-Json -Depth 7

$updated = @{
  instrumentCode = 'ACOUSTIC_GUITAR'
  preferences = @(
    @{ styleCode = 'BLUES'; rating = 'LIKE' },
    @{ styleCode = 'JAZZ'; rating = 'NEUTRAL' }
  )
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Uri "$base/$learnerId/preferences" -Method Put -ContentType 'application/json' -Body $updated |
  ConvertTo-Json -Depth 7
```

Ожидается, что POST вернёт ID, GET восстановит профиль, а PUT оставит только BLUES и JAZZ. После PUT стиль FUNK отсутствует — мы заменили список предпочтений. При ошибке схемы Flyway, недоступной БД или 400/500 пришлите текст ошибки **без паролей**.

## Зачем разделяем сущности

- `Learner` — владелец профиля (позже привяжем к аутентифицированному аккаунту).
- `InstrumentProfile` — отдельный профиль инструмента и техники, пригоден для будущих фортепиано и электрогитары.
- `MusicPreference` — предпочтение стиля, не связанное автоматически с уровнем навыка.
- `LearnerService` — транзакционная бизнес-логика без привязки к HTTP.
- `LearnerController` — REST-адаптер (позднее Vue frontend и Telegram).
- Flyway `V2__create_learner_profiles.sql` создаёт структуру; Hibernate только валидирует её.

## Безопасность

Пока **нет авторизации**. Не открывать порт 8080 в сеть, не пробрасывать роутер, не публиковать стенд и не вносить реальные персональные данные. Перед многопользовательской работой добавим аутентификацию и проверки доступа.
