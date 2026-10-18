# Guitar Mentor AI

Персональный ИИ-наставник по обучению игре на музыкальных инструментах. Начальная специализация — акустическая гитара и fingerstyle. Проект учебный, некоммерческий.

## Навигация по проекту

**[Kanban-доска — Процесс разработки](https://github.com/users/roafo1989/projects/1/views/3)** · [Реестр задач](https://github.com/users/roafo1989/projects/1/views/2) · [Дорожная карта MVP](docs/project/roadmap-mvp-v1.md) · [Документация](docs/index.md)

- **Kanban-доска** — задачи по этапам: Анализ → Оценка → Разработка → Тестирование → Завершение. На карточках показаны состояние, оценка в часах и тип работы.
- **[Стандарт работы над задачами](docs/project/development-workflow.md)** — согласованный цикл: бэклог → оценка → автономная разработка целиком → независимое тестирование → приёмка владельцем; **одна основная задача за раз**, ориентир 4–6 ч на задачу.
- **Реестр задач** — таблица для просмотра, фильтрации и редактирования полей.
- **[Issues](https://github.com/roafo1989/guitar-mentor-ai/issues)** — описания задач и обсуждения; **[Pull Requests](https://github.com/roafo1989/guitar-mentor-ai/pulls)** — изменения кода и ревью.

Проект также доступен через вкладку **Projects → Guitar Mentor AI — MVP** в верхнем меню репозитория.

## Что уже работает

- **Backend:** Java 21, Spring Boot 4.1.1, Spring Data JPA, PostgreSQL 17, Flyway.
- **Frontend:** Vue 3, TypeScript, Vite, Pinia, vue-i18n (RU/EN).
- **Иконка приложения:** собственный favicon (`frontend/public/favicon.png`) — гитара и электронные цепи, подключён через `frontend/index.html`.
- **Навигация:** верхняя панель закреплена при прокрутке; справа показано имя выбранного ученика (из Pinia/PostgreSQL) со ссылкой на профиль. Это **не авторизация и не имя учётной записи**.
- **UC-01:** профиль ученика, техника игры и отношение к музыкальным стилям.
- **UC-03:** музыкальный опыт, репертуар, учебные цели и расписание занятий.
- **Тестирование:** Java CI с PostgreSQL и Vue CI; локальный сценарий от Vue до PostgreSQL проверен.

**Пока не реализованы:** безопасная авторизация, загрузка аудио/видео, первоначальная диагностика исполнения, LLM-планирование занятий, Telegram-адаптер. Используйте тестовые данные; приложение работает только на локальном компьютере, не предназначено для публикации в сеть.

## Документация

- [Утверждённая дорожная карта MVP v1.0](docs/project/roadmap-mvp-v1.md)
- [Оглавление документации](docs/index.md)
- [Архитектурный стек](docs/adr/0001-java-vue-postgresql.md)
- [Первичная диагностика — спецификация UC-02](docs/use-cases/UC-02-initial-diagnostics.md)
- [Медиафайлы и TTL — ADR-0002](docs/adr/0002-media-storage-and-retention.md)
- [Анкета: опыт и цели — UC-03](docs/use-cases/UC-03-onboarding-experience-goals.md)
- [Рабочее место Windows 10](docs/development/workstation-setup-windows.md)

## Проверенный локальный HTTPS-адрес (Windows 10)

**Проверено 18.10.2026 на Windows 10 / Chrome / Caddy v2.11.7:** приложение открывается по **`https://guitar-mentor`** без `.localhost` и порта. Для этого на своём ПК один раз добавлена локальная запись `hosts`, установлен бесплатный Caddy и настроен доверенный локальный HTTPS-сертификат. Проверены чтение существующего профиля и ранее сохранённой анкеты из PostgreSQL после переноса тестового `learnerId`. **Только локальная разработка: доступ из интернета не включён, авторизации нет.**

Пошаговое руководство, раздел **5.1 «Перенос тестового профиля»** и журнал **5.2 «Фактическая проверка»**: [Windows — локальный HTTPS через Caddy](docs/development/local-https-caddy-windows.md). Конфигурация: [`infra/local/Caddyfile`](infra/local/Caddyfile).

## Как запускать

Требуются PostgreSQL 17, JDK 21, Node.js 22+ и настроенная роль БД. Не сохраняйте пароли в исходном коде.

### Backend

```powershell
cd D:\Development\guitar-mentor-ai\backend
$password = Read-Host "Пароль guitar_mentor_app" -AsSecureString
$env:SPRING_DATASOURCE_PASSWORD = [System.Net.NetworkCredential]::new('', $password).Password
.\mvnw.cmd spring-boot:run
```

### Frontend (во втором терминале)

```powershell
cd D:\Development\guitar-mentor-ai\frontend
npm ci
npm run dev
```

В **третьем терминале** VS Code после установки Caddy:

```powershell
cd D:\\Development\\guitar-mentor-ai
& 'C:\\Tools\\Caddy\\caddy.exe' run --config .\\infra\\local\\Caddyfile --adapter caddyfile
```

Открывайте **`https://guitar-mentor/profile`** и **`https://guitar-mentor/onboarding`**. Без Caddy по-прежнему работает `http://127.0.0.1:5173/profile`. **При смене origin старый `localStorage` не переносится автоматически** — см. раздел 5.1 руководства, чтобы не создать дубликат ученика. Для HTTPS нужен запущенный Caddy; для данных — запущенные PostgreSQL и backend.

## Планы

[GitHub Issues](https://github.com/roafo1989/guitar-mentor-ai/issues) — задачи реализации, [Pull Requests](https://github.com/roafo1989/guitar-mentor-ai/pulls) — ревью и история изменений.

В архиве оставлен первоначальный Python-эксперимент ([PR #5](https://github.com/roafo1989/guitar-mentor-ai/pull/5)); основной backend проекта реализуем на Java.
