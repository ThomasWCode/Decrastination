# Questions from building the stages

Questions that came up while the stages were built, each with a recommendation and a blank `ANSWER:` line, apart from the plan's settled ones in [ANSWERS.md](ANSWERS.md). Where one couldn't wait, the stage did what its recommendation says, and its PR says so.

---

### Q34. The briefing on a weekday with no school (found in the 20 Oct docs review; for 4.1.3)

D-74 says "the briefing at 07:00 on school days and 08:30 at weekends", and Settings labels it "Morning briefing, school days". The code (`Daily.nextBriefing`) gives every weekday 07:00, a weekday with no school (a holiday, a training day) among them, and weekends 08:30. So a day off in the week has a 07:00 briefing that D-74's words don't cover.

- **A.** As the code does: 07:00 Monday to Friday, school or not; D-74 and the Settings label say weekdays.
- **B (recommended).** As D-74's words read: 07:00 on a school day by the school calendar (`SchoolDays.kindOf`, as blocking already reads it, BL-02), 08:30 on any other day, a weekday off included, as a weekend's. A day off is a lie-in, and the calendar's already there.
- ANSWER:
