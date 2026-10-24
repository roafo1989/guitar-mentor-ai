# #179 — Единый локальный запуск Google OAuth и email + реальный SMTP

Статус: реализация для пользовательской проверки. **Это режим QA на одном Windows-компьютере, а не публичный релиз.** Используются существующая PostgreSQL и два способа входа из #141. Для публичного HTTPS остаются отдельные задачи #39/#40.

## Единый origin

Рабочий адрес браузера — **https://guitar-mentor** (короткий локальный HTTPS alias). Caddy слушает только 127.0.0.1:443, Vite — 127.0.0.1:5173, Spring Boot — 127.0.0.1:8080. В `local-unified` ссылки подтверждения/восстановления ведут на https://guitar-mentor; cookies Secure/HttpOnly. `http://localhost:5173` используется **только** для временного технического callback Google и не является рабочим URL приложения.

В Google Cloud Console → Clients → Web application укажите **Authorized redirect URI**:
`http://localhost:5173/login/oauth2/code/guitar-mentor`.
В Testing включите тестируемую учётную запись в Test users. Google сам обеспечивает свои MFA-факторы. OAuth client ID и secret остаются на рабочей станции.

Не запускайте `local-smtp-qa` или `local-google` вместе с `local-unified`. Требуются ранее настроенные hosts и доверенный локальный HTTPS-сертификат Caddy. Скрипт запускает Caddy при необходимости. Не открывайте ни один порт наружу.

## Настройка реальной SMTP-доставки

Нужен почтовый провайдер с SMTP submission (обычно STARTTLS, порт 587), учётными данными и разрешённым `From`. Для своего домена настроить и подтвердить домен у провайдера и SPF/DKIM/DMARC по его инструкциям. Бесплатные лимиты, доступность SMTP и необходимость домена зависят от поставщика; приложение **не** создаёт аккаунт провайдера само. Отправляйте тесты только на адрес, которым вы владеете.

Для Windows PowerShell 7 (в корне проекта):

```powershell
pwsh -File .\scripts\local-auth-unified.ps1 -Setup
```

Введите ID/Secret Google OAuth Web client, SMTP host/port, SMTP login/password (или app password), подтверждённый email отправителя и PostgreSQL пароль. Секреты не вставлять в чат/скриншоты/репозиторий. Google Client ID, SMTP host/from сохраняются как параметры, **секреты шифруются Windows DPAPI** с привязкой к текущему пользователю Windows в `%LOCALAPPDATA%\GuitarMentorAI\local-auth-unified.json`. Это защищает от случайного попадания секретов в Git, **но не** от программ, уже работающих с правами того же пользователя. Не переносите файл на другой ПК и не помещайте в облачную синхронизацию.

`-Setup` потребуется снова только при изменении credentials. Код использует STARTTLS с обязательным шифрованием и SMTP AUTH; провайдер должен поддерживать оба режима. Для SMTPS на 465 требуется отдельная настройка, не подменяйте режим 587 без тестирования.

## Ежедневный запуск

Убедитесь, что старые процессы backend и Vite остановлены. PostgreSQL работает. При чистом рабочем дереве переключитесь на ветку с #179 (после merge — `main`). Затем:

```powershell
pwsh -File .\scripts\local-auth-unified.ps1
```

Скрипт запускает системные окна Windows PowerShell для Spring Boot и Vite. При необходимости отдельно запускается Caddy **до расшифровки секретов**, не получая Google/SMTP/DB credentials. Секреты передаются дочерним процессам Java и Vite только через окружение. Откройте **https://guitar-mentor**; localhost нужен исключительно как временный Google callback.

Скрипт устанавливает `SPRING_PROFILES_ACTIVE=secure,local-auth,local-unified`, `VITE_GUITAR_MENTOR_AUTH_REQUIRED=true`, email origin и SMTP. Сервер при неверном loopback/bind, выключенной защите, недостающем Google или обязательном SMTP STARTTLS прерывает запуск. Пароли не выводятся в консоль.

Для остановки: `Ctrl+C` в обоих окнах backend/frontend. Содержимое базы не удаляется. SMTP `aiosmtpd` для этого режима **не требуется**.

## Минимальная приёмка

1. В чистом браузерном профиле зарегистрироваться по email → получить **реальное** письмо → подтвердить почту и войти по паролю.
2. Выйти → войти через Google (проверка провайдера, возврат в локальное приложение); повторный Google вход должен использовать ту же Google identity без приглашения.
3. «Забыли пароль» → реальное письмо → смена пароля (токен сразу исчезает из URL). Старый пароль не действует.
4. Проверить, что первое сохранение музыкального профиля не создаёт второго ученика.

Не используйте реальную личную учётную запись/личные данные как тестовые без необходимости; для проверки возьмите отдельный адрес и аккаунт. Совпадение email Google и локальной записи **не объединяет** аккаунты автоматически. Это ожидаемая безопасность, а не баг.

## Разделение готовности

CI проверяет локальную конфигурацию/правила origin/регрессию и компиляцию. Доставку через выбранного SMTP, настройки DNS, лимиты/репутацию отправителя, доступность Google у пользователя и браузерные e2e тесты подтверждает владелец после подключения. До этого #179 находится в приёмке, **не Done**.

Если Google выдаёт `redirect_uri_mismatch`, проверьте точное соответствие `http://localhost:5173/login/oauth2/code/guitar-mentor`. Если SMTP не доставляет письмо, проверьте подтверждение From, STARTTLS, авторизацию и логи бэкенда **без публикации секретов или ссылок с токенами**. При изменении origin не используйте старые ссылки.

