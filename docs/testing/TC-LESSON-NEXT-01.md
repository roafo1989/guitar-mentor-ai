# TC-LESSON-NEXT-01 — ручное продолжение занятий

**Версия 1.0 · 25.10.2026 · #216 / FT-003-11.** Источник: [Full-контракт](../specifications/LESSON-NEXT-01-manual-next-lesson.md), БТ0.22/ТЗ0.32. Продуктовые проверки ниже **не выполнялись**. Нет RC, кода или результатов CI этой задачи. При исполнении записать SHA, среду, фактический результат и evidence. Анализ не является тестированием.

Тестовые данные: два независимых admitted account A/B; актуальная session A с TASK_ASSIGNED; состояния task ACTIVE, COMPLETED_BY_LEARNER и COMPLETED_PASSED, причём terminal fixtures только в тестовой БД. Учебные упражнения E1/E2 с разрешёнными версиями, probe P1, запрещённый материал X; E2 может быть только test fixture и не объявляется поставленным музыкальным произведением. Фактический каталог стенда проверить отдельно. PostgreSQL обязателен для lock/index/rollback тестов.

| TC | AC | Предусловие и действия | Ожидаемый результат | Факт / статус |
| --- | --- | --- | --- | --- |
| TC-NEXT-01 | 01 | A завершил task самостоятельно; открыть workspace и Home | CTA выбора следующего, реальный COMPLETED_BY_LEARNER и доступ к результатам | Не исполнено / NOT_RUN |
| TC-NEXT-02 | 01 | Повторить для PASSED и ACTIVE; открыть picker напрямую для ACTIVE | PASSED допускает выбор; ACTIVE не заменяется и не закрывается автоматически | Не исполнено / NOT_RUN |
| TC-NEXT-03 | 02 | Открыть каталог, ввести часть title/code, сбросить поиск, просмотреть >20 fixtures | Только подходящий LEARNING_REPERTOIRE, стабильный порядок, страницы по 20 и реальный поиск | Не исполнено / NOT_RUN |
| TC-NEXT-04 | 02 | Preview PDF/аудио, Back/Отмена; проверить БД | Preview работает по pinned версии, PDF свёрнут; переходов/новых session нет | Не исполнено / NOT_RUN |
| TC-NEXT-05 | 02 | Каталог пуст; нет совпадений; GET500/offline; Retry | Разные empty/no matches/error, понятный возврат, без вымышленных заданий | Не исполнено / NOT_RUN |
| TC-NEXT-06 | 03 | Выбрать E2 и явно начать | Один новый session/task ACTIVE, выбранная версия, без повторного intake; старый source ARCHIVED | Не исполнено / NOT_RUN |
| TC-NEXT-07 | 03 | Выбрать то же E1, подтвердить «Начать заново» | Новый task/session, старые attempt IDs сохранены; не переоткрытие и не копия попыток | Не исполнено / NOT_RUN |
| TC-NEXT-08 | 04 | Source имеет REVISE/самостоятельное завершение/замечания; перейти и открыть старый | Оценка и решение ученика раздельны, замечания и evidence сохранены; skill не стал PASSED | Не исполнено / NOT_RUN |
| TC-NEXT-09 | 04 | Старый source имеет attempts после TTL медиа; открыть историю и попытаться upload | Техническая история доступна, недоступное медиа честно помечено; новый upload в terminal запрещён | Не исполнено / NOT_RUN |
| TC-NEXT-10 | 05 | Двойной POST с тем же UUID/payload; затем рестарт Java и replay | 201/200, одни и те же target IDs; долговечная идемпотентность | Не исполнено / NOT_RUN |
| TC-NEXT-11 | 05 | Потерять ответ после commit; Retry тем же key; F5 picker | Source/current показывают созданный successor, дубля нет; потеря HTTP не считается rollback | Не исполнено / NOT_RUN |
| TC-NEXT-12 | 05 | Два POST из вкладок с разными key/одинаковым или разным выбором; concurrent start/complete/upload | Один successor и одна active session; replay/409 по контракту, без deadlock и ошибочной перепривязки | Не исполнено / NOT_RUN |
| TC-NEXT-13 | 05 | Инъекция сбоя после source flush и до insert transition | Весь tx откатывается: прежний current/source восстановлен, нет orphan session/task/transition | Не исполнено / NOT_RUN |
| TC-NEXT-14 | 06 | История >40 занятий с одинаковыми assignedAt, листать cursor, менять limit/портить cursor | Полный tie-break по taskId, страницы без дублей/утечек; 400 для неверного cursor, нет N+1 | Не исполнено / NOT_RUN |
| TC-NEXT-15 | 06 | Открыть old `/lessons/:sessionId`, F5, вернуться к текущему | Старые материалы/результаты read-only; current/key не меняется; ссылка successor не создаёт новый | Не исполнено / NOT_RUN |
| TC-NEXT-16 | 07 | Source key остаётся после transition; F5 Home/diagnostics; logout/login без key | Resolver читает server current и восстанавливает новое занятие; ошибки чтения не приводят к Start | Не исполнено / NOT_RUN |
| TC-NEXT-17 | 07 | A→B→C по цепочке; replay запроса A→B | Replay возвращает исходные ID, но UI восстанавливает фактическое C, не делает B текущим | Не исполнено / NOT_RUN |
| TC-NEXT-18 | 08 | Подменить source/target learner/task, requestId повторить с иным payload, CSRF отсутствует | Чужие данные не выдаются; безопасный отказ/409 key reused; без CSRF writes запрещены | Не исполнено / NOT_RUN |
| TC-NEXT-19 | 08 | Устаревший expectedVersion, current exercise version изменилась, выбрать probe/unpublished/wrong instrument | 409 version conflict/change либо 404 недопустимого выбора; никакого молчаливого переключения версии | Не исполнено / NOT_RUN |
| TC-NEXT-20 | 08 | learning.enabled=false, 500/malformed DTO и timeout на state/POST/history | Нет ложного enabled/успеха; безопасный retry по правилам, существующие данные целы | Не исполнено / NOT_RUN |
| TC-NEXT-21 | 09 | Logout/account switch/unmount/Back при in-flight GET/POST, поздние success/error/finally, A→logout→A | Нет данных/индикаторов старого контекста; POST abort не отрицает commit; identity header не деградирует | Не исполнено / NOT_RUN |
| TC-NEXT-22 | 09 | RU/EN, 360px, 200% zoom, Tab/Enter/Escape, loading/errors | Нет горизонтальной потери действий, правильные focus/labels/live regions; single pending CTA | Не исполнено / NOT_RUN |
| TC-NEXT-23 | 09 | Preview/метроном/тюнер/запись, затем next; #211/#212/#163/#195/current learning regression | Audio и drafts не переносятся; авторизация, библиотеки, нотный viewer и восстановление работают | Не исполнено / NOT_RUN |
| TC-NEXT-24 | 10 | Flyway на копии существующей PostgreSQL; compatible release, forward-fix/rollback rehearsal | История не переписана, UNIQUE/index работают; старый enum не запускается поверх ARCHIVED; доказательства CI/процедуры сохранены | Не исполнено / NOT_RUN |

Не отмечать музыкальную проверку/PASSED по synthetic fixtures. Фикстуры проверяют программную логику. Перед передачей владельцу разработчик прикладывает PR и инструкции обновления ветки; приёмка включает реальный путь самостоятельно завершить → выбрать → открыть новое → вернуться в историю.
