# DEV-01 — Локальный запуск для Windows 10

## Предварительно
- Python 3.12 (проверка в PowerShell: `py -3.12 --version`);
- Git (проверка: `git --version`);
- доступ к приватному репозиторию GitHub.

## Установка
Открой PowerShell:

```powershell
git clone https://github.com/roafo1989/guitar-mentor-ai.git
cd guitar-mentor-ai

# Переключиться на ветку текущего DEV-01, пока PR не включён в main:
git switch feature/dev-01-python-bootstrap

py -3.12 -m venv .venv

.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
```

При использовании Git Credential Manager Git предложит авторизоваться на GitHub. Пароли и Personal Access Tokens не помещай в файлы репозитория.

## Первый запуск и тестирование
```powershell
.\.venv\Scripts\python.exe -m guitar_mentor --check
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m ruff check .
```

Ожидаемый результат команды `--check`:
```text
OK: Guitar Mentor AI bootstrap is ready.
```

Не требуется получать Telegram- или OpenAI-токен для DEV-01. В DEV-02 добавим Telegram polling и реальную команду `/start`.

## Сравнение с Java
- `pyproject.toml` выполняет роль метаданных и конфигурации сборки, отчасти аналогично `pom.xml`.
- `.venv` изолирует установленные Python-пакеты для конкретного проекта.
- `src/guitar_mentor` — Python-пакет, `__init__.py` задаёт границу импортируемого пакета.
- `pytest` по назначению похож на JUnit, хотя использует более краткий синтаксис.
- `ruff` выполняет статическую проверку и стилистический контроль.

## Правила безопасности
Не коммить `.env`, токены, локальную БД, аудио/видео или данные пользователей. `.env.example` содержит только пустые переменные. Сейчас конфигурация читается из переменных окружения ОС; автоматическое чтение `.env` ещё не реализовано.
