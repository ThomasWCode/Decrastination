# Building it (4.9.0)

How [the plan](README.md) is built: the steps in order, each a commit, then the tests, the docs and the phone check. Branch `v4.9.0` from `main` once #150 (4.8.0) is merged; `versionCode` 89, `versionName` "4.9.0" in `app/build.gradle.kts`; PR titled "4.9.0: the app opens on the chat". About 150 lines of Kotlin in six files, and no store, prompt, schema or golden changed.

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
  - On `Chat`: start `AskActivity.intent(this)`, then `finish()`, with no transition (`overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)`), and return.
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

### 6. The icon, the version and the docs

- `res/drawable/ic_calendar.xml`: Material Symbols' *calendar_today*, 24 dp, tinted `?android:attr/colorControlNormal` over a white fill, with its name in a comment, as `ic_chat.xml` is.
- The version, and the docs listed in [README.md](README.md) §The docs the build changes, in the same PR.

## Tests

- New: `HomeTest` (step 1).
- Unchanged and run: `ArchitectureTest` (no store gains a writer; `SetupMark` reads flows only), `ChatScreenStateTest`, the goldens (no prompt changes).
- No Robolectric in the project, so the activities' wiring (the hand-over, the callbacks, the flags) is checked on the phone, below. The decisions they act on are `Home`'s, and those are tested.

To run them:

- `./gradlew compileDebugUnitTestKotlin`
- `./gradlew testDebugUnitTest --tests '*HomeTest*' --tests '*ArchitectureTest*'`
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
6. **Settings**, then Felix's button, then Plan: Day, Settings closed on the way.
7. **The widget.** With the app's task gone (the owner swipes it from Recents, never `am force-stop`), a widget tap opens Day. Back opens the chat, and Back again leaves.
8. **The chat's bar.** The Setup mark is absent while nothing needs you, and the menu lists Mark all answered, What Felix remembers, Clear the chat, then This week, Reviews, Settings and Debug.
9. **Felix's notification.** Its tap opens the chat at its message, as before; his count on Felix's button reads 0 after it.
10. **Rotation** on the home chat keeps the chat (it isn't handed over again); the rotation is restored after.
11. **The block screen**, if blocking is on at the time, else left to real use. Felix's button there opens a chat with a Back arrow, and Back returns to the block screen.

Afterwards:
- the services are bound and the list is unchanged;
- no `FATAL EXCEPTION`;
- no picture-in-picture window;
- dark mode on;
- screenshots that show accounts deleted.

## Risks

- **A launcher that matches tasks by the root's component, not its affinity.** With `MainActivity` finished, the chat is the task's root. A launcher that looked for `MainActivity` would find no task and start a fresh one each tap, so the chat would always open, contrary to Q114. Step 3 of the phone check finds this. The fix would be to keep `MainActivity` as the task's root, finishing it only on Back, at the cost of a Day behind the chat that's never shown.
- **Predictive Back.** The callbacks take over Back at the root, so the system's "back to home" preview isn't shown there. That's accepted, as the chat at the root leaves anyway.
- **The chat's `onResume` runs at every launch.** It costs nothing with nothing waiting (README §What you'll notice). With a message of yours waiting, it asks as opening the chat does now, and Claude being off, resting or capped holds it in `ModelGateway`, as it does today.
