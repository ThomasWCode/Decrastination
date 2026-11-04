package com.thomaswcode.decrastination.sync.protocol

import com.thomaswcode.decrastination.sync.GmailFinishes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Emails finished in Gmail (D-7 as amended, D-106), as commands once the phone sends them (5.15.0): the journal's the same on both
// sides, as it's mirrored, and its writes are made where the sign-in is, the server's then. While mirroring, the phone's screens
// call its journal as before.

/** [taskId], a starred email, finished at your word: unstarred and archived in Gmail after the grace ([GmailFinishes.finish]). */
@Serializable
@SerialName("FinishGmail")
data class FinishGmail(val taskId: String) : Action, Answers<GmailFinishes.Finish> {
    override suspend fun run(owners: Owners): GmailFinishes.Finish = owners.gmail.finish(taskId, explicit = true)

    // Nothing journaled where it's applied (its email closed or gone there meanwhile, or finishing switched off): refused, so a phone
    // that journaled it takes the journal as it stands from its next pull, as what changed there may not be the journal.
    override fun refused(answer: Any?) = "That email can't be finished now".takeIf { answer == GmailFinishes.Finish.Off || answer == GmailFinishes.Finish.No }
}

/** [taskId]'s finish taken back, [writeId]'s alone where it's given ([GmailFinishes.unfinish]): whether there was one. */
@Serializable
@SerialName("UnfinishGmail")
data class UnfinishGmail(val taskId: String, val writeId: String? = null) : Action, Answers<Boolean> {
    override suspend fun run(owners: Owners): Boolean = owners.gmail.unfinish(taskId, writeId)
}

/** [taskId]'s write to Gmail that was given up, sorted at your word: said no more ([GmailFinishes.sorted]). */
@Serializable
@SerialName("SortGmail")
data class SortGmail(val taskId: String) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = owners.gmail.sorted(taskId)
}

/** The very first finish's OK given: it goes, and those after it go with no OK asked ([GmailFinishes.confirm]). */
@Serializable
@SerialName("ConfirmGmail")
data class ConfirmGmail(val confirmed: Boolean = true) : Action, Answers<Unit> {
    override suspend fun run(owners: Owners) = if (confirmed) owners.gmail.confirm() else owners.gmail.refuse()
}
