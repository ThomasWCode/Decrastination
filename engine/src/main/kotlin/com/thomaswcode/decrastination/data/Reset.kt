package com.thomaswcode.decrastination.data

/** What can be reset (LR-08), and what each takes. */
enum class Reset(val title: String, val takes: String) {
    Stats("Stats", "the record of what got done (slots, completions, days, blocks, the reviews and the check-ins), and what the app learned from it"),
    Calibration("What the app has learned", "how long your work takes, the margins and how much of each day you use, learned afresh from now"),
    Memories("What Felix remembers", "his facts about you and how you'd have him behave, and your lines for the profile"),
    Chat("The chat and Felix's history", "the chat's messages, Felix's messages and what he was waiting to say, and his notes and reminders"),
    Instructions("Your changes", "every change in use"),
    Everything("Everything learned", "all of these"),
}
