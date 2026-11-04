# Интерсвязь: контракт по сохранённому трафику

Источник — локальный mitmproxy dump, 327 записей HTTP. Контракт восстановлен офлайн; затем 4 ноября 2026 выполнена ограниченная проверка чтения с уже существующей сессией из capture (результаты ниже). Ни токены, ни пользовательские идентификаторы, телефоны и адреса в этот документ не включены. Это наблюдаемый контракт одной сессии, а не официальная спецификация.

## Общие заголовки

API: `https://api.is74.ru`; камеры: `https://cams.is74.ru`; история: `https://td-crm.is74.ru`.

- `Authorization: Bearer <mainToken>` для API, камер и HLS.
- `Authorization: Bearer <intercomToken>` для `/api/user/history` и его `/api/file` изображений.
- `X-Device-Id: <installationId>`; в capture это 16 символов, одинаковое значение в `deviceId`, `uniqueDeviceId` и заголовке.
- `X-Api-User-Id: <USER_ID>`; `X-Api-Profile-Id: <PROFILE_ID>`.
- `Accept: application/json`; во всех трёх шагах авторизации `application/json; version=v2`.
- Наблюдались также `Platform: Android`, `X-Api-Source: com.intersvyaz.lk`, `X-App-Version: 2.21.3-RS-2d9fb554`. Обязательность этих служебных заголовков по одному capture установить нельзя.
- До авторизации `Authorization` пуст, user ID `-1`, profile ID `null`.

Не сохранять response bodies, токены или полные media URL в логи. Хранить текущие учётные данные локально, шифруя Android Keystore; исключить их из backup. На выходе удалять обе сессии.

## Авторизация по телефону

1. `POST /mobile/auth/get-confirm`, JSON:

   ```json
   {"deviceId":"<installationId>","phone":"<10 digits without +7>"}
   ```

   Ответ `{authType:int, timeoutMins:null|int, message:string, timeoutMinsDefault:int, authId:string, confirmType:int}`. В capture `authType=1`, `confirmType=1`, `timeoutMinsDefault=1`: вход по последним четырём цифрам входящего звонка. `message` — HTML с инструкциями и номером телефона. Не трактовать каждый challenge как SMS; показать очищенный серверный текст. Не фиксировать длину кода только по одному примеру без учёта текста.

2. `POST /mobile/auth/check-confirm`, `application/x-www-form-urlencoded`:
   `phone=<10 digits>&confirmCode=<code>`.

   Ответ `{authId:string, addresses:[{USER_ID:string, ADDRESS:string}]}`. При нескольких адресах пользователь выбирает нужный договор. Не выбирать произвольно первый.

3. `POST /mobile/auth/get-token`, form:
   `authId=<authId from check>&userId=<selected USER_ID>&uniqueDeviceId=<installationId>`.

   Ответ включает `TOKEN:string, USER_ID:int, PROFILE_ID:int, ACCESS_BEGIN:string, ACCESS_END:string, ACCESS_END_LEFT:int`. В этой сессии TTL main token равен году, но это не гарантия для других аккаунтов. `AUTH_TYPE="phone"`. Значения дат без timezone относятся к серверному локальному представлению и не должны использоваться для видео.

4. Для истории домофона: `POST https://td-crm.is74.ru/api/auth-lk`, form `token=<mainToken>&buyerId=1`. В capture заголовок `Authorization` здесь буквально `Bearer` без токена; идентификаторы main account присутствуют. Возвращает `{USER_ID:int, ACCESS_BEGIN:string, ACCESS_END:string, TOKEN:string}`. Это отдельный токен; в capture срок около трёх месяцев. Нет необходимости создавать SIP аккаунт, регистрировать устройство, генерировать виртуальные ключи или посылать аналитику ради просмотра камеры/истории.

Наблюдался `POST https://api.is74.ru/token/check` с пустым body. Не включать автоматические повторные запросы отправки кода или открытия двери.

## Домофоны и камеры

`GET https://api.is74.ru/domofon/relays?mainFirst=1&pagination=1&pageSize=40&page=1&isShared=0` — свои двери. Оригинальное приложение дополнительно передавало `buildingIds=<buildingId>` из `/user/address`; без фильтра контракт по capture не проверен. Общий список доступов: такой же запрос с `isShared=1` без `mainFirst` и `buildingIds`. Ответ — массив, а не объект с `data`; пагинацию завершать при неполной странице, объединять по `RELAY_ID`.

Каждый relay содержит:

