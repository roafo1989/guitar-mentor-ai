# #204 — отчёт проверки общего Work

| Версия | Дата | Изменения |
| --- | --- | --- |
| 1.0 | 01.11.2026 | Реализация, ревью, PostgreSQL и защищённый TCP HTTP; матрица AC и инструкция приёмки. |

PR: [#265](https://github.com/roafo1989/guitar-mentor-ai/pull/265). База: main `a5a67092466bd32c8fdfd49764e435f20f951881`. Итоговые SHA и CI фиксируются в PR/Issue, чтобы отчёт не ссылался на собственный ещё не созданный коммит.

## Фактические проверки

- Изолированная PostgreSQL 16.15, UTF8/C.UTF-8, Java 21.0.13, Maven wrapper 3.9.16; только синтетические данные и временные storage roots.
- Полный backend `verify`: **426 tests, 0 failures, 0 errors, 1 skipped**. Пропущен существующий Docker-only `LibraryConversionProcessRunnerTest.launchesTheRealPinnedContainerThroughJavaProcessBuilderWhenCiEnablesIt` (#170, вне #204).
- После последнего дополнения статусов аудита и конкурентного HTTP delete/withdraw повторно выполнены `CommunityWorkPostgresTest` (3), `CommunityWorkMigrationPostgresTest` (1), `CommunityWorkLiveHttpPostgresTest` (1), `ApiRouteOwnershipInventoryTest` (1): **6 tests, 0 failures/errors/skips**, `verify`/package успешны.
- Чистая миграция и заполненная V34 → V35 проверены Flyway в отдельных уникальных схемах; validate успешен, старые private material и publication snapshot сохранены, `work_id` старых публикаций остаётся NULL; нет автоматического импорта Work.
- Реальный TCP HTTP на случайном порту, local login, независимые A/B/OWNER/guest cookie jars, настоящий CSRF. Не подменяется MockMvc или прямым вызовом контроллера.
- `git diff --check` — PASS. Существующие применённые V1–V34 и published exercise bytes не изменены. UI #168 не менялся.

Первый локальный широкий запуск выявил ограничения JVM self-attach в sandbox; после явного `-javaagent:mockito-core` регресс прошёл. Отдельная изначально SQL_ASCII тестовая БД заменена UTF8/C.UTF-8, как требует существующая Unicode login/search политика. Эти настройки тестового окружения не переносились в production-конфигурацию.

## Критерии и доказательства

| AC | Результат | Доказательство |
| --- | --- | --- |
| AC204-01 | PASS | A/B attach к VERIFIED Work, publish; reader получает две публикации; Work ID snapshot совпадает. PostgreSQL + TCP. |
| AC204-02 | PASS | Delete A оставляет Work, B publication и доступные B bytes; нет cascade. TCP и PostgreSQL. |
| AC204-03 | PASS | B не читает candidate A, не меняет link материала A; guest 401, learner verify 403, signed-in POST без CSRF 403. |
| AC204-04 | PASS | Одинаковые title/artist создают разные CANDIDATE IDs; attach candidate 409; verify только OWNER с If-Match. |
| AC204-05 | PASS | Detach/split не меняют publication.work_id; split требует сохранённый actor-bound dry-run; replay плана возвращает тот же Work; материал получает новую revision. Автоматического destructive merge нет. |
| AC204-06 | PASS для исполненных гонок | Параллельные одинаковые attach из двух сессий A дают одинаковый ответ/revision; stale detach 412; параллельные delete/withdraw завершаются 202/200, B остаётся доступен. Остальные сочетания защищены единым material → Work lock order и constraints; исчерпывающий перебор всех межпоточных чередований не заявляется. |
| AC204-07 | PASS | Empty Work и HIDDEN publication не видны; missing bytes скрывают Work; Work остаётся в БД. Withdraw/delete не меняют чужие материалы. |
| AC204-08 | PASS | Flyway clean + populated V34 upgrade + validate в `CommunityWorkMigrationPostgresTest`. |
| AC204-09 | PASS | PostgreSQL-backed прямые интеграционные тесты плюс отдельный настоящий TCP HTTP/local-auth/CSRF сценарий. |
| AC204-10 | PASS | Существующие Publication/Library API сохранены, nullable workId добавлен аддитивно; полный регресс и Vue CI. Новые frontend controls не входят в backend-задачу. |

## Ревью и исправления

Проведено последовательное ревью авторизации, SQL, FK, транзакций, DTO и тестовых фикстур; отдельный независимый агент-ревьюер не запускался.

1. Вместо account row lock в idempotency применён actor/key advisory lock: публикация/удаление держат material и используют account FK, инверсия давала риск deadlock.
2. Split без фактически сохранённого dry-run запрещён; проверяются actor, fingerprint, source/material revisions и 15-минутный срок. При replay нет второго Work.
3. Private/DRAFT material IDs/metadata не выдаются через administrative split; допустимы только явные текущие links с действующей публикацией исходного Work.
4. Search сканирует Work IDs keyset-порциями по 100, память ограничена; не возвращает counts или private links.
5. Новые API явно внесены в security route ownership inventory; creator/OWNER и admitted policies проверены.
6. HTTP fixture освобождает singleton OWNER и снимает оставшиеся синтетические публикации в finally, чтобы не влиять на последующие тесты.
7. События переходов состояния сохраняют previous_status/new_status; link/delete audit выполняется в транзакции с мутацией.

## Ручная приёмка и ограничения

Исполнимые команды и полный список endpoint: [issue-204-shared-work.md](../development/issue-204-shared-work.md). Минимальный результат для владельца: два автора → один VERIFIED Work → две публикации → delete/withdraw первого → второй доступен. Автотест выполняет этот сценарий самостоятельно, ручное воспроизведение пользователем не требуется для технического PASS.

Финальная продуктовая приёмка остаётся за владельцем. PR не объединять и #204 не закрывать до неё. Production/PILOT, реальные данные, V27 private corpus, #170/#227 и frontend #168 этой работой не изменяются. Доступный GitHub-коннектор не умеет записывать поля Project v2: целевое состояние после всех CI — **Тестирование / Ожидание приёмки**; текст Issue и поля доски необходимо различать.
