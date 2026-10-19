package com.thomaswcode.decrastination.core

import java.util.Locale

/**
 * The settings Felix may change from the chat (your call, 16 Oct): those about the work and about
 * him, never the blocker's own (what's blocked, when, how free time is earned, protection:
 * [com.thomaswcode.decrastination.protect.SettingsChanges]), nor Claude's (its key, its cap), the
 * calendars, your classes, what Claude's told of you, the Teams syncs or where the summary goes.
 * Each by its name in [Settings], its value as he gives it: a number as one, a time of day as
 * HH:MM, hours as HH:MM-HH:MM, words as they are. Pure.
 */
object FelixSettings {

    /** How a value is written. */
    enum class Shape { Number, Time, Hours, Words }

    /** One setting: its [name] in [Settings], its [label] as Felix and the chat say it, and its value read and set as text. */
    class Field(val name: String, val label: String, val shape: Shape, val get: (Settings) -> String, val set: (Settings, String) -> Settings?)

    private fun number(name: String, label: String, range: IntRange, get: (Settings) -> Int, set: (Settings, Int) -> Settings) =
        Field(name, label, Shape.Number, { get(it).toString() }) { s, v -> v.trim().toIntOrNull()?.takeIf { it in range }?.let { set(s, it) } }

    private fun time(name: String, label: String, get: (Settings) -> Int, set: (Settings, Int) -> Settings) =
        Field(name, label, Shape.Time, { clock(get(it)) }) { s, v -> minute(v)?.let { set(s, it) } }

    private fun hours(name: String, label: String, get: (Settings) -> Window, set: (Settings, Window) -> Settings) =
        Field(name, label, Shape.Hours, { get(it).let { w -> clock(w.startMin) + "-" + clock(w.endMin) } }) { s, v -> window(v)?.let { set(s, it) } }

    private fun words(name: String, label: String, max: Int, get: (Settings) -> String, set: (Settings, String) -> Settings) =
        Field(name, label, Shape.Words, get) { s, v -> v.trim().takeIf { it.isNotEmpty() && it.length <= max }?.let { set(s, it) } }

    val FIELDS: List<Field> = listOf(
        words("felixName", "Felix's name", SettingsLimits.MAX_NAME, { it.felixName }) { s, v -> s.copy(felixName = v) },
        words("felixLine", "Who Felix is", SettingsLimits.MAX_FELIX_LINE, { it.felixLine }) { s, v -> s.copy(felixLine = v) },
        number("felixGraceMin", "Felix asks this far into a slot with nothing done (minutes)", SettingsLimits.FELIX_GRACE_MIN, { it.felixGraceMin }) { s, v -> s.copy(felixGraceMin = v) },
        number("felixDefaultCheckMin", "Felix checks again after (minutes)", SettingsLimits.FELIX_CHECK_MIN, { it.felixDefaultCheckMin }) { s, v -> s.copy(felixDefaultCheckMin = v) },
        time("briefingWeekdayMin", "Morning briefing, school days", { it.briefingWeekdayMin }) { s, v -> s.copy(briefingWeekdayMin = v) },
        time("briefingWeekendMin", "Morning briefing, weekends", { it.briefingWeekendMin }) { s, v -> s.copy(briefingWeekendMin = v) },
        time("checkInMin", "Sunday check-in", { it.checkInMin }) { s, v -> s.copy(checkInMin = v) },
        hours("weekdayHours", "School-day working hours", { it.weekdayHours }) { s, w -> s.copy(weekdayHours = w) },
        hours("weekendHours", "Weekend working hours", { it.weekendHours }) { s, w -> s.copy(weekendHours = w) },
        hours("breakWindow", "Morning break", { it.breakWindow }) { s, w -> s.copy(breakWindow = w) },
        number("minSchoolSlotMin", "Shortest school time used (minutes)", SettingsLimits.MIN_SCHOOL_SLOT_MIN, { it.minSchoolSlotMin }) { s, v -> s.copy(minSchoolSlotMin = v) },
        number("boxMin", "A piece's length (minutes)", SettingsLimits.BOX_MIN, { it.boxMin }) { s, v -> s.copy(boxMin = v) },
        number("slotBreakMin", "Break between pieces (minutes)", SettingsLimits.SLOT_BREAK_MIN, { it.slotBreakMin }) { s, v -> s.copy(slotBreakMin = v) },
        number("slotLongBreakMin", "Longer break (minutes)", SettingsLimits.SLOT_BREAK_MIN, { it.slotLongBreakMin }) { s, v -> s.copy(slotLongBreakMin = v) },
        number("slotLongBreakAfterMin", "Longer break after (minutes of work)", SettingsLimits.SLOT_LONG_BREAK_AFTER_MIN, { it.slotLongBreakAfterMin }) { s, v -> s.copy(slotLongBreakAfterMin = v) },
        number("outsideBreaks", "Breaks outside on a free day", SettingsLimits.OUTSIDE_BREAKS, { it.outsideBreaks }) { s, v -> s.copy(outsideBreaks = v) },
        number("outsideBreakMin", "Each break outside (minutes)", SettingsLimits.OUTSIDE_BREAK_MIN, { it.outsideBreakMin }) { s, v -> s.copy(outsideBreakMin = v) },
        number("marginDays", "Days to spare before a deadline", SettingsLimits.MARGIN_DAYS, { it.marginDays }) { s, v -> s.copy(marginDays = v) },
        number("softDeadlineDays", "Days given to undated work", SettingsLimits.SOFT_DEADLINE_DAYS, { it.softDeadlineDays }) { s, v -> s.copy(softDeadlineDays = v) },
        number("softMinPerDay", "Undated work a day (minutes)", SettingsLimits.SOFT_MIN_PER_DAY, { it.softMinPerDay }) { s, v -> s.copy(softMinPerDay = v) },
        time("ankiDeadlineMin", "Anki deadline", { it.ankiDeadlineMin }) { s, v -> s.copy(ankiDeadlineMin = v) },
        number("ankiTextbook", "Current German textbook", SettingsLimits.TEXTBOOK, { it.ankiTextbook }) { s, v -> s.copy(ankiTextbook = v) },
    )

    fun field(name: String): Field? = FIELDS.firstOrNull { it.name == name.trim() }

    /**
     * [settings] with [name] set to [value], and its line ("A piece's length (minutes): 30 → 40"); null
     * if it can't be: a setting he may not change, a value that doesn't read or is out of range, or
     * one that leaves the settings as the Settings screen wouldn't take them ([SettingsLimits.problems]).
     */
    fun set(settings: Settings, name: String, value: String): Pair<Settings, String>? {
        val field = field(name) ?: return null
        val next = field.set(settings, value) ?: return null
        if ((SettingsLimits.problems(next) - SettingsLimits.problems(settings).toSet()).isNotEmpty()) return null
        return next to "${field.label}: ${field.get(settings)} → ${field.get(next)}"
    }

    /** Each setting he may change, as the chat tells him of them: "name | what it is | its value now". */
    fun describe(settings: Settings): List<String> = FIELDS.map { "${it.name} | ${it.label} | ${it.get(settings)}" }

    private fun clock(minute: Int): String = "%02d:%02d".format(Locale.UK, minute / 60, minute % 60)

    private fun minute(text: String): Int? {
        val parts = text.trim().split(':')
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val min = parts[1].trim().toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return hour * 60 + min
    }

    private fun window(text: String): Window? {
        val parts = text.split('-', '–')
        if (parts.size != 2) return null
        return Window(minute(parts[0]) ?: return null, minute(parts[1]) ?: return null)
    }
}