## Resend для личного хобби-проекта (рекомендуемый провайдер)

Наличие юридического лица не требуется для написания и запуска приложения; требования конкретного регистратора и провайдера нужно проверить при регистрации. Не публикуйте домашний адрес в письмах и не вводите вымышленные реквизиты.

1. Владелец зарегистрировал **guitar-mentor.ru** через Timeweb; почтовый домен подтверждён Resend (Verified). Домен для SMTP не делает локальную разработку публичной: интерфейс пока остаётся на `https://guitar-mentor`.
2. В Timeweb уже добавлены TXT DKIM `resend._domainkey` и CNAME `rsend`, `send`; Resend подтвердил домен. Для защиты почты дополнительно рекомендуется DMARC согласно рекомендациям провайдера, без удаления существующих записей.
3. В Resend → API Keys создайте ключ только с разрешением Sending. Храните его как пароль SMTP в локальном хранилище DPAPI, не в Git.
4. В `pwsh -File .\\scripts\\local-auth-unified.ps1 -Setup` введите:
   - SMTP host: `smtp.resend.com`
   - SMTP port: `587` (STARTTLS; существующий launcher намеренно не использует SMTPS 465)
   - SMTP user: `resend`
   - SMTP credential: Resend API key (ввести скрыто, не присылать в чат)
   - From: `no-reply@guitar-mentor.ru`
5. Первое настоящее письмо восстановления через Resend ранее доставлено (PASS в первоначальном объединённом режиме). После перехода на единственный HTTPS alias проверить корректность HTTPS-ссылки отдельно.

Resend Free, по официальной странице на октябрь 2026: 3 000 писем в месяц, максимум 100 в сутки, 3 домена, поддержка SMTP. Покупка домена оплачивается отдельно. Официальные ссылки: https://resend.com/pricing , https://resend.com/docs/send-with-smtp , https://resend.com/domains .

**Контроль:** домен и отправитель принадлежат вам; SMTP-ключ и OAuth Client Secret не записаны в конфигурацию Git. Ни выдачу домена, ни реальные письма нельзя считать готовыми до проверки владельцем.

## Исправление ошибки Google redirect_uri_mismatch (UAT 24.10.2026)

В первом combined UAT Google сообщил, что приложение передало `http://127.0.0.1:5173/login/oauth2/code/guitar-mentor`, хотя в Google Cloud разрешён `http://localhost:5173/login/oauth2/code/guitar-mentor`. Это разные URI. Причина: Vite proxy направляет вызов на backend 127.0.0.1, Spring OAuth по умолчанию формирует callback из proxy Host.

Исправлено на уровне `application-local-unified.yml`: `spring.security.oauth2.client.registration.guitar-mentor.redirect-uri=http://localhost:5173/login/oauth2/code/{registrationId}`. Startup guard требует ровно это значение. Google Cloud OAuth Web client Authorized redirect URI должен быть **http://localhost:5173/login/oauth2/code/guitar-mentor**. Перезапустить backend после обновления Git. Не добавлять 127.0.0.1 callback вместо исправления, не менять значения токенов/SMTP и не менять DNS.


## Единственный адрес разработки: https://guitar-mentor/

**Пожелание владельца:** весь интерфейс, регистрация, email-ссылки, профиль, CSRF и сессии должны работать на `https://guitar-mentor/`. Без редиректа браузера в `http://localhost:5173`. Поэтому временная SPA-переадресация из PR #180 удалена; в `local-unified` включены Secure session cookies и `public-origin=https://guitar-mentor`; требуются ранее установленные hosts/Caddy и доверенный локальный TLS-сертификат. SMTP Resend остаётся независимым и работает в том же backend.

**Google OAuth пока не принят для этого адреса.** Google запрещает короткое имя хоста без публичного TLD в OAuth redirect URI: исключение есть только для `localhost`. Профиль с callback `http://localhost:5173/login/oauth2/code/guitar-mentor` нельзя без изменений использовать для полного OAuth на `https://guitar-mentor`, потому что cookie/session и OAuth state привязаны к разным host. Не допускается считать вход Google успешным без реального UAT.

**Проверки перед merge:** Java/Frontend CI, Google login в Chrome с сохранением URL после возврата на короткий адрес, повторный вход, запрет повторного погашения билета, привязка к исходной сессии, явное Google linking после повторного ввода локального пароля, реальная SMTP-доставка на HTTPS-ссылку. **PR #180 не merge до пользовательской приёмки.**

Официальные ограничения Google OAuth redirect URI: https://developers.google.com/identity/protocols/oauth2/web-server#uri-validation .

### Промежуточный localhost callback для Google

Google Cloud OAuth Web Client должен иметь ровно `http://localhost:5173/login/oauth2/code/guitar-mentor`. Vite проксирует `/oauth-bridge`, `/oauth2` и `/login/oauth2` на loopback Spring Boot. Короткий HTTPS alias остаётся обычным browser origin, Caddy должен быть запущен. `VITE_GUITAR_MENTOR_OAUTH_BRIDGE=true` включает handshake только в desktop QA-режиме. Одноразовый ticket не является cookie, действителен минуту, сохраняется только в памяти одного процесса и привязан к id исходной HTTPS-сессии. Токен сразу удаляется из fragment и одноразово погашается на alias. Из Google identity берутся только уже проверенные server-side OIDC attributes. Интеграция **не подходит** для публичной/multi-instance среды без отдельного security review.
