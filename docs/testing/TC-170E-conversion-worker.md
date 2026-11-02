# 170-E / #266 — матрица проверки

База: `48d03ff9`; feature flag остаётся OFF. Дата сверки: 02.11.2026. Приёмка и rollout отдельно. PASS относится только к реально выполненной проверке; готовый тест не считается пройденным.

| ID | Критерий / шаги | Ожидаемый результат | Фактический статус |
| --- | --- | --- | --- |
| E01 | 77 synthetic GP3/4/5 cases, `check-corpus.py --local` | 12 valid renders, canonical 4/20/128 events; точные отказы/track selection | PASS локальный Node со штатным binary stdout protocol; Docker прогон заблокирован до разрешения push |
| E02 | Java ProcessBuilder fault suite | cancel/watchdog/missing executable/image, invalid/partial/oversized stream, no leaked workspace | PASS, 9 tests, Java 21 |
| E03 | Real cgroup/container probes | 512 MiB/CPU1/PID64/UID10001/no network/readonly; disk-full/OOM/timeout | NOT_RUN: CI не запущен, push заблокирован |
| E04 | Upload → queue → selection → real Docker → private pair → original download | GP3/4/5 SUCCEEDED; оба asset, lineage, quota, owner isolation; cleanup | NOT_RUN: CI не запущен, push заблокирован |
| E05 | Durable retry/cancel/duplicate delivery, 1/account и 2 global, expiry/reclaim | Без дублей и частичных assets, slot freed; discovery не съедает retry | NOT_RUN до PostgreSQL CI |
| E06 | Released reservation reuse | Повторное сохранение проходит после RELEASED, charged один раз | NOT_RUN до PostgreSQL CI |
| E07 | Full Java/Vue CI | Regression tests + сборки PASS на финальном SHA | CI NOT_RUN; локально 233 Java PASS (186 skipped), Vue 247/247 + build PASS |
| E08 | Windows Docker Desktop + Java, путь с пробелами, cancellation/restart | Полный runbook PASS, recorded versions/SHA | NOT_RUN: Windows host недоступен |
| E09 | Живой browser → действующий backend → Docker | Терминальный UI, выбор/повтор/отмена и оба приватных файла | NOT_RUN: mounted frontend и backend E2E являются отдельными слоями |
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

## Блокер публикации и следующий шаг

**Разработка / Ожидание.** Автоматическая approval-проверка дважды отклонила `git push`: сначала потребовала проверить destination, затем после подтверждения owner/admin/public repository указала отсутствие явного разрешения пользователя на публичную публикацию кода. Адрес подтверждён как `https://github.com/roafo1989/guitar-mentor-ai.git`, аккаунт владельца — `roafo1989`. Обход запрета не выполнялся. PR и новые GitHub CI runs не созданы; не считать их успешными или существующими.

Для продолжения требуется явное разрешение отправить `feature/170e-orchestration` в этот публичный репозиторий и открыть PR. Затем выполнить подготовленный Linux Docker/PostgreSQL CI, исправить выявленное и обновить матрицу по итоговому SHA. Windows Docker Desktop, связанный live-browser сценарий и human visual/listening остаются отдельными непроверенными gates. Слияние, production/PILOT и включение feature flag не выполнялись. Поля Project v2 не изменены: доступный connector не предоставляет соответствующие mutations.
