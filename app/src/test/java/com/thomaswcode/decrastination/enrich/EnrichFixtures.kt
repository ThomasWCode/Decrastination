package com.thomaswcode.decrastination.enrich

import com.thomaswcode.decrastination.Fixtures
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Source
import com.thomaswcode.decrastination.core.TaskItem

internal val NOW = Fixtures.at("2026-10-09T17:00")

internal fun email(body: String = "Please sign and return the trip form by Monday 12 October.") = TaskItem(
    id = "gmail:t1", source = Source.Gmail, sourceId = "t1", title = "Berlin trip form", detail = body, kind = Kind.Admin,
    sourceEffortMin = 15, firstSeenAt = NOW, lastSeenAt = NOW,
    extra = mapOf("from" to "Mr Hughes <hughes@school.example>", "received" to Fixtures.at("2026-10-08T16:30").toString()),
)

internal fun assignment(detail: String) = TaskItem(
    id = "teams:a1", source = Source.Teams, sourceId = "a1", title = "Reisen in Europa", detail = detail,
    className = "12.8 German 2026-27", dueAt = Fixtures.at("2026-10-14T08:30"), kind = Kind.Homework, firstSeenAt = NOW, lastSeenAt = NOW,
)
