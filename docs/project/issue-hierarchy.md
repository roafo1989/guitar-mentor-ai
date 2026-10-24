# Guitar Mentor AI — Реестр иерархии Issues

**Каталог — 24.10.2026:** [план и трассировка](CATALOG-ROADMAP-2026-10-24.md), EPIC-004/#154,5новыхстори/#155–159 и16задач/#160–175. Приоритет P0 — личные готовыеPDF/GP; только160–164 в Анализ/В очереди, остальные в бэклоге. БТ0.18 / ТЗ0.25,14действующихUS/54FT. Указания количеств ниже относятся к прежним срезам; новые Issues не означают выполненный код.

**Новый способ входа24.10.2026:** [AUTH-02/#141](../specifications/AUTH-02-email-password.md) добавляет email+логин+пароль наряду с Google. Только приглашения, общие Account/Learner/сессии/права, подтверждение почты и восстановление. Старое ограничение «только OIDC/без хранилища паролей» заменено этим расширением. Full-анализ впереди; существующие PR132/139 не принимаются и не переписываются этим запросом. Порядок38→42→141→39→40;141 является пятой задачей стори61, новая ФТ FT-007-05. Исторические количества и Google-only условия ниже относятся к прежнему объёму.

**Отмена 24.10.2026:** владелец отказался от US-010/#112 и задач #113–116 («Я передумал, они мне не нужны»). BT-020 и FT-010-01…04 сняты с действующего объёма; разработка, оценки и условия старта этой ветки больше не применяются. Основной интерфейс метронома #111 сохраняется. Ниже сведения о скинах оставлены только как история. В активном реестре71 карточка:59 задач/9 стори/3 эпика;112–116 архивированы. Native parent113–116→112→53 сохраняется как история отмены. Закрытие112 not_planned не добавляет принятую стори к трём принятым #55/#58/#65; у эпика53 остаются7 действующих стори.

| Версия | Дата | Что поменялось |
| --- | --- | --- |
| 0.10 | 24.10.2026 | Аудит76 элементов/73 native-связей: восстановлены98/111→57 и100/101→56; вход/доступ в существующей61, ID сохранены. |
| 0.11 | 19.10.2026 | #17 выведена из активного учёта (not_planned, историческая); #76–#89 созданы отдельно под #56, 60 ч без двойного учёта; native Project-связи требуют синхронизации. |
| 0.10 | 19.10.2026 | #14 выбрана и проведена через Анализ / В работе в Оценка / В очереди; Full, старые 15 ч сохранены как исторические. #59 возобновлена в текущем цикле анализа. |
| 0.9 | 19.10.2026 | Новые параметры процесса и синхронизация #20/#30/#21/#55; иерархия и оценки сохранены. |
| 0.8 | 19.10.2026 | Сверены 31 ФТ и состав 43 карточек; убраны взаимоисключающие снимки, отдельно отмечены расхождения Project и приёмки. |
| 0.7 | 19.10.2026 | Подготовка #21: 20 ч, TTL/cleanup после приёмки #30; код #30 есть в PR #69, но ещё не merged. |
| 0.6 | 18.10.2026 | #20 закрыта; #30 выделена в остаток storage/rollback (8 ч); добавлено отдельное покрытие собственных примеров FT-002-04. #30 фактически переведена в Разработка / В очереди; #68 добавлена в бэклог с native parent #56. |
| 0.5 | 18.10.2026 | Актуализированы стори: US-009 выполнена, US-004 в разработке, прочие в бэклоге; неактивные задачи отложены с сохранением точки возврата. |
| 0.4 | 18.10.2026 | Настройка Project выполнена: 42 элемента, 39 native-связей, Уровень, три отдельные доски; у стори/эпиков Бэклог → Разработка → Выполнено. |
| 0.3 | 18.10.2026 | Заголовки всех 45 Issues приведены к `<ID>.<Тип>.<Заголовок>` с сохранением ID и состояний. |
| 0.2 | 18.10.2026 | Добавлена US-009 и две задачи для BT-010/011 БТ 0.2; UX связан с BT-012. |
| 0.1 | 18.10.2026 | Сохранены номера существующих задач; введены эпики, стори и связи US/ФТ. |

**Конфигурация по сохранённым настройкам 18–19.10.2026:** заголовки, метки и текстовая трассировка дополнены 40 проверенными native-связями. После добавления #68 Project 1 содержит 43 уникальных элемента: 31 задачу, 9 стори, 3 эпика; native-связей 40. Уровень заполнен; три отдельные доски сохранены. Настройка #63 подготовлена к проверке и последней командой владельца отложена в Бэклог / В очереди, Issue остаётся открытой. Существующие этапы/статусы и числовые оценки сохранены; для ранее пустых полей новых карточек заданы значения по регламенту. #1–#3 в целевой Project не переносились.

## Эпики

| ID | Issue | Состав |
| --- | --- | --- |
| EPIC-001 | [#53](https://github.com/roafo1989/guitar-mentor-ai/issues/53) | US-001, US-002, US-003, US-004, US-005, US-006, US-009 |
| EPIC-002 | [#37](https://github.com/roafo1989/guitar-mentor-ai/issues/37) | US-007 |
| EPIC-003 | [#54](https://github.com/roafo1989/guitar-mentor-ai/issues/54) | US-008 |

## Стори и задачи

| Эпик | US / Стори | ФТ | Задача |
| --- | --- | --- | --- |
| EPIC-001 | US-001 / [#55](https://github.com/roafo1989/guitar-mentor-ai/issues/55) | FT-001-01 | [#7](https://github.com/roafo1989/guitar-mentor-ai/issues/7) — Профиль и предпочтения через REST |
| EPIC-001 | US-001 / [#55](https://github.com/roafo1989/guitar-mentor-ai/issues/55) | FT-001-02 | [#9](https://github.com/roafo1989/guitar-mentor-ai/issues/9) — Анкета профиля в веб-интерфейсе |
| EPIC-001 | US-001 / [#55](https://github.com/roafo1989/guitar-mentor-ai/issues/55) | FT-001-03 | [#11](https://github.com/roafo1989/guitar-mentor-ai/issues/11) — Опыт, репертуар и цели |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-01 | [#29](https://github.com/roafo1989/guitar-mentor-ai/issues/29) — Диагностическая сессия и критерии |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-02 | [#16](https://github.com/roafo1989/guitar-mentor-ai/issues/16) — Карта навыков и правила подбора |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-03 | [#17](https://github.com/roafo1989/guitar-mentor-ai/issues/17) — историческая (заменена, not_planned); активный остаток = 0 ч |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-03 | [#76](https://github.com/roafo1989/guitar-mentor-ai/issues/76) — Проверка GM-EB01 (3 ч) |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-03 | [#77](https://github.com/roafo1989/guitar-mentor-ai/issues/77) — Этюд GM-EB02 (5 ч) |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-03 | [#78](https://github.com/roafo1989/guitar-mentor-ai/issues/78) — Этюд GM-EB03 (5 ч) |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-03 | [#79](https://github.com/roafo1989/guitar-mentor-ai/issues/79) — Этюд GM-EB04 (5 ч) |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-03 | [#80](https://github.com/roafo1989/guitar-mentor-ai/issues/80) — Этюд GM-EB05 (5 ч) |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-03 | [#81](https://github.com/roafo1989/guitar-mentor-ai/issues/81) — PDF/MP3 GM-EB02/03 (4 ч) |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-03 | [#82](https://github.com/roafo1989/guitar-mentor-ai/issues/82) — PDF/MP3 GM-EB04/05 (4 ч) |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-03 | [#83](https://github.com/roafo1989/guitar-mentor-ai/issues/83) — Права и media pack (3 ч) |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-03 | [#84](https://github.com/roafo1989/guitar-mentor-ai/issues/84) — Java/PostgreSQL/REST (6 ч) |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-03 | [#85](https://github.com/roafo1989/guitar-mentor-ai/issues/85) — Vue цели и критерии (4 ч) |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-03 | [#86](https://github.com/roafo1989/guitar-mentor-ai/issues/86) — Звуковой движок метронома (4 ч) |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-03 | [#87](https://github.com/roafo1989/guitar-mentor-ai/issues/87) — Vue метроном (3 ч) |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-03 | [#88](https://github.com/roafo1989/guitar-mentor-ai/issues/88) — Интеграционные проверки (4 ч) |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-03 | [#89](https://github.com/roafo1989/guitar-mentor-ai/issues/89) — Музыкальная приёмка и документация (5 ч) |
| EPIC-001 | US-003 / [#57](https://github.com/roafo1989/guitar-mentor-ai/issues/57) | FT-003-01 | [#18](https://github.com/roafo1989/guitar-mentor-ai/issues/18) — Попытки, оценки и решение ученика |
| EPIC-001 | US-003 / [#57](https://github.com/roafo1989/guitar-mentor-ai/issues/57) | FT-003-02 | [#19](https://github.com/roafo1989/guitar-mentor-ai/issues/19) — Экран задания, результата и переходов |
| EPIC-001 | US-003 / [#57](https://github.com/roafo1989/guitar-mentor-ai/issues/57) | FT-003-03 | [#24](https://github.com/roafo1989/guitar-mentor-ai/issues/24) — План занятий по истории и целям |
| EPIC-001 | US-003 / [#57](https://github.com/roafo1989/guitar-mentor-ai/issues/57) | FT-003-04 | [#51](https://github.com/roafo1989/guitar-mentor-ai/issues/51) — Удобный сквозной сценарий и лаконичный интерфейс |
| EPIC-001 | US-004 / [#58](https://github.com/roafo1989/guitar-mentor-ai/issues/58) | FT-004-01 | [#20](https://github.com/roafo1989/guitar-mentor-ai/issues/20) — Запись, загрузка и обработка попытки |
| EPIC-001 | US-004 / [#58](https://github.com/roafo1989/guitar-mentor-ai/issues/58) | FT-004-02 | [#30](https://github.com/roafo1989/guitar-mentor-ai/issues/30) — Единый контракт хранения аудио и безопасное удаление |
| EPIC-001 | US-004 / [#58](https://github.com/roafo1989/guitar-mentor-ai/issues/58) | FT-004-03 | [#21](https://github.com/roafo1989/guitar-mentor-ai/issues/21) — Политика хранения и безопасная очистка |
| EPIC-001 | US-005 / [#59](https://github.com/roafo1989/guitar-mentor-ai/issues/59) | FT-005-01 | [#14](https://github.com/roafo1989/guitar-mentor-ai/issues/14) — Тестовый набор и методика аудиопроверки |
| EPIC-001 | US-005 / [#59](https://github.com/roafo1989/guitar-mentor-ai/issues/59) | FT-005-02 | [#15](https://github.com/roafo1989/guitar-mentor-ai/issues/15) — Измерительный аудио PoC |
| EPIC-001 | US-005 / [#59](https://github.com/roafo1989/guitar-mentor-ai/issues/59) | FT-005-03 | [#31](https://github.com/roafo1989/guitar-mentor-ai/issues/31) — Сводное решение о применимости анализа |
| EPIC-001 | US-005 / [#59](https://github.com/roafo1989/guitar-mentor-ai/issues/59) | FT-005-04 | [#22](https://github.com/roafo1989/guitar-mentor-ai/issues/22) — Оценка валидированных параметров |
| EPIC-001 | US-005 / [#59](https://github.com/roafo1989/guitar-mentor-ai/issues/59) | FT-005-05 | [#23](https://github.com/roafo1989/guitar-mentor-ai/issues/23) — Обратная связь по проверенным данным |
| EPIC-001 | US-006 / [#60](https://github.com/roafo1989/guitar-mentor-ai/issues/60) | FT-006-01 | [#25](https://github.com/roafo1989/guitar-mentor-ai/issues/25) — Сквозная проверка безопасности и приватности |
| EPIC-001 | US-006 / [#60](https://github.com/roafo1989/guitar-mentor-ai/issues/60) | FT-006-02 | [#26](https://github.com/roafo1989/guitar-mentor-ai/issues/26) — Локальный запуск и восстановление |
| EPIC-001 | US-006 / [#60](https://github.com/roafo1989/guitar-mentor-ai/issues/60) | FT-006-03 | [#27](https://github.com/roafo1989/guitar-mentor-ai/issues/27) — Пилот и приёмка версии |
| EPIC-002 | US-007 / [#61](https://github.com/roafo1989/guitar-mentor-ai/issues/61) | FT-007-01 | [#38](https://github.com/roafo1989/guitar-mentor-ai/issues/38) — Аутентификация, сессии и объектная авторизация |
| EPIC-002 | US-007 / [#61](https://github.com/roafo1989/guitar-mentor-ai/issues/61) | FT-007-02 | [#42](https://github.com/roafo1989/guitar-mentor-ai/issues/42) — Приглашения и управление доступом |
| EPIC-002 | US-007 / [#61](https://github.com/roafo1989/guitar-mentor-ai/issues/61) | FT-007-03 | [#39](https://github.com/roafo1989/guitar-mentor-ai/issues/39) — Развёртывание с DNS и HTTPS |
| EPIC-002 | US-007 / [#61](https://github.com/roafo1989/guitar-mentor-ai/issues/61) | FT-007-04 | [#40](https://github.com/roafo1989/guitar-mentor-ai/issues/40) — Эксплуатационная готовность и решение о публикации |
| EPIC-003 | US-008 / [#62](https://github.com/roafo1989/guitar-mentor-ai/issues/62) | FT-008-01 | [#13](https://github.com/roafo1989/guitar-mentor-ai/issues/13) — Границы MVP и критерии готовности |
| EPIC-003 | US-008 / [#62](https://github.com/roafo1989/guitar-mentor-ai/issues/62) | FT-008-02 | [#35](https://github.com/roafo1989/guitar-mentor-ai/issues/35) — Базовые поля и этапы Project |
| EPIC-003 | US-008 / [#62](https://github.com/roafo1989/guitar-mentor-ai/issues/62) | FT-008-03 | [#63](https://github.com/roafo1989/guitar-mentor-ai/issues/63) — Уровни, связи и представления Project |
| EPIC-001 | US-002 / [#56](https://github.com/roafo1989/guitar-mentor-ai/issues/56) | FT-002-04 | [#68](https://github.com/roafo1989/guitar-mentor-ai/issues/68) — Собственные примеры и ноты до назначения |
| EPIC-001 | US-009 / [#65](https://github.com/roafo1989/guitar-mentor-ai/issues/65) | FT-009-01 | [#66](https://github.com/roafo1989/guitar-mentor-ai/issues/66) — Главная страница |
| EPIC-001 | US-009 / [#65](https://github.com/roafo1989/guitar-mentor-ai/issues/65) | FT-009-02 | [#67](https://github.com/roafo1989/guitar-mentor-ai/issues/67) — Возврат по логотипу |

## Представления и связи

| Представление | Фильтр | Колонки | Карточек |
| --- | --- | --- | --- |
| [Задачи](https://github.com/users/roafo1989/projects/1/views/3) | Уровень:Задача | Бэклог / Анализ / Оценка / Разработка / Тестирование / Завершение | 31 |
| [Стори](https://github.com/users/roafo1989/projects/1/views/4) | Уровень:Стори | Бэклог / Разработка / Выполнено | 9 |
| [Эпики](https://github.com/users/roafo1989/projects/1/views/5) | Уровень:Эпик | Бэклог / Разработка / Выполнено | 3 |
| [Иерархия](https://github.com/users/roafo1989/projects/1/views/2) | Без сохранённого фильтра | Parent issue / Sub-issues progress | 43 |

Всего 40 native-связей: 9 эпик → стори, 31 стори → задача. Часы учитываются только у конечных задач; родители показывают прогресс. Для чтения всех задач в свёрнутой таблице можно временно применить Уровень:Задача; это не повод создавать отсутствующую на экране карточку повторно.

#63 технически настроена и отложена к проверке/приёмке: Бэклог / В очереди, Issue open. Повторять создание полей/карточек/родителей не требуется. Названия имеют формат `<ID>.<Тип>.<Заголовок>`; номера и ID сохраняются.

## Состояние и расхождения

[Снимок на 19.10.2026](current-state.md) заменяет прежние противоречащие друг другу записи «текущая #20» и «#30 ещё не начата». #20 принята и закрыта; #30 ждёт локальной приёмки; #21 подготовлена и заблокирована #30. Новая разработка не начата.

На живой доске #20 ещё в Тестирование / Выполнено (по приёмке требуется Завершение / Выполнено), #30 в Тестирование / В очереди (по отчёту ждёт владельца). US-001/#55 показана Выполнено при открытой стори и неподтверждённой полной ручной приёмке #11. Изменение документа не является изменением этих полей или новой приёмкой. Подробности и источники — в снимке.

## Исторический бэклог

#1/#2/#3 и PR #4/#5 относятся к прежнему Python/Telegram/SQLite эксперименту. Они остаются вне целевого Java-Project; их история и состояния сохраняются. Не создавать для них фиктивную US и не переносить старую архитектуру в основной backend.

## Настройка по адаптированному стандарту — 19.10.2026

[Протокол фактических изменений](board-standard-migration-2026-10-19.md): Код-ревью перед Тестированием, Status На обсуждении, отдельный Профиль спецификации. #20 — Завершение / Выполнено; #30 — Тестирование / Ожидание, Full; #21 — Разработка / Ожидание, Full; #55 — Бэклог / В очереди до подтверждения оставшейся приёмки. Старые снимки выше исторические. Native-иерархия 43 элементов / 40 связей не менялась.

## Следующий анализ — #14, 19.10.2026

По поручению владельца выбрана #14 / FT-005-01 в US-005 #59: закрыть входные условия аудиоэксперимента, затем #15 → #31 → #22/#23. Реализованное #20/#30 не повторяется; #21 остаётся ранее выбранной разработкой. [Спецификация Full](../specifications/MVP-R2-01-audio-feasibility.md), компонентная и sequence-схемы подготовлены. Переходы #14: Бэклог / В очереди → Анализ / В работе → Оценка / В очереди. Новая оценка не выполнена; 15 ч — прежний ориентир. Родитель #59 возобновляется в Разработке как текущий цикл работы, без заявления о старте кода; остальные дочерние задачи остаются в бэклоге.

Передача на оценку не равна наличию размеченного REAL-корпуса или музыкального PoC. #30 к этому моменту принята/closed, PR #69/#70 в main c521786; старые снимки ожидания выше исторические. Иерархия и номера карточек не меняются.


## Дополнение 23.10.2026 — CORE-01

ТЗ0.18, 10US/41FT. [Анализ и зависимости](../specifications/CORE-01-autonomous-development.md). Исходные датированные количества выше — история, не текущий размер бэклога.

| Эпик | Стори | ФТ | Задача |
| --- | --- | --- | --- |
| #53 | #57 / US-003 | FT-003-08 | #125 Java: ограниченная история попыток |
| #53 | #57 / US-003 | FT-003-09 | #126 Vue: постраничная история и выбранная оценка |
| #53 | #57 / US-003 | FT-003-10 | #127 Vue: безопасные ошибки и повтор |

Готовность:125/127 Оценка/Выполнено,126 Оценка/Ожидание зависимости125/итоговой51. Подготовка не запускает код и не является завершением стори.


## Текущий аудит24.10.2026

[Доказательства и все статусы](board-audit-2026-10-24.md). Project:63 задачи/10 стори/3 эпика,73 native-ребра. Добавлены отсутствовавшие native parents:98/111→57,100/101→56. Числа ранних снимков выше — история. US-007/#61 теперь «Аутентификация, авторизация и доступ по приглашению»:38/42 один блок,39/40 условия безопасной поставки; ID FT-007-01…04 сохранены. Нет новой дублирующей стори.


## Возобновление US-007/#61 —24.10.2026

Поручение владельца начать61:38/42/39/40 Анализ/В очереди;61/37 Разработка/В работе как активный цикл анализа. Native-связи сохранены (четыре задачи→61→37), новые карточки/часы не создаются. Профиль всех четырёх Full; прежние часы предварительные и пересматриваются после анализа. [Актуальный объём, вопросы и порядок38→42→39→40](../specifications/US-007-access-analysis-2026-10-24.md), ТЗ0.20.
