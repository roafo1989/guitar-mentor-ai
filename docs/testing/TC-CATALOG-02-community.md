# TC-CATALOG-02 — публикация и общий каталог

Версия1.0 ·24.10.2026·[CATALOG-03](../specifications/CATALOG-03-community-catalog.md), AC-COM-01…06; #165–168. Это план, а не отчёт о продуктовых тестах. Для всех строк фактический результат **NOT_RUN**. Стенд будущего исполнения: PostgreSQL послеP0/новыхмиграций, Java/VuecandidateSHA, admitted accountsA/B, disabledC, OWNERD, синтетические исходники с лицензией/hash. Автор кейсов не независимый reviewer реализации.

| ID | Требования/задачи | Предусловие → действия → ожидаемый результат |
| --- | --- | --- |
| TC-COM-01 | AC-COM-01,165 | A создал private card → B запросил list/detail/direct file по UUID → private отсутствует,404; OWNER D также не получает private bytes |
| TC-COM-02 | AC-COM-02,165/168 | A загрузил READY PDF без audio → выбрал asset и основание, Publish → snapshot виден B, UNREVIEWED/assignmentEligible=false, исходный draft private |
| TC-COM-03 | AC-COM-02,165/168 | Нет asset rights → Publish →400; UI не считает запрос успешным, файлы не раскрыты |
| TC-COM-04 | AC-COM-02,165 | Успешный publish, потерян response → replay с тем же key →200 и та же publication, один audit; другой payload409; pending409 |
| TC-COM-05 | AC-COM-02/05,165 | Опубликована revision1 → edit draft, добавить media → B видит revision1; явный replace создаёт новый ID, старый404 |
| TC-COM-06 | AC-COM-05,165/166 | B знает content URL → A withdraw → новые list/detail/GET/HEAD/Range у B404, private A доступен; cache no-store |
| TC-COM-07 | AC-COM-05/06,165 | B пытается withdraw/hide; D выполняет hide с причиной → B отказ, D успех и audit, private не открыт; replay hide не отменяет состояние |
| TC-COM-08 | AC-COM-03,166/168 | Созданы ≥45 published, private и withdrawn с разными полями → q и сочетания фильтров, reset → буквальные подстроки, AND между фильтрами/тегами; private исключены |
| TC-COM-09 | AC-COM-03,166 | Получен cursor первой страницы → новый publish и withdraw → нет дубликатов, withdrawn исключены; изменён filter/actor/tampered cursor400 |
| TC-COM-10 | AC-COM-03/04,166/167 | Есть GP/PDF only, READY audio и missing physical audio → hasAudio true/false → учитывается доступный READY audio; после reconciliation missing исключён |
| TC-COM-11 | AC-COM-04,167 | JPEG/PNG с EXIF → upload → hash original не изменён, sanitized preview без EXIF; SVG/HTML/extension mismatch отклонены |
| TC-COM-12 | AC-COM-04,167 | Oversize image/pixel bomb/truncated MP3/bogus WAV/overduration → upload →413/400/415, нет READY или утечки quota, worker bounded |
| TC-COM-13 | AC-COM-02/04,167 | Материал без audio → добавить valid MP3/WAV и role → revision+1; старый snapshot без audio; новый publish явный, source и synth подписаны |
| TC-COM-14 | AC-COM-04/05,167 | Authorized audio → GET single Range, invalid Range, HEAD →206 с верными bytes,416, HEAD без body; после withdraw любой Range404 |
| TC-COM-15 | AC-COM-04,167/168 | PDF с forms/actions/links/attachments/remote refs → canvas preview → нет execution/network/navigation/cookies; sandbox/CSP/nonce проверены в браузере |
| TC-COM-16 | AC-COM-04,167/168 | Encrypted/corrupt/>100 pages PDF или render limit → preview → fallback download exact original, нет зависания и фиктивного изображения |
| TC-COM-17 | AC-COM-04,168 | Медленные preview/search/audio → сменить selection/query или logout → старые ответы не возвращают данные, плеер остановлен, blob отозван |
| TC-COM-18 | AC-COM-02/05,165/167 | Concurrent edit/publish/withdraw/delete → проверить If-Match и atomicity →428/412/409 по контракту, нет доступных PUBLISHED файлов DELETING материала |
| TC-COM-19 | AC-COM-01/02,165/167 | DB/storage fault на каждой commit границе и restart → retry/recovery → нет partial publication, потери READY bytes, double quota/audit |
| TC-COM-20 | AC-COM-03,166 | Synthetic10k publications → EXPLAIN и20 запросов на указанном hardware → bounded query, нет N+1; фактический p95 записан, SLA не объявлена |
| TC-COM-21 | AC-COM-01…06,168 | RU/EN,360px,200% zoom, keyboard, long titles → publish/search/view/withdraw → понятны labels/focus/states, нет XSS и overflow |
| TC-COM-22 | AC-COM-01…06,165–168 | Anonymous/DISABLED/no CSRF/rate overrun → list/modify/media →401/403/429; disabled capabilities не обещает доступ |
| TC-COM-23 | AC-COM-05,165/167 | A удаляет published material →202 скрывает snapshot; worker physical delete и retry → нет доступа B и double quota; audit сохранён |
| TC-COM-24 | AC-COM-01…06,165–168 | Flags off или rollback нового binary → P0 list/download работает, схема сохранена; hidden/withdrawn не восстановлены; старые assignments/security не изменены |

При исполнении каждая строка дополняется actual result, PASS/FAIL/BLOCKED, SHA/версией/командой/HTTPилискриншотом, fixturehash. Developer tests165–167 и browser168 не заменяют независимую итоговую проверку общей US. Финальное принятие владельцем отдельно.
