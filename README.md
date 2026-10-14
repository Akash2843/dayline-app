# Dayline

An offline day planner for Android: a week strip, a morning-to-night timeline with free time between tasks, an inbox for to-dos without a time, and reminder notifications that ring at each task's time (even when the app is closed).

## Install
Open the latest release on this repo from your phone, download **Dayline.apk**, and open it. Android will ask you to allow installs from your browser or Files app the first time.

## How builds work
Every push to `main` runs `.github/workflows/build.yml`, which builds a signed APK and attaches it to a new release. Each build has a higher version number, so a new APK installs over the old one and keeps your tasks.

## Where things live
- `app/src/main/assets/index.html` - the planner screen (HTML/CSS/JS). Tasks are saved on the phone.
- `app/src/main/java/com/akash/dayline/ReminderScheduler.java` - turns tasks into exact alarms for the next 3 days.
- `ReminderReceiver` / `Notifier` - show the notification; `BootReceiver` restores alarms after a restart.

Fonts: Bricolage Grotesque, Figtree and DM Mono (SIL Open Font License), bundled from Fontsource.
