# #235 — начальный профиль нового ученика

| Версия | Дата | Изменения |
| --- | --- | --- |
| 1.0 | 27.10.2026 | Исправление lifecycle анкеты и запуска диагностики; проверки и приёмка |

## Причина и контракт

Исходная версия: `main 0914a79b8fae2441cf0a735e158818a5a99bea18`.
Регистрация Google (`InvitationService.admitPublicGoogle` / `activate`) и local
(`LocalCredentialService.verify`) создаёт ученика без инструментов. До исправления
только PUT preferences создавал начальный профиль. GET background и POST
diagnostic-sessions требовали существующий инструмент и возвращали 404. Поэтому
новый ученик мог попасть в блокировку, перейдя к анкете или диагностике до сохранения
предпочтений. Это воспроизводимая причина в исходном коде; соответствие конкретного
ответа PILOT этой причине без тела ответа и версии сервера не утверждается.

После исправления:

- GET background для существующего ученика без инструментов и `ACOUSTIC_GUITAR`
  возвращает 204, ничего не создаёт. Существующий профиль без анкеты также даёт 204.
- Первый явный PUT background создаёт `ACOUSTIC_GUITAR / FINGERSTYLE` и анкету
  в одной транзакции. POST diagnostic-sessions также поддерживает начальный профиль.
- Все три пути (анкета, предпочтения, диагностика) блокируют одну строку learner.
  Ограничение UNIQUE (learner_id, instrument_code) остаётся защитой БД.
- Если есть другой профиль, или код неизвестен, инициализация не выполняется.
  Missing learner, чужой learner, ошибки чтения и 401/403/404 не становятся пустой анкетой.
- Ни миграции, ни массового исправления production-данных нет. Существующие записи,
  предпочтения и история навыков при чтении не изменяются.
- `GET .../diagnostic-sessions/current?instrumentCode=ACOUSTIC_GUITAR` читает
  текущую сессию, `POST .../diagnostic-sessions` начинает её. GET списка по корневому
  пути не входит в API; это не основание менять любой 404 на empty state.

## Автоматические проверки

| ID | Требование и действия | Ожидаемый результат | Исполнение |
| --- | --- | --- | --- |
| 235-01 | Новый Google account через реальный admission service → GET learner → GET background → PUT → GET | 204 без записей; 200 и сохранённые ответы после PUT | InitialBackgroundHttpPostgresTest |
| 235-02 | Local HTTP register → подтверждение тестового письма → HTTP login → та же цепочка | Один ученик, один инструмент, сохранённая анкета | InitialBackgroundHttpPostgresTest |
| 235-03 | Новый ученик → GET current → POST start → повторный POST → PUT background | 204, затем 201/200 с одним session id, один инструмент | InitialBackgroundHttpPostgresTest |
| 235-04 | Неавторизованный / непринятый / чужой actor; PUT без CSRF; неверный DTO / instrument | 401/403/404/400; ни одного созданного профиля | InitialBackgroundHttpPostgresTest |
| 235-05 | Сохранённая анкета с известной пьесой и навыком → GET, preferences PUT, одинаковый background PUT | Данные и история навыков сохранены, нет повторного claim | InitialBackgroundHttpPostgresTest |
| 235-06 | Два параллельных первых background PUT | Два 200; один профиль, одна анкета, один claim | InitialBackgroundHttpPostgresTest, отдельные транзакции PostgreSQL |
| 235-07 | Параллельные первые background PUT и preferences PUT | Два 200; один профиль; анкета и предпочтения сохранены | InitialBackgroundHttpPostgresTest, отдельные транзакции PostgreSQL |
| 235-11 | Параллельные первые background PUT и diagnostic POST | 200/201; один профиль; анкета и диагностика доступны | InitialBackgroundHttpPostgresTest, отдельные транзакции PostgreSQL |
| 235-08 | Bare / missing learner, неизвестный или другой инструмент, ошибка repository | Empty только у валидного начального профиля; ошибки не скрываются | InitialBackgroundTest |
| 235-09 | UI empty GET → явный save → повторное монтирование | Сохранение доступно; ответы восстановлены; GET не пишет | onboarding-recovery.component.test.mjs |
| 235-10 | UI 401/403/404/500; поздний GET/PUT старого ученика; retry после ошибки чтения | PUT блокируется; старые ответы не попадают новому ученику | onboarding-recovery.component.test.mjs |

