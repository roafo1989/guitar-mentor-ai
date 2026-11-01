# #204 — общий Work и независимые авторские материалы

| Версия | Дата | Изменения |
| --- | --- | --- |
| 1.0 | 01.11.2026 | Схема, API, ручная проверка и ограничения shared identity. |

Исходная проблема: опубликованные аранжировки разных пользователей не имели общей идентичности произведения. V35 добавляет `community_works`, owner-checked association, операции повторных запросов, аудит и сохранённые split plans. Приватный импортированный индекс V27 не меняется.

## Включение и запуск

Нужны Java 21, PostgreSQL с UTF8, Maven wrapper репозитория. Работать на отдельной тестовой БД; реальные пользовательские данные для проверки не нужны. Использовать ветку PR #204, а не текущий main до приёмки.

```powershell
cd backend
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://127.0.0.1:5432/guitar_mentor_qa'
$env:SPRING_DATASOURCE_USERNAME = 'guitar_mentor_qa'
$env:SPRING_DATASOURCE_PASSWORD = '<пароль тестовой БД>'
$env:GUITAR_MENTOR_TEST_POSTGRES = 'true'
$env:GUITAR_MENTOR_SECURITY_ENABLED = 'false' # только автоматические synthetic loopback tests
.\mvnw.cmd '-Dtest=CommunityWorkPostgresTest,CommunityWorkMigrationPostgresTest,CommunityWorkLiveHttpPostgresTest' test
```

Live HTTP тест включает security обратно собственными properties, использует настоящий local login, CSRF и независимые cookie jars. Миграционные проверки создают/удаляют только уникальные схемы `qa204_*` в указанной тестовой БД. Для полного регресса: `./mvnw verify` с теми же переменными. Автотесты создают синтетические аккаунты; реальный SMTP/Google вход не требуются.

Для ручного приложения вернуть `GUITAR_MENTOR_SECURITY_ENABLED=true`, включить `GUITAR_MENTOR_LIBRARY_ENABLED=true`, задать storage path и стабильный cursor secret ≥32 байта по существующему library setup. Существующие правила HTTPS/session/CSRF сохраняются.

## Последовательность API

Все пути начинаются с `/api/v1`. Actor берётся из сессии. Мутации требуют обычный CSRF token. `If-Match` — quoted numeric ETag, `Idempotency-Key` — UUID.

| Операция | Endpoint | Payload / условия |
| --- | --- | --- |
| Кандидат | POST `/community/work-candidates` | `{title,artist}`, Idempotency-Key |
| Приватный просмотр review | GET `/community/work-candidates/{id}` | Только creator/OWNER |
| Подтверждение | POST `/admin/community/works/{id}/verify` | OWNER, If-Match, `{reason}` с основанием/ссылкой |
| Правка общих метаданных | PATCH `/admin/community/works/{id}` | OWNER, If-Match, `{title,artist,reason}` |
| Снятие из выбора | POST `/admin/community/works/{id}/retire` | OWNER, If-Match, `{reason}` |
| Текущая связь собственного материала | GET `/library/materials/{id}/work-link` | `{materialId,workId,revision}` и ETag материала |
| Привязка | PUT `/library/materials/{id}/work-link` | `{workId}`, If-Match материала, Idempotency-Key |
| Отвязка | DELETE `/library/materials/{id}/work-link` | If-Match материала, Idempotency-Key |
| Общий поиск | GET `/community/works?q=&limit=&cursor=` | VERIFIED + разрешённые PUBLISHED snapshots, limit 1–50 |
| Карточка | GET `/community/works/{id}` | Только доступные публикации, без owner/private IDs |
| План split | POST `/admin/community/works/{id}/split/dry-run` | OWNER, If-Match Work, `{title,artist,reason,materials:[{materialId,revision}]}` |
| Применение split | POST `/admin/community/works/{id}/split` | Тот же payload/If-Match, `Split-Plan: <planId>` |

PUT/DELETE увеличивают revision материала только при изменении связи. Ответ и GET work-link возвращают новый ETag. Старый If-Match после изменения получает 412. Повтор ключа с тем же запросом возвращает исходную revision; этот ETag может быть уже историческим — перед следующей командой перечитать материал. Другой fingerprint того же ключа получает 409. Обязательные предусловия без заголовка — 428.

Публикация через существующий endpoint получает nullable `workId` snapshot. Привязать уже опубликованный материал недостаточно для смены общего Work: автор явно withdraw → publish с актуальной revision. Не переписываются старые snapshots, assets, rights или visibility.

## Correction и split

Обычное исправление выполняет автор: detach, attach нового VERIFIED Work, затем при необходимости withdraw/publish. OWNER может split только явно выбранные материалы с действующей публикацией исходного Work; приватные материалы не доступны администратору. При незнакомом или неподходящем ID — одинаковый 404 без подробностей.

Dry-run сохраняет fingerprint плана, actor, source revision и срок 15 минут. Apply повторно проверяет все material revisions и состояние в транзакции. При ошибке ничего не переносится. Старый Work и старые publication.work_id сохраняются. Повтор того же плана возвращает созданный Work, а не создаёт второй.

Автоматического destructive merge нет. Для ручного разрешения дублей OWNER сначала сопоставляет только доступные опубликованные карточки и фиксирует причину в review. Авторы исправляют свои links самостоятельно. Не удалять старые IDs, не переписывать snapshots и не выполнять SQL cascade/массовую перезапись. При необходимости redirect aliases или миграции исторических связей требуется отдельная задача и план; эти операции не входят в #204.

## Ревью и эксплуатация

Устранены при ревью: инверсия account FK/material lock в idempotency (заменено actor/key advisory lock), возможность split без сохранённого dry-run (план обязателен), неограниченная загрузка поисковых кандидатов в память (keyset batches по 100). Единый lock order: отсортированные material rows → Work. OWNER-only операции identity не захватывают material после Work.

Снятие публикации, HIDE и удаление материала используют прежние политики. Delete удаляет только собственную association с аудитом в одной транзакции; Work, чужие ссылки, snapshots и файлы не каскадируются. Пустые Work сохраняются, но не выдаются. Отсутствующие/не READY assets не превращаются в доступную карточку Work.

Откат — отключить library/new endpoints и вернуть код; V35 не удалять и применённые миграции не редактировать. Данные links/events/snapshots сохранять. Production/PILOT rollout, frontend attach controls, AI/OCR и V27 auto-publication не входят в эту поставку.
