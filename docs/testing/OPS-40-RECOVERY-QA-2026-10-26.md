# OPS-02 / #40 — проверка комплекта восстановления

Дата: 26.10.2026. Код: отдельная локальная ветка `ops/40-disaster-recovery`,
основание `ops/39-pilot-release-shortcuts` / PR #219. Владелец явно разрешил
публикацию ветки и открытие PR 26.10.2026. Результат Windows CI фиксируется
отдельно ниже/в PR Checks; поля Project и состояние #40 не изменены.
Этот отчёт не закрывает #39/#40/#26.

## Выполнено

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

## Не выполнено и что именно проверить

| Проверка | Статус / способ |
| --- | --- |
| Windows ACL, реальный DPAPI export/re-import | NOT_RUN здесь; Windows-ветви Recovery.Tests.ps1 подготовлены |
| Реальный PostgreSQL 17 dump/restore нового кластера | NOT_RUN здесь; Recovery.Postgres.Tests.ps1 подготовлен |
| GitHub Windows CI | Результат конкретного head — в PR Checks; не подменять локальным прогоном |
| Повторный вход/загрузка в реальное облако, квота/сеть | NOT_RUN: rclone remote на ноутбуке не настроен этим сеансом |
| Полнота всех пользовательских папок и секретов | NOT_RUN: Inventory + ручная карта extraFolders/менеджер паролей |
| Реальный согласованный restore PILOT/DEV/library | NOT_RUN: §3 runbook, новые изолированные БД |
| Новый Windows/потеря старого DPAPI профиля | NOT_RUN: §4 в отдельной VM/на новом ПК |
| Вход, история, PDF, F5, тюнер/метроном, два аккаунта после восстановления | NOT_RUN: сначала без Funnel, затем отдельная внешняя проверка |
| Фактические RPO/RTO и ежедневная дисциплина копий | NOT_MEASURED; целевые ≤24 ч / 1–2 ч после готовой Windows |

Из-за ограничений среды здесь нельзя запустить Windows DPAPI/службы и PostgreSQL
от отдельного пользователя. Отсутствие этих проверок не подменено чтением кода.
Workflow `recovery-tools-ci.yml` добавлен для Windows, включая настоящий
одноразовый PostgreSQL-кластер и restic; после публикации нужно дождаться его
результата и исправить возможные замечания до приёмки на рабочем хосте.

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
