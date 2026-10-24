# CATALOG-04 — исполнимые исследования и условия старта pipeline

| Версия | Дата (Europe/Moscow) | Изменение |
| --- | --- | --- |
| 1.0 | 24.10.2026 | Full-постановки #169/#171/#173 и точные блокировки #170/#172/#174/#175. |

База БТ0.18/BT-022/024/025, ТЗ0.26, main d9a7c4b805bf48b72adeefb83b818c81a41eaa13, [CATALOG-02](CATALOG-02-evolution-roadmap.md). Владелец поручил подготовить все задачи до разработки. Исследование — отдельный проверяемый результат, не доказательство готовности музыкального продукта. Ни один benchmark этого документа не исполнен, все фактические результаты NOT_RUN. Отчёт автора не независимое ревью.

## 1. Общий исполнимый контракт исследований

Каждое исследование ограничено **6 рабочими часами**: инвентаризация/источники1ч, постановка fixtures и метрик1ч, эксперимент/контракт2ч, анализ и воспроизводимый отчёт1ч, декомпозиция/gate1ч. Это оценка timebox, не обещание успешного GO. Нельзя без конца продлевать исследование ради удачного результата. При нехватке evidence результат INCONCLUSIVE с точным недостающим экспериментом, а не GO. NO-GO также допустимый честный результат исследования, но не реализация исходного пользовательского требования.

Вход: версия БТ/ТЗ, зафиксированный mainSHA и фактический API предшественников. Выход в Git: `docs/research/CATALOG-<issue>-report.md`, manifest fixtures с происхождением/разрешением/hash (без приватных originals), version/command/environment, полные результаты и ошибки, ресурсные измерения, риски, GO/NO-GO/INCONCLUSIVE по конкретному scope и следующие принимаемые инкременты. Каждая feature/format строка получает PASS/FAIL/UNSUPPORTED/NOT_RUN; нельзя заменить таблицу общим «поддерживает GP» или заявлением поставщика.

На реальном prototype — isolated workspace, synthetic/openly permitted fixtures, no secrets/платных аккаунтов/private uploads; установка только из официального registry/upstream с фиксацией версий/лицензий, без deployment и изменения пользовательской БД. Java-интеграция обязательна для app layer; внешний worker допускается только как исследованный вариант, не неявный возврат всего backend на Python. Не отправлять данные провайдеру без отдельного разрешённого режима. $0 budget по умолчанию, отсутствие ключа/разрешения честно ограничивает результаты. Нельзя обойти запрет расходов free-trial подпиской.

Готовность исследования к собственному review: все входные вопросы разобраны, команды/fixtures воспроизводимы, timebox соблюдён, результаты не преувеличены, ограничения и возвращаемый пользователю отказ понятны, schema/API/decomposition proposals выписаны. Отсутствие результата не перекладывается на владельца просьбой самому выбрать библиотеку. После исполнения исследования отдельно обновляются blocked implementation tasks; сам факт закрытия research не снимает gate.

## 2. #169 / FT-013-01 — Guitar Pro → score/PDF/audio

