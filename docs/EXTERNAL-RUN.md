# Внешний запуск, остановка и выполнение команд

Справка для Sshd4a extented (версия 1.13.2.31 и новее). Команды ниже выполняются из `adb shell`,
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

## Запуск и остановка службы в фоне (рекомендуется, версия 1.13.2.31+)

Приёмник `ControlReceiver` запускает и останавливает сервер **без открытия окна приложения**:

```
am broadcast -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.ControlReceiver -a com.hardbacknutter.sshd.fg.START
am broadcast -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.ControlReceiver -a com.hardbacknutter.sshd.fg.STOP
```

С компьютера добавьте `adb shell` в начало. `am broadcast` печатает результат:
`Broadcast completed: result=<код>, data="<сообщение>"`.

| result | Значение |
|---|---|
| 1 | выполнено (сервис запущен / остановлен) |
| 2 | делать нечего (уже работает / не работает) |
| 3 | выключена опция «Allow starting the service by an external Intent» |
| 4 | не удалось (в data причина, например ограничение запуска foreground-сервиса из фона Android 12+) |
| 5 | STOP отклонён: сервис запущен не внешним интентом (как и в варианте через activity, останавливать можно только то, что запущено интентом) |

Если запуск из фона не разрешён системой (result=4, `ForegroundServiceStartNotAllowedException`), пришлите
текст из data: приложение должно быть исключено из оптимизации батареи (это исключение снимает
ограничение), иначе придётся искать другой способ.

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
