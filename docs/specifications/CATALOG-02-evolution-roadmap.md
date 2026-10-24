# CATALOG-02 — этапы общего каталога и подготовки материалов

Версия 1.0 · 24.10.2026 · Full-постановки для **последующего** анализа, не готовые контракты реализации. БТ 0.18/BT-021…025 → ТЗ 0.25/US-012…015 → EPIC-004/#154. Оценки и экспериментальные исследования не выполнялись. Первичная потребность P0 полностью выделена в [CATALOG-01](CATALOG-01-private-library.md), #155/#160–164. Это новая область, не расширение старых #84/#97/#15/#22 без новой оценки.

## 1. Очередность и границы согласования

| Шаг | Стори | Результат | Gate до разработки |
| --- | --- | --- | --- |
| P0 |US-011/#155| Личная карточка, загрузка готового PDF/GP, список/скачивание/delete | Анализ подготовлен, следующая оценка/команда владельца; не ждать шаги ниже |
| P1 |US-012/#156| Явная публикация/withdraw, общий каталог active accounts, поиск/type/tags/hasAudio, PDF/photo/audio | P0 и Full-решения о publication revisions, moderation/rights, безопасном preview и upload formats |
| P2 |US-013/#157| PDF нот/TAB по эталону + synth audio из GP | Исследование 169 на ограниченных fixtures, выбранный converter/licenses/resource caps и GO |
| P3 |US-014/#158| Внешний поиск по названию/автору, источники и допустимый импорт в private draft | Внутренний поиск, conversion capability, provider/budget/SSRF и provenance contracts |
| P4 |US-015/#159| Ноты/TAB из файла/ссылки: сначала solo fingerstyle arrangement, затем original-part transcription | Исследование 173/GO/ограничения качества/стоимости и приватности; для второго режима отдельные fidelity checks |

Приоритет Б из БТ относится **к двум режимам BT-025**. Он не ставит ИИ раньше простой загрузки. Режим по умолчанию не выбран: UI должен требовать явный выбор/объяснить режим, а не скрытно переопределять музыкальный результат. Никакой обязательной работы владельца руками в каждом pipeline не предполагается; участие нужно только для продуктовых/музыкальных решений, которые нельзя доказать автоматически. Не покупать сервисы и не отправлять настоящие private originals провайдерам этой аналитикой.

## 2. US-012 — общий пользовательский каталог (#165–168)

**FT-012-01:** owner публикует конкретную неизменяемую revision с выбранными assets. Publication имеет собственные id, owner, sourceRevision, publishedAt, state PUBLISHED/WITHDRAWN/HIDDEN, provenance и asset-level основания распространения. Музыкальный reviewStatus и assignmentEligible — отдельные поля/решения; по умолчанию UNREVIEWED/false. БТ выбрали explicit publish без обязательной предварительной admin-проверки. Не вводить такой gate молча: если оценка рисков требует ограничения, подготовить отдельное решение до кода. Обычный admitted пользователь читает опубликованные, владелец меняет только своё; операторский hide/report процесс, аудит и уведомления уточнить в Full-анализе. Непроверенный личный оригинал не перераспространяется автоматически.

Не смешивать с MUSIC-CATALOG-01: approved curated educational catalog остаётся отдельным контролируемым ресурсом `exercises`; user-community publication не является APPROVED_FOR_CATALOG/AI_PASS. Новая публикация сама по себе не создаёт assignment или подтверждённый навык. Политика отклика на нарушения/withdrawal и право на каждый score/arrangement/audio требуют конкретного контракта; наличие источника в интернете не трактовать как разрешение на копирование. Автоматических юридических заключений/платежей/обхода источников нет.

