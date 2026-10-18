# Guitar Mentor AI — полная настройка рабочего места разработчика (Windows 10)

> **Актуально на 18.10.2026.** Пошаговое руководство для первого запуска и восстановления среды после смены ПК. Предполагается **Windows 10 x64**, **VS Code**, **Java 21 + Spring Boot 4.1.x**, **PostgreSQL**, **Vue 3 + TypeScript**. Для Windows 11 порядок почти такой же.
>
> **Важно о состоянии репозитория:** в ветке `main` пока **нет** папок `backend/` и `frontend/`. Сейчас можно полностью установить программы, настроить Git, БД и VS Code. Команды сборки проекта из раздела 10 станут применимы **после** публикации Java/Vue-каркаса. Не пытайтесь сейчас запускать несуществующие `mvnw.cmd`, `package.json` или API.
>
> **Репозиторий приватный:** [roafo1989/guitar-mentor-ai](https://github.com/roafo1989/guitar-mentor-ai). Для других участников владелец должен выдать доступ.

## Содержание

1. [Что устанавливаем и зачем](#1-что-устанавливаем-и-зачем)
2. [Перед установкой](#2-перед-установкой)
3. [Установка Git for Windows](#3-установка-git-for-windows)
4. [Установка VS Code и расширений](#4-установка-vs-code-и-расширений)
5. [Установка Java JDK 21](#5-установка-java-jdk-21)
6. [Установка PostgreSQL и создание БД](#6-установка-postgresql-и-создание-бд)
7. [Установка Node.js и npm](#7-установка-nodejs-и-npm)
8. [Доступ к GitHub и клонирование проекта](#8-доступ-к-github-и-клонирование-проекта)
9. [Настройка VS Code под Java и Vue](#9-настройка-vs-code-под-java-и-vue)
10. [Запуск и тестирование проекта — после создания кода](#10-запуск-и-тестирование-проекта--после-создания-кода)
11. [Переезд на новый компьютер](#11-переезд-на-новый-компьютер)
12. [Типовые ошибки](#12-типовые-ошибки)
13. [Чек-лист](#13-чек-лист)

## 1. Что устанавливаем и зачем

| Порядок | Компонент | Выбор для этого проекта | Назначение |
|---|---|---|---|
| 1 | [Git for Windows](https://git-scm.com/install/windows) | x64, с Git Credential Manager | Клонирование GitHub, ветки, коммиты |
| 2 | [Visual Studio Code](https://code.visualstudio.com/download) | **User Installer x64** | Редактор Java, Spring Boot и Vue |
| 3 | [Eclipse Temurin JDK](https://adoptium.net/temurin/releases/?version=21) | **Java 21 LTS**, Windows, x64, **JDK** / MSI | Компиляция и запуск Spring Boot |
| 4 | [PostgreSQL](https://www.postgresql.org/download/windows/) | **PostgreSQL 17.x** для воспроизводимой локальной установки, EDB Windows x64 | Общая реляционная БД |
| 5 | [Node.js](https://nodejs.org/en/download/archive/v24) | **Node.js 24 LTS**, Windows x64 installer (.msi) | npm, Vite, Vue 3/TypeScript |

**Почему такие версии:** Java 21 — выбранная LTS-версия; Spring Boot 4.1 и Spring AI 2.0 совместимы с ней. Node.js **24 LTS**, а не ветка Current 26, чтобы не менять платформу при каждом обновлении; выбирайте свежую патч-версию в пределах 24.x. PostgreSQL **17.x** выбран как стабильная воспроизводимая база для этого руководства. Установка PostgreSQL 18 тоже возможна, но перед переходом всей команды на другую major-версию её следует согласовать. Версии зависимостей приложения впоследствии будут зафиксированы в файлах репозитория.

**Не нужно устанавливать отдельно:** IntelliJ IDEA, JHipster CLI, Java EE application server/Tomcat, Maven, Docker Desktop, WSL, Python, GitHub Desktop, платную БД, Telegram или OpenAI SDK. Maven будет запускаться из репозитория через **Maven Wrapper** (`mvnw.cmd`). **JHipster используем как образец структуры, но не как генератор приложения.**

**Windows 10:** обычная поддержка ОС закончилась **14.10.2025**. Для рабочей машины, связанной с GitHub и API, желательно перейти на Windows 11; при сохранении Windows 10 проверить доступность программы Extended Security Updates (ESU) и своевременность обновлений: [сведения Microsoft](https://support.microsoft.com/en-us/windows/deployment/updates-lifecycle/windows-10-support-has-ended-on-october-14-2025). Инструкция ориентирована на уже имеющуюся Windows 10, но прекращение поддержки ОС — отдельный риск безопасности.

## 2. Перед установкой

1. Войдите в Windows под своей обычной учётной записью. Администраторские права могут потребоваться для JDK, PostgreSQL и Node.js.
2. Убедитесь, что система **64-разрядная**: **Параметры → Система → О системе → Тип системы**.
3. Выберите папку кода. В первоначальной настройке проекта использовалась `D:\Development\guitar-mentor-ai`. Если диска D: нет — используйте `$HOME\Development\guitar-mentor-ai` (домашний каталог Windows).
4. Имейте доступ в интернет к сайтам GitHub, Maven Central, npm и поставщикам дистрибутивов. Прокси/антивирус/корпоративный VPN могут препятствовать загрузке зависимостей.
5. Уже установленный Python **не удаляйте** — он может пригодиться для других задач и позднее для экспериментов с аудио, хотя основному приложению не нужен.
6. **PowerShell** — наш стандартный терминал: кнопка «Пуск» → **Windows PowerShell**. После изменения PATH всегда открывайте **новое окно** терминала.

Команды в блоках `powershell` выполняются в PowerShell, **не** в Java/Node REPL, pgAdmin SQL-редакторе или строке поиска Windows.

## 3. Установка Git for Windows

1. Откройте https://git-scm.com/install/windows и скачайте официальный **64-bit Git for Windows Setup**.
2. Запустите установщик. Для большинства экранов оставьте значения по умолчанию. Важные моменты:
   - вариант работы Git из командной строки Windows (**Git from the command line and also from 3rd-party software**, или аналогичный);
   - оставить **Git Credential Manager** как помощника аутентификации;
   - не включать небезопасное постоянное хранение паролей в открытом виде;
   - редактор по умолчанию можно выбрать **Visual Studio Code**, если он уже установлен; иначе оставить значение по умолчанию и настроить позже.
3. Закройте старый PowerShell, откройте новый и проверьте:

```powershell
git --version
git config --global --get credential.helper
```

Вторая команда показывает текущий helper; отсутствие вывода означает, что помощника нужно будет проверить при первой авторизации. Git Credential Manager штатно предлагается вместе с установщиком Git for Windows.

4. Установите **собственное** имя и почту для подписи коммитов:

```powershell
git config --global user.name "YOUR NAME"
git config --global user.email "YOUR_GITHUB_EMAIL"
git config --global --get user.name
git config --global --get user.email
```

Вместо `YOUR_GITHUB_EMAIL` можно использовать личный GitHub `noreply` email (см. настройки GitHub), если не хотите публиковать адрес в коммитах. **Не копируйте имя/почту другого участника.**

## 4. Установка VS Code и расширений

### 4.1. Редактор

1. Скачайте с https://code.visualstudio.com/download **Windows → User Installer → x64**.
2. Запустите установщик. Желательно включить интеграцию с проводником («Open with Code»), и убедиться, что VS Code добавляется в PATH.
3. Откройте **VS Code**. Сочетание **Ctrl+Shift+X** открывает панель расширений (Extensions).
4. После установки откройте новый PowerShell:

```powershell
code --version
```

Если команда не найдена, открывайте VS Code через меню Пуск и используйте **File → Open Folder**, либо проверьте PATH.

### 4.2. Обязательные расширения

Установите **один раз** (через поиск Extensions или прямые ссылки):

| Название в Marketplace | ID | Для чего |
|---|---|---|
| [Extension Pack for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack) | `vscjava.vscode-java-pack` | Подсказки, JUnit, отладка, Maven, навигация и рефакторинг |
| [Spring Boot Extension Pack](https://marketplace.visualstudio.com/items?itemName=vmware.vscode-boot-dev-pack) | `vmware.vscode-boot-dev-pack` | Spring Boot Dashboard, инструменты Spring и Initializr |
| [Vue (Official)](https://marketplace.visualstudio.com/items?itemName=Vue.volar) | `Vue.volar` | Подсказки/типизация/ошибки в `.vue` файлах |

**Рекомендуемые для будущего фронтенда:** [ESLint](https://marketplace.visualstudio.com/items?itemName=dbaeumer.vscode-eslint) (`dbaeumer.vscode-eslint`) и [Prettier](https://marketplace.visualstudio.com/items?itemName=esbenp.prettier-vscode) (`esbenp.prettier-vscode`). Они начнут приносить пользу после появления конфигурационных файлов во `frontend/`.

Рекомендации расширений также записаны в [`.vscode/extensions.json`](../../.vscode/extensions.json). При открытии проекта VS Code может предложить их установить. Не устанавливайте дополнительно старый **Vetur** для Vue 2 — достаточно **Vue (Official)**.

### 4.3. Что должно появиться

После добавления Maven-проекта в репозиторий VS Code покажет представления **JAVA PROJECTS**, **MAVEN** и **SPRING BOOT DASHBOARD**. **Сейчас** отсутствие этих представлений с найденным приложением нормально — Java-код ещё не загружен в `main`.

## 5. Установка Java JDK 21

1. Откройте https://adoptium.net/temurin/releases/?version=21.
2. Выберите **Windows**, архитектуру **x64**, тип пакета **JDK** (**не JRE**), формат **MSI** и выпуск Java **21**. Скачайте актуальный патч-релиз 21.x.
3. Запустите MSI. Если доступны опции **Set JAVA_HOME variable** и **Add to PATH**, включите их. При необходимости установите сертификаты/ассоциации по желанию; для нашего проекта обязательны только Java/Javac и правильная среда.
4. Перезапустите PowerShell:

```powershell
java -version
javac -version
$env:JAVA_HOME
where.exe java
```

Ожидаемая **основная версия 21** и существующий путь к установленному JDK. Если видите Java 8/17/25 вместо 21, есть конфликт PATH/JAVA_HOME.

**Проверка вручную:** откройте свойства системы → **Дополнительные параметры системы → Переменные среды**. `JAVA_HOME` должен указывать на **корень JDK** (каталог, содержащий `bin`), например `C:\Program Files\Eclipse Adoptium\jdk-21...`. В PATH должен присутствовать `%JAVA_HOME%\bin` либо совместимая запись установщика. Изменения применятся в новом терминале.

## 6. Установка PostgreSQL и создание БД

### 6.1. Установщик

1. Перейдите на https://www.postgresql.org/download/windows/ → **Download the installer** (официальная страница направляет к EDB).
2. Выберите **PostgreSQL 17.x**, **Windows x86-64**. В выборе установщика отдавайте предпочтение последнему доступному патч-релизу 17.x. Учтите: официальный список тестирования EDB указывает серверные версии Windows; конкретную совместимость вашего выпуска Windows 10 нужно подтвердить фактической установкой.
3. В мастере установки:
   - компоненты: **PostgreSQL Server**, **pgAdmin 4**, **Command Line Tools**; **Stack Builder** необязателен;
   - каталог данных: оставьте предлагаемый, если достаточно места;
   - **пароль роли `postgres`**: придумайте надёжный пароль и сохраните в менеджере паролей. Это административная роль СУБД, **не** GitHub-аккаунт;
   - **порт 5432** (по умолчанию, если свободен);
   - локаль — по умолчанию, если специальных требований нет;
   - по окончании не требуется скачивать плагины через Stack Builder.
4. В Windows в **Службах** должен появиться и запуститься сервис PostgreSQL. Его можно найти PowerShell-командой:

```powershell
Get-Service -Name 'postgresql*'
```

Ожидается статус **Running** для установленного экземпляра.

### 6.2. Подключение через pgAdmin

1. Запустите **pgAdmin 4** из меню Пуск.
2. При первом запуске pgAdmin может попросить создать **master password** для сохранённых подключений — это пароль приложения pgAdmin, он отличается от пароля роли `postgres`.
3. Раскройте **Servers**. Если локальный сервер уже зарегистрирован — откройте его, введя пароль роли `postgres`.
4. Если сервера нет: **Servers → Register → Server…**:
   - **General → Name:** `Local PostgreSQL` (произвольная подпись);
   - **Connection → Host name/address:** `localhost`;
   - **Port:** `5432`;
   - **Maintenance database:** `postgres`;
   - **Username:** `postgres`;
   - **Password:** заданный при установке административный пароль.
   - Нажмите **Save**.
5. Убедитесь, что сервер доступен и видны базы данных.

### 6.3. Отдельный пользователь приложения

**Не запускаем будущий Spring Boot от имени административной роли `postgres`.** Создадим отдельную роль.

1. В дереве pgAdmin: **Servers → Local PostgreSQL → Login/Group Roles → правый клик → Create → Login/Group Role…**
2. **General → Name:** `guitar_mentor_app`.
3. **Definition → Password**: новый **отдельный** пароль роли приложения (сохраните в менеджере паролей, нигде не публикуйте).
4. **Privileges:** **Can login? = Yes**; **Superuser? = No**, **Create databases? = No**, **Create roles? = No**; остальные права оставить минимальными.
5. Нажмите **Save**.

Теперь в дереве **Databases → правый клик → Create → Database…**:
- **Database:** `guitar_mentor`;
- **Owner:** `guitar_mentor_app`;
- **Save**.

Наконец проверьте **подключение под прикладной ролью** (важно: одно лишь существование БД не проверяет пароль). В pgAdmin создайте **ещё одну** регистрацию сервера с Host `localhost`, Port `5432`, Maintenance database `guitar_mentor`, Username `guitar_mentor_app`, её паролем. Откройте **Query Tool** на этой базе и выполните:

```sql
SELECT current_user, current_database(), version();
```

Должны отображаться `guitar_mentor_app` и `guitar_mentor`.

**Почему именно PostgreSQL:** для нашего Spring Data JPA/Hibernate это штатно поддерживаемая БД; SQLite потребовала бы отдельный community-диалект и проверки совместимости. PostgreSQL бесплатен как ПО; для MVP отдельный платный сервер БД не нужен.

### 6.4. Какие параметры позже понадобятся Java

В будущем Spring Boot будет использовать:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/guitar_mentor
SPRING_DATASOURCE_USERNAME=guitar_mentor_app
SPRING_DATASOURCE_PASSWORD=<пароль роли приложения, не включать в Git>
```

Это **планируемая конфигурация backend**, не команда установки. **В `main` пока нет Java-кода для её применения.** Настройку локальных секретов оформим в DEV-01-JAVA; не предполагаем, что Spring Boot автоматически прочитает файл `.env`.

Пароль желательно вводить через защищённые настройки запуска или переменные окружения на локальной машине; нельзя записывать пароль в `application.yml`, `pom.xml`, коммит, README или рабочие скриншоты. Реальные значения **никогда** не публикуйте в GitHub Issues/PR.

### 6.5. Что делать, если порт 5432 занят

Проверьте:

```powershell
Get-NetTCPConnection -LocalPort 5432 -State Listen -ErrorAction SilentlyContinue
```

При существующем PostgreSQL не нужно устанавливать второй экземпляр без причины: можно создать нужную роль/БД в работающем сервере. При использовании другого порта в будущем нужно заменить его **и** в URL datasource. Не отключайте защиту сети и не публикуйте PostgreSQL-порт в интернет.

## 7. Установка Node.js и npm

1. Откройте [архив официальных сборок Node.js 24](https://nodejs.org/en/download/archive/v24) либо [общую страницу загрузки Node.js](https://nodejs.org/en/download) и выберите **Node.js 24.x LTS** для **Windows x64**, MSI. На 18.10.2026 актуальный патч Node 24 LTS — **24.22.0**; если страница предлагает более новую поддерживаемую патч-версию 24.x, можно выбрать её.
2. В мастере установки оставьте **npm package manager**, добавление в PATH и стандартные компоненты. Для типичного Vue/Vite проекта **не требуется** устанавливать Visual Studio C++ Build Tools, Chocolatey или Python для сборки самого Node.js; дополнительные средства нужны только некоторым сторонним npm-пакетам с нативной компиляцией.
3. Откройте новый PowerShell и проверьте:

```powershell
node --version
npm.cmd --version
```

Результат первой команды должен начинаться с **v24.**. Во второй намеренно используем `npm.cmd`: на Windows PowerShell вызов `npm` иногда блокируется политикой выполнения `npm.ps1`. Через `npm.cmd` обычно можно работать **без изменения ExecutionPolicy**.

**Не запускайте `npm install` в корне репозитория сейчас:** файла `package.json` в `main` пока нет. Установка зависимостей будет в папке `frontend/` после её добавления.

## 8. Доступ к GitHub и клонирование проекта

### 8.1. Подтвердите права доступа

1. Откройте в браузере https://github.com/roafo1989/guitar-mentor-ai.
2. Войдите в **свою** учётную запись GitHub. Если приватный репозиторий не виден, запросите доступ у владельца, не пытайтесь угадывать или копировать его токен.
3. При первом Git-обращении через HTTPS **Git Credential Manager** обычно предлагает вход через браузер. Завершите авторизацию там; не вставляйте пароль в Git URL.

### 8.2. Склонируйте на диск D:

В **PowerShell**:

```powershell
New-Item -ItemType Directory -Path 'D:\Development' -Force | Out-Null
Set-Location 'D:\Development'
git clone https://github.com/roafo1989/guitar-mentor-ai.git
Set-Location '.\guitar-mentor-ai'

git remote -v
git branch --show-current
git status
```

Нет диска **D:**? Вместо первых двух команд используйте:

```powershell
New-Item -ItemType Directory -Path "$HOME\Development" -Force | Out-Null
Set-Location "$HOME\Development"
```

Остальные команды те же. **Не выполняйте `git clone` повторно в уже существующую папку проекта.** Если проект уже склонирован, просто откройте папку, проверьте `git status` и обновите актуальную ветку через `git pull --ff-only` только после сохранения локальных изменений.

### 8.3. Текущие ветки и важная оговорка

- **`main`** — текущая документация, архитектурное решение и (позднее) Java/Vue.
- **`feature/dev-01-python-bootstrap`** / [PR #5](https://github.com/roafo1989/guitar-mentor-ai/pull/5) — **старый Python-прототип**. Не переключайтесь на него ради Java.
- Когда появится ветка DEV-01-JAVA, мы отдельно зафиксируем её имя и правила переключения. **Не создавайте вручную ветку с предполагаемым именем ради выполнения этой инструкции.**

## 9. Настройка VS Code под Java и Vue

1. В VS Code откройте **File → Open Folder** → `D:\Development\guitar-mentor-ai` (или альтернативный путь).
2. **Trust Folder** выбирайте только если понимаете, что это ваш доверенный клон репозитория: расширения и задачи рабочего пространства могут исполнять код.
3. Проверьте панель **Extensions**: Java Pack, Spring Boot Pack, Vue (Official), при желании ESLint и Prettier. Вы также увидите рекомендации из `.vscode/extensions.json`.
4. **Ctrl+Shift+P → Java: Configure Java Runtime**: убедитесь, что JDK 21 обнаружен. После появления `backend/pom.xml` выберите его как project runtime, если VS Code выбрал другую Java.
5. **Terminal → New Terminal**: PowerShell. Здесь будут команды Git, Maven Wrapper и `npm.cmd`.
6. Не включайте глобальное автоматическое форматирование, если оно противоречит конфигурации проекта. После появления Java/Vue-кода правила будут храниться в репозитории (Java formatter, ESLint, Prettier, EditorConfig).

### Планируемая структура кода (вдохновлена JHipster, но не сгенерирована им)

```text
guitar-mentor-ai/
├── backend/
│   ├── mvnw.cmd
│   ├── .mvn/wrapper/...
│   ├── pom.xml
│   └── src/main/
│       ├── java/...                  # domain, application, adapters
│       └── resources/db/migration/   # Flyway SQL
├── frontend/
│   ├── package.json
│   ├── package-lock.json
│   └── src/
│       ├── app/
│       │   ├── core/                 # auth, http и инфраструктура клиента
│       │   ├── entities/
│       │   │   ├── lesson/
│       │   │   │   ├── LessonListView.vue
│       │   │   │   ├── LessonDetailView.vue
│       │   │   │   ├── lesson.service.ts
│       │   │   │   └── lesson.routes.ts
│       │   │   ├── exercise/
│       │   │   └── student/
│       │   ├── router/
│       │   ├── shared/model/
│       │   ├── shared/components/
│       │   └── stores/               # Pinia
│       ├── i18n/
│       │   ├── ru/                    # global.json, lesson.json, ...
│       │   └── en/
│       └── main.ts
├── docs/
└── .vscode/extensions.json
```

Это **схема будущей структуры**, а не перечень существующих сейчас файлов. Следуем принципам JHipster: выделенные **Vue-компоненты**, **сервисы REST API**, **маршруты**, **типизированные модели**, **общие компоненты**, отдельные **JSON-файлы текстов интерфейса** через `vue-i18n`. Например ключ `lesson.fields.name` будет отображаться как «Название урока», не смешиваясь с техническим именем поля `name` в Java/TypeScript. Привычные CRUD-модули не должны мешать отдельным функциональным сценариям, таким как запись гитары и просмотр табулатуры.

Почему **не** нужен JHipster CLI: он принёс бы генератор и административные модули, которые нам пока не требуются. Сохраняем узнаваемую организацию, но пишем компактный Vue 3 + TypeScript фронтенд.

## 10. Запуск и тестирование проекта — после создания кода

### 10.1. Сначала проверьте, появились ли файлы

Из корня репозитория:

```powershell
Test-Path .\backend\mvnw.cmd
Test-Path .\backend\pom.xml
Test-Path .\frontend\package.json
Test-Path .\frontend\package-lock.json
```

**Пока будет `False`: соответствующие команды ниже выполнять бессмысленно.** Не создавайте пустые `pom.xml` или `package.json` самостоятельно — они будут добавлены коммитом соответствующего этапа. К этому моменту мы обновим данную инструкцию подтверждёнными командами.

### 10.2. Backend (после DEV-01-JAVA)

Запуск **из корня репозитория** с отдельным терминалом PowerShell:

```powershell
Set-Location .\backend
.\mvnw.cmd --version
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

Maven Wrapper скачивает нужную версию Maven и зависимости автоматически; интернет нужен при первом запуске. Перед стартом приложения PostgreSQL должен работать, роль/БД должны существовать, а локальная конфигурация datasource — быть задана способом, который будет указан в README Java-модуля. **Данные доступа не вставлять в исходники.**

### 10.3. Frontend (после добавления Vue)

В **другом** терминале из **корня репозитория**:

```powershell
Set-Location .\frontend
npm.cmd ci
npm.cmd run dev
```

`npm ci` воспроизводимо устанавливает зависимости из `package-lock.json`, который должен находиться в Git вместе с `package.json`. Адрес сайта в браузере (часто `http://localhost:5173`) и прокси к REST API будут уточнены **по фактической конфигурации Vite**. Не считать пример порта обязательным.

### 10.4. Что НЕ делаем на этапе установки рабочего места

Не нужно пока создавать Telegram-бота через BotFather, покупать API-кредиты, вводить OpenAI API key, строить CI, запускать Docker или вручную генерировать JHipster-проект. Это отдельные задачи реализации. ИИ-платные вызовы на стадии настройки компьютера не нужны.

## 11. Переезд на новый компьютер

1. Установите Git, VS Code, JDK 21, PostgreSQL 17.x и Node.js 24 LTS из разделов 3–7. Если переходите на Windows 11 — настройка такая же.
2. Выдайте новой учётной записи разработчика доступ к приватному репозиторию, настройте свои Git `user.name` и `user.email`.
3. Склонируйте свежую `main` или согласованную рабочую ветку. **Не копируйте вручную всю папку вместе с кэшами**, если код можно получить через GitHub.
4. Установите расширения VS Code (или примите рекомендации из `.vscode/extensions.json`).
5. Создайте локальную роль и БД PostgreSQL; затем **отдельно** восстановите данные через штатный backup/restore, если это требуется.
6. **Восстановите секреты из отдельного безопасного источника**: пароли СУБД, токены Telegram/OpenAI (когда они появятся). В GitHub их не будет.
7. Когда Java/Vue-каркас появится, используйте `mvnw.cmd` и `npm.cmd ci`: зависимости скачиваются заново на новый ПК.
8. Проверьте сборку, тесты, доступ к БД и авторизацию в приложении.

**Важно:** GitHub — резервная копия **исходного кода**, но не содержимого PostgreSQL, не секретов и не пользовательских записей. Для PostgreSQL понадобится отдельная политика `pg_dump`/восстановления и резервное хранение, когда приложение начнёт содержать реальные данные учеников. Сырые медиа по правилам проекта хранятся временно и удаляются после обработки; долгосрочно храним результаты проверок и решения ученика.

## 12. Типовые ошибки

| Что происходит | Как исправить |
|---|---|
| `git` или `code` не распознаётся | Перезапустить PowerShell/VS Code, проверить PATH и факт установки |
| `java -version` не равно 21 | Проверить `JAVA_HOME`, `where.exe java`, порядок записей PATH |
| `javac` не найден | Установлена JRE вместо **JDK**, либо PATH смотрит не туда |
| Ошибка Git `Repository not found` / 403 | Проверить учётную запись GitHub и доступ к **приватному** репозиторию |
| `npm.ps1 cannot be loaded` | Использовать **`npm.cmd`** вместо `npm`; не отключать безопасность PowerShell |
| `psql` не найден | CLI мог не попасть в PATH: проверить через установленную **SQL Shell (psql)** или pgAdmin; не обязательно для запуска backend |
| `Connection refused` к БД | Проверить службу PostgreSQL и адрес/порт `localhost:5432` |
| `password authentication failed` | Проверить пароль **именно** роли `guitar_mentor_app`, а не `postgres` или master password pgAdmin |
| `database "guitar_mentor" does not exist` | Создать БД в pgAdmin и подтвердить, что приложение подключается к нужному экземпляру |
| `mvnw.cmd` не найден | Java backend ещё не опубликован либо выбран не тот каталог/ветка |
| `package.json` не найден | Vue frontend ещё не опубликован либо вы не в `frontend/` |
| Ошибки импорта Java в VS Code | После появления backend проверить JDK 21, Maven импорты, Java Projects |
| Vue-подсказки не работают | Проверить **Vue (Official)** и то, что открыт проект `frontend` |
| Первый Maven/npm запуск не качает пакеты | Проверить сеть, VPN/прокси и доступ к Maven Central/npm registry; не отключать TLS-проверку |

## 13. Чек-лист

Отметьте по ходу настройки:

- [ ] Windows x64, папка разработки выбрана; вопросы безопасности Windows 10 учтены
- [ ] Git установлен; `git --version` работает
- [ ] Git `user.name` / `user.email` настроены на **своего** разработчика
- [ ] VS Code установлен; расширения Java, Spring Boot, Vue установлены
- [ ] JDK 21 установлен; `java -version` и `javac -version` показывают 21
- [ ] PostgreSQL работает; pgAdmin подключается к локальному серверу
- [ ] Роль `guitar_mentor_app` и база `guitar_mentor` созданы; подключение под ролью проверено
- [ ] Node.js 24 LTS установлен; `node --version` и `npm.cmd --version` работают
- [ ] Есть доступ к приватному GitHub-репозиторию; репозиторий склонирован
- [ ] Корень проекта открыт в VS Code; JDK обнаруживается расширением
- [ ] **После DEV-01-JAVA:** `mvnw.cmd test` и запуск backend выполняются успешно
- [ ] **После Vue-этапа:** `npm.cmd ci` и `npm.cmd run dev` выполняются успешно

## Связанные документы и официальные источники

- [ADR-0001 — Java / Vue / PostgreSQL](../adr/0001-java-vue-postgresql.md)
- [Архив первоначальной Python-настройки](archive/python-workstation-2026-10.md)
- [README репозитория](../../README.md)
- [VS Code: Java](https://code.visualstudio.com/docs/java/java-tutorial) и [Spring Boot в VS Code](https://code.visualstudio.com/docs/java/java-spring-boot)
- [Spring Boot: требования](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring AI: getting started](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [PostgreSQL Windows installer](https://www.postgresql.org/download/windows/)
- [Node.js 24 LTS](https://nodejs.org/en/download/archive/v24)
- [JHipster Vue sample — пример структуры](https://github.com/jhipster/jhipster-sample-app-vuejs)

**Правило актуализации:** после коммита Java-каркаса и Vue-фронтенда заменить предполагаемые команды запуска на проверенные, добавить точные версии зависимостей, URL API, правила авторизации и способ локального хранения секретов. При изменении структуры проекта синхронно обновлять этот документ и `README.md`.
