# DEV-05B / #30 — единое хранилище и компенсация rollback

**Объём:** FT-004-02, спецификация DEV-05B v1.0 (аналитическая ветка, PR #64). Нет TTL, планировщика очистки, S3, нового API или Flyway-миграций.

## Реализованная архитектура

- AttemptMediaStore — один внутренний порт upload/worker; LocalAttemptMediaStore — единственная реализация.
- delete(key): только сгенерированный ключ UUID + допустимое аудиорасширение. Результат: DELETED / ALREADY_ABSENT или IOException (INVALID_KEY, INVALID_MEDIA, DELETE_IO_ERROR).
- Для чтения и удаления проверяются тот же storage key и root, а также regular file без следования symlink (NOFOLLOW_LINKS). Старые файлы и их ключи совместимы.
- MediaTransactionGuard: callback зарегистрирован после store и до saveAndFlush. При подтверждённом ROLLED_BACK удаляет только новый файл; при COMMITTED сохраняет; при UNKNOWN не удаляет и пишет событие.
- При ошибке удаления пишет MEDIA_ROLLBACK_DELETE_FAILED с requestId/фазой/кодом, без пути, имени файла, аудио и stack trace. Ошибка БД остаётся основной.
- Работа без транзакции отклоняется, файл компенсируется, HTTP 201 не возвращается.
- Нет фонового поиска сирот (это будущая #21). Нет второй системы storage.

## Автоматические проверки

Java CI выполняет unit tests и реальный PostgreSQL 17 integration:
- Storage: форматы и лимиты #20; DELETED → ALREADY_ABSENT, сохранение соседнего файла; traversal/absolute path; каталог и symlink; детерминированный отказ ОС через mock Files.delete.
- MediaTransactionGuard: COMMITTED, ROLLED_BACK, STATUS_UNKNOWN, сбой удаления без утечки приватного пути и отсутствие активной транзакции.
- Spring + PostgreSQL: настоящий поздний rollback после успешного saveAndFlush; настоящий flush failure посредством временного SQL CHECK; commit, сохранённый файл, повтор requestId и неизменная нумерация.
- Existing HTTP smoke: upload, два формата проверки, idempotency, 120 секунд, отказ недостоверному MP3 и статус обработки.
- Vue TypeScript build отдельно. Нет музыкальной оценки, PROCESSED означает только техническую проверку.

Интеграционные тесты запускаются только при GUITAR_MENTOR_TEST_POSTGRES=true с настроенными SPRING_DATASOURCE_* (CI устанавливает эти значения). Они создают собственные фикстуры и временное SQL CHECK. Запускать только на тестовой БД.

## Ручная регрессия владельца

1. В VS Code остановить Spring Boot через Ctrl+C. В корне репозитория:

    git status
    git fetch origin
    git switch feature/issue-30-safe-media-store
    git pull --ff-only

   Если есть несохранённые локальные изменения, не использовать git reset --hard.

2. Spring Boot из D:\Development\guitar-mentor-ai\backend:

    $env:GUITAR_MENTOR_ENABLE_UPLOADS = "true"
    .\mvnw.cmd spring-boot:run

   FFprobe, Vite и Caddy — как в DEV-07. Не использовать настоящие персональные записи.

3. Открыть https://guitar-mentor/diagnostics. Убедиться, что прежние попытки №1–5 и статус №5 FAILED остались. Старые файлы вручную не удалять.
4. Записать 5–10 секунд тестового звука или выбрать короткий WAV; прослушать и отправить. Ожидать только одну новую попытку в статусе PROCESSED с duration > 0; вся история на месте.
5. F5 и перезапуск Spring Boot: история, номера и новые файлы сохранились.
6. При ENABLE_UPLOADS=false форма записи заблокирована, история видна. При возврате true форма доступна.
7. Массовое удаление пользовательских медиа **не входит** в эту задачу; тесты безопасного delete и rollback автоматизированы на временных файлах.
8. PR объединяется с main и Issue #30 закрывается только после отдельной приёмки владельцем.

## Критерии AC30-01–08

| Критерий | Проверка |
| --- | --- |
| AC30-01 | Один storage adapter в upload/worker, прежний API |
| AC30-02 | DELETED, повтор ALREADY_ABSENT, соседний файл сохранился |
| AC30-03 | traversal/absolute/каталог/symlink отклонены |
| AC30-04 | Ошибка ОС DELETE_IO_ERROR, не успешное удаление |
| AC30-05 | PostgreSQL flush failure и late rollback компенсируют файл, строки нет |
| AC30-06 | Commit/повтор requestId сохраняют файл и одну строку |
| AC30-07 | UNKNOWN без удаления, ошибка компенсации наблюдаема без утечки |
| AC30-08 | V7–V9, API, история, лимиты #20 и feature flag не изменены |

**Ограничение:** при аварийном выключении JVM до callback файл может остаться без строки. Реконсиляция и TTL — #21, не #30.
