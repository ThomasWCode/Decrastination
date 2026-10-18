# Memory that expires

You asked for the selective remembering a person does: he keeps what you tell him for as long as it's needed, and you never have to remind him or prune. Today he remembers facts (one a topic, within 6 000 characters, tidied weekly) and standing instructions (up to 20), each for ever until you delete it or the tidy rewrites it (AI-21, D-64). What's missing is a shelf life.

## What changes

```kotlin
@Serializable data class Fact(val topic: String, val text: String, val at: Long, val until: Long? = null, val taskId: String? = null)
@Serializable data class Rule(val text: String, val at: Long, val until: Long? = null)
```

- **`until`**: the fact or rule holds till then, and is forgotten after: "I've got a cold this week" (until Sunday), "don't message me tonight" (until 07:00 tomorrow), "I'm away Thursday to Sunday" is a change (NoSchool or BusyTime), not a memory, as the prompt already says.
- **`taskId`**: the fact is about a task and goes with it, a fortnight after the task is finished or gone: "for the trip, I'm sharing a room with a friend" (the trip's email), "the teacher said the essay can be over length".
- **Neither**: as now, a fact that stays till replaced or tidied; a rule that stays.

**Forgetting** is silent, as a person's is: no line in the chat. `Memories.expired(state, tasks, now)` (pure) drops what's past, run where the memory is read for the profile (`AppGraph.profile`) and at the morning's sweep, so the profile never carries a stale line. Settings → Felix and the chat's menu list each fact with "until Sun 25 Oct" or "with “Trip consent form”", so you can see what's temporary (Q15: A).

**The tidy** (`MemoryTidy`) leaves expiring facts as they are: they go by themselves, and a merge of them would lose their until. Only facts with neither field are sent to it; the count it's due by counts all.

**The profile** (`Profile.block`) shows an expiring fact with its until ("- cold: feeling rough (until Sun 25 Oct)") so he knows it's passing. The profile's shape changes, which the readings see too; it's not a reading's prompt, and `GoldenPromptsTest` ties `profile.txt` to `Prompts.VERSION`: raising the version re-reads every open task (about 40 calls, 60 cents). `Prompts.VERSION` goes to 8 with F (Q15: A), batched with any other reading-prompt change due then, and the one re-read is accepted.

## How he sets an until

**The schemas.** The chat's `MEMORY` object and his own messages' (`FelixPrompts.SCHEMA` reuses it) gain `until` (a date as YYYY-MM-DD, or a date and time, or empty) and `taskId` (or empty): plain strings, no union. Both schemas are compiled by the API before the release (D-87).

**The prompt** (the chat's item 4, and the line in his own prompt), in [05](05-his-voice.md)'s register: a fact that only matters till a date (a cold, an exam week, a visitor) gets `until`; one that only matters while a task's open gets `taskId`; a rule for tonight or this week gets `until` too; most have neither. A reply that says "don't message me tonight" is a rule until the morning; "for a bit" is a week. Examples pin it (AI-07: a few made-up lines, each an answer the checks pass).

**Checks** (`Memories.edit`): an until in the past is refused with its line; one over a year ahead is cut to a year; a `taskId` must be a listed task (else kept with none, and said). Undo brings the fact back with its until.

## What it changes elsewhere

- **`MemoryEdit.line`** says the until: "Remembered: cold: feeling rough, until Sunday".
- **`Memories.undo`**: as now, the fact compared whole (until included).
- **The backup** carries the new fields; a fact from before has none.
- **Reset → What Felix remembers**: as now.
- **The weekly review** reads Felix's counts, not his memory: nothing.

## Suggestions in the same spirit

- **A quiet-until rule from his own messages**: when your reply to a slot message says "leave me alone tonight", his answer's memories can give a rule until tomorrow 07:00, which `FelixRules` reads before writing (it reads rules through the profile already: a rule "don't message tonight" makes him give an empty message, per his prompt). No new code beyond the until; an example goes in his prompt (Q15: A).
- **"Until the exam" without a date**: he can't resolve it to a date unless the exam's a task with a deadline; the prompt tells him to use the task's date where there is one, else ask.
- **Facts that should become changes**: the prompt already says busy times, limits and days off are changes. The register's examples keep saying it.

## Implementation (4.2.0-F)

One PR, the commits in this order:

1. **The types and the rules.** `core/Memory.kt`: `Fact.until`, `Fact.taskId`, `Rule.until`; `Memories.expired(state, tasks, now)`; `Memories.edit` reading `Given.until` and `Given.taskId` with the checks (past refused with its line, over a year cut, an unknown task kept without and said); `MemoryEdit.line` with the until; `undo` comparing whole. Tests first.
2. **The schemas.** `MEMORY`'s `until` and `taskId` strings in `ChatPrompts.SCHEMA` and `FelixPrompts.SCHEMA` (which reuses the object); `ChatAnswers.parse` and `FelixAnswers.parse` read them, empty as none; the goldens; `SchemaLimitsTest`; `SchemaCompileTest -PcheckSchemas=true` on both, the result in the PR.
3. **The prompts.** Item 4 of the chat's and the memory sentence of his own gain the until rule and one example each; the "quiet until" example in `felix.txt`; `felix-inputs.md` regenerated.
4. **Forgetting.** `AppGraph.profile` runs `Memories.expired` and writes the result when it differs (one `update`, idempotent); `Briefing.run` calls it at the morning's sweep too.
5. **The profile.** `Profile.block` shows "(until Sun 25 Oct)" and "(with “Trip consent form”)"; `profile.txt` regenerated; `Prompts.VERSION` 8.
6. **The tidy.** `MemoryTidy.due` and `describe` take only facts with neither field; `after` keeps the expiring ones as they were.
7. **The lists.** `MemorySection` (Settings → Felix and the chat's menu) shows the until and the task; the backup carries the fields with no format change (defaults).
8. **The docs**: `felix.md` §What he remembers; `decisions.md` D-97.

## Tests

- `Memories.expired`: by until, by task gone or finished (with the fortnight), neither; idempotent.
- `Memories.edit` with until and taskId: past refused, far cut, unknown task kept without; the lines; undo whole.
- `MemoryTidy`: expiring facts not sent, counted.
- `Profile.block`: the until shown; an expired fact absent; the golden's `profile.txt` regenerated with one, and `Prompts.VERSION` 8 (Q15: A).
- `ChatAnswers.parse` and `FelixAnswers.parse`: the fields read; empty is none.
- `SchemaLimitsTest`, and `SchemaCompileTest` by the API before the merge.

## Phone check

- `chat-ask --es text "I've got a cold till Sunday"` (a paid call): "Remembered … until Sun …" under his reply; `memory` shows it with its until; Settings → Felix lists it so.
- Set the phone's date past it? Never: `stay_on_while_plugged_in` and the date are left alone. Instead a fact set with `until` an hour ahead, and `memory` an hour later: gone, with no line in the chat.
- Reply "leave me alone tonight" to a `felix-say` SlotStart (a paid call): a rule until the morning; `felix-check` writes nothing; `memory` the next day: the rule gone.
