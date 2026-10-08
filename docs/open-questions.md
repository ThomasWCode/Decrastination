# Open questions

## Answered 7 Oct 2026

| # | Question | Decision |
|---|---|---|
| Q1 | Power Planner access | **Web API** with stored (encrypted) credentials. Phase 0 runs `scripts/powerplanner_probe.py` first. |
| Q2 | Gmail access | **IMAP + Google app password.** |
| Q3 | AI | **Anthropic API key, `claude-opus-5-5`, high effort.** £200/month of unused credit; the model does triage, assignment splitting, the weekly review, optional photo checks. Monthly cap in Settings. |
| Q4 | Unlock rule | **Strict while anything is in today's or tomorrow's bucket; earned free time by task size only when nothing is.** Large tasks split backwards from their deadline into day chunks. **No bypass button.** The app learns from how tasks actually go. `docs/scheduler.md` §3–§6. |
| Q5 | Anki quota | **All due reviews + 20 new from the lowest-numbered deck with new cards left + any deck a German assignment names.** Quota deadline 21:00 (**ASSUMED**; say if you want it later given the 22:00 weekday window). |
| Q6 | Hours | **Capacity Mon–Fri 16:45–22:00, Sat–Sun 08:30–22:30; quiet (no blocking) 22:30–07:00.** Commute is inside the 16:45 start. Box length starts at 45 min and the learning layer experiments with 25/45/60. |
| Q14 | Anti-tamper | **24-hour delay on loosening changes.** Override: each pending change emails a one-time code to **richard.white@lshtm.ac.uk**; typing it applies that one change immediately. Protection-off alert after an hour goes to the same address. Sender is a dedicated mailbox your dad sets up (so the code never passes through your own Gmail); the authenticator-app variant is documented as an alternative. Weekly note to him: toggle, default off. |
| Q15 | Learning | **Measure and calibrate + one-tap self-assessment after each task + Sunday five-question check-in.** Practice-test scheduling not chosen. |

## Settled by Phase 0, 8 Oct 2026

| # | Question | Outcome |
|---|---|---|
| Q10 | Where the Teams provider change goes | In the widget, as planned: TeamsAssignmentsWidget #13 (0.3.0) adds the permission, the provider, change notifications and the two calls. Widget behaviour unchanged. |
| Q11 | Package name and location | `com.thomaswcode.decrastination`, in `C:\Users\thoma\Documents\Decrastination`, repository `ThomasWCode/Decrastination` (public, so `private/` keeps real inbox and agenda data out of git), signed with a copy of the widget's committed `app/debug.keystore`. |

## Still open: going with these defaults unless you say otherwise

### Q7. Google Calendar as busy time
**Default:** yes in Phase 5, read-only. The Open Day on 10 October and "Call with Jags" on 14 October then reduce capacity automatically.

### Q8. Block list and the browser
**Default blocklist:** YouTube, YouTube Music, Instagram, Snapchat, Twitch, Netflix. Discord and WhatsApp left alone. Chrome not blocked as an app, but YouTube/Instagram/Twitch hostnames blocked inside Chrome via the URL bar (Phase 3).

### Q9. The sideloaded "Digital Wellbeing" app (`com.screentime` 1.6, installed 7 Sep)
It holds usage-access permission. If it is a blocker you already tried, what went wrong is useful; otherwise I leave it alone. Also `app.humanforest`, if relevant.

### Q12. The work-experience email from your dad
It contains a calendar of 2027 deadlines (STEM Potential opens 30 Oct 2026, NPL/Diamond in December, RAL in January). **Default:** one task per email; extracting dated items into separate wait-until-date tasks is a Phase 5 option the model can do.

### Q13. Morning briefing
**Default:** a notification at 07:00 on school days (the end of quiet hours) with today's bucket, and the Teams sync requested then.

### Q16. Photo checks for written work
**Default:** on, Phase 5, optional per chunk (the timer path always remains).

### Q17. Override delivery
**Default:** dedicated sender mailbox set up by your dad (Q14). Alternative: TOTP secret in an authenticator app on his phone, email carries no secret. Tell me if he would prefer the second.

### Q18. Which textbook a German section number means (from Phase 0)
AnkiDroid has `Textbook 1::1.1` to `6.3` and `Textbook 2::1.1` to `6.3`, so "Learn vocabulary column 1.2" matches two decks. **Default:** a "current textbook" setting, starting at Textbook 1 for Year 12, and the LLM may override it when an assignment names the book or a topic only one of them has. The daily quota's "lowest-numbered deck with new cards left" walks the current textbook first.

### Q19. Letting the watchdog switch protection back on (from Phase 0)
Phase 0 found the focus service can be put on an accessibility key shortcut without opening its guarded page, after which a key press turns it off. Android lets a sideloaded app change secure settings only if `WRITE_SECURE_SETTINGS` is granted once over adb (`adb shell pm grant com.thomaswcode.decrastination android.permission.WRITE_SECURE_SETTINGS`). **Default:** yes, granted at setup; the watchdog then switches the service straight back on and removes it from any shortcut, instead of only showing PROTECTION OFF and emailing. It survives reboots and updates, and goes only if the app is uninstalled.
