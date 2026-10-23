# #51 — проверки анализа и будущего интерфейса

Версия1.1,23.10.2026(Москва). База frontend-аудита main26bd83c; заключительно проверен mainb6485e4 после merged119/PR122, frontend diff пуст; [спецификация](../specifications/UX-01-student-journey.md), ТЗ0.17/FT-003-04, БТ0.10. Самопроверка СА, независимого review/пользовательской приёмки нет.

## Фактически выполнено при подготовке

| Проверка | Результат | Граница |
| --- | --- | --- |
| Исходники Home/router/store/Diagnostic/Submission/LearningResults/onboarding/services и зависимости119/120/85 | PASS-INSPECTION | Статическое чтение, приложение/реальный backend не запускались. |
| Числа route/actions и существующий короткий путь через header | PASS-INSPECTION | Формальная активация контролов, не пользовательский хронометраж. |
| JavaScript автономного прототипа | PASS-SYNTAX | Node vm.Script успешно разобрал inline script; это не исполнение DOM и не проверка дизайна. |
| Прототип не обращается к network/storage/microphone | PASS-INSPECTION | Нет fetch/XMLHttpRequest/WebSocket/localStorage/getUserMedia, внешних scripts/assets. Нет реальных файлов/решений. |
| AC→TC, ссылки/файлы, неизменный состав38FT | PASS-STATIC | Проверка опубликованных текстов и дерева, не runtime. |
| Открытие GitHub в CUA | PASS-ACCESS | После начальных тайм-аутов доступ восстановлен; поля доски проверяются отдельно. |
| Открытие локального HTML в облачном браузере | BLOCKED-POLICY | Browser URL policy отвергла file:; разрешены http/https. Обход/публикация ради обхода запрета не выполнялись. |
| Визуальный desktop/mobile/zoom, взаимодействие мышью/клавиатурой и JS в браузере | NOT_RUN | Доступ к локальному прототипу заблокирован; статический CSS не является визуальным PASS. |
| Утверждение прототипа владельцем (AC10) | BLOCKED-OWNER | Требуется исходным объёмом51; обсуждается готовый файл, не пустая идея. |

Для воспроизведения syntax-check из root checkout:
```bash
node -e 'const fs=require("fs"),vm=require("vm");const h=fs.readFileSync("docs/ux/UX-01-student-workspace-prototype.html","utf8");new vm.Script(h.match(/<script>([\s\S]*?)<\/script>/)[1]);console.log("SYNTAX PASS")'
```
Прототип открыть локально обычным браузером после скачивания. Панель review меняет сценарий, язык и ширину; это демонстрационные состояния, не настоящий backend/микрофон.

## Матрица приёмки

AC IDs стабильны; каждый future runtime case начинается только после соответствующей реализации H1/H2. Моки использовать для управляемых404/поздних ответов и явно помечать MOCK. Затем выполнить доступные интеграционные проверки с реальным backend; не требовать реальных музыкальных оценок из ещё отложенных22/23.

| TC / AC | Предусловия | Действия | Ожидание | Факт/статус |
| --- | --- | --- | --- | --- |
| TC-UX51-01 / AC-UX51-01 | main26bd83c и требования51 | Сопоставить аудит с компонентами/готовыми задачами | #19/20/95/audio не объявлены отсутствующими и не оцениваются повторно | PASS-INSPECTION |
| TC-UX51-02 / AC-UX51-02 | Одинаковый старт каждого сценария | Проверить9путей и history по правилам счёта | Homeprimary3→1, headerуже1; Record/Stop/Send3иComplete/Confirm2не сокращаются | PASS-INSPECTION |
| TC-UX51-03 / AC-UX51-03 | Автономный HTML,desktop/mobile | Пройти home/new/setup/intake/catalog/lesson/loading/error/empty черезcontrols | Понятное главное действие, нет реальной отправки/выдуманной оценки | BLOCKED-POLICY в облачном браузере; файл подготовлен |
| TC-UX51-04 / AC-UX51-04 | H1; управляемые learner/session/background/task/learning GET | Все ветви таблицы:204/404/5xx/nulltask; поздний ответ прежнегоlearner | CTAверен, RetryтолькоGET, правильная очисткаключа, нет staleчужогоконтекста | NOT_RUN, H1не реализован |
| TC-UX51-05 / AC-UX51-05 | H2; возможности upload включены | Record/Stop/Listen/Send; ошибкаPOST; retry; попыткауйти сdraft/busy | Отдельный Send; file/comment/requestIdсохранены; guardкакв19/20 | NOT_RUN, H2не реализован |
| TC-UX51-06 / AC-UX51-06 | PROCESSED/NO_ASSESSMENT и реальныеavailableActions | ContinuePracticing; CompleteCancel/Confirm; открытьterminalпослеF5 | Не появляетсяmusicalPASS/confirmedlevelизрешения; nextunavailable | NOT_RUN |
| TC-UX51-07 / AC-UX51-07 | Историянесколькихпопыток/revisions, cursors,409,PDF404 | Раскрытьhistory/More/retry, выбратьattempt, открытьPDF/audio/metronome/tuner, запись | Ничегонеудалено, несломаныguards/races/версии/инструменты;collapsedPDF | NOT_RUN |
| TC-UX51-08 / AC-UX51-08 | RU/EN;360px/desktop/200%zoom/keyboard | Пройтиглавныйпуть/ошибки/длинныйтекст; F5языка, detailsиdialogsEscape | Читаемость/44pxtargets/focus, нетперекрытия, актуальныеCTA/ошибкидоступны | NOT_RUN, browservisualнепроверен |
| TC-UX51-09 / AC-UX51-09 | Спецификация/Issue51/85 | Сверитьscope/оценки/dependencies | 12чH1/H2отделеныот51/6ч85;119/120непишутсяв51;новыхIssuesнет | PASS-INSPECTION |
| TC-UX51-10 / AC-UX51-10 | Владелецвидитготовыйпрототип/спецификацию | Reviewглавной/урока/mobile/решения; записатьзамечанияирешение | ЯвноеUX-утверждениедооформленияruntimeзадач/старта;51поканеCLOSED | BLOCKED-OWNER |

## Будущая передача реализации

После согласования прототипа оформить H1/H2 отдельными задачами со своими runtime-ФТ/родителем, оценками6+6ч и этой матрицей; утвердить приоритет и одну задачу в разработку. Это рекомендация для конкретного следующего шага, не автоматический запуск или создание дубликатов.

Из frontend на featureветке: `npm ci`, `npm run build`, `npm test`, `npm run test:intake`, `npm run test:tuner`; добавить component tests H1readstates/races и H2draft/history/layout. Существующий CIзелёныйнаmainнеявляетсяприёмкойновогоUX. РазработчикфиксируетSHA/команды/CI; затемКод-ревью/Вочереди, независимоеreview, тестирование, приёмкавладельца. Аналитическая51принимаетсякакUXпакет, некакреализованныйfrontend.