```text
RELAY_ID:string           # идентификатор операции открытия
ENTRANCE_UID:string       # UUID камеры (совпадение подтверждено capture)
MAC_ADDR:string           # связь с событиями params.mac
ADDRESS:string
PORCH_NUM:string
RELAY_TYPE:string         # например «Главный вход»
RELAY_DESCR:null|string
STATUS_CODE:string        # "0" при доступности
STATUS_TEXT:string        # "OK" при доступности
HAS_VIDEO:string          # "1"
IS_MAIN:string
SMART_INTERCOM:string
BUILDING_ID:string
IMAGE_URL:string
LINKS.open:string         # отдельная td-crm ссылка; вместо неё ниже подтверждён api route
```

`GET /user/address` даёт `BUILDING_ID`, а также `buildingString` и `flatString` для отображения. Не полагаться на структуру улицы/номера для связки камер.

`POST https://cams.is74.ru/api/limited-info-by-uuid`, form `CAMERA_UUIDS[]=<ENTRANCE_UID>`. Для нескольких камер повторять поле массива. Ответ — объект, ключи которого числовые camera IDs; значения:

```text
ID:int, UUID:string, NAME:string, ADDRESS:string, PORCH:string
ACCESS.LIVE.{STATUS:boolean,REASON:string}
ACCESS.ARCHIVE.{STATUS:boolean,REASON:string,AUDIO:boolean}
ACCESS.MOVEMENT.{STATUS:boolean,REASON:string}
MEDIA.HLS.LIVE.MAIN:string                # абсолютный URL master playlist
MEDIA.HLS.LIVE.LOW_LATENCY:string         # URL master playlist с realtime=1
MEDIA.HLS.ARCHIVE:string                  # base URL master playlist для архива
MEDIA.SNAPSHOT.LIVE.{MAIN,LOSSY}:string
ARCHIVE.{START_TIME,STOP_TIME}:string     # dd.MM.yyyy HH:mm, локальное время камеры
```

`MEDIA.HLS` использовать приоритетно; старые поля `HLS`, `REALTIME_HLS`, `ARCHIVE.LINK` относительные и другого формата. В capture нет доступа к `DOWNLOAD`, `MOVEMENT`, `PTZ`, `ANNOTATION`; live и archive доступны. Каждую возможность определять через флаг ответа.

## HLS, архив и время

Live: URL из `MEDIA.HLS.LIVE.LOW_LATENCY` (fallback `MAIN`) вида
`https://cdn.cams.is74.ru/hls/playlists/multivariant.m3u8?uuid=<uuid>&realtime=1&token=<opaqueMediaToken>`.

Архив: к `MEDIA.HLS.ARCHIVE` добавить **UTC ISO 8601** параметры:
`start-date=2026-01-02T10:00:00Z&stop-date=2026-01-02T11:00:00Z`.
Эти имена регистрозависимы, с дефисами. Во всех API query датах отправлять целые секунды без дробной части: проверка 4 ноября показала HTTP 422 для `STOP_DATE` с микросекундами и HTTP 200 для того же диапазона после усечения до секунд. Это относится и к `START_DATE`/`STOP_DATE` запроса интервалов; formatter должен быть общим для архивных ссылок и интервалов. Media token отличается от main token; он непрозрачный, TTL из capture неизвестен. При истечении ссылки запросить свежие сведения камеры и восстановить выбранную позицию.

`GET https://cams.is74.ru/api/record/interval/list?CAMERA_ID=<camera ID>&START_DATE=<UTC ISO8601>&STOP_DATE=<UTC ISO8601>` возвращает `[{ID:int,START_DATE:string,STOP_DATE:string}]`. Это реальные интервалы наличия записи. Они могут быть неупорядочены и содержать разрывы: сортировать, объединять только пересекающиеся/соприкасающиеся интервалы. Пустой ответ означает отсутствие записи; не рисовать сплошную доступность.

В capture хранится около семи дней. Границы брать с сервера, не считать фиксированными. Все `START_DATE`, `STOP_DATE`, event `create_date` содержат `Z`. В HLS `#EXT-X-PROGRAM-DATE-TIME` содержит смещение `+0500` (камера в часовом поясе UTC+5), а не московское время пользователя. Для UI явно указывать выбранный пояс; для API преобразовывать к UTC. `ARCHIVE.START_TIME/STOP_TIME` — локальные строки UTC+5 этой камеры, без встроенной зоны.

HLS master содержит дочерний `ts.m3u8`, сегменты MPEG-TS. Live segments около 1.5–2 секунд. Archive — `#EXT-X-PLAYLIST-TYPE:VOD`, `#EXT-X-ENDLIST`, segments около 14–16 секунд, с `PROGRAM-DATE-TIME`. В наблюдаемом запросе первый архивный segment начинался приблизительно за 8 секунд **до** `start-date`. Поэтому точное время плеера = program-date-time первого сегмента + позиция; для перехода учитывать фактическое начало playlist (Media3 `Timeline.Window.windowStartTimeMs`), а не слепо прибавлять позицию к запрошенному началу. Часовые диапазоны делают seek быстрым и ограничивают размер playlist.

