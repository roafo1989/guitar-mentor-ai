# CATALOG-ROADMAP — анализ обновлений БТ и порядок поставки

**Текущая очередь:** [Вечерняя сверка 24.10.2026](board-audit-evening-2026-10-24.md).160/161 приняты; следующая162→163→164. До их завершения P0 не принят целиком. Следующие разделы сохраняют исходные контракты и историю подготовки; прежние NOT_RUN/не начато относятся к тому срезу.

Версия1.1 · 24.10.2026. База: БТ0.18 (прочитана предыдущая0.17, разделы12–13 и приоритет владельца), ТЗ0.26; main d9a7c4b805bf48b72adeefb83b818c81a41eaa13. Создан отдельный EPIC-004/#154: личная библиотека и дальнейшая подготовка материалов. **Новая разработка этой аналитикой не начинается.**

## Решение и готовность

Первой идёт US-011/#155. Все16 задач проработаны по новому поручению. **12 задач160–169/171/173 оценены (96ч в сумме) и выбраны в разработку**:160 свободна, остальные ожидают перечисленных predecessors. **170/172/174/175 — Анализ/Ожидание**, без фиктивных оценок до исследовательских результатов. [Подробная передача, состав оценок и gates](CATALOG-READINESS-2026-10-24.md). Это не96ч на весь эпик и не начало кодирования. Все пять стори входят в выбранный аналитический цикл; у эпика и155 Разработка/В очереди, у156–159 Разработка/Ожидание, финальной приёмки нет.

Старые84/119/120 относятся к проверенному каталогу упражнений.161 не заменяет их и не вставляет пользовательские файлы в exercises. #141 не блокирует библиотеку: общий Account/CurrentActor уже есть после38/42, Google достаточен. Несогласованные музыкальные идеи, source selection и существующие PR76–89 не принимаются. Дизайнерская ветка используется для общего стиля, отдельный редизайн не создаётся.

## Состав и зависимости

