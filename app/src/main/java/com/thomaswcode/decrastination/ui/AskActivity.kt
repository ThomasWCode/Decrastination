package com.thomaswcode.decrastination.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.enrich.ChatPrompts
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * Asking Claude (2.0.0, Q29), in place of the Instructions page: **Chat**, where you tell it about
 * your work and it answers with cards to add a task or apply changes; and **Your changes**, the
 * instructions in use (taken back there) and any still to check. A message about a task, an event
 * or a day comes from its page, with what it's about shown over the box.
 */
class AskActivity : ComponentActivity() {

    /** What the message being written is about, from the page that opened this one. */
    private val about = mutableStateOf(About())

    /** Words handed in to send (the Calendar's box), or, if one still waits, to go in the box. */
    private val handed = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        if (savedInstanceState == null) take(intent)
        setContent { AppTheme { Screen(graph) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent) {
        about.value = About(
            taskId = intent.getStringExtra(EXTRA_TASK),
            taskTitle = intent.getStringExtra(EXTRA_TASK_TITLE),
            eventKey = intent.getStringExtra(EXTRA_EVENT),
            eventTitle = intent.getStringExtra(EXTRA_EVENT_TITLE),
            eventStart = intent.getLongExtra(EXTRA_EVENT_START, 0L).takeIf { it != 0L },
            day = intent.getStringExtra(EXTRA_DAY),
        )
        handed.value = intent.getStringExtra(EXTRA_TEXT)
    }

    override fun onResume() {
        super.onResume()
        // What waits for Claude (it was off, resting, or capped) is tried again on opening.
        val graph = AppGraph.get(this)
        graph.scope.launch {
            graph.answerChat()
            graph.readInstructions()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen(graph: AppGraph) {
        val chat by graph.chat.state.collectAsStateWithLifecycle()
        val instructions by graph.instructions.state.collectAsStateWithLifecycle()
        val tasks by graph.tasks.state.collectAsStateWithLifecycle()
        val settings by graph.settings.state.collectAsStateWithLifecycle()
        val byId = tasks.tasks.associateBy { it.id }
        val now = graph.clock.now()
        val zone = graph.clock.zone()
        val scope = rememberCoroutineScope()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var draft by rememberSaveable { mutableStateOf("") }
        var menu by remember { mutableStateOf(false) }
        var clearing by remember { mutableStateOf(false) }
        // A card's task being changed before it's added: "<message id>|<proposal id>".
        var editing by rememberSaveable { mutableStateOf<String?>(null) }
        val claudeOn = settings.aiEnabled && settings.aiKeyActive
        val toCheck = instructions.instructions.filter { it.state != InstructionStatus.Applied }.sortedByDescending { it.at }
        val inUse = instructions.applied.reversed()

        // Words handed in: sent, or, while one of yours still waits, put in the box.
        val words = handed.value
        LaunchedEffect(words) {
            if (words != null) {
                handed.value = null
                if (!graph.ask(words, about.value)) draft = words else about.value = About()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Ask Claude") },
                    navigationIcon = { IconButton(onClick = { finish() }) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") } },
                    actions = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Menu") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Clear the chat") }, enabled = chat.messages.isNotEmpty(), onClick = {
                                    menu = false
                                    clearing = true
                                })
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Chat") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = {
                        // How many wait to be checked, as a badge: the label stays one line.
                        BadgedBox(badge = { if (toCheck.isNotEmpty()) Badge { Text("${toCheck.size}") } }) { Text("Your changes") }
                    })
                }
                if (tab == 0) {
                    Chat(graph, chat.messages, byId, now, zone, claudeOn, onEdit = { m, p -> editing = "$m|$p" })
                    InputBar(
                        draft = draft,
                        onDraft = { draft = it },
                        about = about.value,
                        onForget = { about.value = About() },
                        enabled = claudeOn && chat.unanswered == null,
                        now = now,
                        zone = zone,
                    ) {
                        val text = draft
                        scope.launch {
                            if (graph.ask(text, about.value)) {
                                draft = ""
                                about.value = About()
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        section("To check", toCheck, graph, byId, now, zone)
                        section("In use", inUse, graph, byId, now, zone)
                        if (instructions.instructions.isEmpty()) {
                            item(key = "none") {
                                Text(
                                    "No changes in use. What you apply from the chat is listed here, and taken back here too.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        editing?.split('|', limit = 2)?.let { (messageId, proposalId) ->
            val proposal = chat.messages.firstOrNull { it.id == messageId }?.proposals?.firstOrNull { it.id == proposalId }
            val task = proposal?.task
            if (task == null || proposal.state == ProposalState.Applied) {
                LaunchedEffect(editing) { editing = null }
            } else {
                OwnTaskForm(graph, editing = null, initial = task, onSave = { changed ->
                    graph.applyProposal(messageId, proposalId, changed)
                    graph.chat.value.messages.firstOrNull { it.id == messageId }?.proposals?.firstOrNull { it.id == proposalId }?.state == ProposalState.Applied
                }) { editing = null }
            }
        }
        if (clearing) {
            AlertDialog(
                onDismissRequest = { clearing = false },
                title = { Text("Clear the chat?") },
                text = { Text("Its messages go. What you added or applied from it stays: your tasks, and your changes (taken back under Your changes).") },
                confirmButton = {
                    TextButton(onClick = {
                        clearing = false
                        scope.launch { graph.clearChat() }
                    }) { Text("Clear") }
                },
                dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } },
            )
        }
    }

    private fun LazyListScope.section(title: String, list: List<Instruction>, graph: AppGraph, tasks: Map<String, TaskItem>, now: Long, zone: ZoneId) {
        if (list.isEmpty()) return
        item(key = "title-$title") { SectionHeading(title, trailing = "${list.size}") }
        itemsIndexed(list, key = { _, it -> it.id }) { index, instruction ->
            ListTile(index, list.size) { InstructionContent(graph, instruction, tasks, now, zone) }
        }
    }

    companion object {
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TASK = "task"
        private const val EXTRA_TASK_TITLE = "taskTitle"
        private const val EXTRA_EVENT = "event"
        private const val EXTRA_EVENT_TITLE = "eventTitle"
        private const val EXTRA_EVENT_START = "eventStart"
        private const val EXTRA_DAY = "day"

        /** The chat, about [about], with [text] to send (or to put in the box, if one still waits). */
        fun intent(context: Context, about: About = About(), text: String? = null): Intent = Intent(context, AskActivity::class.java)
            .putExtra(EXTRA_TEXT, text)
            .putExtra(EXTRA_TASK, about.taskId)
            .putExtra(EXTRA_TASK_TITLE, about.taskTitle)
            .putExtra(EXTRA_EVENT, about.eventKey)
            .putExtra(EXTRA_EVENT_TITLE, about.eventTitle)
            .putExtra(EXTRA_EVENT_START, about.eventStart ?: 0L)
            .putExtra(EXTRA_DAY, about.day)
    }
}

/** The examples the empty chat shows. */
private val EXAMPLES = listOf(
    "I need to write my UCAS personal statement by 1 December, about 8 hours",
    "The physics prep will take 90 minutes",
    "Move the German vocab to Thursday",
    "I'm out on Saturday evening from 7",
    "Remind me to buy mum a present before Sunday",
)

/** The conversation, newest at the foot, kept in view as it grows. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Chat(
    graph: AppGraph,
    messages: List<ChatMessage>,
    tasks: Map<String, TaskItem>,
    now: Long,
    zone: ZoneId,
    claudeOn: Boolean,
    onEdit: (messageId: String, proposalId: String) -> Unit,
) {
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.note) {
        if (messages.isNotEmpty()) list.animateScrollToItem(messages.size + 1)
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "intro") {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!claudeOn) {
                    Text(
                        "Claude is off, or has no key: switch it on in Settings (its key goes in Setup). Meanwhile, New task in Tasks adds one with the form.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (messages.isEmpty()) {
                    Text(
                        "Tell Claude about your work: a task to add, a deadline or how long something takes, a day you can't work. It answers with cards, and nothing changes until you tap Add or Apply.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    EXAMPLES.forEach { Text("“$it”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        items(messages, key = { it.id }) { message ->
            if (message.from == Speaker.You) Yours(graph, message, now, zone) else Claudes(graph, message, tasks, now, zone, onEdit)
        }
        item(key = "end") { Box(Modifier.size(1.dp)) }
    }
}

/** A message of yours, on the right: what it's about, your words, and, while it waits, why. */
@Composable
private fun Yours(graph: AppGraph, message: ChatMessage, now: Long, zone: ZoneId) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (message.about != About()) Text(aboutLine(message.about, now, zone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(message.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        if (message.answeredAt == null) {
            if (message.note == null) {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("Claude is thinking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(message.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                Row {
                    TextButton(onClick = { graph.scope.launch { graph.answerChat() } }) { Text("Try again") }
                    TextButton(onClick = { scope.launch { graph.withdrawQuestion() } }) { Text("Take back") }
                }
            }
        }
    }
}

/** A message of Claude's, on the left: its words, then a card for each thing it would do. */
@Composable
private fun Claudes(graph: AppGraph, message: ChatMessage, tasks: Map<String, TaskItem>, now: Long, zone: ZoneId, onEdit: (String, String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp), modifier = Modifier.widthIn(max = 320.dp)) {
            Text(message.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
        message.proposals.forEach { ProposalCard(graph, message.id, it, tasks, now, zone, onEdit) }
        message.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp)) }
    }
}

/** One thing Claude would do, with what you can do about it: add or apply it, change it first, set it aside, or undo it. */
@Composable
private fun ProposalCard(graph: AppGraph, messageId: String, proposal: Proposal, tasks: Map<String, TaskItem>, now: Long, zone: ZoneId, onEdit: (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    val applied = proposal.state == ProposalState.Applied
    val discarded = proposal.state == ProposalState.Discarded
    Card(
        modifier = Modifier.fillMaxWidth().padding(end = 24.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (discarded) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val task = proposal.task
            if (task != null) {
                Text(if (task.kind == Kind.Event) "New event of yours" else "New task of yours", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(taskLine(task, now, zone), style = MaterialTheme.typography.bodyMedium)
                if (task.steps.isNotEmpty()) {
                    task.steps.forEach { s ->
                        val dates = listOfNotNull(s.from?.let { "from " + Format.at(it, now, zone) }, s.dueAt?.let { Format.due(it, now, zone) }).joinToString(", ")
                        Text("• ${s.title} (${Format.minutes(s.minutes)}${if (dates.isEmpty()) "" else "; $dates"})", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (task.notes.isNotBlank()) Text(task.notes, style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Changes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                // In the words it was proposed in: made, the task's estimate is the new one.
                val lines = proposal.lines.takeIf { it.size == proposal.changes.size } ?: proposal.changes.map { Instructions.describe(it, tasks, zone) }
                lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
            proposal.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    applied -> {
                        Text(if (task != null) "Added" else "Applied", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.CenterVertically))
                        if (proposal.made != null) TextButton(onClick = { scope.launch { graph.undoProposal(messageId, proposal.id) } }) { Text("Undo") }
                    }
                    discarded -> {
                        Text("Set aside", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                        TextButton(onClick = { scope.launch { graph.discardProposal(messageId, proposal.id, discarded = false) } }) { Text("Bring back") }
                    }
                    else -> {
                        Button(onClick = { scope.launch { graph.applyProposal(messageId, proposal.id) } }) { Text(if (task != null) "Add" else "Apply") }
                        if (task != null) OutlinedButton(onClick = { onEdit(messageId, proposal.id) }) { Text("Change first") }
                        TextButton(onClick = { scope.launch { graph.discardProposal(messageId, proposal.id) } }) { Text("Set aside") }
                    }
                }
            }
        }
    }
}

/** A card's task in a line: its kind and subject, when it's due (or starts), how long it takes. */
private fun taskLine(task: OwnTask, now: Long, zone: ZoneId): String = listOfNotNull(
    task.kind.label,
    task.className,
    task.dueAt?.let { if (task.kind == Kind.Event) Format.at(it, now, zone) else Format.due(it, now, zone) } ?: "No due date",
    task.startFrom?.let { "not before " + Format.at(it, now, zone) },
    if (task.kind == Kind.Event) null else Format.minutes(if (task.steps.isEmpty()) task.minutes else task.steps.sumOf { it.minutes }),
).joinToString(" · ")

/** The box you write in, with what the message is about over it, and Send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(draft: String, onDraft: (String) -> Unit, about: About, onForget: () -> Unit, enabled: Boolean, now: Long, zone: ZoneId, onSend: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (about != About()) {
                InputChip(
                    selected = true,
                    onClick = onForget,
                    label = { Text(aboutLine(about, now, zone), maxLines = 1) },
                    trailingIcon = { Icon(painterResource(R.drawable.ic_close), contentDescription = "Not about it", modifier = Modifier.size(16.dp)) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    draft,
                    { onDraft(it.take(ChatPrompts.MAX_TEXT)) },
                    placeholder = { Text("Tell Claude…") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
                IconButton(enabled = enabled && draft.isNotBlank(), onClick = onSend) {
                    Icon(painterResource(R.drawable.ic_send), contentDescription = "Send")
                }
            }
        }
    }
}