Для плеера следовать HTTPS ссылкам на известные media hosts; не пересылать заголовки авторизации произвольному домену из внешнего URL. В capture HLS запросы содержат main Bearer token и User-Agent `skyplayer`.

## События домофона

`GET https://td-crm.is74.ru/api/user/history?from=2026-01-01&to=2026-01-02&page=1&perPage=60`, с **intercom token**.

```json
{
  "data": [
    {
      "create_date": "2026-01-02T10:00:00Z",
      "type": "HANDSET_CALL",
      "params": {"mac":"<relay MAC_ADDR>","address":"<address>","entranceTitle":"<title>"},
      "image_link": "https://td-crm.is74.ru/api/file?path=<opaque>"
    }
  ],
  "page": 1,
  "perPage": 60,
  "count": 16
}
```

`image_link` может быть пустым. Это всё события аккаунта: фильтровать конкретную дверь по нормализованному `params.mac == relay.MAC_ADDR`. Сохранять неизвестные `type` как «Событие домофона», не терять их. В capture подтверждены:

- `HANDSET_CALL` — вызов домофона;
- `OPEN_API` — открытие через приложение;
- `OPEN_HANDSET` — открытие с трубки.

Пагинацию читать до `count` либо пустой страницы; не останавливаться после первой только потому, что в тестовой сессии было 16 событий. Конкретная включительность `to` и часовой пояс календарных фильтров не доказаны capture; допустимо запросить на день шире по обе стороны и отфильтровать точным UTC диапазоном выбранного дня на клиенте.

Тап по событию переводит к UTC `create_date` (предпочтительно с 5–10 сек контекста до него), только если момент находится в доступном архиве. Маркеры таймлайна должны соответствовать этим же моментам. Показать состояния «нет записи» и «нет доступа» отдельно от «нет событий».

`GET https://cams.is74.ru/api/event/extended?CAMERA_ID=...&START_DATE=...&END_DATE=...`, header `api-version: 2.0`, во всех пяти запросах вернул **403** `{"ERRORS":["Нет прав на просмотр событий камеры"]}`. Это отдельная аналитика движения, не замена работающей истории домофона. Не подавлять этот ответ как пустой список и не считать подтверждённой схему успешного ответа.

## Открытие двери

`POST https://api.is74.ru/domofon/relays/<RELAY_ID>/open?from=app`, main token, пустое body, `Content-Type: application/json`.

Успешный ответ capture: `{"statusCode":0}`. HTTP 200 сам по себе недостаточен: проверить `statusCode`. Повторять только по явному действию пользователя; при сетевом timeout не делать retry (дверь уже могла открыться). На время запроса блокировать повторное нажатие, затем короткий cooldown. При неактивной двери или недействительной сессии кнопка должна объяснять состояние.

## Проверка чтения API 4 ноября 2026

С существующими main/td токенами из capture выполнена проверка без операций изменения:

- `/user/address` — HTTP 200.
- `/domofon/relays` — HTTP 200, одна дверь.
- `/api/limited-info-by-uuid` — HTTP 200, одна камера, доступ к live и archive.
- `/api/record/interval/list` — HTTP 200, 29 интервалов в исходном недельном диапазоне; 14 интервалов текущего дня при датах без дробных секунд. Даты ответов UTC с `Z`.
- `/api/user/history` — HTTP 200, 16 событий, типы `HANDSET_CALL`, `OPEN_API`, `OPEN_HANDSET`.
- Актуальные live master/media HLS playlists — HTTP 200, есть `PROGRAM-DATE-TIME` со смещением `+0500`; один MPEG-TS сегмент скачан в память, HTTP 200, корректный sync-byte.
- Актуальная подписанная ссылка archive master/media playlist — HTTP 200, VOD, `ENDLIST`, `PROGRAM-DATE-TIME`.

При этой проверке сервер отдавал HLS playlists с ошибочным `Content-Type: application/json`; тела содержали валидный HLS. Плееру следует явно задавать MIME `application/x-mpegURL`. Проверка TLS оставалась включена. Токены и видео не сохранялись в проект или APK; отчёт содержит только агрегированные результаты.

## Пределы проверки

Контракт подтверждён сохранёнными запросами одной сессии и указанной проверкой чтения. Не проводились новый вход, отправка подтверждения, обмен токенов `/api/auth-lk`, открытие физической двери, изменение прав, создание ключей или регистрация устройства. Скачивание одного сегмента не доказывает воспроизведение/декодирование на конкретном устройстве. Поведение ошибок авторизации, повторная выдача кода, множество договоров/камер и отрицательные ответы открытия в capture отсутствуют; приложению нужны обычные устойчивые состояния ошибок, без выдуманного протокола.
