# The chat keeps your words

What's typed in the chat's box and not sent is there again when you go to the plan (*Plan*) and come back, however you come back.

## Why it's lost now

The box's words are the chat screen's own (`var draft by rememberSaveable` in `AskActivity.Screen`). They're kept through a turn of the phone, and through Android stopping the app behind your back while the screen's still in its task, but not once the screen is closed or another chat screen is opened:

- **The chat over Day** (Day opened first, by a notification or a tab asked for, then Felix's button): *Plan* starts `MainActivity` with `FLAG_ACTIVITY_CLEAR_TOP`, which closes the chat over it, its words with it. Back on Day, at its task's root, then opens a new, empty chat (`MainActivity.backToChat`, Q116).
- **Felix's button on Day over the chat** (the chat first, then *Plan*): `AskActivity` is `singleTop`, which reuses only a chat that's on top, so a second, empty chat opens over Day. Your words are in the first, under it, out of sight.
- **A send that fails after you've left** (the chat's file not written, U-05): the text is put back into the `draft` of a screen that's gone (`AskActivity.kt`, the `if (!sent)` after `graph.conversation.ask`), so it's lost too.

Changing *Plan*'s `CLEAR_TOP` to `REORDER_TO_FRONT` would keep the first chat for the first two, but changes how the app's screens stack, which Q116 and D-121 settled. That would be a decision of yours, not this fix.

## What's built

- **One draft for the app**: `Conversation` (the chat's owner) holds `val draft = MutableStateFlow("")`, in memory alone (you answered: not kept past the app's process). It isn't a store: no file, no version, no migration, no writer in `ArchitectureTest`, nothing in the backups or the resets.
- **The box reads and writes it**: `AskActivity.Screen` collects `graph.conversation.draft`; `InputBar`'s `onDraft` sets it. Every chat screen, the first or a second, shows the same words.
- **Through a stop of the process, as now**: `onSaveInstanceState` keeps the draft as `STATE_DRAFT`; `onCreate`, made again, puts it back only where the holder is empty, so a stop behind your back keeps the words as `rememberSaveable` does now, and a newer draft isn't replaced by an older screen's.
- **Sending**: the box is cleared at once, as now (`draft.value = ""`). A message that couldn't be kept goes back into the holder, not the screen, so it's there wherever you are: into an empty box as it was, or before what you've typed since, as now (`text + "\n\n" + draft`).
- **What it's about and what it replies to** aren't kept with it: they stay as each opening of the chat sets them (`about`, `ChatScreenState.replyingTo`), as now.
- **Clear chat** leaves the draft alone: it clears the messages, not the box.
- **The block screen's chat** (`EXTRA_BACK`) shares the holder, as you answered.

## Words handed in

Some buttons open the chat with words already put in its box for you to change or send: *Split with Felix* on a task's sheet ("Split this into steps"), *What gives?* on a deadline that won't fit (PL-02). Today they replace what's in the box (`if (!sent) draft = words`), which loses nothing, as each such opening is a new, empty chat. Once the box's words are kept, that same line would replace words you'd typed earlier and left.

For example, you type "can I do the essay on Saturday instead", tap *Plan*, open a task's sheet and tap *Split with Felix*. The chat opens with "Split this into steps" in its box. Your earlier words could be (you answered A):

- **A. Kept, after the handed words** (your answer): "Split this into steps", a blank line, then "can I do the essay on Saturday instead". Nothing's lost, and what you just asked for comes first, as a failed send's words come first now.
- **B. Kept, before them**: your words first, then "Split this into steps".
- **C. Replaced**: the box holds only "Split this into steps", and your earlier words are gone (as now).

Words handed in to be sent at once (an instruction about an event or a day) don't touch the box, as now.

A is built as a pure function beside the chat screen's state (`ChatScreenState.withHanded(draft, words)`), so it's tested on its own.

## Tests

- `ChatScreenStateTest`: words handed in to an empty box are the box's; to a box holding words, the handed words, a blank line, then the box's (A); blank words handed in leave the box as it is.
- The holder itself is a `MutableStateFlow` with no rule of its own: there's nothing more to test without a screen, so the phone's steps 1 to 4 ([README](README.md#at-the-phone)) hold it.

## Spec

`docs/spec/felix.md` §The chat: a line that the box's words are kept, not sent, across the app's screens while the app runs, and lost when it stops for good; the failed send's line says it goes back into the box wherever you are.
