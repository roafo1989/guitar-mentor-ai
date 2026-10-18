# DEV-06 — Предпросмотр этюда (PDF и MP3)

## Контракт
До назначения студент может прослушать аудио и просмотреть ноты+TAB в PDF без создания DiagnosticTask. После назначения оба медиа остаются на экране урока. Браузерный HTML5 player / iframe + ссылка открыть PDF в новой вкладке. Музыкальные примеры v0.8B синтезированы; не выдавать их за исполнение преподавателя.

## Хранение
Flyway V6 добавляет exercises.preview_audio_resource / preview_pdf_resource. В PostgreSQL хранятся лишь **версионированные пути**. PDF/MP3 обслуживает backend:
- GET/HEAD /api/v1/exercises/GM-EB01/preview/audio — audio/mpeg
- GET/HEAD /api/v1/exercises/GM-EB01/preview/pdf — application/pdf
- Если медиа ещё не установлены — 404, а frontend показывает понятное сообщение и ссылку на MusicXML.

На первом этапе медиа находятся локально в Java classpath. Позднее выделим хранилище учебного контента, отдельное от личных записей учеников.

## Установка Windows
В чате доступен ZIP: **guitar-mentor-dev06-preview-media-v08B.zip**. Внутри PDF (3 страницы) и MP3 (~36 с).
1. Остановить Spring Boot Ctrl+C.
2. Сохранить ZIP в D:\Development.
3. Распаковать в корень репозитория:
```powershell
Expand-Archive -Path "D:\Development\guitar-mentor-dev06-preview-media-v08B.zip" `
  -DestinationPath "D:\Development\guitar-mentor-ai" -Force
```
4. Убедиться, что файлы по путям:
```text
D:\Development\guitar-mentor-ai\backend\src\main\resources\exercises\GM-EB01\preview_v08B.mp3
D:\Development\guitar-mentor-ai\backend\src\main\resources\exercises\GM-EB01\score_v08B.pdf
```
5. Перезапустить backend, открыть https://guitar-mentor/diagnostics и обновить Ctrl+Shift+R.
6. Проверить аудио/все страницы PDF **до** назначения, затем после назначения и F5.

Медиа локально исключены из Git через .gitignore и пока не включены в публичный PR. По умолчанию CI проверяет код и миграции, а ручная медиа-проверка выполняется после установки ZIP. **PR не сливать до ручной приёмки.**

## Следующий инкремент
Отправка записи, обратная связь и пересдача — отдельные R4/R5/R6. См. docs/ux/lesson-workflow-skillspace-reference.md.
