# Конвертация Guitar Pro — 170-E

`LibraryConversionWorker` читает durable PostgreSQL очередь и связывает claim/heartbeat/cancel с Java ProcessBuilder, изолированным alphaTab и атомарным сохранением приватной пары PDF + WAV. По умолчанию `GUITAR_MENTOR_LIBRARY_CONVERSION_ENABLED=false`; слияние кода не разрешает rollout.

Поддерживается прежний scope: GP3 v3.00, GP4 v4.06, GP5 v5.10; одна выбранная гитарная партия, стандартный EADGBE, 4/4, четвертные ноты без пауз, полифонии, повторов, эффектов, изменения темпа, каподастра и альтернативного строя. Версия проверяется по embedded header. GP7/8/GPX/GTP не поддерживаются. Discovery нескольких дорожек не расходует одну из двух попыток рендеринга.

## Сборка и исполнение

Из корня репозитория:

```powershell
docker build -f scripts/conversion-worker/Dockerfile -t gm-conversion-worker:170e .
```

Используются закреплённые Node 22.16.0, alphaTab 1.8.4, lockfile #169. Runtime применяет `--pull=never`; новый образ устанавливают заранее. Старый `:170b` несовместим с потоковым протоколом `:170e`.

| Настройка | По умолчанию |
| --- | --- |
| `GUITAR_MENTOR_LIBRARY_CONVERSION_ENABLED` | `false` |
| `GUITAR_MENTOR_LIBRARY_CONVERSION_WORKER_ENABLED` | `true`, действует только при включённых library + conversion |
| `GUITAR_MENTOR_LIBRARY_CONVERSION_IMAGE` | `gm-conversion-worker:170e` |
| `GUITAR_MENTOR_LIBRARY_CONVERSION_DOCKER_EXECUTABLE` | `docker` |
| `GUITAR_MENTOR_LIBRARY_CONVERSION_WORKSPACE` | `./.local-media/conversion-work` |

Два локальных потока не блокируют scheduler. Глобальные два слота и максимум одна активная job на account обеспечиваются PostgreSQL. Каждому claim назначается новый UUID владельца lease; callback каждые 5 s продлевает lease и проверяет cancel. Потеря lease запрещает commit. Shutdown прерывает процесс, незавершённая job восстанавливается по lease. Успех требует обоих валидных файлов, исходного SHA и выбранной дорожки. Квота/lineage/assets/SUCCEEDED фиксируются одной транзакцией.

## Граница изоляции

- `/input/source.gp`: неизменяемая копия, максимум 20 MiB, SHA проверяется до и после копирования; mount read-only.
- Контейнер: UID 10001, no network, read-only root, dropped ALL capabilities, no-new-privileges, 512 MiB RAM без swap, CPU 1, PID 64.
- `/tmp` и `/out` ведут на один tmpfs `/scratch`, максимум 100 MiB для всех временных файлов контейнера. Writable bind mounts отсутствуют.
- Java watchdog 60 s; независимый `timeout` внутри контейнера посылает TERM на 59 s и KILL не позднее 60 s. Это действует и при гибели Java.
- В production stdout — бинарный протокол: `GM170E01`, затем три блока `uint32 big-endian length + bytes`: JSON (≤64 KiB), PDF (≤20 MiB), WAV (≤20 MiB). Имена фиксированы, распаковки архивов нет. Java потоково ограничивает каждый блок до записи на host. Backpressure учитывается.
- Дополнительное host staging ограничено копией исходника + двумя outputs + JSON (≤60 MiB +64 KiB на job). Оно отдельно от контейнерного tmpfs; нужен такой свободный объём на host.
- Контейнер удаляется по заранее назначенному имени при любом исходе; workspace удаляется после persistence или отказа. Остатки после аварии старше 10 минут убирает sweep. Символические ссылки не обходятся.
- Ошибки не содержат stack trace, paths или Docker diagnostics. Unsupported score не создаёт производные assets; original остаётся доступен.

## Проверки

```powershell
python -m pip install -r scripts/research/catalog-169/requirements.txt
python scripts/conversion-worker/generate-corpus.py
python scripts/conversion-worker/check-isolation.py
python scripts/conversion-worker/check-corpus.py
```

77 независимых синтетических случаев: GP3/4/5 success и single/multi-track, parity MIDI events, 1/5/32 такта и многостраничный PDF, неподдерживаемые ритмы/строи/эффекты/automation, corrupt/truncated/empty/wrong version/oversized/ZIP. ZIP отвергается по header до распаковки. GP3 не кодирует palm mute; этот тест проводится на GP4/5. Валидный GP5 second voice вызывает отказ pinned importer (`INVALID_OR_UNSUPPORTED_FILE`), проверяется именно отказ, поддержка полифонии не обещается.

`check-isolation.py` проверяет реальные cgroup limits, UID, loopback-only network, read-only root, ENOSPC, OOM kill и независимый timeout. `LibraryConversionProcessRunnerTest` проверяет process faults/protocol/cancel/watchdog/cleanup. `LibraryConversionWorkerPostgresTest` выполняет upload → queue → track selection → Docker → private persistence → owner download.

[Матрица и результаты](../../docs/testing/TC-170E-conversion-worker.md), [Windows runbook](../../docs/operations/conversion-worker-windows.md). Windows Docker Desktop и human visual/audio acceptance остаются отдельными gates.

При распространении образа сохраняются лицензии alphaTab MPL-2.0, Bravura SIL OFL 1.1, SONiVOX Apache-2.0 из закреплённых зависимостей.
