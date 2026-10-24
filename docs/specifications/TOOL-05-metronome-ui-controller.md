# TOOL-05 / #113 — Общая UI-модель и реестр скинов метронома

**Отмена 24.10.2026:** владелец отказался от US-010/#112 и задач #113–116 («Я передумал, они мне не нужны»). BT-020 и FT-010-01…04 сняты с действующего объёма; разработка, оценки и условия старта этой ветки больше не применяются. Основной интерфейс метронома #111 сохраняется. Ниже сведения о скинах оставлены только как история. **Документ архивный, не готовность к разработке.**

| Версия | Дата | Изменение |
| --- | --- | --- |
| 1.0 | 22.10.2026 | Контракт извлечения controller из принятого B1, владение состоянием/фокусом, 9 AC, оценка. |

## Паспорт, источники и факт

Full / Фронтенд. Issue [#113](https://github.com/roafo1989/guitar-mentor-ai/issues/113), native parent должен быть US-010/#112, EPIC-001/#53. БТ0.10 / BT-020, ТЗ0.13 / FT-010-01; подробные SKIN-04…09 в #112. NFR-001/004/007/008, SEC-001/005/008 по применимости. Автор СА; самопроверка, независимое review не выполнено. База main `63977770bf82ef07e258bd23462c7c6af11520fa`, #111/PR117 CLOSED/MERGED, утверждённый B1 Graphite Mint. Анализ готов, продуктовый код не менялся.

Как ученик, я хочу выбирать разные компоновки метронома без потери ритма и ввода. Вклад этой задачи — устойчивый единый controller и точка подключения представлений. Пользовательского выбора стиля в этом инкременте ещё нет.

В main singleton engine и metronome-ui.ts уже отделены, но большинство формы/команд/ошибок/панелей находится внутри MetronomeDialog.vue. Прочитаны sync/start/apply/setMeter/changeGroups, preset CRUD/reset, обработчик Escape/Tab, mounted/unmount и B1 CSS/template. Не переписывать эти правила с нуля. #109 меняет audio internals отдельно; #108 не блокирует работу.

## Границы

Включено: shell, стабильный экземпляр controller, thin host, registry только B, контракт view/actions, прежние B1 controls и регрессия. Исключено: скины A/C (#114/#116), публичный selector/persistent skin preference (#115), новый AudioWorklet (#109), новый дизайн B, storage migration, backend/БД/права/звуковые функции. Skin registry статический, не механизм установки пользовательского кода.

## Ответственность и время жизни

[Схема](../diagrams/issue113-controller.mmd).

1. metronome singleton остаётся единственным владельцем аудио и active/pending config. metronome-ui.ts сохраняет open/sourceExercise/returnFocus/presets/storageError.
2. MetronomeDialog shell сохраняет Teleport, aria-modal/заголовок, close/minimize emits, body overflow, focus trap, Escape внутренних панелей/всего окна и возврат фокуса. Один controller создаётся на mounted shell, subscribe ровно один; уничтожается только с shell. При обычном minimize shell как сейчас может unmount, engine не останавливается; повторное открытие читает актуальный engine. Новое сохранение draft через minimize не вводится.
3. useMetronomeController предоставляет refs/computed и команды ниже. На смене дочернего представления controller не пересоздаётся. Engine subscription не дублируется.
4. MetronomeSkinHost получает единый controller/view contract и статический id. Registry содержит только b→MetronomeSkinB, fallback b. В этой Issue host можно remount в тесте, реального UI switch нет.
5. Skin B — раскладка и tokens. Общие controls/panels связываются с controller. Skin не импортирует singleton, localStorage, router или Media APIs и не имеет своих watchers start/stop/apply. DOM refs принадлежат binding shell, не бизнес-модели.

### UI state v1 (внутренний TypeScript, не REST)

| Область | Поля/источник |
| --- | --- |
| engine projection | state,status,beat; active config и pending config из getConfig/getPendingConfig, read-only для view |
| editable | draftConfig (копия requested), raw BPM string, dirty, lastRequested signature |
| rhythm form | meterN/meterD, raw groupInput, beatSelection |
| validation | tempo error, configError, tapStatus; valid/disabled вычисляются тем же parser |
| presets | presetName, selectedPreset, confirmAction replace/delete, confirmReset; presets/storage flags из metronomeUi |
| panels | settings/presets/null, логический focus target/opener; DOM element binder задаёт shell |
| tap | один TapTempo на controller; presentation remount не сбрасывает taps |

Невалидные строки — полноценное состояние: пустая BPM, незаконченная цифра, некорректная группировка не заменяются «последним хорошим» на тике. config numerical bounds наследуются из metronome-config.ts:20–240, N1–16/D2,4,8,16, valid groups, subdivision1–4, volumes0–100, max20presets, name1–60, storage64KiB. Новых диапазонов нет.

Действия controller: editBpm/commitBpm/stepBpm/tap; pickMeter/editGroups/setTempoUnit/setSubdivision; selectBeat/cycleAccent/setPositionVolume/setMaster; apply; load/save/replace/deletePreset/resetPreferences; start/stop/toggle; openPanel/closePanel. Их аргументы примитивные проверяемые значения и config-копии. UI не мутирует active или shared preset config. Названия допустимо адаптировать к существующему code style; тестируемое поведение неизменно.

### Важные инварианты переноса

- sync сначала читает state/active/requested; обновляет clean draft только по изменению requested signature. raw invalid BPM не стирать, focused valid input не перезатирать. Регистрация текущего input предоставляет controller `isTempoEditing`/binding, чтобы прежняя проверка document.activeElement не сломалась после извлечения.
- Чистая смена presentation не вызывает engine.applyConfig/setBpm/start/stop и persistence. Обычный пользовательский BPM вызывает setBpm немедленно с reset beat1 по #87/#104; не обещать, что изменение самого BPM сохраняет фазу. Смена будущего скина фазу сохраняет потому, что вообще не отправляет музыкальную команду.
- Структурный Apply/preset во время playing сохраняет pending до границы; мастер немедленный. selectedPreset/dirty/visible error остаются связаны с реально введёнными данными.
- Start вызывается из прямого пользовательского события. Не переносить его только в async watcher; resume/autoplay контракт сохранить.
- На controller dispose только unsubscribe/refs. Stop принадлежит явным lifecycle-командам shell/metronome-ui, а не skin onUnmounted.
- Перенос панели не ломает inert фон, Tab trap, Escape (сначала закрыть панель), confirm и returnFocus. При remount view восстановить фокус по логическому control id, если control сохранился; иначе безопасный BPM fallback после nextTick. Не хранить оторванный HTMLElement как единственное доказательство фокуса.
- Ключ/схема guitar-mentor.metronome.v1 и поведение corrupt/blocked storage остаются как в #104. Новый ключ skin.v1 в этой задаче запрещён; b в registry — compile-time default, не сохранённое предпочтение.

## Ошибки, НФТ и безопасность

Те же RU/EN loading/starting/running/stopped/unavailable, invalid BPM/config/groups, silent pattern, storage failure/preset limit. Error boundary не прячет невозможность Start. Новых пользовательских данных/сети/секретов нет; ошибки выводятся текстом, без v-html. Registry импортирует доверенные repo-components; arbitrary dynamic import/URL/CSS/html не принимаются. Новые логи не нужны; существующие безопасные коды ошибок сохраняются.

Визуально B1 сравнить с принятой поставкой #111 на360×640 иdesktop: исходная компактность, крупный BPM, разнесённые ±, Start/Stop; 200% zoom допускает прокрутку без потери controls. Keyboard/RU/EN/reduced motion наследуются. Это перенос принятого вида, нового макета и его утверждения для #113 не требуется.

## Приёмка

| AC | Дано / Когда / Тогда |
| --- | --- |
| AC-CTRL-01 | Дано принятый B1. Когда UI извлечён. Тогда ready/playing/pending/invalid/settings/presets остаются визуально и функционально эквивалентны baseline #111. |
| AC-CTRL-02 | Дано один shell/controller и running engine. Когда B-child unmount/mount без shell. Тогда subscribe остаётся один, start/stop/apply/setBpm/storage не вызваны и фаза не сброшена. |
| AC-CTRL-03 | Дано raw BPM пусто/невалидно, dirty rhythm/groups, выбранный beat и error. Когда приходит tick и remount view. Тогда введённое не теряется; Apply/Save не проходят валидацию ошибочного ввода. |
| AC-CTRL-04 | Дано pending preset, затем valid BPM/master. Тогда остаются прежние правила next-bar, immediate BPM reset/master; UI различает requested и sounding. |
| AC-CTRL-05 | Дано presets CRUD/confirm/reset, max20 и store failure. Тогда их данные/ошибки равны принятому контракту; новый ключ скина не создаётся. |
| AC-CTRL-06 | Дано open settings/presets и keyboard. Когда Escape/remount/Tab/close. Тогда панель закрывается первой, focus остаётся внутри модалки/возвращается вызывающему элементу; фон inert. |
| AC-CTRL-07 | Дано запись/running/minimize/reopen/header/exercise entry/tuner/route. Тогда действуют прежние lifecycle и один engine; наличие #109 не переопределяется controller. |
| AC-CTRL-08 | Дано360×640,desktop,RU/EN,200%zoom. Тогда B1 controls и ошибки доступны без горизонтальной прокрутки/обрезания; вид/семантика подтверждены сравнением. |
| AC-CTRL-09 | Дано production build. Тогда registry содержит только b; unknown internal id fallback b; нет selector/storage скинов и импорта аудио из skin. Tests/typecheck/build и документация contract приложены к PR. |

TC=те же AC с позитивной/ошибочной ветвью; дополнительно regression test suite metronome/tuner/diagnostic из package.json. В ходе анализа эти runtime-кейсы NOT_RUN; зелёные CI main относятся к исходной базе, не к будущему рефакторингу.

## Установка, smoke, откат и оценка

Frontend-only, без миграций/новой конфигурации. Разработчик даёт точную ветку/commit и актуальные команды npm test/typecheck/build. Smoke: открытьB1→84Start→невалидный BPM→settings→pending7/8→preset→minimize/reopen→Stop; сравнить вид сbaseline. Trigger отката: потеря draft/preset/focus или спонтанный stop/start. Владелец локальной установки возвращает предыдущий принятый frontend, проверяетB1/Start/Stop/пресеты. Формат storage тот же, удаления данных нет.

Рабочая оценка **12ч (9–16)**, уверенность средняя: извлечениеcontroller4; shell/host/registry/B3; тестыфокуса/remount/состояний3; сравнениеB1/документация2. Это оставшаяся работа, не повторная оценка #111/#104 и не трудоёмкость A/C/selector. Проверяемые коммиты внутри одной Issue; если извлечение потребует изменения звукового контракта — остановить расширение scope/переоценить, а не включать скрыто.

Зависимость #111 выполнена; новых пользовательских решений для старта #113 нет. Готова к выбору разработки. Следующие #114→#115→#116 сохраняют свои gates и ещё не взяты в работу этим анализом.
