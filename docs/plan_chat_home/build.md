# Building it (4.9.0)

How [the plan](README.md) is built: the steps in order, each a commit, then the tests, the docs and the phone check. Branch `v4.9.0` from `main`, which has 4.8.0 (#150) merged; `versionCode` 89, `versionName` "4.9.0" in `app/build.gradle.kts`; PR titled "4.9.0: the app opens on the chat". About 200 lines of Kotlin in eight files, and no store, prompt, schema or golden changed.

## The steps

### 1. The routes, pure (`ui/Home.kt`, `HomeTest`)

The choices the activities make are given by one small object, so they're tested without Android, as `DayModel` and `ChatScreenState` are:

```kotlin
/** Where the app opens (D-121): the launcher's start goes to the chat, the app's home. */
object Home {
    /** What a start of [MainActivity] does before it draws anything. */
    enum class Start { Chat, Leave, Stay }

    /**
     * The launcher's own start ([launcher]: `ACTION_MAIN` with `CATEGORY_LAUNCHER`) of a task's root hands over
     * to the chat; one on top of a task already there finishes, so what was on top shows (Q114). Made again
     * ([restored]) or asked for a tab or a task, it stays.
     */
    fun start(launcher: Boolean, root: Boolean, restored: Boolean, asked: Boolean): Start = when {
        restored || asked || !launcher -> Start.Stay
        root -> Start.Chat
        else -> Start.Leave
    }

    /** Back on Day, Work or Stats with nothing under it opens the chat in its place (Q116). */
    fun backOpensChat(root: Boolean): Boolean = root
}
```

`HomeTest` covers:
- the launcher's start of a fresh task goes to the chat;
- the launcher's start on top of a live task leaves;
- a start made again after rotation or process death stays;
- the widget's start (no action) stays;
- a start with `EXTRA_TAB` or `EXTRA_TASK` stays, `OPEN_SETUP` included;
- Back opens the chat only at the task's root.

### 2. The launcher hands over (`MainActivity`)

- In `onCreate`, before `AppGraph.get`, `openSetupIfAsked` (which takes `EXTRA_TAB` off the intent) and `setContent`: `Home.start(intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER), isTaskRoot, savedInstanceState != null, intent.hasExtra(EXTRA_TAB) || intent.hasExtra(EXTRA_TASK))`.
  - On `Chat`: start `AskActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)`, then `finish()`, and return. The flag works from API 1, so it suits `minSdk` 26. `overrideActivityTransition` needs API 34, and its `OPEN` override would set this activity's own opening, not the chat's.
  - On `Leave`: `finish()` and return.
  - On `Stay`: go on as now.
- Back: an `OnBackPressedCallback` added in `onCreate`. While `Home.backOpensChat(isTaskRoot)` holds, it starts `AskActivity.intent(this)` and finishes; otherwise it's disabled and Back closes Day as now. A sheet, a dialog or a menu open over Day takes Back itself, in its own window, so Back closes it first, as now.
- `TABS`, `MENU` and the comments on `EXTRA_TAB` and the class say the chat is the home. `MENU` becomes `internal`, as the chat's menu lists it too (step 4).

### 3. The Plan button (`AskActivity`)

- `navigationIcon`: an `IconButton` with `ic_calendar`, described as "Plan". It starts `Intent(this, MainActivity::class.java)` with `FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP` and no tab. Underneath, `MainActivity.onNewIntent` takes it with its tab as it was (it reads `EXTRA_TAB` only when there is one). With none underneath, a new one opens on Day.
- `EXTRA_BACK`, a new extra and the `intent(...)` parameter `back: Boolean = false`: set only by Felix's button on the block screen (`FelixFab(..., back = true)` from `BlockedActivity`). The chat it opens keeps today's Back arrow, as the block screen's task mustn't hold Day (README §Decided without asking). It's kept in `onSaveInstanceState`, as the other extras are.
- Back on the task's root: `moveTaskToBack(true)` in place of finishing, through an `OnBackPressedCallback` enabled while `isTaskRoot`. The keyboard, the remembered sheet and the clearing dialog still take Back first, as now. A Compose `BackHandler` added to the chat later would take it before this callback, as the dispatcher asks the newest first.
- The manifest: `AskActivity` loses `android:label="Ask Claude"` and takes the application's.

### 4. The bar's mark and menu (`ui/SetupMark.kt`, `MainActivity`, `AskActivity`)

- A move first, a commit of its own: the `attention` expression and its `IconButton` move from `MainActivity.Main` into `@Composable fun SetupMark(graph: AppGraph, activity: Activity)` in `ui/SetupMark.kt`, unchanged. `MainActivity` calls it where the code was.
- Then the chat's `actions` call `SetupMark(graph, this)` before its menu. The menu's three items are followed by a `HorizontalDivider` and `MainActivity.MENU`'s items, each starting its activity as Day's do.

### 5. The fallbacks to the chat (`Notify.rebuild`, `OpenTaskActivity`)

- `Notify.rebuild`'s `app` intent: `AskActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)`. Its request code stays 0; a `PendingIntent`'s identity includes its component, so it clashes with none.
- `OpenTaskActivity` with no task: the same intent.
- Unchanged, by Q115: `NextWidget`'s taps and `FreeTimeNotice.backToWork` (Day), `ModelAlerts`, `Watchdog`, `GmailWriteWorker`, `TimerAlarms` and `TaskOpener` (their tab or Setup). Their Back reaches the chat through step 2.

### 6. Read once seen (`ChatScreenState`, `AskActivity`, `Conversation`, `Outbox`; Q120)

Before this step, the chat on screen cancels his notification (`Outbox.notify` with `chatOnScreen()`), and leaving it reads up to then. After it, both wait till the visit is *seen*.

- **`ChatScreenState`:**
  - `const val READ_AFTER_MS = 3_000L`.
  - A visit's `seen` flag, false from `opened`.
  - `seen(now)`, true once `now - since >= READ_AFTER_MS` or once `markSeen()` has been called. `since` is already the visit's start.
  - `markSeen()` sets the flag, and `sent()` calls it.
  - `paused(read)` is unchanged, but its callers pass `read` only for a seen visit, so `leftAt` moves only then, and a quick visit's *New messages* line comes back next time.
- **`Conversation`:** `var chatSeen = false` beside `chatInFront`. `Outbox` is given `chatSeen = { conversation.chatSeen }` from `AppGraph`, as it's given `chatOnScreen`.
- **`Outbox.notify`:**
  - with the chat seen, his notification is cancelled (today's on-screen branch);
  - with the chat on screen but not yet seen, it's made as usual with `alertFor` dropped, so it's updated and never sounds;
  - off screen, as now.
- **`AskActivity`:**
  - **`onResume`,** with the *Chat* tab showing:
    - `chatInFront = true`, as now;
    - in place of `outbox.notify(now)`, a job on `lifecycleScope` waits `READ_AFTER_MS`, then marks the visit seen, sets `chatSeen = true` and calls `outbox.notify(clock.now())`;
    - opened from his notification (`openedAt`), it's seen at once, as that open reads already.
  - **`onPause`:**
    - the job is cancelled;
    - the visit is read only if it's seen: `screen.paused(read = now.takeIf { chatTab && screen.seen(now) })` and `conversation.read(now)` only then;
    - `chatSeen = false`, `chatInFront = false`;
    - a visit that wasn't seen leaves his notification alone, with no `notify`. Nothing took it away, and a call here would bring back one you'd swiped away.
  - **`showingChat`:** the same pair, for *Your changes* shown and the *Chat* tab back.
  - **Sending:** each of the three ways a message of yours leaves the chat calls `screen.markSeen()`, sets `chatSeen = true` and calls `outbox.notify(now)`, so the notification goes at once:
    - the box's send;
    - a reply chip;
    - words handed in and sent as the chat opens: the `LaunchedEffect(words)` that sends `handed` with `sendHanded`, from the calendar's box through `sendInstruction`.

    Words handed in only to go in the box (`send = false`) don't count, as nothing is sent.
- **Nothing stored changes:** `readAt` is set as before, only later or not at all.

### 7. The icon, the version and the docs

- `res/drawable/ic_calendar.xml`: Material Symbols' *calendar_today*, 24 dp, tinted `?android:attr/colorControlNormal` over a white fill, with its name in a comment, as `ic_chat.xml` is.
- The version, and the docs listed in [README.md](README.md) §The docs the build changes, in the same PR.

## Tests

- New: `HomeTest` (step 1).
- Added to `ChatScreenStateTest` (step 6):
  - a visit is not seen at 2,999 ms and seen at 3,000;
  - `markSeen()` makes it seen at once;
  - `sent()` marks it seen;
  - a new `opened` starts unseen;
  - a visit paused unseen keeps the next visit's *New messages* line on the same messages.
- Added to `OutboxTest` (step 6):
  - on screen and not seen, `notify` keeps his notification and never alerts, a new message included;
  - seen, it's cancelled;
  - off screen, it's as before.
- Added to `ConversationTest`: `read` isn't called for a visit left unseen. Where that's in `AskActivity` alone, the phone check covers it instead.
- Unchanged and run: `ArchitectureTest` (no store gains a writer; `SetupMark` reads flows only), `ChatScreenStateTest`, the goldens (no prompt changes).
- No Robolectric in the project, so the activities' wiring (the hand-over, the callbacks, the flags) is checked on the phone, below. The decisions they act on are `Home`'s, and those are tested.

To run them:

- `./gradlew compileDebugUnitTestKotlin`
- `./gradlew testDebugUnitTest --tests '*HomeTest*' --tests '*ChatScreenStateTest*' --tests '*OutboxTest*' --tests '*ConversationTest*' --tests '*ArchitectureTest*'`
- before the push, CI's own: `./gradlew testDebugUnitTest lintDebug assembleDebug`

## The phone check

Done before the merge, as the change is what the device does. First by the scripts:
- `private/tools/keep_before.sh` (the APK and the data kept);
- `adb install -r`;
- `enabled_accessibility_services` unchanged, every service bound (`dumpsys accessibility`);
- no `FATAL EXCEPTION` in logcat.

`held_by_name.py` isn't needed: no class moves or is renamed.

The install kills the app, so the first launch after it is a fresh start. Each step is a guarded tap or `am start`, the focus checked in the same command, read from a screenshot:

1. **The icon, fresh.** The chat opens at its foot or its *New messages* line, with no Day drawn first. Recents shows *Decrastination*.
2. **The Plan button.** Day opens. Back returns to the chat, and Back again goes to the home screen. The icon then brings back the chat.
3. **Where you left it.** Chat, Plan, Day, then Home, then the icon: Day. Back goes to the chat.
4. **Felix's button on Day.** A chat opens over Day. Back returns to Day, and Back again to the home chat. Felix's button again, then that chat's Plan button: Day, with one chat under it (Back, then Back, leaves).
5. **A task's sheet**, then *Ask Felix about it*: the chat, about the task. Back returns to the sheet.
6. **Settings opened from Day,** then Felix's button, then Plan: Day, Settings closed on the way.
6b. **Settings opened from the chat's menu,** with no Day in the task, then Felix's button, then Plan: Day opens over chat, Settings and chat (README §Decided without asking). Back walks down that stack, one screen a press.
7. **The widget.** With the app's task gone (the owner swipes it from Recents, never `am force-stop`), a widget tap opens Day. Back opens the chat, and Back again leaves.
8. **The chat's bar.** The Setup mark is absent while nothing needs you, and the menu lists Mark all answered, What Felix remembers, Clear the chat, then This week, Reviews, Settings and Debug.
9. **Felix's notification.** Its tap opens the chat at its message, as before; his count on Felix's button reads 0 after it.
10. **Rotation** on the home chat keeps the chat (it isn't handed over again); the rotation is restored after.
11. **The block screen**, if blocking is on at the time, else left to real use. Felix's button there opens a chat with a Back arrow, and Back returns to the block screen.
12. **A quick pass isn't reading** (Q120). This needs one of his messages unread with his notification showing; one written for the test is taken out afterwards with `clean_test_chat.py`.
    - Open from the icon and tap Plan within about a second: his notification is still there, and his count on Felix's button is unchanged.
    - Back to the chat, then stay 5 seconds: the notification goes. Leave, and the count reads 0.
13. **Sending reads at once.** With one unread, open the chat, send a reply at once and leave within 3 seconds: no notification comes back, and the count reads 0.

Afterwards:
- the services are bound and the list is unchanged;
- no `FATAL EXCEPTION`;
- no picture-in-picture window;
- dark mode on;
- screenshots that show accounts deleted.

## Risks

- **A launcher that matches tasks by the root's component, not its affinity.** With `MainActivity` finished, the chat is the task's root. A launcher that looked for `MainActivity` would find no task and start a fresh one each tap, so the chat would always open, contrary to Q114.
  - It's probably not a risk at all. By AOSP's default, a task keeps its identity, the launcher intent that made it, when its root finishes (unless `android:relinquishTaskIdentity` is set), so the launcher's tap finds it.
  - If that holds, `Home.Start.Leave` is only the guard for a launcher or installer that starts `MainActivity` again on top. The label is probably the application's in Recents already, so dropping "Ask Claude" is harmless rather than needed.
  - Steps 1 and 3 of the phone check settle both. The fix would be to keep `MainActivity` as the task's root, finishing it only on Back, at the cost of a Day behind the chat that's never shown.
- **The notification's state while the chat's on screen** (step 6). Today it's simply cancelled. Kept for the first 3 seconds, every `notify` meanwhile (a message arriving, a reply from it) must keep it silent and up to date. `OutboxTest` pins the three branches; step 12 checks it on the phone.
- **Predictive Back.** The callbacks take over Back at the root, so the system's "back to home" preview isn't shown there. That's accepted, as the chat at the root leaves anyway.
- **The chat's `onResume` runs at every launch.** It costs nothing with nothing waiting (README §What you'll notice).
  - With a message or a change of yours waiting, it asks Claude as opening the chat does now. Its calls are interactive, so they go through a rest (AC-04); only Claude being off or the cap holds them.
  - So a launch during a rest, a quick pass to Day included, now asks for what you left waiting. Today that happened only when you opened the chat.
  - It's kept in `onResume` rather than moved into the 3-second job. It's the same single call you'd make on your next look, made sooner, and your waiting words shouldn't wait on a look (Q120 is about his messages being read, not yours being answered).
