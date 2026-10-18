# DEV-05A — Проверка диагностической сессии (Windows 10)

**Что проверяем:** создание одной активной сессии для акустической гитары, сохранение прогресса, безопасное повторение команд и переход в состояние ожидания диагностического задания. **Загрузки и оценки исполнения здесь пока нет.**

## 1. Ветка разработки

Останови Spring Boot и Vite (`Ctrl+C`). В PowerShell:

```powershell
cd D:\Development\guitar-mentor-ai
git status
git fetch origin
git switch --track origin/feature/dev-05a-diagnostic-session
```

Если ветка уже создана локально: `git switch feature/dev-05a-diagnostic-session`, затем `git pull --ff-only`. При несохранённых изменениях сначала разбери `git status`.

## 2. Backend

PostgreSQL должен работать. В первом терминале:

```powershell
cd D:\Development\guitar-mentor-ai\backend
.\mvnw.cmd test
$env:SPRING_DATASOURCE_PASSWORD = Read-Host "Пароль guitar_mentor_app" -MaskInput
.\mvnw.cmd spring-boot:run
```

Flyway применит V4__create_diagnostic_sessions.sql; старые записи учеников и анкеты не изменяются.

## 3. Vue

Во втором терминале:

```powershell
cd D:\Development\guitar-mentor-ai\frontend
npm ci
npm run dev
```

Открой `http://127.0.0.1:5173/diagnostics`.

- Появится профиль уже созданного тестового ученика либо ссылка на его создание.
- **«Начать диагностику»** создаст сессию в статусе COLLECTING_EVIDENCE.
- **«Продолжить без записи»** переводит её в READY_FOR_TASK.
- После F5 статус и UUID должны восстановиться.
- Новые записи и ноты пока загрузить нельзя. Никаких оценок навыка не появляется.

## 4. Проверка с помощью SQL

```sql
SELECT l.display_name, ip.instrument_code, ds.id, ds.status,
       ds.created_at, ds.updated_at, ds.version
FROM diagnostic_sessions ds
JOIN instrument_profiles ip ON ip.id = ds.instrument_profile_id
JOIN learners l ON l.id = ip.learner_id
ORDER BY ds.created_at DESC;
```

Повторный запуск диагностики для того же инструмента должен возвращать **тот же UUID**, а не создавать ещё одну активную сессию. Условный индекс не допускает две активные сессии на один инструмент.

## О безопасности

В текущем MVP нет авторизации. UUID лишь адресует данные, но не подтверждает право ученика на их чтение. **Использовать только тестовые данные и локальные порты 127.0.0.1; не публиковать сервер в сеть.** Процесс диагностики намеренно не содержит загрузки файлов, музыкального анализатора или назначения упражнений.