| Приоритет | Стори | Задачи | Готовность |
| --- | --- | --- | --- |
| P0 | [US-011/#155](https://github.com/roafo1989/guitar-mentor-ai/issues/155) — Сохранить готовые PDF и Guitar Pro в личной библиотеке | #160, #161, #162, #163, #164 | P0: оценено42ч, первая160; дальше зависимости |
| P1 | [US-012/#156](https://github.com/roafo1989/guitar-mentor-ai/issues/156) — Публиковать материалы и находить их в общем каталоге | #165, #166, #167, #168 | Full-контракт готов,36ч, ждётP0 |
| P2 | [US-013/#157](https://github.com/roafo1989/guitar-mentor-ai/issues/157) — Получать PDF и аудиопример из поддерживаемого Guitar Pro | #169, #170 | 169research6ч;170ждётGO |
| P3 | [US-014/#158](https://github.com/roafo1989/guitar-mentor-ai/issues/158) — Найти материал в интернете и сохранить подготовленный черновик | #171, #172 | 171research6ч;172ждётGO |
| P4 | [US-015/#159](https://github.com/roafo1989/guitar-mentor-ai/issues/159) — Готовить fingerstyle-аранжировку или транскрипцию из аудио | #173, #174, #175 | 173research6ч;174/175ждутGO |

Порядок P0: **160 →161 →162 →163 →164**; Vue может начать работу с frozen DTO после160, но завершает послеAPI. После приёмки P0 —156, затем157,158,159. Внутри159 сначала arrangement, затем transcription; режим по умолчанию не выбран. [Схема этапов](../diagrams/catalog-delivery-order.mmd).

## Полная трассировка согласованных критериев БТ

| Бизнес-критерии | Стори / ФТ | Доказательства будущей приёмки |
| --- | --- | --- |
| AC-021-01 | US011/FT01101…05, затемUS012/FT01201 | AC-LIB-01…08, TC-LIB-01…24; private draft и отсутствие утечек |
| AC-021-02 | US012/FT01201/02/04 | AC-COM-01/02/06; явная публикация, active-account scope |
| AC-022-01 | US011/FT01102; US012/FT01203 | TC-LIB-01/09/10; allowlist и точные исходные bytes |
| AC-022-02 | US012/FT01203/04 | AC-COM-04; PDF/photo preview и optional audio; P0 только download |
| AC-022-03 | US012/FT01203; US013/FT01302; US015 | AC-COM-04, AC-CONV-02, AC-AUD-05; original vs synthesized |
| AC-022-04 | US013/FT01301/02 | AC-CONV-01…05; format benchmark и эталон PDF |
| AC-022-05 | US012/FT01201…04 | AC-COM-02/03; audio optional, later revision и hasAudio |
| AC-023-01…03 | US012/FT01202/04 | AC-COM-03/04; type/tags/search composition, empty/reset |
| AC-024-01/02 | US014/FT01401/02 | AC-WEB-01/02; explicit external search, real provenance/ambiguity |
| AC-024-03…05 | US014/FT01402; US013 | AC-WEB-03…06; actual complete vs partial, private result |
| AC-025-01/02 | US015/FT01501…03 | AC-AUD-01/05; input availability и score/TAB |
| AC-025-03/04 | US015/FT01501…03 | AC-AUD-03…05; source/derived sound, uncertainty и fidelity |
| AC-025-05 | US015/FT01501…03 | AC-AUD-06/07; failure/cancel без фиктивного success |
| AC-025-06/07 | US015/FT01501…03 | AC-AUD-02…04; explicit mode, one-guitar arrangement, separate transcription |

## Артефакты

- [CATALOG-01: Full P0](../specifications/CATALOG-01-private-library.md): границы, security, REST, PostgreSQL, states, bounded IO, квоты, failure/recovery, UI, rollout/rollback.
- [OpenAPI3.0.3](../api/library-v1.openapi.yaml): формальная схема контрактов P0, не работающий endpoint.
- [CATALOG-02: следующие этапы](../specifications/CATALOG-02-evolution-roadmap.md): publication/versioning/filters, converter, web и audio с выходами исследований.
- [ADR-0004](../adr/0004-user-library-storage-and-publication.md): оригиналы отдельно от attempts/exercises; отсутствие48hTTL библиотеки.
- [Компоненты](../diagrams/catalog-library-components.mmd), [ER](../diagrams/catalog-library-data.mmd), [sequence](../diagrams/catalog-library-upload.mmd), [этапы](../diagrams/catalog-delivery-order.mmd).
- [TC-LIB-01…24](../testing/TC-CATALOG-01-private-library.md): картаAC/FT и будущие проверки. Всё NOT_RUN, самопроверка документов не независимоеreview.

## Допущения и границы

Технические defaults P0:20MiB aggregate/create, до5файлов,200MiB и100файлов/account; основаны на текущем20/32MiB transport и малом пилоте, не названы ответами владельца. Они конфигурируемы, без покупки ресурсов. Original GP хранится без парсинга/запуска; P0 не утверждает правильность музыки и не открывает inline-viewer. Только сформированный проектом PDF обязан эталону; чужой готовый оригинал не переписывается.

Исследовательские gates CATALOG-02 сохраняют конкретные открытые вопросы: moderation/tags, converter/formats, providers/cost/SSRF, качество arrangement/transcription, privacy/TTL input audio и будущая интеграция с учебными assignments. Они не перекладывают на пользователя выбор библиотек/моделей и не обещают обработку любой песни.

## Проверка артефактов и следующий шаг

Проверяются синтаксис YAML/ссылки/ref, уникальность US/FT/Issues, весь перечень критериев BT021…025, согласованность14действующихUS/54FT (старыйUS010/4FT отменены), native parent и реальные поля Project. Результат runtime/CI/музыкальных испытаний не создаётся подготовкой документов. Первый кандидат для реализации — **#160**,8ч; после него161–164. Нет команды merge/publish/deploy и нет дублирующих задач на готовые метаданные119/ресурсы120.

## Актуализация готовности по поручению владельца

[CATALOG-READINESS](CATALOG-READINESS-2026-10-24.md) заменяет первоначальный снимок статусов. P0дополнен replay/fullquota/delete-race; добавлены FullCATALOG-03,24TCcommunity и3исследовательскихконтракта CATALOG-04. Проработаны все16, у4реализаций остаются явно зарегистрированные gates. Статусы/часы синхронизируются с фактической доской. Код/CI/music NOT_RUN.