**FT-012-02:** published list/detail по active actor. Предлагаемые API для анализа: GET `/api/v1/catalog/materials` с q/type/tags/hasAudio/limit/cursor; GET detail; POST publish/withdraw — под owner-only library route. q ищет title/author, не содержимое private PDFs. Тип — EXERCISE/ETUDE/COMPOSITION/OTHER; техника, жанр, культурная категория и свободные tags не должны оказаться одним enum. До кода выбрать vocabulary/ограничения/нормализацию и точные AND/OR (предложение: AND между фильтрами, AND выбранных тегов), возвращаемый sort/cursor и индексы; предложенные правила не выдаются за уже выбранные заказчиком. Counts/search suggestions не раскрывают private/withdrawn материалы. hasAudio=true только при доступном опубликованном audio asset, не по полю URL.

**FT-012-03:** фото JPEG/PNG показываются без OCR; добавленный пользователем audio — явный ORIGINAL_RECORDING или SYNTHESIZED_PREVIEW с источником и revision. Список codecs, размеры/длительность и безопасный image/audio processing reuse из 20/94 анализируются отдельно, не переносится 120sec attempt limit автоматически. Необязательность аудио — согласованное правило; можно publish без него, добавить позже новой revision. PDF, photo и GP-original неизменны; derived sanitized preview и filenames/metadata original разделены. Встроенное отображение private/user-hosted original требует дополнительной безопасной архитектуры (изоляция viewer/active content, no credentials leak, hard limits); скачивание P0 работает независимо от preview.

**FT-012-04:** вкладки Мои/Общие, явные publish/withdraw, фильтры и результат, media fallback, UNREVIEWED badge, RU/EN/keyboard/mobile/200%zoom. Смена selection/revision останавливает прежний плеер и игнорирует старые async-ответы; no autoplay. Поздний поиск/пустая выдача/снятая публикация отображаются честно. Framework/layout согласовать с утверждённым дизайном без дублирования дизайнерской ветки.

**Versioning/removal:** editing draft не меняет опубликованный snapshot. Withdrawal сразу закрывает server downloads для других accounts и исключает list/search; shared cache/public bucket запрещены. Уже скачанные локальные копии отозвать невозможно — UI не обещает этого. Будущие pinned copies в учебных assignments, если появятся, потребуют самостоятельного права/retention решения, а не обхода withdrawn-прав. На первом этапе user-community не назначается автоматически, поэтому current diagnostic FK не затрагивается.

Приёмка AC-COM-01…06: private isolation; explicit publish без audio; stable filters; media formats/fallback; unpublish directURL; publication vs music approval/role controls. Матрица связывает с AC-021-01/02, AC-022-01/02/03/05 и AC-023-01…03.

## 3. US-013 — детерминированная конвертация GP (#169–170)

**169, research:** upstream Guitar Pro и TuxGuitar перечисляют .gtp/.gp3/.gp4/.gp5/.gpx/.gp, но это не доказывает поддержку конкретной версии/эффектов нашим сервером. Проверить Java-oriented importer/exporter/headless-подход, immutable pinned dependencies/лицензии и font/audio assets. Не внедрять платный Guitar Pro GUI на сервере без подтверждённого механизма/прав. Кандидат TuxGuitar не выбран автоматически.

Fixtures: минимум валидные образцы заявляемых форматов; corrupt/oversize/passwordlocked; разные размеры (включая 3/4,3/8,6/8), повтор/паузы/полиголосие, строй/каподастр, несколько дорожек, одногитарный голос/выбор track, сложная аппликатура и неприятные/неподдержанные эффекты. Сверить нотную **октаву** гитарной записи с TAB/звучанием, не рисовать MIDI высоту на стане напрямую без нужной трансформации; исходная ритмика/повторы/голоса/фреты сохраняются. MUS-NOTATION-01 требует ноты сверху/TAB снизу и 4 полных такта/строку; не «почти похожая» export screenshot. Отдельно слуховой и структурный контроль audio из того же score. Выход: format/feature matrix PASS/FAIL/UNSUPPORTED, команды/версии, ограничения CPU/RAM/timeout/стоимости и GO/NO-GO.

