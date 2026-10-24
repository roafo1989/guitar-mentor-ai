# #193 — Локальный импорт личного музыкального индекса (Windows 10 / PowerShell 7)

> Эта инструкция **не** меняет локальную БД без явной команды `--apply`. В публичный GitHub не загружать оригиналы, CSV, дампы БД или пароль PostgreSQL.

## Preconditions

- #190 / PR #191 слиты в `main` (Flyway V27 и `scripts/music_catalog_import.py`).
- Источники: `D:\GuitarLibrary\Index\v1\works.csv`, `arrangements.csv`, `files.csv`, `review-queue.csv`. При распаковке ZIP может возникнуть дополнительная вложенная `GuitarMentor-Index-v1` — ниже есть проверка.
- Исходные файлы в `D:\GuitarLibrary\ScoresAndMedia` остаются вне БД.
- Есть существующий активный account владельца, ID берётся из БД, не придумывается.
- Изолированная тестовая база предпочтительна для первого `--apply`.

## 1. Refresh and verify repository

```powershell
Set-Location 'D:\Development\guitar-mentor-ai'
git status --short
# Если есть локальные изменения, НЕ выполняй git pull до их разбора.
git pull --ff-only origin main
Test-Path '.\backend\src\main\resources\db\migration\V27__private_music_catalog_index.sql'
Test-Path '.\scripts\music_catalog_import.py'
```

Обе проверки должны вернуть `True`. Локальные незакоммиченные изменения не уничтожать.

## 2. Find extracted CSV directory

```powershell
$index = 'D:\GuitarLibrary\Index\v1'
if (!(Test-Path -LiteralPath (Join-Path $index 'works.csv'))) {
    $index = Join-Path $index 'GuitarMentor-Index-v1'
}
'Index: ' + $index
@('works.csv','arrangements.csv','files.csv','review-queue.csv') |
    ForEach-Object { [PSCustomObject]@{ File=$_; Present=(Test-Path -LiteralPath (Join-Path $index $_)) } }
```

Все четыре `Present` должны быть `True`.

## 3. Dry-run (без доступа к PostgreSQL)

```powershell
py -3 .\scripts\music_catalog_import.py --directory $index
```

Ожидаемо: `works=66649 arrangements=77596 files=77596 review-queue=24222` (формат сообщений зависит от версии скрипта); финал `DRY-RUN ONLY. No database changes.` Не продолжать при ошибках.

## 4. Backup and Flyway

Перед импортом сделай резервную копию **своей** БД штатным `pg_dump` или pgAdmin Backup, убедись, что файл читается. Дамп приватен и не должен попадать в репозиторий. Не указывай пароли в команде и не отправляй их в чат.

Flyway V27 применяется при штатном запуске обновлённого backend, если `spring.flyway.enabled=true`; проверь `flyway_schema_history` в тестовой или локальной БД. **Не запускай SQL V27 вручную**, если её уже применил Flyway.

```sql
SELECT version, description, success
FROM flyway_schema_history
WHERE version = '27';
```

Если V27 отсутствует, сначала штатно запускай миграции backend в согласованном тестовом окружении; не делать импорт, пока таблицы не созданы.

## 5. Identify owner account

В pgAdmin/psql подключись к нужной локальной БД и выполни:

```sql
SELECT id, role, status FROM accounts ORDER BY created_at;
```

Используй только UUID **своего** активного аккаунта. Не отправляй его вместе с секретами доступа.

## 6. First apply (отдельное осознанное действие)

Установи зависимость в **локальном venv**, не в папке приложения:

```powershell
py -3 -m venv .venv-music-index
.\.venv-music-index\Scripts\python.exe -m pip install "psycopg[binary]"
```

Параметр `MUSIC_CATALOG_DSN` настрой локально, не коммить его. Не печатай строку подключения в чат или в журналы. Подключись сначала к тестовой БД; в PowerShell сохрани DSN в переменную окружения только текущего сеанса.

```powershell
$ownerId = '<UUID_EXISTING_ACTIVE_ACCOUNT>'
.\.venv-music-index\Scripts\python.exe .\scripts\music_catalog_import.py --directory $index --apply --account-id $ownerId
```

Команда без `--apply` — dry-run. При ошибке любая транзакция откатывается. Сведения о файлах попадут в БД, **но открыть оригиналы пока нельзя**.

## 7. Verify and rerun

Подключись к той же БД:

```sql
SELECT count(*) FROM music_catalog_works WHERE owner_account_id = '<OWNER_UUID>';
SELECT count(*) FROM music_catalog_arrangements WHERE owner_account_id = '<OWNER_UUID>';
SELECT count(*) FROM music_catalog_source_files WHERE owner_account_id = '<OWNER_UUID>';
SELECT count(*) FROM music_catalog_review_queue WHERE owner_account_id = '<OWNER_UUID>';
SELECT count(*) FROM music_catalog_source_files
WHERE owner_account_id = '<OWNER_UUID>' AND visibility <> 'PRIVATE';
```

Ожидаемо: **66649 / 77596 / 77596 / 24222 / 0**. Повтори ту же команду `--apply`, затем повтори запросы: количества должны остаться теми же. Не передавай SQL/дамп с приватными именами и полными путями; достаточно результатов counts.

## Ownership and limits

- Схема V27 — индекс метаданных, отдельный от V25 `library_materials` / `library_assets`.
- Нет нового веб-API или публичного доступа. Не следует считать импортированную запись реально загруженным оригиналом.
- Не включать в общий пользовательский каталог (#156/#168) без проверки прав.
- Сохранять исходные `D:\Гитара`, `D:\Табы`, курсы и `ScoresAndMedia` до завершения резервного копирования и отдельного решения об очистке.
