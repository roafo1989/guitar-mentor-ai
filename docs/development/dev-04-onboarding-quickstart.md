# DEV-04 — Анкета опыта и целей (локальная проверка)

## Получить изменения
Перед сменой ветки проверить, что `git status` не показывает несохранённые изменения.

```powershell
cd D:\Development\guitar-mentor-ai
git status
git fetch origin
git switch --track origin/feature/dev-04-onboarding
```

Если ветка уже создана локально, вместо последней команды: `git switch feature/dev-04-onboarding` и `git pull --ff-only`.

## Backend
В первом терминале VS Code:

```powershell
cd D:\Development\guitar-mentor-ai\backend
.\mvnw.cmd test
```

PostgreSQL 17 должен быть запущен. Настроить пароль `guitar_mentor_app` в текущем терминале по [инструкции backend](../../backend/README.md); **не добавлять пароль в Git**. Затем:

```powershell
.\mvnw.cmd spring-boot:run
```

Flyway автоматически применит миграцию V3 (новые таблицы анкеты). Данные из V1/V2 сохраняются.

## Frontend
В новом терминале VS Code:

```powershell
cd D:\Development\guitar-mentor-ai\frontend
npm ci
npm run dev
```

Открыть: `http://127.0.0.1:5173/`.

## Тестовый сценарий
1. На странице «Профиль ученика» открыть ранее созданный тестовый профиль.
2. Перейти к «Опыт и цели».
3. На шаге «Опыт» выбрать уровень, техники и при желании ввести произведения (`Название | Автор`, одно на строку).
4. На шаге «Цели» выбрать направления, продолжительность занятия и число занятий в неделю.
5. Нажать «Сохранить анкету», обновить страницу `F5` и проверить восстановление данных.
6. Изменить один из ответов и сохранить повторно — данные должны обновиться, а не создать вторую анкету.

### Проверка SQL в pgAdmin
```sql
SELECT
  l.display_name, ip.instrument_code,
  b.experience_level, b.years_playing,
  b.practice_minutes, b.sessions_per_week, b.notes
FROM learning_backgrounds b
JOIN instrument_profiles ip ON ip.id = b.instrument_profile_id
JOIN learners l ON l.id = ip.learner_id
ORDER BY l.display_name;
```

По выбранному профилю должна быть **ровно одна** запись в `learning_backgrounds`.

## Ограничения
Анкета — самоописание, не объективная диагностика. Загрузка аудио, вопросы от ИИ, автоматическое оценивание — следующие этапы. **Без авторизации — запускать только на localhost, с тестовыми данными, не открывать в интернет.**
