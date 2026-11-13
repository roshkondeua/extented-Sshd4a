# Внешний запуск, остановка и выполнение команд

Справка для Sshd4a extented (версия 1.13.2.36 и новее). Команды ниже выполняются из `adb shell`,
из любой оболочки на самом устройстве (SSH-сессия, Termux, скрипт, root) — синтаксис одинаков.
С компьютера перед командой добавляется `adb shell`.

> **Статус:** формы с явным компонентом (`-n`) составлены по манифесту и исходному коду приложения.
> На устройстве владельца ещё **не проверены**.

## Почему имя пакета другое

Приложение ставится рядом с оригинальным Sshd4a, поэтому у него другой идентификатор пакета:

| | Оригинал (апстрим) | Этот форк |
|---|---|---|
| Пакет (applicationId) | `com.hardbacknutter.sshd` | `com.roshkond.sshd4a.extended` |
| Классы внутри (namespace) | `com.hardbacknutter.sshd.*` | `com.hardbacknutter.sshd.*` (не менялись) |
| Действия (action) интентов | `com.hardbacknutter.sshd.fg.START/STOP/EXEC` | те же (не менялись) |

Отсюда проблема старой справки: действия `…fg.START` и т. д. у обоих приложений одинаковые. Команда
`am start -a com.hardbacknutter.sshd.fg.START` без указания приложения либо уйдёт в оригинал, либо
покажет окно выбора, и наш сервис не запустится. Поэтому **всегда указывайте компонент или пакет**.

Имя компонента состоит из пакета форка и класса из старого namespace:

- Activity: `com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.MainActivity`
- Приёмник EXEC: `com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.ExecReceiver`

## Подготовка

В настройках приложения включите нужные опции (по умолчанию обе выключены):

- **Allow starting the service by an external Intent** — для START и STOP;
- **Allow external command execution** (раздел External command execution) — для EXEC. Токен там же
  необязателен; если он задан, его надо передавать в `--es token`.

Без включённой опции интент молча игнорируется. Безопасности по умолчанию нет: пока опция включена,
запустить или остановить сервис (и выполнить команду, если включён EXEC) может любое приложение
на устройстве.

## Запуск и остановка без окна (рекомендуется, версия 1.13.2.32+)

Невидимая activity `QuickStartActivity` запускает или останавливает сервер и сразу закрывается; окна приложения
на экране не появляется:

```
am start -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.QuickStartActivity -a com.hardbacknutter.sshd.fg.START
am start -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.QuickStartActivity -a com.hardbacknutter.sshd.fg.STOP
```

С компьютера добавьте `adb shell` в начало. Результат `am start` не показывает: смотрите
`logcat -d -s ExternalControl` (там строка `quick-activity|start|result=<код>|<сообщение>`), а при включённой
записи лога — `sshd4a-debug.log`.

**Почему именно activity.** Начиная с Android 12 приложение в фоне не может запустить foreground-сервис.
Приёмник широковещательных сообщений остановленного приложения под это не подпадает: система отвечает
`ForegroundServiceStartNotAllowedException` (проверено логом на устройстве: `uidState: RCVR … DENIED`,
исключение от батареи не помогло). Activity, запущенная из adb/shell, на мгновение переводит приложение в
состояние «на экране», и запуск сервиса разрешается.

## Остановка и запуск через приёмник (без activity)

```
am broadcast -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.ControlReceiver -a com.hardbacknutter.sshd.fg.STOP
am broadcast -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.ControlReceiver -a com.hardbacknutter.sshd.fg.START
```

Приёмник печатает результат: `Broadcast completed: result=<код>, data="<сообщение>"`.

| result | Значение |
|---|---|
| 1 | выполнено (сервис запущен / остановлен) |
| 2 | делать нечего (уже работает / не работает) |
| 3 | выключена опция «Allow starting the service by an external Intent» |
| 4 | не удалось (в data причина) |
| 5 | STOP отклонён: сервис запущен не внешним интентом (как и в апстриме, останавливать можно только то, что запущено интентом) |

**Что делает STOP.** Закрывает приём новых подключений **и завершает активные SSH-сессии**: каждому процессу
сессии посылается SIGTERM, оболочки получают hangup. Процессы, отвязанные намеренно (`nohup`, `setsid`, сервер
tmux, задания из `autostart.d`), продолжают работать. (Остановка кнопкой в приложении ведёт себя как в апстриме:
закрывается только приём подключений, текущие сессии остаются.) Остановка самого приложения как процесса — отдельная
вещь и этой командой не делается.

