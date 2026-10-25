# TC-CAT195 — Личный импортированный архив

| Версия | Дата (МСК) | Основание |
| --- | --- | --- |
| 1.0 | 25.10.2026 | [Full195](../specifications/CAT-LOCAL-03-private-archive-ui.md), US-017/FT-017-03, main d310b344 |

Подготовлено24кейса; **все NOT_RUN**. Наличие спецификации, fixtures и старого CI194 не означает выполнение этих проверок. AC-195-01…11 покрыты полностью. Исполнитель указывает exact candidate SHA, среду, fixture/mock vs actual HTTP, наблюдение/доказательство и PASS/FAIL/BLOCKED/NOT_RUN. Самопроверка анализа не независимое review.

## Данные и стенд

Синтетические аккаунтыA/B с разными индексами; id в примерах не UUID. Fixtures: Work Yesterday, Ветер перемен, blank title/artist, literal `%`/`_`; две аранжировки одного Work с различными GP5/GPX и parserStatus; nullable/raw сложные строки; неизвестные identity/parser/rights; file0bytes иUNAVAILABLE. Для bounded DOM —1000малых Arrangement и несколько страниц files, общий JSON≤2MiB. Отдельные oversize и malformed payload. Ошибки и задержки задаются транспортным fixture без записи cookies/токенов.

На реальном стенде не изменять77kиндекс/права, не запускать import и не использовать рабочие исходники как disposable. Для read-only195 это не требуется. Проверка OWNER и второго аккаунта не означает OWNER-only продуктового ограничения: любой admitted читает **свой** индекс.

## Кейсы

