# 170-E: очередь конвертации Guitar Pro и проверка изоляции

Проект PR к #266 / #170. До этой доработки durable jobs не вызывали renderer; теперь Java consumer проводит задание через claim, heartbeat, выбор дорожки, отмену/повтор и атомарное сохранение приватной пары PDF + WAV. Original остаётся неизменным; поддерживаемый GP3/4/5 subset не расширяется.

Устранены повторное резервирование после RELEASED, расход попытки на discovery, несогласованные лимиты output, cidfile/cleanup races и уменьшение PDF-систем при пагинации. Временные файлы renderer ограничены одним tmpfs; результирующие файлы поступают через протокол с ограниченными длинами. Container deadline действует независимо от Java. Добавлены RU/EN ошибки, conformance corpus, fault/queue tests, CI и Windows runbook.

Проверено локально: 77/77 conformance, 9/9 process faults, 233 Java tests PASS (186 skipped; две DB suites исключены), 247/247 frontend tests, typecheck/build. PDF просмотрен ассистентом; WAV проверен технически. Полный PostgreSQL/Docker CI пока НЕ выполнен, Windows/live-browser/human listening NOT_RUN. Подробная матрица: `docs/testing/TC-170E-conversion-worker.md`.

Флаг по умолчанию OFF. В задаче остаются эксплуатационные gates; PR не закрывает #266 автоматически. Production/PILOT rollout не входит в этот PR. Не объединять до приёмки владельцем.
