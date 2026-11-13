# Sshd4a extented — техническая спецификация

Актуально для версии **1.13.2.36**. Документ описывает то, что реализовано и проверено
на устройстве, отдельно от планов (раздел 13). Пометки: **[проверено]** — подтверждено
на устройстве владельца; **[не проверено]** — реализовано, но ещё не подтверждено.

Форк проекта [tfonteyn/Sshd4a](https://github.com/tfonteyn/Sshd4a) (dropbear + rsync + sftp-server).
Апстрим явно запрещает root-функциональность, поэтому всё описанное живёт только в этом форке
и не предлагается в апстрим.

- applicationId: `com.roshkond.sshd4a.extended`, имя в лаунчере «Sshd4a extented», ставится рядом с оригиналом.
- minSdk 30 (Android 11).
- Репозиторий: https://github.com/roshkondeua/extented-Sshd4a, ветка `main`.

---

## 1. Роли входа

Три роли SSH-сессии. Логин и пароль задаются для каждой роли отдельно в настройках приложения.
Формат записи (файл авторизованных пользователей): `user:hash[:role]`, роль необязательна
(по умолчанию `user`). Хеш — sha512 в base64.

| Роль | uid | Как получаем | HOME | Приглашение |
|---|---|---|---|---|
| user | uid приложения (`u0_aNNN`) | напрямую, dropbear | домашняя папка из настроек | `$PWD $` |
| shell | 2000 | через `su` | `/data/local/tmp/Sshd4a/shell/home` | `$PWD şҥ` |
| root | 0 | через `su` | `/data/local/tmp/Sshd4a/root/home` | `$PWD #` |

Выбор роли делает пропатченный dropbear: `fill_passwd` в `common-session.c` по роли пользователя
подставляет `pw_dir` и `pw_shell` (для root и shell login-shell — это `libsuroot.so` и `libsushell.so`,
см. раздел 2). Для роли user используется оболочка из настроек.

## 2. Запуск оболочек root и shell (W^X и особенности su)

### 2.1 Почему не обычный скрипт

С Android 10 процесс приложения не может исполнить файл, записанный самим приложением (в том числе
в `/data/local/tmp`). Поэтому login-shell для root и shell — скомпилированные бинарники
`libsuroot.so` и `libsushell.so` в `nativeLibraryDir`. Источник один, `su-shell/su-login-shell.c`,
собирается дважды через CMake с `-DSSHD4A_SU_TARGET_UID=0` и `=2000`.

### 2.2 Особенности su на устройстве владельца **[проверено]**

- `su UID <путь_к_файлу>` игнорирует путь и понимает только имена оболочек вроде `sh`;
- режим `su ... -c` теряет управляющий терминал.

Поэтому всегда запускается голый `su UID sh`, а нужное окружение приходит через переменную `ENV`
(файлы `etc/rc-root`, `etc/rc-shell`). Тот же приём используется для `su user` и для Termux.

### 2.3 Передача в bash

В конце rc-файла стоит передача управления в bash, защищённая переменной `SSHD4A_SHELL_UPGRADED`
(иначе bash, перечитав тот же файл, зациклится): сначала `/system/bin/bash --rcfile <rc>`
(Magisk bash 5.0.16 **[проверено]**), иначе `bin/bash` голым (его `--rcfile` **[не проверен]**).
Если `/system/bin/bash` нет (так на части устройств), стартует наш `bin/bash` 4.1.0 **без** `--rcfile`; он читает
не `$ENV`, а `~/.bashrc`, поэтому провижининг создаёт `root/home/.bashrc` и `shell/home/.bashrc`, которые подключают
`rc-root` / `rc-shell` (файл с пометкой `SSHD4A_GENERATED` пересоздаётся; без пометки — считается вашим и не трогается).
Без этого в такой сессии не было алиаса `tmux`, функции `su` и т. д. **[найдено на устройстве: `$0` = `bin/bash`, `$BASH_VERSION` = 4.1.0, `alias` пуст]**.
Урезанный bash от bnsmb на `--rcfile` роняет сессию — не использовать. Собственные сборки
bash 4.1.0 и 3.2.0 лежат в `docs/pkg-index/binaries/`.

### 2.4 Вложенный su

`su` из сессии наследует `ENV` родителя. Чтобы root-оболочка, запущенная из shell-сессии, не получила
профиль shell, `rc-shell` при `id -u = 0` подключает `rc-root` **[проверено]**.

## 3. Переключение пользователей: функция `su`

Функция `su` определена в `etc/rc-lib` и подключается из rc-root, rc-shell и rc-user:

| Команда | Действие |
|---|---|
| `su`, `su root`, `su r` | `su 0 sh` с `ENV=rc-root` |
| `su shell`, `su s` | `su 2000 sh` с `ENV=rc-shell` |
| `su user`, `su u` | `su <uid приложения> sh` с `ENV=rc-user` **[проверено: whoami = u0_a295]** |
| прочее | системный `su` без изменений |

Перед запуском сбрасывается `SSHD4A_SHELL_UPGRADED`, чтобы новая оболочка снова могла перейти в bash.

## 4. Файловая раскладка (`/data/local/tmp/Sshd4a`)

```
bin/            pkg-установленные утилиты и команды (pkg, sshd4a, version, ver, help, termux, tmx, busybox)
etc/            конфигурация (см. 4.1)
tmp/            общий /tmp, права 1777 (нужен tmux и др.; на Android /tmp нет)
root/home/      HOME root; chmod 701; внутри autostart.d (700, root)
shell/home/     HOME shell; chown 2000, chmod 701; внутри autostart.d (700, 2000)
```

Домашние папки имеют права 701, чтобы `chdir` dropbear до `su` проходил. Файлы rc лежат здесь, а не в
приватной папке приложения, потому что SELinux-категория приложения блокирует uid shell независимо от
unix-прав.

### 4.1 Файлы в `etc/`

Создаются один раз и дальше **не перезаписываются** (правьте свободно):

| Файл | Назначение |
|---|---|
| `bashrc-common` | алиасы для root и shell |
| `custom-root`, `custom-shell` | дополнения для одной роли |
| `tmux.conf` | общий конфиг tmux (изначально `set -g mouse on`) |
| `pkg-repos.list` | список репозиториев пакетов |

**Пересоздаются** при каждом запуске службы и сохранении настроек (не править):
`rc-root`, `rc-shell`, `rc-user`, `rc-lib`, `rc-termux`, `env`, `termux-info`, `app-version`, `help.txt`.

Переменные в rc-root/rc-shell/rc-user: `HOME`, `TMPDIR`, `TMUX_TMPDIR`, `TERMINFO=/system/usr/share/terminfo`,
`PATH` дополняется `bin/`, `HISTFILE` в HOME.

## 5. Провижининг

`RootProvisioner.java`: через `su`, скрипт подаётся в stdin, файлы пишутся heredoc'ами. Запускается
при старте службы и при сохранении настроек, только если задана хотя бы одна из ролей root/shell.
Затем при каждом старте службы выполняется автозапуск (раздел 7) и записываются причины прошлых
завершений процесса (раздел 11).

Из-за гонки со стартом службы вход под shell/root в первые секунды после установки или перезапуска
может получить неполный rc-файл (файл пересоздаётся) — в этом случае достаточно переподключиться.
**[гипотеза, замеченная один раз; не подтверждена]**

## 6. tmux

- Бинарник ставится через `pkg install tmux`.
- `tmux` — алиас, всегда с `-S <tmp>/tmux-<роль>.sock -f etc/tmux.conf`. Одного `TMUX_TMPDIR` недостаточно
  («no suitable socket path»).
- **Сокет у каждой роли свой** (`tmux-root.sock`, `tmux-shell.sock`, `tmux-user.sock`): tmux создаёт сокет
  с правами 700 от своего uid, общий сокет даёт «Permission denied» **[проверено]**.
- `tmux` без аргументов и `tmux new-session -d` работают **[проверено на 1.13.2.33]**; сервер tmux переживает перезапуск службы.
  Прежние сбои («no suitable socket path», `[exited]`) объяснялись тем, что в сессии не загружался профиль
  (голый `bin/bash` не читает `$ENV`; см. раздел 2.3).

## 7. Автозапуск

Папка `~/autostart.d` у каждой роли (root/home, shell/home, домашняя папка приложения для user).
При каждом старте службы файлы по порядку имён запускаются командой `sh <файл>` от своей роли:
root и shell — из одного `su`-скрипта (shell через вложенный `su 2000 sh`), user — из приложения.
Вывод дописывается в `~/autostart.log`.

**Запуск асинхронный:** каждый скрипт уходит в фон, следующий стартует сразу, не дожидаясь
завершения. Порядок имён определяет только порядок старта. При перезапуске службы скрипты запускаются
снова — они должны переносить повторный запуск.

## 8. Пакетный менеджер `pkg`

Скрипт `assets/pkg` → `bin/pkg`. Индекс `docs/pkg-index/default.list`, отдаётся через
`raw.githubusercontent.com`. Формат строки: `name|arch|version|url|sha256|install_name`, `#` — комментарий.

Команды: `pkg update`, `list`, `installed`, `install [--force] ИМЯ`, `remove ИМЯ`,
`repo list|add URL|remove URL`.

- Загрузка через `busybox wget` (curl с c-ares не резолвит DNS на Android); без busybox — curl.
- Проверка sha256; без `--force` не перекрывает то, что уже есть в системе.
- Индекс по умолчанию — в основном bnsmb/binaries-for-Android (только arm64), плюс собственные
  сборки bash для обеих архитектур. Под armeabi-v7a пока только bash.
- Устанавливает в `bin/`, принадлежащий root: для роли user установка не работает.
- Короткое имя `busybox`: обёртка `bin/busybox` запускает `libbusybox.so` из `nativeLibraryDir`
  (не перезаписывает настоящий busybox, установленный через pkg). **[не проверено]**
- `libbusybox.so` (meefik 1.34.1, arm64-v8a и armeabi-v7a) лежит в `src/main/jniLibs/`, так как артефакты
  CMake custom_command AGP не упаковывает.

## 9. Внешнее управление

Полная справка: [`EXTERNAL-RUN.md`](EXTERNAL-RUN.md).

Действия интентов остались как в апстриме (`com.hardbacknutter.sshd.fg.START|STOP|EXEC`), а пакет у форка
другой (`com.roshkond.sshd4a.extended`; классы остались в namespace `com.hardbacknutter.sshd`). Так как оригинал
ставится рядом и слушает те же действия, команды нужно давать с явным компонентом:

```
am start     -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.QuickStartActivity -a com.hardbacknutter.sshd.fg.START|STOP
am broadcast -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.ControlReceiver     -a com.hardbacknutter.sshd.fg.STOP|START
am start     -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.MainActivity        -a com.hardbacknutter.sshd.fg.START|STOP
am broadcast -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.ExecReceiver        -a com.hardbacknutter.sshd.fg.EXEC --es cmd "id" --es exec_as root
```

- **`QuickStartActivity`** (с 1.13.2.32): невидимая (`Theme.NoDisplay`) activity без intent-filter, запускает или останавливает
  службу и закрывается. Нужна потому, что с Android 12 foreground-сервис нельзя стартовать из фона, а приёмник
  остановленного приложения под исключения не попадает **[подтверждено логом: `Background started FGS: Disallowed … DENIED`]**;
  activity из shell переводит приложение в состояние «на экране». Результат пишется в logcat (тег `ExternalControl`) и в файловый лог.
  Работа без окна **[не проверена]**.
- **Внешний STOP** (`ExternalControl.stop` → `SshdService.stopService(ctx, true)`) перед остановкой слушателя посылает SIGTERM
  всем его прямым потомкам (процесс dropbear на каждое соединение), затем убивает слушатель. Оболочки получают hangup;
  отвязанные процессы (nohup/setsid/tmux) живут. Остановка из интерфейса приложения и «чистка» устаревшего процесса при
  старте сессии не трогают — как в апстриме **[не проверено]**.
- **`ControlReceiver`**: возвращает result-код и сообщение (`am broadcast` их печатает). STOP работает всегда,
  START — только когда Android разрешает старт из фона (иначе result=4). Общая логика обоих входов — `ExternalControl`.
- START/STOP через `MainActivity` (`singleTask`) оставлены как в апстриме; всплывает окно приложения.
  Включается опцией «Allow starting the service by an external Intent» (она же нужна и для двух вариантов выше).
- **EXEC-интент** (`ExecReceiver`): extras `cmd`, `exec_as` = `root|shell|user` (по умолчанию `user`),
  необязательный `token`. По умолчанию выключен, при включении показывается предупреждение. Токен необязателен —
  решение владельца. Команда выполняется через `su <uid> sh -c`; результат команды не возвращается (только лог),
  принятие запроса видно в result-коде.
- Формы с `-n` **[не проверены]** на устройстве.

## 10. Команды CLI

| Команда | Действие |
|---|---|
| `version`, `ver`, `sshd4a -v` | версия из `etc/app-version` |
| `help`, `sshd4a help` | справка из `etc/help.txt`: на русском при русской локали приложения, иначе на английском **[проверка ru — в 1.13.2.30]** |
| `sshd4a start`, `stop`, `status` | запуск и остановка без окна (через `QuickStartActivity`), проверка порта по `etc/sshd-port` |
| `sshd4a env [--file]` | код для `eval`, добавляющий `bin/` в PATH; файл `etc/env` для `. etc/env` |
| `termux`, `tmx` | вход в Termux (раздел 12) |

`help` — функция в rc-lib (в bash встроенная `help` иначе перекрывает файл) и скрипт `bin/help`.
Планируется: `sshd4a --get-settings` / `--set-settings` (нужен мост к настройкам приложения).

## 11. Диагностика

- `sshd4a-debug.log` (`FileLogger`, путь и включение — в настройках, секция Diagnostics; пишет напрямую,
  при отказе через su).
- `wrapper-debug.log` — пишет C-обёртка в файлах приложения.
- `ExitReasonLogger`: при каждом старте службы пишет новые записи `getHistoricalProcessExitReasons`
  (причина, статус, importance, PSS/RSS, описание) в `sshd4a-debug.log`. Нужна включённая запись лога.
  Позволяет выяснить, кто убил процесс, без подключения в момент убийства.

## 12. Termux (`termux` / `tmx`)

**С root [проверено на Termux 0.118.3]:** команда читает `etc/termux-info` (снимок из PackageManager на
старте службы: установлен ли, версия, источник установки, uid) и запускает
`su <uid Termux> sh` с `ENV=etc/rc-termux`. Профиль задаёт `HOME`, `PREFIX`, `PATH`, `TMPDIR`, `LANG`,
`LD_PRELOAD=libtermux-exec.so` (если есть) и вызывает штатный `login` Termux.

Сообщения: Termux не установлен → предложение F-Droid/GitHub; источник `com.android.vending` (Google Play —
отдельная сборка с другой подписью, не поддерживается проектом Termux) → предупреждение; нет su →
сообщение, что без root режим ещё не реализован. В манифесте для этого есть `<queries>` на `com.termux`.

**Без root — не реализовано.** План: Termux `RUN_COMMAND` (нужны Termux с F-Droid или GitHub,
`allow-external-apps=true` и разрешение `com.termux.permission.RUN_COMMAND`). Это не интерактивный shell:
у RUN_COMMAND нет pty, возможны режимы «команда → вывод» или sshd внутри Termux с подключением на localhost.

## 13. Сборка

Только через GitHub Actions (в песочнице нет Android SDK). Запуск:
`POST /repos/roshkondeua/extented-Sshd4a/actions/workflows/build.yml/dispatches {"ref":"main"}`.
Шаги: JDK 21; Android SDK вручную через cmdline-tools (`android-actions/setup-android` сломался
2026-09-14, Google удалил пакет `tools`); NDK 30.0.14904198; CMake 4.1.2; `prebuild-rsyn.sh`,
`prebuild-dropbear.sh`; keystore из Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD`; PKCS12); `gradlew assembleRelease`.

- Версия: `ext.applicationVersionName` в корневом `build.gradle` (схема `1.13.2.N`), `versionCode` = 1000 + номер запуска.
- APK публикуется как GitHub Release `build-N`.
- При падении шага Gradle лог пушится в ветку `ci-logs` (`ci-logs/run-<id>.log`).
- Ключ подписи менять нельзя: обновления поверх перестанут устанавливаться.

## 14. Magisk-модуль (1.13.2.34+; псевдоним `sshd` и `shell` — с 1.13.2.35)

Только для рутованного устройства с Magisk (на устройстве владельца Magisk 29: `magisk --install-module ZIP` есть **[проверено]**).
Установка и удаление — в Настройках приложения, раздел «Magisk module (optional)», с подтверждением и показом вывода.

**Что делает модуль.** Добавляет **два новых файла-«шима»**: `/system/bin/sshd4a` и короткий псевдоним `/system/bin/sshd` (`sshd start|stop|status|shell`).
Шим только вызывает актуальный CLI `bin/sshd4a` (его обновляет провижининг), поэтому сам модуль приходится переустанавливать редко
(с 1.13.2.36; до этого в модуль был встроен снимок CLI). Команды работают в `adb shell` и в любом терминальном приложении
без полного пути и без root **[проверено на 1.13.2.34: после перезагрузки `sshd4a status` из adb shell и из JuiceSSH под `u0_a421` находится по имени]**.
Ничего не заменяет, не ставит скриптов загрузки (`service.sh`, `post-fs-data.sh` нет), не меняет правила SELinux.

- `status` без root: приложение не читает `/proc/net/tcp`, поэтому используется проверка баннера SSH через `nc` на 127.0.0.1 **[не проверено]**.
- `shell`: запускает `sh -i` с `ENV` = `rc-root` / `rc-shell` / `rc-user` по uid вызывающего (PATH, алиас tmux, приглашение) **[не проверено]**.
  Окружение родительской оболочки скрипт изменить не может, поэтому это именно новая вложенная оболочка (`exit` возвращает назад).

**Меры безопасности:**
- Установка только штатным установщиком Magisk (`magisk --install-module`), zip собирается приложением (`MagiskModule.java`) в кэше.
  Активируется после перезагрузки; при ошибке установщика ничего не включается.
- `customize.sh` отказывается ставить, если `/system/bin/sshd4a` или `/system/bin/sshd` уже есть и принадлежит не нашему модулю, либо Android ниже 11.
- Удаление: «Remove the module» ставит штатный флаг `remove` (применяется при перезагрузке) либо отключение/удаление в приложении Magisk.
- Имена `sshd4a` и `sshd` выбраны как не занятые. В `/system/bin` уже могут быть файлы других модулей (например `busybox-ndk` даёт `busybox`),
  поэтому имена вроде `busybox`, `tmux`, `bash` модуль не добавляет.

**Запуск наших программ обычным приложением [проверено на 1.13.2.35]:** `/data/local/tmp/Sshd4a/bin/tmux -V` из терминального приложения
под `u0_a421` без root печатает `tmux 3.5a`. Смена SELinux-метки (`chcon … system_file`), которую я планировал, **не нужна**.

**Ограничение без root:** `sshd start|stop` отправляет интент командой `am`, а она работает только из `adb shell` и под root (ссылается на пакет
`com.android.shell`; обычный uid получает отказ). В терминале обычного приложения CLI пробует `su -c am …` (если `su` выдан приложению),
иначе сообщает причину. Запуск без root и без adb возможен только из приложений, умеющих отправлять интенты (Tasker, MacroDroid и т. п.).

**Следующие шаги (не реализованы):** посредник с белым списком приложений вместо «доступ для всех»; автозагрузка и меры выживания
процессов (раздел 15).

## 15. Выживание процессов

**Данные ExitReason с устройства `obsidian` (с 1.13.2.29, 25 записей):** подавляющее большинство завершений —
`LOW_MEMORY` при importance=400 (процесс уже «в кэше», то есть служба не работала как foreground), одно `SIGNALED`/9
при importance=230, остальное — принудительные остановки пользователем (`[FORCE STOP] … key shortcut`) и обновления пакета.
Убийств работающего foreground-сервиса (importance 125) в этих данных нет. Для Redmi 12C журнал ещё не снят.

 на Redmi 12C (Android 12, MIUI, без root)

Система убивает приложение несмотря на автозапуск, «без ограничений», закрепление и исключение
из оптимизации батареи. Сервис — foreground (specialUse), START_STICKY. Сервис и dropbear живут в
процессе и под uid приложения, поэтому всё, что стартует из приложения, умирает вместе с ним.

Гипотеза (не подтверждена): «убийца фантомных процессов» Android 12 (больше 32 процессов или высокая
нагрузка). Проверка — записи `ExitReasonLogger`.

Известные меры через adb (не применялись):
```
device_config set_sync_disabled_for_tests persistent
device_config put activity_manager max_phantom_processes 2147483647
cmd deviceidle whitelist +<pkg>
cmd appops set <pkg> RUN_IN_BACKGROUND allow
cmd appops set <pkg> RUN_ANY_IN_BACKGROUND allow
```
На Android 12L и 13+: `settings put global settings_enable_monitor_phantom_procs false`.

Идеи: служба специальных возможностей (система держит привязку к процессу); эксперимент
`setsid nohup sleep 86400 &` от uid shell; встроенный ADB-клиент (беспроводная отладка Android 11+) для
применения этих настроек и запуска отвязанных демонов; для рутованного телефона — Magisk-модуль
(`magisk --install-module`, `service.sh` при загрузке, `oom_score_adj`, `deviceidle whitelist`).
Device Owner отложен. Перед использованием готовых библиотек ADB проверить лицензии: в сборку входит
rsync, то есть вероятно GPLv3.

## 16. Открытые задачи

1. Внешний STOP не завершает сессии: `terminateSessions` находил 0 процессов, хотя `ps` показывает ребёнка слушателя; в 1.13.2.34 три способа поиска с диагностикой в логе.
2. Termux без root (раздел 12).
3. `sshd4a --get-settings / --set-settings`.
4. Индекс пакетов под armeabi-v7a (сейчас только bash); разобрать Magisk bash-модуль на сборки x64/x32.
5. Проверить `--rcfile` на собственном bash 4.1.0.
6. Убрать устаревшую настройку su-style; локализовать новые строки (сейчас только английские, кроме справки).
7. Раздел 15: журнал ExitReason с Redmi, команды Android 12, ADB-клиент; Magisk-модуль — следующие шаги из раздела 14.