| TC | AC | Действия / предусловия | Проверяемый результат | Уровень | Статус |
| --- | --- | --- | --- | --- | --- |
|TC-195-01|AC-195-01|ВойтиA; открыть account menu → Личный архив; затем войти обычным admitted B с пустым индексом|Оба видят link без role gate; route `/music-archive`; B видит только свой пустой архив. Музыкальный профиль/инструмент не prerequisite|Mounted + actual HTTP|NOT_RUN|
|TC-195-02|AC-195-01|Local prototype без actor и прямой route; затем secure mode без сеанса|Prototype не выполняет API и показывает необходимость входа; secure AuthGate закрывает данные. Нет demo/UUID/local learner fallback|Mounted + browser|NOT_RUN|
|TC-195-03|AC-195-01|Найти, выбрать Work, уйти/Back/F5; inspect storage и route|Нет q/metadata в URL/storage; после remount новый default запрос20/0; не появляются данные прошлого actor|Mounted + browser|NOT_RUN|
|TC-195-04|AC-195-02|q150/151UTF16units, emoji boundaries, pasted C0/C1 controls, whitespace; submit|150валидно;151/controls local validation без HTTP; q не режется молча; limitcodepoints не подменяет UTF16|Service + mounted|NOT_RUN|
|TC-195-05|AC-195-02|Enter/кнопка: Yesterday, Ветер, `%`,`_`, пустойq; reset; duplicate submit во время loading|Буквальные URLSearchParams; один актуальный запрос; reset defaults/offset/selection; q пустой разрешён|Service + mounted + actual HTTP|NOT_RUN|
|TC-195-06|AC-195-03|Последовательно GP/GP3/GP4/GP5/GPX/PDF и Все; inspect query/options|Точные lowercase values, `.gp` не означает family; GTP не предлагается; filter не меняет права или оригиналы|Service + mounted|NOT_RUN|
|TC-195-07|AC-195-03|PARSED/METADATA_ONLY/Все; сортировки title/artist/title_desc; редактировать форму без submit|Точные case-sensitive значения; порядок сервера сохранён; pagination disabled до применения draft, прошлые результаты не названы новой выборкой|Service + mounted|NOT_RUN|
|TC-195-08|AC-195-04|20items,nextOffset20; Next; nextOffsetnull; Previous|Список заменяется, не append; next disabled приnull, prev возвращает visitedoffset, нет total/last-page/count оценок|Service + mounted|NOT_RUN|
|TC-195-09|AC-195-04|Next получает500, retry; пока ждём изменить appliedQuery|Подтверждённый offset не продвинут ошибкой, retry тот жеsnapshot; старый retry не пишет в новый поиск|Mounted|NOT_RUN|
|TC-195-10|AC-195-04|Fixture offset200000,nextOffset200020; также fractional/negative/nonmonotonic offset в JSON|За границу запрос не отправляется, показано уточнение поиска; malformed страницы не коммитятся|Service + mounted|NOT_RUN|
|TC-195-11|AC-195-05|Найти Work фильтромGPX; выбрать его с GPX иGP5 версиями; вернуться к результатам|Один GET works/id, без N+1; обе версии видны с пояснением фильтра; query/scroll/focus сохранены при закрытии detail|Mounted + browser|NOT_RUN|
|TC-195-12|AC-195-05|1000Arrangement/несколько страницFile в одном валидномdetail; менять локальные страницы и Work|В DOM≤20rows каждого видимого списка; элементы доступны локальной пагинацией без новых API; выбор Work сбрасывает local page|Mounted + browser|NOT_RUN|
|TC-195-13|AC-195-06|ФайлыUNAVAILABLE; подмена originalAvailability=AVAILABLE и дополнительные downloadUrl/path fields|Ни кнопок/links ни fetch оригиналов. Неизвестная availability — contract error; новые поля не становятся действиями|Service + mounted|NOT_RUN|
|TC-195-14|AC-195-06|Null/blank/`"0"`/сложные tempo/timeSignatures; VERIFIED/CANDIDATE/unknown parser/rights; filenameсHTML/путём;0bytes|Fallback/исходные nullable строки/нейтральные статусы; не выдуманBPM/музыкальная проверка/право публикации. Только basename/plaintext,0bytesвалидно|Service + mounted|NOT_RUN|
|TC-195-15|AC-195-07|400list,404list,404detail,ordinary403,429/5xx/network;200empty с default/критериями|Ошибка не равнаempty; detail404не раскрывает чужойID и сохраняет список; ручной retry без скрытого поиска; подходящие RU/EN тексты|Mounted + actual HTTP|NOT_RUN|
|TC-195-16|AC-195-07|200HTML/wrong MIME/bad JSON/shape/id mismatch/duplicateids/size>2MiB; таймаут15s; callerAbort|Не показанrawbody/partialsuccess; stream останавливается поbudget даже безContent-Length, timeout отличим от staleabort; retry исправленного ответа работает|Service + mounted|NOT_RUN|
|TC-195-17|AC-195-08|Deferred searchA → searchB; завершитьA200/error/finally послеB|ТолькоBвлияет на rows/error/busy; позднийfinallyA не снимаетloadingB, AbortErrorнеtoast|Mounted|NOT_RUN|
|TC-195-18|AC-195-08|DetailX→Y, позднийX; новый поиск при detail loading; unmount с ответом в пути|Ни staleX, ни olddetail не показывается; отсутствие commits/timers послеunmount, controlsживыпосле возврата|Mounted|NOT_RUN|
|TC-195-19|AC-195-09|Aс загруженнымdetail → logout → B; затемlogout→loginA; завершитьстарые200|Мгновенная очистка при смене actor, включая новую сессию того же аккаунта; storage пуст, старые данные не появляются даже на кадр|Mounted + actual HTTP|NOT_RUN|
|TC-195-20|AC-195-09|Global installedfetch: oldA request → newB или newA object → old401/403ACCOUNT_NOT_ADMITTED|Старый ответ не очищает новый actor/CSRF/состояние; guard действует перед reset, в том числе после await error JSON|Transport auth regression|NOT_RUN|
|TC-195-21|AC-195-09|Актуальный401/403ACCOUNT_NOT_ADMITTED иordinary403; direct foreign work IDactualHTTP|Актуальная потеря сессии очищает auth/195; ordinary403 не разлогинивает глобально; чужой detail404 не содержит метаданных. Правила доступа сохранены|Auth regression + actual HTTP|NOT_RUN|
|TC-195-22|AC-195-10|Переключить RU/EN с form/detail/error, longartist/filename иunknown enums|Все UI-тексты локализованы; исходные названия не переведены; нет лишнего fetch/сброса формы; размеры и числа форматируются текущим locale|Mounted + browser|NOT_RUN|
|TC-195-23|AC-195-10|1440/1180/1024/768/360;320reflow/200%zoom; Tab/ShiftTab/Enter/Escape/reducedmotion|Нет горизонтального переполнения; labels/focus/aria-busy/alerts доступны; возврат к строке/меню работает; auth/tools не заслонены|Browser manual|NOT_RUN|
|TC-195-24|AC-195-11|Merge с актуальнымmain/163 если он интегрирован; npm scripts/bothbuilds; smoke Home/library/score/tools/auth; rollback195|Обе routes сохранены, `/library` не переписана; metadata UI не вызывает POST/DELETE/download. После frontend rollback прежние функции работают, БД и файлы не изменены|CI + browser integration|NOT_RUN|

## Исполнение и передача владельцу

Developer fixtures/service/mounted/auth regression выполняются автономно в реализации. На CI явно включить новые тесты в scripts, а не просто положить файлы. Проверить `npm test`, `test:intake`, `test:tuner`, typecheck/build default и Light Minimal, browser smoke по затронутым сценариям. Не считать tests, которые только ищут строки исходника, достаточным доказательством жизненного цикла запросов.

Реальный UAT: найти Yesterday/Ветер (если они присутствуют в собственном индексе), открыть несколько аранжировок с unavailable originals, фильтры/Next/Back, второйаккаунт/выход, мобильная компоновка и клавиатура. Если архив пользователя не доступен исполнителю, такие пункты NOT_RUN/BLOCKED с причиной, а не fake PASS. Отдельная независимая проверка и приёмка владельца остаются этапами процесса.
