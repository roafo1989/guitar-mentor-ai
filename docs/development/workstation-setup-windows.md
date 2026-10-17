# Guitar Mentor AI — рабочее место разработчика (Windows 10, Java)

> **Статус на 18.10.2026:** целевой стек утверждён: Java 21, Spring Boot 4.1.x, PostgreSQL, Vue 3 и VS Code. Этот документ описывает подготовку новой машины и ожидаемый порядок запуска. **Java-проект пока не закоммичен**, поэтому команды `./mvnw` и `npm` станут исполняемыми после реализации DEV-01-JAVA и фронтенда.
>
> Если нужен исторический Python DEV-01, см. [архив первоначальной инструкции](archive/python-workstation-2026-10.md). Архитектурное решение: [ADR-0001](../adr/0001-java-vue-postgresql.md).

## 1. Минимальный набор на Windows 10

| Инструмент | Зачем | Официальная ссылка |
|---|---|---|
| VS Code | Общий редактор Java/Vue | https://code.visualstudio.com/download |
| Git for Windows + Git Credential Manager | Работа с репозиторием и вход в GitHub | https://git-scm.com/install/windows |
| Eclipse Temurin **JDK 21** x64 | Компилятор и запуск Java/Spring | https://adoptium.net/temurin/releases/?version=21 |
| PostgreSQL | Локальная реляционная база | https://www.postgresql.org/download/windows/ |
| Node.js **LTS** x64 + npm | Vue/Vite и сборка frontend | https://nodejs.org/en/download |

При установке JDK включить опции **JAVA_HOME** и добавление Java в **PATH**, если мастер их предлагает. Для PostgreSQL задайте надёжный пароль администратора БД и храните его вне репозитория. PostgreSQL можно установить нативно без Docker. На хостинге БД будет работать как обычный сервис; отдельный платный managed database необязателен.

В VS Code установите расширения:
- **Extension Pack for Java** (`vscjava.vscode-java-pack`);
- **Spring Boot Extension Pack** (`vmware.vscode-boot-dev-pack`);
- **Vue (Official)** (`Vue.volar`).

IntelliJ IDEA, JHipster, глобальная установка Maven, Docker, WSL, Python не требуются для **основного Java MVP**. При необходимости Python можно установить отдельно для экспериментального анализа аудио.

## 2. Проверка установки (PowerShell)

Откройте **новый** PowerShell после установок:

```powershell
git --version
java -version
javac -version
node --version
npm --version
```

`java` и `javac` должны сообщить версию 21.x. Если `java` отсутствует, проверьте `JAVA_HOME` и PATH (после изменения переоткройте терминал). PostgreSQL проверяется через **pgAdmin** или `psql` — исполняемый файл `psql.exe` не всегда добавляется в PATH установщиком, и это не ошибка.

`mvn -version` **не обязателен**: backend должен получить **Maven Wrapper** (`mvnw.cmd`, `.mvn/wrapper`), позволяющий работать без глобальной установки Maven.

## 3. GitHub и клонирование

Для каждого разработчика Git настраивается на **своё** имя и email:

```powershell
git config --global user.name "Your Name"
git config --global user.email "you@example.com"
```

Клонирование в рекомендованную папку на диске D:

```powershell
New-Item -ItemType Directory -Force -Path D:\Development | Out-Null
Set-Location D:\Development
git clone https://github.com/roafo1989/guitar-mentor-ai.git
Set-Location .\guitar-mentor-ai
```

Если D: нет, выберите `$HOME\Development` или другую папку. Репозиторий приватный: требуется доступ GitHub. При первом входе через Git Credential Manager удобно выбрать авторизацию в браузере. Не помещайте токены в Git-команды и файлы проекта.

**Важное различие веток:** прежний `feature/dev-01-python-bootstrap` — экспериментальный Python-каркас из PR #5. Он **не является** основой Java-проекта. До появления рабочего DEV-01-JAVA в `main` доступны архитектура и инструкции, но не готовый backend.

## 4. Локальная PostgreSQL

Для единообразия окружений используем **PostgreSQL и локально, и при размещении** (а не SQLite/H2 локально и PostgreSQL в продакшене).

