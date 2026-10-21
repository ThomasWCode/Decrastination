package com.thomaswcode.decrastination.data

import com.thomaswcode.decrastination.core.GmailWrite
import kotlinx.serialization.Serializable

/**
 * The journal of writes to Gmail (docs/plan_v4/10-emails-done.md), `gmail-writes.json`: each finish of a
 * starred email, and each Undo of one past its grace, journaled before it's made and kept as a receipt
 * while its Undo can stand ([com.thomaswcode.decrastination.core.GmailWrites]). The Sync side's
 * [com.thomaswcode.decrastination.sync.GmailFinishes] alone writes it. A backup keeps the receipts, never
 * a pending write (this phone's and this hour's); no reset clears it.
 */
@Serializable
data class GmailWriteStore(
    val writes: List<GmailWrite> = emptyList(),
    /** The shape this is in ([Migration]): raised with each change of it, a migration bringing older files to it. */
    val version: Int = 1,
)
