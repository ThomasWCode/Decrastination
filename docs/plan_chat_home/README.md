# The chat as the app's home (a plan, 29 October 2026)

The owner's ask, 29 Oct 2026: the app opens on the chat by default, as the chat is now its main feature. Nothing is built; this folder is the plan. It was written in a worktree of `main` at 242abc79 (4.7.0), while 4.8.0 (#150) was still open. It reverses Q26 of 18 Oct ("the chat as the app's home screen: dropped; the app opens on Day"), which offered three of the shapes asked again here.

- this README covers what changes, every way into the app before and after, your answers (Q113 to Q119), the calls the plan made itself, what you'll notice, and the decision to record;
- [build.md](build.md) covers how it's built (the steps, files and commits), its tests and its phone check.

It's proposed as **4.9.0**, built once 4.8.0 is merged, from `main` as it is then (short stacks). The numbers follow 4.8.0's: its PR takes D-120 and Q107 to Q112, so this plan's decision is D-121 and its questions Q113 to Q119.

## The short version

1. **The launcher opens the chat.** `MainActivity` stays the launcher's entry, since the home-screen icon is pinned to it by name. On a launcher start it hands over to the chat (`AskActivity`) and closes before drawing anything, so Day never flashes first.
2. **The chat is the root; Day, Work and Stats sit over it.** A Plan button (a calendar icon) takes the Back arrow's place on the chat's bar and opens Day. Back from Day returns to the chat, and Back from the chat leaves the app.
3. **Back always reaches the chat.** Day, Work or Stats opened straight from the widget or a notification, with nothing under them, open the chat in their place on Back.
4. **Felix's button works as it does now.** From Day, Work, Stats, Settings or a task's sheet it opens a chat over that screen, about what the screen shows, and Back returns there. The home chat underneath is left as it was.
5. **The chat's bar gains the Setup mark and Day's menu.** The red mark shows on the chat when Setup needs you, and the chat's menu gains *This week*, *Reviews* and *Settings* under its own three items. *Sync now* stays on Day.
6. **The chat itself doesn't change.** Its read marks, its hold on his notifications while it's on screen, its tabs and its retry on opening stay as they are. No new call to Claude, no stored shape changed, no prompt changed.

## Every way in, before and after

"Over" means on top of, so Back returns to the screen underneath.

| Way in | Today | After |
|---|---|---|
| The launcher icon, a fresh start | Day | The chat, at its *New messages* line or its foot; Back leaves the app (Q113, Q119) |
| The launcher icon, the app's task alive | Where you left it | The same (Q114) |
| Felix's notification | The chat at its message, over whatever was on top | The same |
| Felix's button: Day, Work, Stats, Settings, a task's sheet | The chat over that screen, about it; Back returns | The same (Q118) |
| Felix's button on the block screen | The chat over the block screen; Back returns | The same: that chat keeps its Back arrow (see below) |
| The chat's Back arrow | Closes the chat | Gone: the Plan button opens Day, or closes back down to Day when one's underneath (Q117) |
| The widget: its slot, its warning, its red ! | Day | Day; Back opens the chat (Q115, Q116) |
| The free-time notice and its *Back to work* | Day | Day; Back opens the chat |
| A timer's sheet, a dropped plan, a Gmail write given up, a task's Open from the chat (`TAB_TASKS`) | Work, at the task where there is one | The same; Back opens the chat when nothing's under it |
| A protection or key alert (`OPEN_SETUP`) | Setup over Day | The same; Back from Day opens the chat |
| A held notification rebuilt with no tap kept (`Notify.rebuild`) | Day | The chat (Q115) |
| A widget task that's gone (`OpenTaskActivity`) | Day | The chat (Q115) |

## Asked and answered (29 Oct)

Each question was asked on its own, with a recommendation; each answer is the option you picked.

**Q113. The shape.** Offered: the chat as home, the launcher opening it, a button to Day/Work/Stats, Back from the chat leaving (recommended: the chat's lifecycle stays as it is, so it's small and low-risk); the chat put over Day at launch, Back going to Day; one screen with a bottom bar, Felix | Day | Work | Stats (the usual shape, but the chat moved into `MainActivity` and its read marks and notification hold rebuilt per tab); the chat as the first top tab (the same rebuild, with two rows of tabs). Your pick: **the chat as home**.

**Q114. Coming back.** You've gone from the chat to Day, pressed Home, and tap the icon again. Offered: where you left it, Android's usual (recommended); always the chat (the task cleared on launch); the chat after a while away. Your pick: **where you left it**. A fresh start always opens on the chat.

**Q115. The other ways in with no tab named.** Offered: what's about the plan opens Day (the widget, the free-time notice) and the generic "open the app" fallbacks open the chat (recommended); everything to the chat; everything to Day as now. Your pick: **the plan's ones to Day, the fallbacks to the chat**.

