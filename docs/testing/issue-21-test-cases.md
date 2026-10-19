# Тест-кейсы #21 — TTL и безопасная очистка медиа

Редакция 1.0 · 19.10.2026 · US-004 / FT-004-03 · PR #72 · исходный проверяемый SHA `632d85f8f5a866651bea725b1e08a134813206dd`.

## Предусловия и правила

- **Только отдельный тестовый PostgreSQL и временный storage**; не менять часы хранения и не запускать cleanup на папке с личными записями. Один backend JVM и единственный владелец локального каталога.
- Данные: тестовый WAV, ≥2 попытки с разными requestId, имитация файлов с валидными UUID-ключами, старый/свежий orphan, symlink/каталог, тестовые processed/processing/pending записи.
- Настройки в `system_settings`: `media.retention_after_processing_hours=48`, `media.max_unprocessed_age_hours=48`, `media.cleanup_interval_minutes=60`; для ускорения сдвигать timestamps только тестовых строк SQL/через Clock, **не ждать реальные 48 часов**.
- Проверить запись результата в БД и физический файл: только UI не подтверждает отсутствие файла. После проверок откатить SQL-настройки/временные ограничения и удалить фикстуры.
- Статус **CI OK** означает успешное прохождение общего workflow, а не доказательство каждого сценария. **Требует QA** — отдельной приёмочной проверки нет.