**Результат:** доказать или ограничить поддерживаемые версии/эффекты и выбрать воспроизводимый законно используемый renderer/importer для Java приложения. Источники [Guitar Pro](https://www.guitar-pro.com/c/14-guitar-pro-features) и [TuxGuitar](https://www.tuxguitar.app/files/devel/desktop/help/file_formats.html) — только списки кандидатов; не evidence нашего pipeline.

В corpus минимум один созданный исследователем или законно распространяемый валидный sample каждой реально заявляемой версии .gtp/.gp3/.gp4/.gp5/.gpx/.gp; если нет законного sample — NOT_RUN и эту версию не заявлять. Дополнительные cases: corrupt/empty/truncated, bogus extension/zip bomb, multi-track с явным выбором, 3/4/3/8/6/8, rests/repeats/polyphony, standard/dropD/openG/capo, tempo changes и unsupported effects. Один fixture может покрыть несколько случаев; manifest явно связывает checks.

Проверки: извлечённые events↔исходный known score; количество/позиции долей/повторов, струна/лад/высота, гитарная нотная октава; PDF по MUS-NOTATION-01, 4 полных такта/system, staff над TAB; аудио из той же event revision с верным темпом/повторами. Автоматические event checks дополнить визуальным/слуховым разбором, отметив кто проверил и что не доказано. Soundfont/fonts/export libraries licenses и distribution/version pin обязательны.

Кандидаты сравнить по documented headless Java integration, OS portability, faithful events, layout control, music/audio export, sandbox dependency footprint. Нельзя молча выбрать TuxGuitar, если он не даёт обязательный layout. Допустим раздельный importer + deterministic renderer; дополнительные components/licensing/оценка отражаются. Commercial Guitar Pro GUI/server license не предполагается купленным.

Базовые ограничения для измерения: вход≤20MiB (как P0), одингитарный выбранный track; isolated process no-network,1job/account/global2,512MiBRAM/1CPU/60s wall/100MiB scratch. Это экспериментальные caps; если кандидат не укладывается, записать измерение и предложение с обоснованием, не увеличить молча. Долгое произведение/нестандартная возможность — honest unsupported/limit, не повреждённый READY.

**Gate169→170:** pinned toolchain и legal distribution evidence; матрица реальной поддержки; reproducible events/PDF/audio; измеренные caps; контракт multi-track/ошибок; конкретные API/schema/states и разбиение Java queue/worker/UI на принимаемые инкременты с оценкой. GO только для доказанного subset. При NO-GO сохраняется P0 original download, конвертационная capability false. Ни принятый #104, ни хранение GP в #161 не заменяют эти доказательства.

## 3. #171 / FT-014-01 — внешний поиск и законный импорт

**Результат:** provider/source capability matrix + точный контракт поиска/скачивания, допускающий самостоятельную Java реализацию #172. #171 не является внедрением LLM и не обязан отправлять запросы платному provider.

Проверить минимум два официально документированных кандидата search/source adapters. Для каждого: endpoint/auth/terms/date, title/author query fields, pagination/dedup, permitted personal retrieval vs redistribution, direct bytes/reference-only, attribution/provenance, rate/quota/cost, outage behavior. Нет доступного законного API — кандидат UNSUPPORTED/reference-only, не скрейпить login/paywall. При отсутствии live access записать контракт как DOCUMENTED_NOT_EXECUTED, без GO для неиспытанного скачивания.

Corpus минимум12 synthetic/правомерных cases: точное произведение/автор; одноимённые разные произведения; разные аранжировки; duplicate URLs; PDF only; GP; link-only page; нет результата;403/login;429/timeout; redirect/private destination; page/prompt injection. Минимум две проверки ambiguity; URL и библиография реальные, не сгенерированные. Пользователь выбирает только существенную неоднозначность, не обязан вручную обрабатывать каждый нормальный результат.

Контракт будущего pipeline: explicit internal-empty→external search; query только title/author, actor приватные данные не уходят; source work/arrangement stable ID; candidates и CHOICE_REQUIRED; доступные original bytes→private library, поддерживаемый GP→conversion; состояния COMPLETE/PARTIAL/LINK_ONLY/FAILED с реальными asset IDs и provenance. COMPLETE требует фактических PDF и audio одной редакции. Поиск PDF не включает неоговорённую транскрипцию аудио.

Fetcher design: HTTPS per-provider allowlist, connect-time destination/A+AAAA checks, reject private/linklocal/metadata/loopback, повторная проверка каждогоredirect, no credentials/cookies, bounded redirects/bytes/time, egress изоляция от appDB/FS, запрет shell и выполнения page instructions. Зафиксировать adversarial test vectors и expected terminal code. Mock test не называется live-provider PASS.

Budget default для исследования **$0**, для будущего production adapter disabled и дневной spend cap0 до отдельной настройки. В отчёте выбрать конфигурируемые limits requests/job/account/day +concurrency/timeout/redirect/bytes и измеренную cost formula выбранного provider; нет скрытой покупки. Kill switch/cancel/bounded retry и логи без q/private input обязательны.

**Gate171→172:** реально доступный допустимый adapter (или явно ограниченная reference-only поставка как новое согласованное изменение, не подмена #172), provenance/rights matrix, tested SSRF/cost/cancel contracts, формат запросов/ответов/errors, схема persisted search/import jobs, измеренные quotas/cost и декомпозиция Java/Vue/adapter. Для GP-dependent ветки дополнительно GO169 и готовая170. No provider access/license evidence — gate остаётся закрытым, не назначать выдуманные часы реализации.

## 4. #173 / FT-015-01 — fingerstyle arrangement и original-part transcription

**Результат:** отдельные feasibility/rubrics для ARRANGEMENT и TRANSCRIPTION; порядок разработки сначала сольная аранжировка, потом транскрипция. Целевой продукт не «распознать любую песню». Режим выбирается явно, default не вводится.

Corpus minimum6 разрешённых sourceaudio+knownscore clips15–60s: mono guitar, polyphonic guitar, guitar с фоном/другими инструментами, melody+bass, необычный метр/темп, noisy/ambiguous. Это граница исследовательского набора, не продуктовый лимит длительности. Авторский fixture может быть программно синтезирован; отчёт различает real recording и synth. Ни полностью synthetic corpus, ни сравнение с собственной generated reference не доказывают качество real music. Недоступный законный реальный sample → отдельный NOT_RUN блокер generalization. Права на audio/arrangement/engraving различаются; оригиналы пользователя не используются автоматически.

Сравнить минимум2 технических маршрута: выделение/распознавание/структурирование/фреты/engraving для transcription; извлечение melody/harmony +playable melody/bass/accompaniment для arrangement. Один инструмент/модель может обслуживать оба маршрута только при разных оценках. Для каждого — primary source/version/licenses, Java integration, local/SaaS privacy, hardware/runtime/dollar estimates, tuning/capo и ограничения. Не обещать, что general LLM извлекает MIDI fidelity из произвольного audio без evidence.

Рубрика до эксперимента: transcription note/onset/offset/rhythm/voice errors по knownscore; arrangement recognisable melody/harmony, pulse/voices, bass coverage, solo-guitar playability, texture/phrasing и uncertainty. Оценка музкачества человеком фиксируется отдельно от automated metrics. Пороги выбираются из baseline и ошибки, не произвольный accuracy%. Нужен точный список поддерживаемых входов, не только aggregate average. Аппликатура output — вычисленная играбельная, не восстановленная авторская без доказательства.

Для первого предлагаемого ограниченного инкремента arrangement: одна гитара, standard EADGBE, без кастомного упрощения под learner level/редактора/перкуссии. Это scope proposal исследования; если baseline этого не даёт, не объявлять задачу174готовой. Решение о расширении строя/уровне вынести с обоснованием до реализации. Пользователь ранее просил строи тюнера; это не доказывает готовый audiopipeline для всехстроев.

В отчёте определить storage/TTL incoming source vs persistent output, безопасные file/link formats и caps, purpose/consent передачи провайдеру, retention/delete/cancel, cost/rate/concurrency, model/tool/version и source provenance, uncertainty segments и frontend error language. По умолчанию нет передачи провайдерам, расходов и внешнего download универсальных URLs. Ссылки обрабатываются только через исследованный allowlisted adapter171, без обхода доступа. Нет никакой попытки удалять saved originals по attempt48hTTL.

**Gate173→174:** воспроизводимый ограниченный ARRANGEMENT baseline с музыкальной оценкой, выбранный reproducible engine и разрешённое использование, measured resource/cost/privacy/retention contracts, PDF/TAB+synth одной revision, ясный failure/uncertainty и декомпозиция/оценка JavaAPI/jobs/worker/Vue. Для первого174 не требуется готовый transcription. **Gate173/174→175:** отдельные real fidelity evidence для TRANSCRIPTION, выбор слышимой партии/неоднозначность/overlap, сохранение distinct mode/history, готовый shared pipeline174 и точные контракты/остаток. Провал174 не доказывает невозможность175, но требует пересмотра принятого порядка, а не скрытой подмены.

## 5. Реестр блокировок на 24.10.2026

| Задача | Что препятствует честному старту реализации | Кто снимает и какое доказательство |
| --- | --- | --- |
| #170 | Нет результатов169, выбранного verified toolchain и измеренных format/layout/caps; pipeline ещё многосоставной | Исполнитель169 + СА: gate169, точные контракты, декомпозиция/часы; затем Review готовности170 |
| #172 | Нет171/provider/terms/budget/SSRF evidence; conversion ветка зависит170 | Исполнитель171 + СА, по GP исполнитель170; gate171 и реализованные predecessors |
| #174 | Нет173/arrangement quality/resources/privacy evidence; output contract зависит169/170 | Исполнитель173 + СА; gate173 и готовый renderer/queue, затем оценка частями |
| #175 | Нет отдельного transcription benchmark, shared pipeline174, selection/quality contract | Исполнитель173/174 + СА; отдельный gate transcription, оценка оставшегося объёма |

Состояние этих четырёх задач — **Анализ / Ожидание**, оценки реализации отсутствуют намеренно. Это внешние исследовательские зависимости с ответственными и условиями; не вопрос, который владелец обязан решать вручную сейчас. Остальные12задач имеют ограниченный оцениваемый результат. Перевести исследовательский отчёт в «готово» без его исполнения или назначить этим4 задачам точные часы означало бы скрыть неизвестный объём. Поручение на подготовку не является результатами экспериментов.