**Q116. Back from Day opened straight from the widget or a notification.** Offered: Back to the chat, then Back leaves (recommended: Android's own advice for screens opened from a notification or a widget); Back leaves the app, as now. Your pick: **Back to the chat**.

**Q117. The chat's bar.** Offered: the Plan button in the Back arrow's place, the Setup mark, and Day's menu items under the chat's own, with Sync now staying on Day (recommended); the Plan button alone; a mirror of Day's whole bar. Your pick: **the Plan button, the mark and the menu**.

**Q118. Back from a chat opened by Felix's button.** Offered: back to where you asked from, as now, with the home chat underneath left as it was (recommended); back to the one home chat, Day closed on the way, so Back from it then leaves. Your pick: **where you asked from**.

**Q119. Claude off or without a key.** Offered: the chat anyway, its red line saying what's wrong and the Setup mark opening the fix (recommended); Day while Claude is off. Your pick: **the chat anyway**. The monthly cap and its rests don't count as off (`claudeOn` is `aiEnabled && aiKeyActive`); they show in the chat as they do now.

## Decided without asking

- **`MainActivity` keeps the launcher's filter.** The home-screen icon is pinned to the component `.ui.MainActivity`, and held notifications' taps name it too. Moving `MAIN`/`LAUNCHER` to `AskActivity` would drop the icon from the home screen. So `MainActivity` hands a launcher start on to the chat and finishes.
- **A launcher start into a live task changes nothing.** Some launchers and installers start the launcher activity again on top of a task that's already there. A launcher start that finds `MainActivity` isn't its task's root just finishes, so what was on top shows (Q114). One that is the root hands over to the chat.
- **Back on the home chat sends the task to the back** (`moveTaskToBack`), as Android 12 on does for a launcher's root. It doesn't close the chat, so the icon brings it back as it was.
- **One rule for the Plan button**, on every chat but the block screen's. It opens `MainActivity` with `CLEAR_TOP | SINGLE_TOP`. With `MainActivity` underneath (a chat from Felix's button, over Settings say), everything above it closes and Day shows on the tab it had. With none (the home chat), a new one opens over the chat, on Day.
- **The block screen's chat keeps its Back arrow.** The block screen runs in its own task, so that neither Back nor Recents shows the app behind it (`docs/spec/blocking-and-protection.md`), and a Plan button there would open Day inside it. That chat is told so by an extra from Felix's button there, and stays as it is now.
- **Felix's button stays on Day, Work, Stats and Settings** (UI-16). With the chat underneath it's partly a way back, but it opens the chat about what the screen shows, and its unread count is how you see he's written while you're on Day.
- **The chat's label becomes the app's.** `AskActivity` is labelled "Ask Claude" in the manifest, left from 2.0.0's page of that name. As the task's root its label may be what Recents shows, so the label goes and the chat takes the application's, *Decrastination*. The phone check reads Recents to settle it.
- **A new icon,** `ic_calendar`, Material Symbols' *calendar_today*, drawn as the other icons are. None of the existing ones reads as "the plan".

## What you'll notice

- **Opening the app reads his messages.** The chat on screen takes his notification away as it opens (`outbox.notify`) and marks what it showed read as you leave (`Conversation.read`). Today, opening the app on Day left both alone. His count on Felix's button will be 0 more often.
- **His messages don't notify while you're on the chat** (`chatInFront`), as now, but you'll be on it more of the time. His *open it in Teams* (G3) works only with the chat in front, so it works more often.
- **No new calls to Claude.** Opening the chat already tries again what waits for Claude (`answerChat`, `readInstructions(interactive = true)`). Both return at once with nothing waiting (`answerChat` on `chat.value.unanswered == null`), so a launch now does what opening the chat did, nothing more.
- **The first run lands on the chat,** with its red "Claude is off" line and, till a source is read, the Setup mark (Q119).
- **Day's and Work's first-run hints stay true** ("Day is when… Work is what: every task, on the next tab"). Nothing new is hinted: the chat's own intro says what it's for when it's empty.

## The decision to record (D-121, with the build)

It goes into `docs/decisions.md` in 4.9.0's PR, not now, so that it doesn't collide with #150's D-120:

> **D-121. The app opens on the chat** (4.9.0, 29 Oct; Q113 to Q119), reversing Q26 of 18 Oct. The chat is the app's home: the launcher opens it, Back from it leaves the app, and Back from Day, Work or Stats always reaches it, however they were opened. Day, Work and Stats are opened from the chat's Plan button; what's about the plan (the widget, the free-time notice) still opens Day, and what has a target keeps it. Felix's button opens a chat over the screen it's on, Back returning there. Coming back to a live task shows where you left it. The chat opens first whether or not Claude is on. Applies: `ui/Home.kt`, `MainActivity`, `AskActivity`.

## The docs the build changes

- `docs/decisions.md`: D-121, as above.
- `docs/plan_v4/answers.md`: Q26's line says it was reversed by D-121, and a section *The chat as the app's home (4.9.0, 29 Oct)* holds Q113 to Q119 from this README. This folder is then cut to a line in `docs/plan_v4/README.md`, as the speech review's was folded into 4.8.0.
- `docs/plan_v4/README.md`: "the chat as the app's home screen (Q26: dropped; the app opens on Day)" comes out of what the plan left out, and 4.9.0 goes into the record.
- `docs/spec/requirement-ids.md`:
  - UI-16: Felix's button, and the chat's Plan button.
  - UI-17: the chat first, then Day, Work and Stats.
  - UI-18: the menu on the chat too, and the mark on both bars.
- `docs/spec/felix.md` §The chat: the home, its bar, Back, the Plan button, and opening the app as reading his messages.
- `docs/spec/day.md`'s first line: Day opened from the chat's Plan button.
- `README.md`: the screens' description, and this folder's row until it's folded.
- `CLAUDE.md`: this folder's line in *Where things are said* until it's folded.
