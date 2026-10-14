# Dayline

An offline day planner for Android with a calm, minimal design.

## What it does
- **Timeline** of your day with free time between tasks and a "now" marker
- **Repeats** every day or on chosen weekdays (e.g. Mon, Wed, Fri)
- **Checklists** inside a task, with progress like 2/5
- **Carry over** unfinished tasks from earlier days in one tap
- **Reminders** that ring with the app closed, with **Done** and **Snooze 10 min** buttons
- **Morning summary** notification at a time you choose
- **Focus mode** with a countdown for the task you're doing now
- **Inbox** for to-dos without a time
- **Plan with Claude**: describe your day in plain words (needs your own Claude API key)
- **Backup and restore** to a file, **undo** for deletes and ticks, weekly progress and streak
- Light, dark, or follow the phone

## Install
Open the latest release on this repo from your phone, download **Dayline.apk**, and open it. A new APK installs over the old one and keeps your tasks.

## How builds work
Every push to `main` runs `.github/workflows/build.yml`, which builds a signed APK and attaches it to a new release.

## Where things live
- `app/src/main/assets/index.html` - all screens (HTML/CSS/JS). Tasks are saved on the phone.
- `ReminderScheduler.java` - exact alarms for the next 3 days, snooze, morning summary.
- `ReminderReceiver` / `Notifier` - notifications and their Done/Snooze buttons; `BootReceiver` restores alarms after a restart.
- `Bridge.java` - what the screens can ask the phone to do (reminders, backup files, Claude requests, theme bars).

Font: Figtree (SIL Open Font License), bundled from Fontsource.

Crafted by Akash Pawar.