Результаты исполнения и ссылки на CI фиксируются в PR и Issue. Наличие кейса в этой
таблице само по себе не означает PASS. SMTP и Google principal синтетические;
реальный внешний Google redirect / браузерная сессия проверяется владельцем.

## Повторить проверки

На **отдельной тестовой PostgreSQL**, без реальных пользовательских данных,
указать `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD`; установить `GUITAR_MENTOR_TEST_POSTGRES=true`.
Backend: Java 21, из `backend` запустить `./mvnw --batch-mode verify`
(Windows: `.\mvnw.cmd --batch-mode verify`). CI делает это с PostgreSQL 17.
Frontend: из `frontend` выполнить `npm ci`, `npm test`, `npm run test:intake`,
`npm run test:tuner`, `npm run build`, `npm run test:profile-browser`.

## Саморевью и проверка регрессии

Саморевью сопоставляет diff с критериями #235; это не независимое ревью другим
человеком или агентом. При проверке учтено дополнение владельца про diagnostic-sessions.
Выявленный пробел проверок — гонка первого PUT background с POST diagnostic-sessions —
устранён отдельным PostgreSQL/HTTP тестом. Проверены общая блокировка с preferences,
разделение empty state и ошибок, отсутствие записей из GET, атомарность сохранения,
сохранение доступа только к своему learner и отсутствие повторных skill claims.

Контроль на исходных двух сервисах из `0914a79b` при новых тестах:
14 тестов, три ошибки `Instrument profile not found`: чтение пустой анкеты,
первое сохранение анкеты, первый запуск диагностики. С исправленными сервисами
целевые 17 Java-тестов проходят. Полный PostgreSQL/HTTP набор исполняется в CI.

## Приёмка владельцем на DEV

Использовать ветку `fix/235-onboarding-initial-profile`, обновить **backend и
frontend**, затем перезапустить DEV backend. Записать `git rev-parse HEAD` и
результат backend-сборки. Одна перезагрузка вкладки не обновляет запущенный JAR.
Не направлять тесты на PILOT/production БД.

Отдельная рабочая копия для проверки (PowerShell из корня текущего репозитория):

```powershell
git status --short
git fetch origin
git worktree add --detach ..\guitar-mentor-qa235 origin/fix/235-onboarding-initial-profile
Set-Location ..\guitar-mentor-qa235
git rev-parse HEAD
```

Перед запуском настроить существующий DEV datasource и DEV-переменные авторизации
по `scripts/local-auth-unified.ps1` / действующей инструкции окружения. Затем в этой
копии собрать backend (`backend\mvnw.cmd --batch-mode -f backend\pom.xml package`)
и frontend (`Set-Location frontend; npm ci; npm run build`). Остановить старый **DEV**
процесс и запустить DEV из этой копии штатным способом. Тестовый backend должен
использовать DEV БД; `verify` с `GUITAR_MENTOR_TEST_POSTGRES=true` — отдельную QA БД.
Сборка JAR без перезапуска процесса не меняет обслуживаемую версию API.

1. Войти новым Google или local пользователем; открыть анкету до сохранения
   предпочтений. Нет «Данные недоступны», в Network GET background = 204.
2. Заполнить и сохранить анкету; F5 — ответы остались.
3. Другим новым пользователем сразу начать диагностику: POST diagnostic-sessions
   = 201; повторный запуск = 200 с той же сессией. Если выбрано обычное прохождение,
   сначала сохранить профиль и анкету, затем начать диагностику.
4. Под существующим пользователем проверить прежнюю анкету и историю навыков.
5. Записать фактическую версию DEV, результаты и принять PR. Выпуск на PILOT
   производится отдельно после приёмки, с обновлением backend и frontend.

Личные UUID и реальные ответы пользователей в доказательства не включать.