**170, implementation после GO:** persisted ConversionJob (inputAsset/revision/kind/toolVersion/status, bounded attempts/cost), worker с readonly input/outdir, без сети и shell-interpolation, quota/reservation и hard time/CPU/memory/disk limits. Возможный multi-track ambiguity даёт понятный выбор, не первую случайную дорожку. PDF и audio derivatives сохраняются под новым revision; original не заменяется. Статусы QUEUED/RUNNING/READY/PARTIAL/FAILED/CANCELLED, transient retry bounded и idempotent, dead worker lease/recovery. PARTIAL не READY комплекта. Cancel не удаляет original; повтор по новой toolVersion не переписывает опубликованную редакцию. UI показывает источник/что создано/что не удалось и ручное добавление разрешённых файлов.

Приёмка AC-CONV-01…05: declared formats+honest unsupported; version-matched PDF/TAB/audio; sandbox/cancel/retry; original survival/quotas; private draft/no autopublish. FT-013-02 требует дополнительной декомпозиции Java/job/Vue по результатам 169, нынешняя постановка не готовый необусловленный бюджет.

## 4. US-014 — внешний поиск и подготовка (#171–172)

**171, research/contract:** provider APIs/доступные источники/разрешённый download vs reference-only, source terms, budgets/rate/concurrency, actual availability of score/arrangement. Pipeline сначала ищет точный existing note source; аудиорасшифровка — отдельный BT-025, не скрытый fallback. Реальные источники/provenance включают URL, title/creator, fetchedAt, selectedWork/arrangement, asset hashes и allowed-use evidence. Нельзя генерировать библиографию/URL вместо найденного материала. При существенной неоднозначности названия/аранжировки спросить выбор у пользователя; иначе продолжать автоматически. Ссылка на страницу не файл; paywall/login/403/notfound показываются, ограничения не обходятся.

**172, implementation:** user запускает поиск из empty internal catalog → results/dedup/candidate identification → user choice only when needed → разрешённый fetch/adapters → готовый original или конвертация 013 → private draft. Ветка ссылок без доступных bytes остаётся LINK_ONLY/INCOMPLETE, не файл с фиктивным успехом. Результат успешного полного комплекта содержит PDF+audio однойредакции; исходник только PDF без audio не объявляется автоматическимполным комплектом. Publication отдельное действие и основание распространения отдельно от права личного чтения.

Untrusted pages/LLM outputs не команды. Fetcher использует HTTPS allowlist-provider adapters, запрещает credentials/fragments для serverrequests, private/loopback/link-local/metadataIP и file/gopher/smb/ftp; проверяет A/AAAA и destination при соединении, каждый redirect и response size/type/time, исключает DNS rebinding и отправку session cookies. Egress sandbox отдельно от appDB/FS. Точный provider/allowlist и лимиты обязательны до кода; universalURL downloader недопустим. Query содержит только введённые title/author, приватные файлы/почту/профиль не пересылает скрытно. Не создавать realaccounts/платежи во время анализа.

Приёмка AC-WEB-01…06: internal→external explicit; realprovenance/ambiguity; lawful fetch/linkonly; SSRF/prompt injection/budget; actualPDF/audio vspartial; private-result/no auto-publish. Provider outage/timeout/429/cancel имеют bounded поведение. Ввести расходы по job/account/day и кнопку остановки до production; численные значения — результат research171, не выдуманная оценка.

## 5. US-015 — аудио → аранжировка/транскрипция (#173–175)

Первый пользовательский режим — **ARRANGEMENT**: для одной гитары мелодический fingerstyle без требования медиатора/перкуссии, мелодия+бас+сопровождение. Второй — **TRANSCRIPTION**: слышимая гитарная партия. Самостоятельная аранжировка не выдаётся за точную расшифровку. Режим хранится с входом, source, результатом, model/toolVersion; две версии не генерируются насильно на каждуюзаявку. Режим по умолчанию не назначен.

