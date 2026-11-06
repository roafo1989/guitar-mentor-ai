# 170-E / #266 — матрица проверки

База: `48d03ff9`; feature flag остаётся OFF. Дата сверки: 06.11.2026. Приёмка и rollout отдельно. PASS относится только к реально выполненной проверке; готовый тест не считается пройденным.

| ID | Критерий / шаги | Ожидаемый результат | Фактический статус |
| --- | --- | --- | --- |
| E01 | 77 synthetic GP3/4/5 cases, `check-corpus.py --local` | 12 valid renders, canonical 4/20/128 events; точные отказы/track selection | PASS: Node и реальные Docker containers, 77/77; CI на `e1231233` |
| E02 | Java ProcessBuilder fault suite | cancel/watchdog/missing executable/image, invalid/partial/oversized stream, no leaked workspace | PASS, 9 tests, Java 21 |
| E03 | Real cgroup/container probes | 512 MiB/CPU1/PID64/UID10001/no network/readonly; disk-full/OOM/timeout | PASS: Linux Docker/PostgreSQL CI на `e1231233` |
| E04 | Upload → queue → selection → real Docker → private pair → original download | GP3/4/5 SUCCEEDED; оба asset, lineage, quota, owner isolation; cleanup | PASS: Linux Docker/PostgreSQL CI на `e1231233` |
| E05 | Durable retry/cancel/duplicate delivery, 1/account и 2 global, expiry/reclaim | Без дублей и частичных assets, slot freed; discovery не съедает retry | PASS: PostgreSQL CI на `e1231233` |
| E06 | Released reservation reuse | Повторное сохранение проходит после RELEASED, charged один раз | PASS: PostgreSQL CI на `e1231233` |
| E07 | Full Java/Vue CI | Regression tests + сборки PASS на финальном SHA | PASS на `e1231233`: Java 432 cases (427 PASS, 5 skipped), Vue CI PASS. Финальный SHA — в [PR #267](https://github.com/roafo1989/guitar-mentor-ai/pull/267/checks) |
| E08 | Windows Docker Desktop + Java, путь с пробелами, cancellation/restart | Полный runbook PASS, recorded versions/SHA | NOT_RUN: Windows host недоступен |
| E09 | Живой browser → действующий backend → Docker | Терминальный UI, выбор/повтор/отмена и оба приватных файла | На `a2068bec` браузерный сценарий завершился, но проверка БД в тесте упала. Итог E09 — в [PR #267](https://github.com/roafo1989/guitar-mentor-ai/pull/267/checks) |
| E10 | Human PDF review + listening | MUSIC-01 layout/октава/чистота аудио | NOT_RUN: требуется фактическая пользовательская проверка |

Автотесты используются только с synthetic files и отдельной БД. Производственные данные/флаги не затрагивались. Отдельная автоматическая визуальная проверка PDF не заменяет человеческую приёмку.

## Найденные и исправленные дефекты

- Отсутствовал queue consumer; worker связывает lease, cancel, selection и atomic persistence.
- `RELEASED` резерв нельзя было снова зарезервировать: UPDATE разрешал только `NONE`.
- Discovery дорожек расходовал одну из двух попыток, лишая multi-track job штатного retry.
- Output bind mount не ограничивал scratch: outputs и tmp теперь внутри одного capped tmpfs; host читает ограниченные блоки через stdout.
- Setup/copy ошибки и cidfile race могли оставить workspace/container; теперь cleanup в finally и container name известен до запуска.
- При аварии Java не было независимого deadline процесса: добавлен контейнерный watchdog.
- Размеры outputs у runner и persistence расходились (80 vs 20 MiB): единый предел 20 MiB на файл.
- В результате не сверялся выбранный index и размер файлов: проверяются index, hash/revision и фактические размеры.
- Пунктир/триоли принимались вне subset; исправлен отказ. Alternate tuning корректно диагностируется после обнаружения гитарной дорожки.
- PDF уменьшал очередную систему под остаток страницы; теперь сначала рассчитывается стабильный масштаб, затем перенос.

## Ограничения интерпретации

GP3 не умеет сохранять palm mute: корпус не выдаёт отсутствие этого эффекта после записи за проверку его отказа. GP5 second voice валиден для PyGuitarPro, но pinned alphaTab отказывает ещё при import; ожидается `INVALID_OR_UNSUPPORTED_FILE`. Полифония остаётся вне scope. Canonical event parity проверяет pitch/string/fret/duration; human listening остаётся отдельным gate.

Артефакты CI: `conversion-170e-evidence` (reports, PDF/WAV, surefire). Windows шаги: [runbook](../operations/conversion-worker-windows.md).


## Локальный результат — 02.11.2026

- Ветка `feature/170e-orchestration`, реализация в коммите `9bd13aa6`; последующие локальные изменения дополняют corpus, PostgreSQL fault cases и отчёт.
- `check-corpus.py --local`: **77/77 PASS** через production binary stdout protocol; header/length/parity проверены. Синтетические 1, 5 и 32 такта для GP3/4/5.
- `LibraryConversionProcessRunnerTest`: **9/9 PASS**, включая timeout/cancel, отсутствующий executable/image, malformed/oversized/partial output, selected-track mismatch и cleanup/symlinks.
- Доступная локальная Java-регрессия: **419 cases, 233 PASS, 186 skipped, 0 failures, 0 errors**. Две DB-зависимые Spring context suites (`ApiRouteOwnershipInventoryTest`, `SecurityHttpIntegrationTest`) исключены из локального прогона; остальные PostgreSQL suites пропущены штатными guards. Mockito запущен с явным javaagent из-за отсутствия self-attach в среде. Первый полный локальный запуск без этих настроек завершился инфраструктурными ошибками; он не считается успешным full CI.
- Frontend: **247/247 PASS**, `vue-tsc` + Vite build PASS. Известное предупреждение о размере bundle не блокирует сборку.
- PDF просмотрен ассистентом после Poppler rendering: 5 тактов — 1 страница, 32 такта — 2 страницы A4; четыре такта в полной строке, читаемые ноты/TAB, без обрезаний и сжатия последней системы под остаток страницы. Это не human acceptance.
- WAV технически проверен: stereo PCM, 44 100 Hz, 12.501 s для 20 четвертей при 96 BPM, пик 6808/32768, clipped samples 0. Human listening **NOT_RUN**.
- YAML parse, Node syntax, Python compilation, `git diff --check` — PASS.

## Опубликованный результат и следующий шаг

Пользователь разрешил публикацию; открыт [PR #267](https://github.com/roafo1989/guitar-mentor-ai/pull/267), ветка `feature/170e-orchestration`. На `e12312339b81d29ce299766f7756139b9bcafc11` прошли все четыре workflow:

- [Conversion worker 170-E](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/41490629433): 77/77 container corpus, isolation probes, 25/25 Java conversion tests; [PDF/WAV и отчёты](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/41490629433/artifacts/13164626065).
- [Java backend](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/41490629311): 432 cases, 427 PASS, 5 skipped, 0 failures/errors; package и запуск приложения PASS.
- [Vue frontend](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/41490629402): PASS.
- [CATALOG-169](https://github.com/roafo1989/guitar-mentor-ai/actions/runs/41490629310): PASS.

Добавлен `LibraryConversionBrowserPostgresTest` / `frontend/tests/conversion-live-browser.mjs`: настоящая форма входа, upload GP3/4/5, выбор дорожки, восстановление после reload, отмена, повтор после отсутствующего тестового image, скачивание оригинала/PDF/WAV, независимые sessions владельца/другого account/гостя, квота и cleanup. API и worker не подменяются. На `a2068bec` браузерный сценарий завершился. Проверка отсутствия публикаций обращалась к несуществующему полю `library_materials.visibility`, исправлена на запрос к `library_publications`. Итоговые результаты зафиксированы в [PR #267](https://github.com/roafo1989/guitar-mentor-ai/pull/267). Для failure/retry используется отдельный UUID Docker tag, основной `:170e` не удаляется.

Windows Docker Desktop и human visual/listening остаются NOT_RUN. Feature flag OFF, rollout отдельно. Этап и состояние задачи ведутся в GitHub Project.
