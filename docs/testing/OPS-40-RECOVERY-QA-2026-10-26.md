# OPS-02 / #40 — проверка комплекта восстановления

## Актуализация — 31.10.2026: перенос на main и единый приёмочный сценарий

Код перенесён из PR #230 на актуальный main после merge PR #246: [PR #247](https://github.com/roafo1989/guitar-mentor-ai/pull/247), head `ce90f86ba2dd877a48f015ebe532e5cb330b01e4`. PR #247 остаётся открытым; merge и пользовательская эксплуатационная приёмка не заявлены.

- [Windows workflow #39 — PASS](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/41218007145): синтаксис всех recovery scripts, AES-GCM/DPAPI portable envelope, ACL, restic synthetic snapshot/restore/check, тесты дискового резерва и безопасной диагностики, настоящий PostgreSQL 17 isolated restore, release safety regression.
- Добавлен `Invoke-RecoveryAcceptance.ps1`: одна команда может выполнить backup → полное чтение encrypted repository → isolated file restore → PILOT/DEV database restore → table/row/Flyway checks → restored JAR HTTP/CSRF smoke → post-start DB verification.
- Прогресс пишется атомарно; тот же WorkRoot продолжает с неуспешного шага. Временные реквизиты drill-БД зашифрованы DPAPI текущего Windows-пользователя; в отчёт они не попадают.
- Перед backup проверяется минимальный резерв staging-диска (default 1 GiB, configurable); ошибки места/квоты, доступа, сети и целостности дают безопасные понятные подсказки.
- Отчёт содержит snapshot short ID, точный source HEAD, release ID, возраст данных, длительность текущего/полного прогона, количество восстановленных файлов/байт, library originals, БД/строки/Flyway и результат локального smoke.

**Остаётся NOT_RUN:** rclone remote/реальный облачный аккаунт владельца, Inventory/extraFolders, пользовательский encrypted backup, restore из него, запуск на отдельной Windows-учётной записи/машине и фактические RPO/RTO. Windows CI использует синтетические данные и не заменяет эти действия. AI API пока вне реализации; постоянный мониторинг/24×7 не добавлялся. Загрузка/аккаунтные лимиты уже задаются действующим PILOT runtime: 20 MB файл, 32 MB multipart request, 200 MB/account, 100 files/account, 5 files/material; пересекающиеся пользовательские проверки остаются в #245.

Дата: 26.10.2026. Код: опубликованная ветка `ops/40-disaster-recovery`,
основание `ops/39-pilot-release-shortcuts` / PR #219. Владелец явно разрешил
публикацию ветки и открытие PR 26.10.2026. Результат Windows CI фиксируется
отдельно ниже/в PR Checks; поля Project и состояние #40 не изменены.
Этот отчёт не закрывает #39/#40/#26.

## Исходный локальный прогон

Среда: Linux x64, настоящий PowerShell 7.4.13 и restic 0.18.0.

| Проверка | Результат |
| --- | --- |
| Recovery.Tests.ps1, включая синтаксический разбор всех entry scripts | PASS, 45 assertions |
| AES-GCM/PBKDF2: перенос Unicode/кавычек, неверный пароль, изменение ciphertext, неизвестный формат | PASS |
| Скопированные бинарные/скрытые файлы, исключение кэшей, отказ symlink/рекурсии/того же SSD | PASS |
| READY assets: DB SHA/размер, отсутствие оригинала, некорректный storage key | PASS |
| Git bundle и binary diff: точный неопубликованный HEAD, удалённый tracked файл, бинарные изменения, .env, untracked | PASS |
| Отказ перезаписи рабочего дерева | PASS |
| Манифест комплекта/релиза, повреждение config, неожиданный файл, неполный набор | PASS |
| Изоляция наследуемых PostgreSQL/app/restic настроек, ввод через stdin, nonzero exit, отсутствие raw stderr в ошибке | PASS |
| Реальный restic: init, encrypted snapshot, поиск root marker, subtree restore --verify, полное check --read-data | PASS на локальном синтетическом repository |
| Pilot.Tests.ps1 — существующая релизная регрессия | PASS, 86 assertions на Linux |
| git diff --check | PASS |

Реальный тест restic использует локальный transport только в тестовой fixture.
Он подтверждает шифрованный snapshot/restore и разбор ответа, но не отправку
в аккаунт пользователя. Runtime config принимает rclone backend облака;
оператор должен выбрать настоящий облачный remote, не local/alias этого SSD.

## Подтверждённый Windows CI

Проверенный код: `6b1bf49ef8b552ae0212a301c82fb2084ddbddb7`,
[PR #230](https://github.com/roafo1989/guitar-mentor-ai/pull/230),
основание PR #219. Последующее обновление этого отчёта меняет только документацию.
Среда CI: Windows Server 2025 / windows-latest, PostgreSQL 17.11, restic 0.18.0.
Это не пользовательский Windows 10 и не реальные данные PILOT.

- [Disaster recovery tools — PASS](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/40300928056):
  50 recovery assertions, закрытый ACL, настоящий Windows DPAPI export/AES/import
  с повторным шифрованием в той же учётной записи, restic snapshot/restore/check;
  реальный изолированный PostgreSQL dump/restore, количество строк/Flyway,
  Unicode и бинарные данные, пароль с кавычкой и кириллицей, отказ перезаписи
  существующих данных и отказ при несовпадении; кластер остановлен;
  существующая релизная регрессия — 91 assertion PASS.
- [Pilot release tools — PASS](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/40300928081):
  91 assertion PASS, Maven verify на отдельном PostgreSQL — BUILD SUCCESS,
  334 tests, 0 failures, 0 errors, 5 skips. Тестовый сервер остановлен.

Windows-прогон обнаружил и позволил исправить два дефекта DR-комплекта:
наследование pipe handles тестовым PostgreSQL-демоном и искажение Unicode-пароля
через нативное Windows-окружение. Для SQL фиксирована UTF-8 кодировка, а
PostgreSQL credentials передаются через краткоживущий UTF-8 pgpass с закрытым ACL.
Тесты не ослаблены и не отключены ради зелёного результата.

В первом прогоне существующей Java-регрессии был один сбой
`DiagnosticEvidencePostgresIntegrationTest.sixtyFailedPendingDeletesCannotStarveFreshEligibleMedia`
(ожидалось 26, получено 25). Повторный запуск и следующие полные прогоны прошли.
Причина нестабильности этого существующего теста не установлена; продуктовый
код очистки и сам этот тест в DR-инкременте не изменялись.

## Не выполнено и что именно проверить

| Проверка | Статус / способ |
| --- | --- |
| Windows ACL/DPAPI, PostgreSQL 17, restic на синтетических данных CI | PASS, ссылки и точный код выше |
| Повторный вход/загрузка в реальное облако, квота/сеть | NOT_RUN: rclone remote на ноутбуке не настроен этим сеансом |
| Полнота всех пользовательских папок и секретов | NOT_RUN: Inventory + ручная карта extraFolders/менеджер паролей |
| Реальный согласованный restore PILOT/DEV/library | NOT_RUN: §3 runbook, новые изолированные БД |
| Новый Windows/потеря старого DPAPI профиля | NOT_RUN: §4 в отдельной VM/на новом ПК |
| Вход, история, PDF, F5, тюнер/метроном, два аккаунта после восстановления | NOT_RUN: сначала без Funnel, затем отдельная внешняя проверка |
| Фактические RPO/RTO и ежедневная дисциплина копий | NOT_MEASURED; целевые ≤24 ч / 1–2 ч после готовой Windows |

В локальной Linux-среде Windows-проверки недоступны; их реально выполнил
GitHub Windows CI. Перенос на другую Windows/учётную запись, полный запуск
восстановленного приложения и доступ к пользовательскому облаку остаются
отдельной приёмкой владельца; синтетический CI их не заменяет.

## Воспроизведение

```powershell
pwsh -NoProfile -File .\scripts\recovery\tests\Recovery.Tests.ps1 -ResticPath (Get-Command restic.exe).Source
pwsh -NoProfile -File .\scripts\recovery\tests\Recovery.Postgres.Tests.ps1
pwsh -NoProfile -File .\scripts\pilot\tests\Pilot.Tests.ps1
```

Postgres test создаёт свой случайный cluster/порт, синтетическую исходную БД,
новые целевые роли/БД, проверяет реальный restore, bytes, count/Flyway, пароль
с кавычкой/Unicode, отказ перезаписи и несовпадения данных. Рабочие БД/пароли
не читает. После теста останавливает **свой** cluster; при неудачной остановке
оставляет его файлы для разбора. Ничего не публикует.

После успешного пользовательского прогона приложите приватный отчёт с точным
snapshot ID, временем, размерами, проверками и оставшимися NOT_RUN. В публичную
Issue допустим только обезличенный итог, без дампов, путей личных документов,
OAuth/SMTP credentials или recovery-кодов.