**173, исследование:** ограниченный корпус законных sourceaudio+известных нот/партий, моно/полифония/мультиинструментальные записи, разные темпы/размеры/качество и безопасные ссылки. Сравнить достижимость/инструменты/Java integration/стоимость/приватность; нет заранее выбранного DSP/LLM/SaaS и обещания обработки любой песни. Рубрика отдельно: сохранение узнаваемой мелодии/гармонии, устойчивые voices/bass/phrasing, fingerstyle playability, ритмические ошибки/uncertainsegments, fidelity транскрипции и музыкальное качество аранжировки. Вывести количественные пороги из фактического baseline и утвердить применимый ограниченный scope, а не поставить произвольный accuracy процент. Исследование может завершиться NO-GO и предложением linkonly/manualsource, а не фиктивной готовойпартитурой.

**174, после GO:** поддерживаемый файл или разрешённаяссылка → изолированная обработка с квотой/стоимостью → candidate score/events → PDF нот/TAB+синтезированныйпример → private unverifieddraft с uncertainty. Исходная аудиодорожка и synthesisedoutput имеют разные явные роли. Нельзя назвать фреты восстановленной исходнойаппликатурой, если это только вычисленныйиграбельныйвариант. Требования под уровень ученика/степеньупрощения/редактор не согласованы — закрыть на анализе, не спрашивать владельца о выборе модели. Нет автоматического музыкального PASS/assignment/publish.

**175, второйприоритет:** отдельный benchmark и режим TRANSCRIPTION, не переименовать output174; error/fidelity по известной партии, instrumentselection/overlap/ambiguity. Сохранить обеопции и историю. Условия включения определяются 173 и full анализом 175.

Политика incomingaudio/sourcefetch/downloadtemp отдельно от attempt48h и persistentlibrary: privateinputs, purpose/consent/ограничение provider передачи, длительность/размер/TTL удаления после job, отмена/retention derivatives, budget согласуются до кода. Публичные аудиолинки не обещают download; не обходить источник. Не вводить OCR фото в эту стори.

Приёмка AC-AUD-01…07: bothinputs/honestavailability; explicitmode; one-guitararrangement; transcriptionfidelity separately; score/TAB/audio format+uncertainty; budgets/SSRF/privacy/cancel; nogeneratedfake-success/nopublication. Все NOT_RUN.

## 6. Открытые решения и готовность

| ID | Решение и кто закрывает | Что блокирует |
| --- | --- | --- |
| CAT-Q01 | SA/разработчик уточняют словари/tags/поиск и moderation/takedown/access revisions с продуктовым решением только при спорном правиле | US012, не P0 |
| CAT-Q02 | SA/разработчик подтверждают GPconverter+лицензии+форматы+resource limits на fixtures |169→170, не storageP0 |
| CAT-Q03 | SA выбирает search/provider иразрешённыеадаптеры, бюджет/источники/условиядля asset |171→172 |
| CAT-Q04 | Research173 выводит ограниченный source/quality scope/cost, owner утверждает GO музыкальныйрезультат |174/175 |
| CAT-Q05 | До jobrelease определяются sourceaudioTTL, внешняяпередача/consent/ключи/стопрасходов | US014/015 |
| CAT-Q06 | Включение user-community в exercises/назначения и удаление ужеиспользованныхкопий — отдельныйвыбор, сейчас вне объёма | futureintegration, не catalogupload |

Открытые businessinterview BT017/018 не разрешаются этой декомпозицией. Старые musicaltasks76–89/неудачные GM-EB02 не закрываются/не переписываются. Текущая авторизация #141 и дизайнерская работа продолжаются своим циклом. Часы никому не назначены: P0 готов к следующей оценке, остальное — backlog с gates. После research создать меньшие инкременты, не считать pipeline на 40 часов одной автономной задачей.

## 7. Источники

