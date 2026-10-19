# DEV-05A — Проверка диагностической сессии (Windows 10)

**Что проверяем:** создание одной активной сессии для акустической гитары, сохранение прогресса, безопасное повторение команд и переход в состояние ожидания диагностического задания. **Собственных примеров до назначения и музыкальной оценки ещё нет; аудиопопытки назначенного задания уже реализованы в #20.**

## 1. Ветка разработки

Останови Spring Boot и Vite (`Ctrl+C`). В PowerShell:

```powershell
cd D:\Development\guitar-mentor-ai
git status
git fetch origin
git switch main
git pull --ff-only
```


## 2. Backend

PostgreSQL должен работать. В первом терминале:

```powershell
cd D:\Development\guitar-mentor-ai\backend
.\mvnw.cmd test
$dbSecret = Read-Host "Пароль guitar_mentor_app" -AsSecureString
$env:SPRING_DATASOURCE_PASSWORD = [System.Net.NetworkCredential]::new('', $dbSecret).Password
.\mvnw.cmd spring-boot:run
```

Flyway применит ещё отсутствующие миграции, включая V4__create_diagnostic_sessions.sql; последующие принятые V5–V9 добавляют задания и аудиопопытки. Старые записи учеников и анкеты не должны удаляться.

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
- **Собственные примеры и ноты до назначения задания** пока загрузить нельзя (#68). Аудиопопытки **назначенного задания** доступны отдельно в #20; никакой музыкальной оценки навыка это пока не даёт.

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

В текущем MVP нет авторизации. UUID лишь адресует данные, но не подтверждает право ученика на их чтение. **Использовать только тестовые данные и локальные порты 127.0.0.1; не публиковать сервер в сеть.** Начальный срез этой инструкции не проверяет весь учебный цикл. В main после V5–V9 уже доступны назначение и аудиопопытки; музыкальный анализ и intake #68 остаются будущим объёмом.