| ID | AC | Шаги | Ожидаемый результат | Подтверждение и состояние |
| --- | --- | --- | --- | --- |
| TC21-01 | 01 | Накатить Flyway V10,V11 поверх базы V7–V9 с историческими UPLOADED/PROCESSING/PROCESSED/FAILED; снять снимок до/после. | ID, requestId, комментарии, hash, статусы сохранены; известная terminal дата переносится, неизвестная остаётся NULL; outcome отдельным столбцом. | Flyway/startup CI OK; детальная сверка миграции — требует QA. |
| TC21-02 | 02 | Установить `processing_finalized_at=now-48h+1s`; выполнить cleanup, затем `=now-48h`. | До TTL media_state AVAILABLE и файл существует; на границе DELETED и файл отсутствует. | PostgreSQL integration, CI OK. |
| TC21-03 | 03 | В одной SQL-транзакции заменить 48→72 и interval 60→1, пройти служебный тик; затем значение 0, отрицательное, неверный текст, overflow. | Изменения применяются без рестарта; при любой невалидной настройке удалений нет, безопасный код конфигурации. | Unit policy и CI OK; реальное обновление без restart — требует QA. |
| TC21-04 | 04 | Создать UPLOADED и зависший PROCESSING старше 48h (без активного worker), выполнить цикл. | Оба получают FAILED/RETENTION_EXPIRED до DELETE_PENDING; отсутствие бесконечного requeue. | PostgreSQL тест UPLOADED CI OK; PROCESSING — требует QA. |
| TC21-05 | 05 | В одном потоке удерживать upload внутри реальной транзакции до rollback, в другом вызвать scheduler/cleanup; повторить с worker, recovery. | Занятый объект не удаляется; rollback компенсирован; guard освобождён; нет взаимной блокировки. | Concurrent PostgreSQL upload + unit guard CI OK; worker/recovery конкурентные пути — требует QA. |
| TC21-06 | 06 | Прервать процесс в состоянии DELETE_PENDING до физического удаления и отдельно после него до DB-confirm; повторно запустить цикл. | Повтор идемпотентен, media_state DELETED только при подтверждённом отсутствии, history не потеряна. | PostgreSQL crash-window simulation CI OK; kill/restart приложения — требует QA. |
| TC21-07 | 07 | Вызвать отказ Files.delete/AccessDenied, затем ошибку подтверждения DELETED в PostgreSQL; восстановить доступ, повторить. | До успеха DELETE_PENDING, без ложного deleted_at; после повторного ALREADY_ABSENT подтверждено DELETED; пути в логах не раскрываются. | Unit IO + настоящий PostgreSQL CHECK-rejection CI OK; проверка логов — требует QA. |
| TC21-08 | 08 | Создать старый UUID orphan без ссылки, старый UUID с DB-ссылкой, свежий UUID, произвольное имя, symlink и каталог; затем отключить БД. | Удаляется лишь старый свободный валидный файл; при ошибке БД ничего не удаляется; symlink и чужие файлы защищены. | FS unit / mock DB CI OK; end-to-end reconciliation — требует QA. |
| TC21-09 | 08,10 | Подготовить >10 000 ссылочных старых UUID-файлов и orphan с ключом после них; выполнить последовательные тики. | Orphan рано или поздно достигается keyset-pagination, а ссылочные файлы остаются. | Есть тест 1100-файлового keyset и DB 125 records CI OK; масштаб 10 001+ — требует QA. |
| TC21-10 | 09 | Удалить файл после TTL, перезагрузить страницу, GET history, повторить POST с прежним requestId, затем с новым. | Старые id/status/comment/hash видны, старый requestId не создаёт файл, новый создаёт следующую попытку. | PostgreSQL idempotency CI OK; Vue/F5 — требует QA. |
| TC21-11 | 10 | Выключить upload в конфигурации, поместить просроченный зарегистрированный тестовый файл, рядом файл учебного MP3/PDF каталога; выполнить scheduler. | Очистка продолжает работать, учебный каталог не затронут; >100 записей обработаны несколькими циклами. | PostgreSQL 125 records CI OK; uploads=false + каталог — требует QA. |
| TC21-12 | 11 | Для unknown `processing_plan` и terminal без finalized_at запустить cleanup; для обычной попытки дважды finalize, сравнить даты. | Неизвестный/нефинализированный объект не удалён; повтор finalize не продлевает TTL. | PostgreSQL fail-closed + unit finalization CI OK. |
| TC21-13 | 12 | Просмотреть RU/EN UI для AVAILABLE, DELETE_PENDING, DELETED, FAILED/RETENTION_EXPIRED; проверить HTTP API. | Есть нейтральный текст об удалении источника, технический статус не подменяется оценкой исполнения, пути/ключи и скачивание не раскрываются. | Vue build CI OK; ручная UX/security проверка — требует QA. |
| TC21-14 | 07,12 | Состояние DELETE_PENDING + отсутствие файла, проверка `media_delete_reason` и `media_delete_outcome` после успешного повтора. | Причина PROCESSED_TTL/UNPROCESSED_TTL сохраняется, outcome ALREADY_ABSENT, deleted_at заполнен только после фиксации. | Unit и PostgreSQL + Flyway V11 CI OK. |
| TC21-15 | 05,08 | Завершить/откатить upload при одновременном orphan reconciliation, новый файлик с recent mtime. | Записанный и незакоммиченный файлы не удаляются orphan-поиском; rollback удаляет только свой ключ. | Частичная concurrency проверка CI OK; полная гонка — требует QA. |
| TC21-16 | 12 | Проверить отсутствие нового публичного DELETE/download API и утечек системных путей в HTTP, Vue, structured logs; выполнить регрессию #20/#30. | Нет публичной выдачи файлов, персональных путей или ложной музыкальной оценки; загрузка и rollback ведут себя как ранее. | HTTP smoke + Java/Vue CI OK; negative API/privacy + пользовательский прогон — требует QA. |

## Автоматическая проверка в текущем PR

- Java backend CI: https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39312295021 — **SUCCESS** на указанном SHA: unit, реальная PostgreSQL/Flyway интеграция, запуск backend и HTTP smoke.
- Vue frontend CI: https://github.com/roafo1989/guitar-mentor-ai/actions/runs/39312294787 — **SUCCESS** на том же SHA.
- Важное ограничение: нет оснований помечать все TC как PASS только на основании этих двух workflow; особенно TC21-03, 05, 08–11, 13, 15, 16 требуют отдельного прогона.

## Выходные критерии QA

Все 16 кейсов имеют конкретный результат PASS/FAIL/BLOCKED с ссылкой на лог, скриншот или SQL-доказательство, ни одного открытого дефекта высокой критичности, ручные опасные сценарии выполняются исключительно в тестовой среде. При FAIL создать комментарий в #21 с TC, шагами, actual/expected и вернуть в «Разработка / В очереди». При полном PASS сообщить владельцу на приёмку. **Не выполнять merge/close автоматически.**