БТ0.17/разделы12–13, решение24.10 оP0; [MUS-NOTATION-01](MUS-NOTATION-01-score-tab-standard.md); main `docs/specifications/MUSIC-CATALOG-01-curated-first.md` (согласование смысла проверенного educationalcatalog). [Guitar Pro](https://www.guitar-pro.com/c/14-guitar-pro-features), [TuxGuitar](https://www.tuxguitar.app/files/devel/desktop/help/file_formats.html) — кандидаты/форматы. [OWASP File Upload](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html), [OWASP SSRF](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html) — первичные технические источники; провайдерыAI ещё не выбраны.

## 8. Реестр критериев последующих стори

| ID | Проверяемое поведение | Связанные задачи |
| --- | --- | --- |
| AC-COM-01 | Другой аккаунт не видит личный черновик и файлы, включая прямые ссылки |165/168 |
| AC-COM-02 | Явная публикация открывает выбранную редакцию допущенным пользователям; отсутствие аудио не блокирует действие |165/167/168 |
| AC-COM-03 | Поиск, тип, теги, hasAudio и их сочетания работают по опубликованным данным, сброс и пустой результат понятны |166/168 |
| AC-COM-04 | PDF/фото показываются, доступное аудио воспроизводится; нет ложного OCR или синтеза, роли источников различаются |167/168 |
| AC-COM-05 | Снятие публикации закрывает прямую выдачу и поиск, не меняя приватный оригинал и прежние учебные задания |165/166/168 |
| AC-COM-06 | Публикация не делает материал музыкально проверенным или пригодным для автоматического назначения |165–168 |
| AC-CONV-01 | Матрица форматов и особенностей подтверждена на воспроизводимых примерах; неподдержанное обозначено |169/170 |
| AC-CONV-02 | PDF нот/TAB по эталону и аудио относятся к одной редакции; октава, голоса, повторы и темп проверены |169/170 |
| AC-CONV-03 | Ограничения worker, timeout, отмена и повторы проверены без исполнения посторонних команд и сетевого доступа |170 |
| AC-CONV-04 | Оригинал и его контрольная сумма сохраняются при любой ошибке, затраты и место ограничены |170 |
| AC-CONV-05 | Производные остаются личным черновиком, частичный результат не обозначен полным, публикация отдельна |170 |
| AC-WEB-01 | После внутреннего поиска пользователь явно запускает внешний по названию/автору |171/172 |
| AC-WEB-02 | Источники реальные; существенная неоднозначность требует выбора произведения/аранжировки |171/172 |
| AC-WEB-03 | Доступный разрешённый исходник импортируется; ссылка без файла и ограничения доступа обозначены честно |171/172 |
| AC-WEB-04 | SSRF, инструкции из страниц и превышение бюджета блокируются; отмена/повтор ограничены |171/172 |
| AC-WEB-05 | Готовый комплект содержит фактические PDF и аудио одной редакции, ошибка не создаёт фиктивный успех |172 |
| AC-WEB-06 | Результат сохраняется личным черновиком с происхождением, не публикуется автоматически |172 |
| AC-AUD-01 | Допустимый файл или доступная поддерживаемая ссылка принимаются; невозможность получить звук объяснена |173–175 |
| AC-AUD-02 | Оба режима выбираются явно и сохраняются с результатом, скрытой подмены нет |173–175 |
| AC-AUD-03 | Аранжировка предназначена для одной гитары: мелодия, бас и сопровождение; она не названа точной транскрипцией |173/174 |
| AC-AUD-04 | Верность исходной партии оценивается отдельно от качества новой аранжировки; аппликатура не объявлена восстановленной без доказательства |173/175 |
| AC-AUD-05 | Ноты/TAB и синтезированный пример согласованы; оригинал звука и непроверенные фрагменты явно выделены |173–175 |
| AC-AUD-06 | Приватность, стоимость, сроки хранения, ошибки получения и отмена имеют проверенные ограничения |173–175 |
| AC-AUD-07 | Ошибка не создаёт выдуманную партитуру или музыкальный PASS; результат не публикуется автоматически |174/175 |

Все критерии выше — NOT_RUN для будущей реализации. Исследовательская постановка не означает, что требуемое качество уже достижимо.
