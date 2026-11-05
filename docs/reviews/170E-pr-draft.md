# 170-E: очередь конвертации Guitar Pro и проверка изоляции

[PR #267](https://github.com/roafo1989/guitar-mentor-ai/pull/267), #266 / #170. До этой доработки durable jobs не вызывали renderer; теперь Java consumer проводит задание через claim, heartbeat, выбор дорожки, отмену/повтор и атомарное сохранение приватной пары PDF + WAV. Оригинал остаётся неизменным; поддерживаемый GP3/4/5 subset не расширяется.

Устранены повторное резервирование после RELEASED, расход попытки на discovery, несогласованные лимиты output, cidfile/cleanup races и уменьшение PDF-систем при пагинации. Временные файлы renderer ограничены одним tmpfs; результаты поступают через протокол с ограниченными длинами. Container deadline действует независимо от Java. Добавлены RU/EN ошибки, corpus, fault/queue tests, CI и Windows runbook.

На `e1231233` все четыре CI PASS: 77/77 container corpus, 25/25 conversion tests, Java 427 PASS / 5 skipped, Vue и CATALOG-169. Добавлен настоящий браузерный сценарий Chromium → authenticated HTTP → scheduler → Docker → private downloads; его результат ожидает CI. Проверки и ссылки: [матрица](../testing/TC-170E-conversion-worker.md).

Windows Docker Desktop и human PDF/audio acceptance остаются NOT_RUN. Feature flag OFF. Issue остаётся открытой; слияние и rollout требуют приёмки владельцем.
