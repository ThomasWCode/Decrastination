# Questions and actions raised while the stages are built

What implementing Stage 6 has needed from you, from 19 Oct 2026: questions with my recommendation and a blank `ANSWER:` line each. What was decided meanwhile, so the work didn't wait, is said with it and in its PR. [ANSWERS.md](ANSWERS.md) stays closed. Once each is settled, it's recorded where it belongs (its PR, [decisions.md](../decisions.md), [14](14-your-part.md)) and this file goes.

## Questions

### S1. Two services share their names with core's pure objects

The plan names 6c's services `Instructions` and `OwnTasks` (in `tasks/`), and `core/` already has two objects of those names: the instructions' fold (`core.Instructions`: what's laid on a task, the circle check, the late check) and your own tasks' rules (`core.OwnTasks`: the form's checks, a task made from a draft). Where both meet, a file can name only one plainly. In the two services, core's are named in full (`com.thomaswcode.decrastination.core.Instructions.latePin(…)`), as the conventions name an ambiguous one (11-conventions-and-docs.md §Files); elsewhere nothing clashes, as the screens reach the services as `graph.instructions` and `graph.ownTasks`.

- **A (recommended).** Rename core's two objects in Stage 9 (names), say to `InstructionRules` and `OwnTaskRules`, and the full names go. The rename touches about 26 files, so it belongs with Stage 9's other renames rather than in a move.
- **B.** Keep the full names in the two services for good.
- **C.** Rename the services instead (`InstructionService`, `OwnTaskService`), against the plan's names (11-conventions-and-docs.md §Names).
- Decided meanwhile (6c, #78): the plan's names, core's named in full in the two services.
- ANSWER:
