# TC-UX02 — приёмка Light Minimal / US016

**Редакция1.1,25.10.2026:** актуализированы19–26 после150, добавлена [детальная матрица14TC-151+12TC-152](TC-UX02-media-results-refresh.md). Stable26TC-LM сохранены; все случаи требуют фактического исполнения на новом candidate, старый PASS150 не равен PASS151/152. Final integration включает198; её24TC отдельно. Далее первоначальный план и уточнённые строки, не отчёт выполненных проверок.

Версия1.0 ·24.10.2026·[UX-02](../specifications/UX-02-light-minimal-handoff.md), #147/#148–152. **План:26 кейсов, все NOT_RUN.** Не является независимым отчётом QA или визуальным одобрением.

Предусловия будущего прогона: candidateSHA поверх проверенного main/predecessor; frontend с light flagtrue, тот же backend/БД; active account с bare learner, account с guitar/onboarding и sessions в трёх состояниях, task с known version PDF/audio, реальные assessment fixtures с provenance. Fixture/mock данные и реальные HTTP результаты в отчёте различаются. Не добавлять настоящие частные записи/токены в Git/скриншоты.

| TC | AC / задачи | Дано → когда → ожидаемый результат |
| --- | --- | --- |
| TC-LM-01 |01,148| Light shell → прокрутить profile/diagnostics, нажать logo → белый sticky header, Home открывается, focus/контент не перекрыты |
| TC-LM-02 |01/02,148| Active user с длинным displayName →1440/1180/768/360 и200%zoom → имя доступно целиком через accessible name, tools/language/logout доступны, нет body overflow |
| TC-LM-03 |02,148| Header nav и mobile disclosure → keyboard/Tab/Escape, все links → только существующие routes, active state верен, Escape возвращает focus |
| TC-LM-04 |01/02,148| Anonymous и authenticated состояния → register/email verify/reset/Google/settings/logout pending/error → существующая141 не возвращена к приглашениям, styling единый, права неизменны |
| TC-LM-05 |01/02,148| Actual computed CSS controls/text/focus/hover/selected → измерить контраст и targets → заданные ratios выполнены; декоративная граница не единственный признак input |
| TC-LM-06 |02,148| Locale RU/EN, reduced-motion, keyboard profile/onboarding/tools → семантические labels/visible focus, нет скрытых обязательных полей или сломанного dialog trap |
| TC-LM-07 |03,149| Нет learner или bare learner без guitar → открыть Home → CTAprofile, нет преждевременного запроса instrument background/создания diagnostic |
| TC-LM-08 |03,149| Guitar с/без onboarding, нет selected session → Home → onboarding/diagnostics по resolver; нет нового home-summary endpoint/POST |
| TC-LM-09 |03,149| COLLECTING_EVIDENCE/READY_FOR_TASK/TASK_ASSIGNED ACTIVE/COMPLETED → Home → соответствующие CTA/title/code/version; completed ведёт к результату без обещания next lesson |
| TC-LM-10 |04,149| Loading/network/500/invalid response/session404 → Home/retry → ошибки не выглядят новым пользователем; только404 выбранной session удаляет её selection |
| TC-LM-11 |04,149| Slow summary accountA → сменить actor/learner/logout → поздние данныеA не появляются; private UI/selection очищаются по общему контракту |
| TC-LM-12 |05,150| Реальные3 diagnostic states, probes/repertoire/recommendation/no-match → новый layout → доступные действия/metadata/guards сохранены, assigned не единственная работающая ветвь |
| TC-LM-13 |05,150| Assigned task →1440/1100/1024/768/360, landscape и200%zoom → score>60% в desktop grid, наузком один столбец, горизонтальная прокрутка только внутри score |
| TC-LM-14 |06/07,150| Reader закрыт вручную/legacy, медленный audioHEAD → раскрыть score → PDF независимо проверяется/грузится, показывается реальнаяpage; закрытый не скачивался заранее |
| TC-LM-15 |06,150| Synthetic lawful multi-page PDF → prev/next/page input/zoom50–250/FitWidth → границы и controls верны, не меняются исходныеbytes, page/zoom видны |
| TC-LM-16 |06,150| Viewer open → fullscreen gesture/API отказ/CSS fallback/Escape → focus возвращён, task/route и submission state сохранены, нет двойного overlay trap |
| TC-LM-17 |07,150| Null/missing/corrupt/encrypted/overlimit PDF; GETfail послеHEADsuccess → open/retry → honest unavailable/error/fallback, нет вечного spinner/ложнойREADY |
| TC-LM-18 |07,150| Сменаcode@version/actor/unmount при slow fetch/render; hostile URL/actions/postMessage → старое отменено; нет latest fallback/cross-origin credentials/network из sandbox; exactversion/MusicXML доступны |
| TC-LM-19 |08,151| Один K/recorder/draft → вкладки record/file,resize,PDF/description/fullscreen/menu → File/comment/key/stream не теряются; сохранить принятый150 layout |
| TC-LM-20 |08/10,151| Есть draft → mic deny/cancel/invalid recording, затем valid replacement/upload error/retry/success → прежний File жив до valid replacement, same payload/key retry; edited payload=newkey, success очищает nativeinput/comment |
| TC-LM-21 |09,151| Inline/header/dialog:3/8 eighth100/6/8,edited accents/volumes/subdivision → record/stop-start/minimize/reopen/background → один engine/config, фазу безStop не сбросить, запись не останавливает click |
| TC-LM-22 |09,151| Recorder владеет mic → tuner; затем idle tuner→recorder → отказ tuner не прерывает запись/clock, recorder вытесняет tuner; Standard/DropD/OpenG иAuto/Manual сохранены |
| TC-LM-23 |10,151| Sample/draftplay→mic; capture pending/busy→play/Back/logo/F5/logout/switchK → players pause доmic, нет auto-resume; cancelLeave сохраняет draft, logout/late callbacks очищены |
| TC-LM-24 |11,152| Initial/error/stale/unknown; latest≠selected; PROCESSED безassessment;HUMAN_REVIEW/VALIDATED_MEASUREMENT/UNCERTAIN → честный selected result/provenance/date/limitations/banner, без фиктивных%/streak/AI-source |
| TC-LM-25 |11/12,152| Pinned attempt+historypages,COMPLETE безassessment приallowedaction,409/networkretry,COMPLETED_BY_LEARNER → frozencommand/idempotency/freshguards/history сохранены, skill не autoPASS,nextStepNOT_IMPLEMENTED |
| TC-LM-26 |01…12,148–152/198| Общий candidate после151+198 → auth/learning/media/results/score smoke иRUEN/keyboard/reflow/zoom/flagoff/rollback → фактический отчёт сSHA/fixtures-vs-physical,owner visual gate; отдельные19824TC не заменяют integration |

При исполнении дописать в каждую строку actual result, PASS/FAIL/BLOCKED, appSHA/design/docSHA, браузер/ОС/viewport/zoom, действия и доказательства. Для #148–151 developer checks включают затронутые кейсы; #152 — общий проход, не подмена ранних тестов. Необязательный hardware/safari тест нельзя выдавать за выполненный только по desktop Chrome.

Ориентир команд из main: `npm run build`, `npm test`, `npm run test:intake`, `npm run test:tuner` и адресные новые browser/component tests. Выполнять на фактическом candidate; существующий `test:browser` про историю126 не покрывает весь новый UI автоматически. После QA всё ещё требуется реальная визуальная приёмка владельца; утверждение концепции не её замена.