Через pgAdmin или `psql`, подключившись под администратором сервера:
1. Создайте отдельного пользователя-роль для приложения, например `guitar_mentor`, с **LOGIN**, без привилегий superuser.
2. Создайте БД `guitar_mentor` с владельцем `guitar_mentor`.
3. Ограничьте внешние подключения к локальной БД; для локальной разработки обычно нужен только `localhost:5432`.
4. Сохраните пароль учётной записи приложения в локальном менеджере секретов, **не в Git**.

Пример SQL, выполняемого **администратором** в локальном psql (вместо значения-подсказки придумайте секрет, вводите его без записи в репозиторий):

```sql
CREATE ROLE guitar_mentor LOGIN PASSWORD 'SET_A_STRONG_LOCAL_PASSWORD';
CREATE DATABASE guitar_mentor OWNER guitar_mentor;
```

Если команда `CREATE DATABASE` запускается в транзакционном блоке, выполните её отдельно. При использовании pgAdmin проще воспользоваться графическим интерфейсом.

Целевой набор переменных backend (будет подключён при DEV-01-JAVA):

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/guitar_mentor
SPRING_DATASOURCE_USERNAME=guitar_mentor
SPRING_DATASOURCE_PASSWORD=*** local secret ***
```

Значения задавать как **переменные окружения процесса**, либо в локальном неотслеживаемом `.env` **только если конкретный механизм загрузки будет явно добавлен**. Не предполагайте, что Spring Boot сам автоматически читает произвольный `.env` файл.

Используем миграции Flyway; не включать `ddl-auto=update` в основной среде.

## 5. Открытие проекта и запуск — после DEV-01-JAVA

Откройте корневую папку в VS Code: **File → Open Folder** → `guitar-mentor-ai`. Расширения Java и Spring должны обнаружить Maven проект в `backend/` после его создания. Выберите JDK 21 через **Java: Configure Java Runtime** при необходимости.

**Планируемые команды** (сейчас в main ещё нет этих каталогов — не выполнять до готовности bootstrap):

```powershell
# После добавления backend с Maven Wrapper:
Set-Location .\backend
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

Фронтенд будет добавлен следующим этапом:

```powershell
# После появления frontend/package.json:
Set-Location .\frontend
npm ci
npm run dev
```

`npm ci` предполагает сохранённый `package-lock.json` — мы добавим его вместе с `frontend`. Реальные переменные, порты, API эндпоинты и команды проверки обновим при создании соответствующего кода.

## 6. Перенос на другой компьютер

1. Установить VS Code, Git, JDK 21, PostgreSQL и Node.js LTS.
2. Предоставить доступ к приватному репозиторию; клонировать `main` или актуальную рабочую ветку.
3. Создать PostgreSQL роль/БД и восстановить секреты **вне Git**.
4. После появления Java-каркаса запустить Maven Wrapper — он скачает зависимости в кэш нового ПК.
5. При наличии frontend запустить `npm ci`; проверить тесты и запуск.
6. Отдельно перенести **нужные** локальные данные/резервные копии PostgreSQL. Git хранит только исходный код, не пользовательскую БД или загруженные медиа.

## 7. Безопасность и стоимость

- Не коммитить токены Telegram/OpenAI, пароли PostgreSQL, персональные данные, аудио/видео, базу данных и временные файлы.
- Сохранение исходных медиа — временное, с автоудалением после обработки в соответствии с политикой проекта. Результаты оценивания и принятые учеником решения храним отдельно.
- PostgreSQL бесплатен как ПО, но потребляет ресурсы сервера. При будущем размещении оценивать стоимость **всей** машины и резервного копирования, а не только цену лицензии БД.
- Для MVP достаточно **одного backend + одного PostgreSQL**; Docker и микросервисы не обязательны.

## 8. Документация

- [Архитектурное решение](../adr/0001-java-vue-postgresql.md)
- [Предыдущая Python-инструкция (архив)](archive/python-workstation-2026-10.md)
- [Spring Boot](https://docs.spring.io/spring-boot/reference/index.html)
- [Spring AI](https://docs.spring.io/spring-ai/reference/getting-started.html)
- [PostgreSQL Windows](https://www.postgresql.org/download/windows/)

**Правило обновления:** после создания реального Java backend заменить в этой инструкции запланированные команды на **проверенные** команды из репозитория и описать подтверждённые версии/порты/переменные.