**STOP через приёмник работает всегда. START через приёмник** сработает только пока Android разрешает запуск
сервиса из фона (приложение на экране или в разрешённом состоянии); для остановленного приложения вернётся
`result=4` с `ForegroundServiceStartNotAllowedException`, поэтому для START используйте `QuickStartActivity`.

## Запуск и остановка с открытием окна (вариант через activity)

То же через `MainActivity`; на экране появляется окно приложения:

```
am start -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.MainActivity -a com.hardbacknutter.sshd.fg.START
am start -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.MainActivity -a com.hardbacknutter.sshd.fg.STOP
```

- Если сервис уже работает, START ничего не делает.
- Из Termux (без root) или другого фонового приложения Android может запретить запуск activity
  из фона; нужно разрешение «Поверх других окон». Из `adb shell` и из root ограничение не действует.

## Выполнение команды (EXEC)

```
am broadcast -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.ExecReceiver \
    -a com.hardbacknutter.sshd.fg.EXEC --es cmd "id" --es exec_as root
```

Параметры (extras):

| Параметр | Обязателен | Значение |
|---|---|---|
| `cmd` | да | команда |
| `exec_as` | нет | `root`, `shell` или `user` (по умолчанию `user`) |
| `token` | только если задан в настройках | токен |

Заметки:

- Команда выполняется как `su 0 sh -c "<cmd>"` (root), `su 2000 sh -c "<cmd>"` (shell) или `sh -c "<cmd>"`
  (user). Запуск идёт в фоне, результат не возвращается.
- `am broadcast` показывает, принят ли запрос: result=1 принят, 3 выключена опция, 6 неверный токен, 7 нет `cmd`.
  Само выполнение результата не даёт: вывод и код возврата пишутся в `sshd4a-debug.log`, если включена запись лога (Settings → Diagnostics),
  и в logcat (тег `ExecReceiver`).
- Команду с пробелами и `;` с компьютера передавайте в одинарных кавычках целиком:
  `adb shell 'am broadcast -n ... --es cmd "id; ls /data"'`.

## Если не сработало

1. Проверьте, что опция в настройках включена, и что установлена именно версия форка:
   `pm list packages | grep sshd` должен показать `com.roshkond.sshd4a.extended`.
2. Запустите команду с `-W` (для `am start`) и посмотрите вывод; при ошибке компонента `am` напишет
   `Error type 3` или `does not exist`.
3. Включите Diagnostics и смотрите `sshd4a-debug.log`.

## Короткие команды: `sshd4a start | stop | status`

Скрипт `sshd4a` лежит в `/data/local/tmp/Sshd4a/bin` и вызывает `QuickStartActivity` за вас:

```
/data/local/tmp/Sshd4a/bin/sshd4a start
/data/local/tmp/Sshd4a/bin/sshd4a stop
/data/local/tmp/Sshd4a/bin/sshd4a status
```

В `adb shell` короткое имя `sshd4a` не находится, потому что `PATH` оболочки adb не содержит `bin/`; либо пишите
полный путь, либо один раз выполните `. /data/local/tmp/Sshd4a/etc/env`. `status` проверяет, слушается ли порт из
настроек (файл `etc/sshd-port`, обновляется при старте службы). Нужна опция «Allow starting the service by an
external Intent». Короткие имена прямо в `adb shell` без `. etc/env` — задача для будущего Magisk-модуля.

## С Magisk-модулем (Настройки → «Magisk module»)

Модуль кладёт в `/system/bin` команды `sshd4a` и `sshd`, поэтому полный путь не нужен ни в `adb shell`, ни в других терминалах:

```
sshd start       # или sshd4a start
sshd stop
sshd status      # без root проверяет баннер SSH через nc, с root/shell — читает /proc/net/tcp
sshd shell       # оболочка с окружением приложения для текущего пользователя
```

`sshd shell` запускает новую вложенную оболочку (родительскую изменить нельзя): под root подключается профиль root, под shell (uid 2000) —
профиль shell, под остальными — профиль обычного пользователя; `exit` возвращает в прежнюю оболочку.

### Ограничение: терминал обычного приложения без root

Команду `am` из обычного приложения использовать нельзя (она работает только из `adb shell` и под root). Поэтому `sshd start` / `sshd stop`
в таком терминале повторяют запрос через `su -c am ...`, если `su` разрешён этому приложению; иначе пишут причину.
`sshd status` и запуск наших программ (`/data/local/tmp/Sshd4a/bin/tmux` и т. п.) под обычным приложением работают.
