# The owner's answers, and the choices made for them

The questions put to the owner while the timer was planned (25 Oct 2026), their answers as given, and the choices the plan makes without asking. An assumption the owner overturns is changed here and in `README.md` before the build touches it.

## Asked and answered

**Q1. While a timer runs on a task, which of Felix's messages about that task should be held back?** Offered: the slot-start nudge and the slot-over message (recommended: the slot's outcome recorded as now); the slot-start nudge only; both messages and the missed outcome (the slot counted as under way past its end).

> Hold back the slot-start and slot-over messages as recommended. Ensure that it will still record and message as 'missed' if the task does not get ticked off within 30 min of the session ending. In general, ensure that it can cope with the task not actually being completed in the session.

Applied: `README.md` §What it does to Felix (the hold lifts 30 minutes after the countdown's zero, or at a Stop by hand) and §What it must cope with; `build.md` stage 4 (`TIMER_HOLD_MS`, the freshness measured from the lift).

**Q2. How should the timer show time over once the countdown reaches zero?** Android draws the notification's counter and decides its format: a countdown past zero reads "−05:32", and the app can't put a "+" in it. Offered: Android's own −mm:ss, with the running notification retitled and a separate "time's up" one (recommended); the counter re-based to count up from zero, no sign; the text rewritten to "+05:30" every half minute by the accessibility service's tick (stale when it isn't running, a little battery, 30-second steps).

> Android's own: "−05:32" (Recommended)

Applied: `README.md` §What it is (At zero); `build.md` stage 3 (the same `when` for both states).

**Q3. Where should Start (and Stop while a timer runs) appear?** Offered: the piece's actions and the task sheet (recommended, the widget showing the state only); those plus a ▶ on the widget; the piece's actions only.

> Those plus a ▶ on the widget

And, sent while the plan was written:

> Make the tick and start button on the widget appear above each other in a column, and then the reload button appear in the column to the right, as it does now.

Applied: `README.md` §What it is (Where Start is); `build.md` stages 5 and 6.

**Q4. After a Stop by hand with the piece undone, when may Felix's slot-over ("you missed it") message come?** Asked after a side agent's note on the first plan, which had a Stop by hand lift the hold at once: that read Q1's "the session ending" as the countdown's zero alone, and would have let Felix say "you missed it" in the seconds between stopping the timer and ticking the piece. Offered: 30 minutes after any end, zero or Stop, whichever comes first (recommended); at once on Stop, as first planned; a short grace on Stop and 30 minutes at zero.

> 30 minutes after any end (Recommended)

Applied: `README.md` §What it does to Felix and §What it must cope with; `build.md` stages 3 (the hold's alarm set on a Stop too) and 4 (`liftAt`).

## Decided without asking

Each a routine call the owner may overturn; the alternative is named so the build can switch.

1. **It's called a timer**, in code (`PieceTimer`, `TimerAlarms`), docs and the owner's screen, not a focus session, so D-55 keeps its meaning: what stays gone is a session that blocks and earns. The owner's own word for it in the ask was "session". Alternative: call it a session on screen and amend D-55 to say sessions are back without blocking or credit.
2. **One timer at a time.** Start while one runs replaces it, and says so. Alternative: one a task, several at once; more state, more notifications, little use.
3. **Start doesn't open the task.** Open stays its own button beside it. Alternative: Start also opens the piece where it lives, one tap for both; against it, Open can fail (the Teams widget off, Gmail with no link) and the toast would have to carry both outcomes.
4. **What it counts**: the plan's piece's minutes, as laid; a step the plan has no piece for counts the step's minutes as the sheet shows them; a task with no steps, from its sheet, its next piece in the plan, and no Start when it has none. Alternative: let the owner pick the minutes at Start; a dialog on every Start for a rare need.
5. **The timer's time counts for nothing**: not the kept slot's minutes (LR-09's "more if it took longer"), not the calibration, not free time. The owner's ask was "just a timer and to stop the nudges". Possible follow-up, not in 4.4.0: offer the overrun as the kept slot's extra minutes when the piece is ticked with the timer over, which would teach the pace from measured time; against it, a timer left running through dinner would teach a wrong one.
6. **Start counts as something done on the task** (`Looks.noteActivity`), so the slot-start nudge for that slot stays off after a Stop. Alternative: only the running timer holds it; a Stop at 17:08 would then bring the 17:10 nudge.
7. **Felix is told** of a running timer in one clause of his plan context, so his chat answers and his other messages know; **he can't start or stop one** from the chat (no new action in his schema, no golden or compile-size risk). Alternative: an `action` of his for Start and Stop, a schema change with its golden and `SchemaCompileTest`.
8. **Wall clock, not uptime.** The old session fought date changes because blocking rode on it; nothing rides on this one. Alternative: the uptime clock as before, more code for no protection worth having.
9. **The "time's up" notification comes whatever the hour**, quiet hours included: the owner started it, and it's the app's, not Felix's. Alternative: held in quiet hours as his are.
10. **Not backed up**, and cleared by *Everything learned* with the rest of `session.json`; a running timer is this phone's moment. Alternative: add it to the backup's part of `session.json`; a restore would then bring back a timer from another day.
11. **An Undo of the tick or Done that ended it doesn't restart it.** Alternative: restart it from where it was; the state would have to keep the ended timer and the Undo would have to know of it.
12. **A timer ended by its piece being done lifts no hold**: the slot's record is judged as now, so a piece ticked late isn't followed by "you missed it". A Stop by hand lifts it 30 minutes on (Q4), so Stop then tick never brings it either. A Start on another task meanwhile replaces the stopped timer's record, and the first task's miss falls back to his ordinary freshness; accepted as rare.
13. **The widget's column drops ▶ first** where the height fits only one icon, keeping ✓ as now; the threshold is settled on the phone at 2×1 and 4×2. Alternative: shrink both to fit at every size, or drop ✓ and keep ▶.
14. **The widget's pill says "⏱ till 17:30" and "⏱ over"**, not minutes left, because the widget redraws at most every 15 minutes and a count would go stale; the notification's counter is the live one. Alternative: minutes left, redrawn each minute, which costs the phone wake-ups it doesn't spend today.
15. **No setting** for the hold (30 minutes) or the timer; a constant in `FelixRules`. Alternative: beside `felixGraceMin` in Settings → Felix.
