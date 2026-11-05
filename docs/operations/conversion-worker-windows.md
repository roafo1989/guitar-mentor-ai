# 170-E — проверка Windows Docker Desktop

Статус на 05.11.2026: **NOT_RUN**. Автоматический Linux CI не подтверждает работу на Windows 10/11. Production/PILOT не изменяются. Выполнять на отдельном тестовом checkout, тестовой PostgreSQL и синтетических данных. Перед началом Docker Desktop должен работать в режиме Linux containers; Java 21 и Python доступны в PATH.

## Изолированный сценарий

```powershell
git fetch origin
git switch feature/170e-orchestration
docker info --format '{{.OSType}}'
docker build -f scripts/conversion-worker/Dockerfile -t gm-conversion-worker:170e .
python -m pip install -r scripts/research/catalog-169/requirements.txt
python scripts/conversion-worker/generate-corpus.py
python scripts/conversion-worker/check-isolation.py
python scripts/conversion-worker/check-corpus.py

docker run --detach --name gm170e-test-db -e POSTGRES_DB=guitar_mentor -e POSTGRES_USER=guitar_mentor_app -e POSTGRES_PASSWORD=ci_only_password -p 127.0.0.1:55432:5432 postgres:17
docker exec gm170e-test-db pg_isready -U guitar_mentor_app -d guitar_mentor
```

Когда БД готова, в том же PowerShell:

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:55432/guitar_mentor'
$env:SPRING_DATASOURCE_USERNAME = 'guitar_mentor_app'
$env:SPRING_DATASOURCE_PASSWORD = 'ci_only_password'
$env:GUITAR_MENTOR_TEST_POSTGRES = 'true'
$env:GM_TEST_REAL_DOCKER = 'true'
$env:GM_TEST_CONVERSION_CORPUS = (Resolve-Path 'artifacts/conversion-170e/corpus').Path
Push-Location backend
.\mvnw.cmd '-Dtest=LibraryConversionWorkerPostgresTest,LibraryConversionJobPostgresTest,LibraryConversionOutputPostgresTest' test
Pop-Location
```

Для связанного browser сценария дополнительно:

```powershell
Push-Location frontend
npm ci
npx playwright install chromium
Pop-Location
$env:GM_TEST_REAL_BROWSER = 'true'
Push-Location backend
.\mvnw.cmd '-Dtest=LibraryConversionBrowserPostgresTest' test
Pop-Location
Remove-Item Env:GM_TEST_REAL_BROWSER
```

Результат и screenshots: `artifacts/conversion-170e/browser/`. Браузер использует только запущенный тестом loopback server и synthetic accounts; приложение и Docker image tag для fault/retry изолированы.

Тест использует путь workspace с пробелами: проверяются аргументы ProcessBuilder, mount/ACL и очистка. POSIX fake-process tests на Windows намеренно пропускаются; реальные Docker/DB tests обязательны. Сохранить surefire reports, JSON reports, `docker version`, `java -version`, версию Windows и commit SHA. Ни один скрипт не включает production feature flag.

## Ручные проверки

На отдельно запущенном тестовом приложении с library/conversion, включёнными **только в тестовой сессии**, загрузить `artifacts/conversion-170e/corpus/valid.gp5`; выбрать Lead Guitar. Убедиться, что UI доходит до результата, PDF/WAV открываются, оригинал скачивается без изменения, другому account результат недоступен. Повторить отмену во время RUNNING, отсутствие image, retry после восстановления image, перезапуск приложения с активной job. После завершения проверить отсутствие `gm-conversion-*` контейнеров и временных job directories; после аварии sweep имеет 10-минутный grace period.

Открыть PDF из `results/valid-GP5/`: четыре такта в полной строке, последняя неполная допустима, нотный стан/TAB совпадают, перенос не уменьшает шрифт, нет обрезаний. Прослушать WAV: 20 событий, ровные четверти, правильная высота/октава, без шума, клиппинга и лишних голосов. Зафиксировать отдельные PASS/FAIL для visual review и listening.

## Диагностика

| Симптом | Проверка |
| --- | --- |
| `WORKER_UNAVAILABLE` | `docker info`, `docker image inspect gm-conversion-worker:170e`; разрешения Docker Desktop текущему пользователю |
| `WORKER_PROTOCOL_ERROR` | Должен быть новый образ `170e`, не старый `170b`; пересобрать на проверяемом SHA |
| Source/mount failure | Shared filesystem/ACL Docker Desktop; workspace с запятой не поддерживается, пробелы поддерживаются |
| `OUTPUT_QUOTA_EXCEEDED` | Освободить место в тестовой библиотеке и повторить; частичной пары быть не должно |
| `WORKER_RESOURCE_LIMIT` / `TIMED_OUT` | Проверить короткий synthetic fixture; пределы не отключать |
| Job остаётся RUNNING после аварии | Ждать expiry lease 90 s и sweep; проверить доступность тестовой PostgreSQL |

После проверки удалить только созданную тестовую БД:

```powershell
docker rm --force gm170e-test-db
Remove-Item Env:SPRING_DATASOURCE_URL,Env:SPRING_DATASOURCE_USERNAME,Env:SPRING_DATASOURCE_PASSWORD,Env:GUITAR_MENTOR_TEST_POSTGRES,Env:GM_TEST_REAL_DOCKER,Env:GM_TEST_CONVERSION_CORPUS
```
