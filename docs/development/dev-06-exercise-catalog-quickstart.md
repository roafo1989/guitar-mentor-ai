# DEV-06 — каталог этюдов и назначение первого задания

**Ветка:** `feature/dev-05b-exercise-catalog`  
**Статус:** готово к локальному тестированию после зелёного CI.  
**Важно:** локальный прототип без авторизации; только тестовые данные.

## Что реализовано

- Flyway V5: каталог `exercises`, `skill_codes`, `exercise_skills` и назначения `diagnostic_tasks`.
- Первое оригинальное упражнение: GM-EB01 v0.8B «Вечерний блюз в ми» (84 BPM, 12 тактов, E7/A7/B7).
- Тот же MusicXML и JSON событий, которые сохранены в репозитории, включены в ресурсы Java backend.
- API каталога и назначения задачи, статус сессии `TASK_ASSIGNED`.
- Упрощённая ASCII-TAB на странице диагностики и ссылка на исходный MusicXML с нотным станом.

**Ограничение:** web-preview TAB показывает позиции нот по сетке восьмых, а **не точную ритмическую нотацию**. Для точных пауз, длительностей, голосов и нотного стана откройте MusicXML в нотном редакторе (MuseScore/Guitar Pro). PDF-renderer, аудиоанализ, оценка навыков, запись игры и метроном пока не реализованы. Публикация/автовыдача реальным ученикам требует проверки аппликатуры, импорта партитуры и авторизации.

## Windows: как перейти на рабочую ветку

**Перед переключением** остановите backend и Vite (Ctrl+C) и убедитесь, что в Git нет несохранённых изменений.

```powershell
cd D:\Development\guitar-mentor-ai
git status
git fetch origin
git switch --track origin/feature/dev-05b-exercise-catalog
```

Если эта ветка уже создана локально: `git switch feature/dev-05b-exercise-catalog` и `git pull --ff-only`. При изменённых локальных файлах не переключаться без их сохранения/разрешения.

## Запуск backend

В первом терминале VS Code (PostgreSQL 17 должен быть запущен):

```powershell
cd D:\Development\guitar-mentor-ai\backend
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

Предполагается, что переменная Windows `SPRING_DATASOURCE_PASSWORD` уже настроена. Если нет, используйте ввод через `Read-Host -MaskInput` как в инструкции по настройке рабочего места. **Не сохраняйте пароль в Git или на скриншотах.**

Проверьте, что применена V5 и приложение запущено на 8080. Старые профили и анкеты миграция сохраняет.

## Запуск frontend

Во втором терминале:

```powershell
cd D:\Development\guitar-mentor-ai\frontend
npm ci
npm run dev
```

Откройте `http://127.0.0.1:5173/diagnostics`.

## Ручная проверка UX

1. Вернитесь на уже созданный тестовый профиль ученика, если нужно.
2. Предыдущая сессия `READY_FOR_TASK` должна восстановиться после F5; это та же сессия, что раньше.
3. Теперь есть карточка «Вечерний блюз в ми» (GM-EB01 v0.8B), 84 BPM.
4. Нажмите **«Назначить этот этюд»**.
5. Должно появиться название, рекомендации, исходный MusicXML и блоки TAB для тактов 1–4, 5–8, 9–12.
6. Обновите страницу F5: тот же этюд и та же сессия восстановятся; второе назначение не создаст дубликат.
7. Проверьте импорт скачанного MusicXML в MuseScore или Guitar Pro; если есть смещения нот/струн, сообщите.

## API для REST-проверки

```http
GET /api/v1/exercises?instrumentCode=ACOUSTIC_GUITAR&techniqueCode=FINGERSTYLE
GET /api/v1/exercises/GM-EB01
GET /api/v1/exercises/GM-EB01/score
GET /api/v1/exercises/GM-EB01/events
POST /api/v1/learners/{learnerId}/diagnostic-sessions/{sessionId}/tasks
Content-Type: application/json

{"exerciseCode":"GM-EB01"}

GET /api/v1/learners/{learnerId}/diagnostic-sessions/{sessionId}/tasks
```

Назначение допускается только из `READY_FOR_TASK`. Повторный POST с тем же кодом возвращает назначенное задание; с другим кодом — `409 Conflict`. `GET /tasks` до назначения возвращает `204 No Content`.

## PostgreSQL: данные задания

```sql
SELECT l.display_name, ds.id AS session_id, ds.status,
       dt.id AS task_id, e.code, e.version, e.title, dt.assigned_at
FROM diagnostic_sessions ds
JOIN instrument_profiles ip ON ip.id = ds.instrument_profile_id
JOIN learners l ON l.id = ip.learner_id
LEFT JOIN diagnostic_tasks dt ON dt.diagnostic_session_id = ds.id
LEFT JOIN exercises e ON e.code = dt.exercise_code
ORDER BY ds.created_at DESC;
```

**Контроль:** `TASK_ASSIGNED`, ссылка на GM-EB01 v0.8B и одна строка назначения на выбранную сессию. Это назначение не означает, что ученик уже сыграл или освоил материал.

## Следующие этапы

- Полноценный рендер нот + TAB внутри страницы, PDF и удобные инструкции.
- Ещё 4 коротких оригинальных этюда для набора R3-02.
- Метроном и отслеживание практики; далее медиа/TTL и PoC объективных методов оценки.
