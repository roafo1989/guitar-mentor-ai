# Guitar Mentor AI — новый логотип, пакет1.0

Получен25.10.2026 по прямому выбору владельца для #198. [Контракт](../../../specifications/AUTH-03-auth-ui-and-branding.md). Это новый источник вместо прежнего «сохранить текущий logo» в исходной карточке198.

Архив guitar-mentor-logo-concept.zip содержит7PNG:32/64/128/256/512/1024 и horizontal1200×280. Все файлы извлечены **без изменений байтов**. Отдельно переданные1024/512/horizontal совпадают с архивом. Изображения RGBA с прозрачностью; квадратный canvas содержит исходные прозрачные поля (1024: bbox107,92–917,932); не растягивать/не запекать фон и не принимать поля за чёрную заливку просмотрщика. [Размеры,bytes,SHA-256 и source IDs](manifest.json).

| Файл | Применение разработчиком |
| --- | --- |
|guitar-mentor-logo-128.png|Auth-card56CSSpx и header40CSSpx (при необходимости32), object-fit:contain, явный width/height; оригинал остаётся квадратным|
|guitar-mentor-logo-32.png|Versioned favicon32, в том числе browser render16px|
|guitar-mentor-logo-64.png|Versioned favicon64/HiDPI; не использовать маленький favicon как source большого логотипа|
|guitar-mentor-logo-256.png|Запас для larger/highDPI использования, не грузить1024 ради header|
|guitar-mentor-logo-512.png /1024.png|Master/reference для будущих экспортов, не runtime default|
|guitar-mentor-logo-horizontal.png|Reference горизонтальной композиции, содержит raster RU subtitle; дляEN/доступности предпочтителен отдельный знак+live text|

Пути реализации: frontend/public/brand/ с теми же file names, header/card ссылаются на них; versioned favicon names/URLs в index.html. Перечисленные размеры не требуют ресайза/новой генерации. В текущей подготовке frontend не изменён.

Brand text — Guitar Mentor AI, существующий локализованный subtitle. Изображение рядом с текстом имеет alt=""/aria-hidden; у brand-home link существующее доступное имя. Если знак самостоятельный, нужен осмысленный alt. Главная по клику на logo/name, новый отдельный Home link не добавлять. Шапка при авторизации сохраняет имя, инструменты, logout, locale и sticky behaviour.

Пользовательский дизайн не даёт прав на Google mark: официальный Google G из соседнего google-signin имеет отдельное назначение. Не перерисовывать этот логотип из концепта, не придумывать SVG/path и не подменять другим изображением. Новое редактирование рисунка/брендбук/PWA не входит в198. Приёмка отображения в приложении впереди, сами файлы подготовлены и доступны без поиска в чате.
