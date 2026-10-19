package com.thomaswcode.decrastination.core

/**
 * Values of yours as a test gives them, plain, each set [by] you unless it says Felix: as the
 * instructions' fold makes them ([InstructionRules.taskOverrides]).
 */
fun mineOf(
    notATask: Boolean = false,
    from: Long? = null,
    after: String? = null,
    dueAt: Long? = null,
    minutes: Int? = null,
    minutesPacedFrom: Int? = null,
    minutesPacedHash: String? = null,
    onDay: String? = null,
    atTime: Long? = null,
    sameAs: String? = null,
    steps: List<OwnStep>? = null,
    title: String? = null,
    by: By = By.You,
): Mine = Mine(
    notATask = SetBy(true, by).takeIf { notATask },
    from = from?.let { SetBy(it, by) },
    after = after?.let { SetBy(it, by) },
    dueAt = dueAt?.let { SetBy(it, by) },
    minutes = minutes?.let { SetMinutes(it, by, minutesPacedFrom?.let { p -> Paced(p, minutesPacedHash) }) },
    onDay = onDay?.let { SetBy(it, by) },
    atTime = atTime?.let { SetBy(it, by) },
    sameAs = sameAs?.let { SetBy(it, by) },
    steps = steps?.let { SetSteps(it, by) },
    title = title?.let { SetBy(it, by) },
)

/**
 * [this] with values of yours as a test gives them ([mineOf]); what it leaves out is kept as it was.
 * Laid as they are, without the fold's or [withMine]'s rules.
 */
fun TaskItem.yours(
    notATask: Boolean = mine.notATask != null,
    from: Long? = mine.from?.value,
    after: String? = mine.after?.value,
    dueAt: Long? = mine.dueAt?.value,
    minutes: Int? = mine.minutes?.minutes,
    pacedFrom: Int? = mine.minutes?.paced?.from,
    pacedHash: String? = mine.minutes?.paced?.contentHash,
    onDay: String? = mine.onDay?.value,
    atTime: Long? = mine.atTime?.value,
    sameAs: String? = mine.sameAs?.value,
    steps: List<OwnStep>? = mine.steps?.steps,
    title: String? = mine.title?.value,
    by: By = By.You,
): TaskItem = copy(mine = mineOf(notATask, from, after, dueAt, minutes, pacedFrom, pacedHash, onDay, atTime, sameAs, steps, title, by))
