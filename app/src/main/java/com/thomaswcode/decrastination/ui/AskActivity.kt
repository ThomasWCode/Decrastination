package com.thomaswcode.decrastination.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thomaswcode.decrastination.AppGraph
import com.thomaswcode.decrastination.R
import com.thomaswcode.decrastination.core.About
import com.thomaswcode.decrastination.core.ChatMessage
import com.thomaswcode.decrastination.core.ChatSummary
import com.thomaswcode.decrastination.core.Instruction
import com.thomaswcode.decrastination.core.InstructionStatus
import com.thomaswcode.decrastination.core.Instructions
import com.thomaswcode.decrastination.core.Kind
import com.thomaswcode.decrastination.core.Memories
import com.thomaswcode.decrastination.core.OwnTask
import com.thomaswcode.decrastination.core.Proposal
import com.thomaswcode.decrastination.core.ProposalState
import com.thomaswcode.decrastination.core.Speaker
import com.thomaswcode.decrastination.core.TaskItem
import com.thomaswcode.decrastination.felix.ChatPrompts
import com.thomaswcode.decrastination.felix.Conversation
import com.thomaswcode.decrastination.felix.Replies
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Asking Claude (2.0.0, Q29), in place of the Instructions page: **Chat**, where you tell it about
 * your work and it answers with cards to add a task or apply changes, and where every message of
 * Felix's is, what he writes unasked among them; and **Your changes**, the instructions in use (taken
 * back there) and any still to check. A message about a task, an event or a day comes from its page,
 * with what it's about shown over the box. Each day's date over its first message, the time under each,
 * and a divider over what's new; scrolled back, the earlier messages; a message swiped or held to reply
 * to it ([ChatScreenState], docs/plan_v4/05-the-chat.md §4, §5). Opened from his notification, it opens
 * at the message it was for; left, what's in it is read ([Conversation.read]).
 */
class AskActivity : ComponentActivity() {

    /** What the message being written is about, from the page that opened this one. */
    private val about = mutableStateOf(About())

    /** Words handed in to send (the Calendar's box), or only to go in the box ([sendHanded]). */
    private val handed = mutableStateOf<String?>(null)

    /** Whether [handed] is sent, or only put in the box to be changed or sent (*What gives?*, PL-02). */
    private var sendHanded = true

    /** The screen's state beside the chat: the divider, where it opens, the earlier pages, the reply being written. */
    private val screen = ChatScreenState()

    /** Opened from his notification just now: what was unread is marked already, as it was before it was read. */
    private var openedAt = false

    /** Whether the Chat tab is the one showing, not Your changes: only then is the chat on screen. */
    private var chatTab by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)
        // Made again (turned, or brought back): what it's about kept, the words handed in already sent, and
        // the tab showing, the message being replied to and the archive's pages loaded, as they were.
        if (savedInstanceState == null) {
            take(intent)
        } else {
            about.value = aboutFrom(savedInstanceState)
            chatTab = savedInstanceState.getBoolean(STATE_CHAT_TAB, true)
            screen.restored(savedInstanceState.getInt(STATE_EARLIER), savedInstanceState.getString(STATE_REPLYING)?.let { graph.conversation.outbox.find(it) }, savedInstanceState.getBoolean(STATE_OFFERED))
        }
        setContent { AppTheme { Screen(graph) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val a = about.value
        outState.putString(EXTRA_TASK, a.taskId)
        outState.putString(EXTRA_TASK_TITLE, a.taskTitle)
        outState.putString(EXTRA_EVENT, a.eventKey)
        outState.putString(EXTRA_EVENT_TITLE, a.eventTitle)
        outState.putLong(EXTRA_EVENT_START, a.eventStart ?: 0L)
        outState.putString(EXTRA_DAY, a.day)
        outState.putInt(STATE_EARLIER, screen.earlier)
        outState.putString(STATE_REPLYING, screen.replyingTo?.id)
        outState.putBoolean(STATE_OFFERED, screen.offered)
        outState.putBoolean(STATE_CHAT_TAB, chatTab)
    }

    /** What a message is about, from an intent's extras or a saved state's. */
    private fun aboutFrom(b: Bundle?): About = About(
        taskId = b?.getString(EXTRA_TASK),
        taskTitle = b?.getString(EXTRA_TASK_TITLE),
        eventKey = b?.getString(EXTRA_EVENT),
        eventTitle = b?.getString(EXTRA_EVENT_TITLE),
        eventStart = b?.getLong(EXTRA_EVENT_START, 0L)?.takeIf { it != 0L },
        day = b?.getString(EXTRA_DAY),
    )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    private fun take(intent: Intent) {
        // Every way in is to the chat: left on Your changes, the Chat tab's shown.
        showingChat(true)
        about.value = aboutFrom(intent.extras)
        sendHanded = intent.getBooleanExtra(EXTRA_SEND, true)
        handed.value = intent.getStringExtra(EXTRA_TEXT)
        // From his notification: opened at its message, what was unread marked for this visit, and what
        // it held read now (now as it opens, not as the work runs, which may be after another came).
        intent.getStringExtra(EXTRA_OPEN_AT)?.let { id ->
            val graph = AppGraph.get(this)
            screen.opened(graph.chat.value, graph.clock.now(), at = id)
            openedAt = true
            val now = graph.clock.now()
            graph.scope.launch { graph.conversation.read(now) }
        }
    }

    override fun onResume() {
        super.onResume()
        // What waits for Claude (it was off, resting, or capped) is tried again on opening.
        val graph = AppGraph.get(this)
        // What's unread marked for this visit, for its divider: from his notification, as it was when it
        // opened; on Your changes, once the Chat tab's shown.
        if (!openedAt && chatTab) screen.opened(graph.chat.value, graph.clock.now())
        openedAt = false
        graph.conversation.chatInFront = chatTab
        // His notification taken away: what it held is on the screen.
        graph.conversation.outbox.notify(graph.clock.now())
        graph.scope.launch {
            graph.conversation.answerChat()
            // On the screen they're shown on: you're waiting on them.
            graph.readInstructions(interactive = true)
        }
    }

    override fun onPause() {
        super.onPause()
        val graph = AppGraph.get(this)
        graph.conversation.chatInFront = false
        val now = graph.clock.now()
        // Every message closed again for the next visit, its labels done with (5c), and what it showed read.
        screen.paused(read = now.takeIf { chatTab })
        // What arrived while it was on screen was seen: read up to now, so only what comes after is his notification.
        if (chatTab) graph.scope.launch { graph.conversation.read(now) }
    }

    /**
     * The Chat tab shown, or Your changes ([chat] false): the chat's on screen only on its tab, so what
     * arrives while the other's showing is his notification. Left for it, the chat is left as the screen
     * is (what it showed read, every message closed again); back on it, it's opened as the screen is, at
     * what came meanwhile, and his notification's taken away. Called before the tab changes, so the chat
     * is drawn knowing where it opens.
     */
    private fun showingChat(chat: Boolean) {
        if (chatTab == chat) return
        chatTab = chat
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val graph = AppGraph.get(this)
        graph.conversation.chatInFront = chat
        val now = graph.clock.now()
        if (chat) {
            screen.opened(graph.chat.value, now)
            graph.conversation.outbox.notify(now)
        } else {
            screen.paused(read = now)
            graph.scope.launch { graph.conversation.read(now) }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen(graph: AppGraph) {
        val chat by graph.chat.state.collectAsStateWithLifecycle()
        val archive by graph.chatArchive.state.collectAsStateWithLifecycle()
        val instructions by graph.instructions.state.collectAsStateWithLifecycle()
        val tasks by graph.tasks.state.collectAsStateWithLifecycle()
        val settings by graph.settings.state.collectAsStateWithLifecycle()
        val byId = tasks.tasks.associateBy { it.id }
        val now = graph.clock.now()
        val zone = graph.clock.zone()
        val scope = rememberCoroutineScope()
        val tab = if (chatTab) 0 else 1
        var draft by rememberSaveable { mutableStateOf("") }
        var menu by remember { mutableStateOf(false) }
        var clearing by remember { mutableStateOf(false) }
        // What he remembers, from the menu, each to delete (your call, 16 Oct), as Settings → Felix has it.
        var remembering by rememberSaveable { mutableStateOf(false) }
        // A card's task being changed before it's added: "<message id>|<proposal id>".
        var editing by rememberSaveable { mutableStateOf<String?>(null) }
        val claudeOn = settings.aiEnabled && settings.aiKeyActive
        val toCheck = instructions.instructions.filter { it.state != InstructionStatus.Applied }.sortedByDescending { it.at }
        val inUse = instructions.applied.reversed()

        // Words handed in: sent, or, when only to be put there, put in the box.
        val words = handed.value
        LaunchedEffect(words) {
            if (words != null) {
                // Let go of once sent: cleared first, they recomposed this effect away, which cancelled
                // the send halfway, its message saved to the file but neither shown nor answered.
                val sent = sendHanded && graph.conversation.ask(words, about.value)
                handed.value = null
                if (!sent) draft = words else about.value = About()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(settings.felixName) },
                    navigationIcon = { IconButton(onClick = { finish() }) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") } },
                    actions = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Menu") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("What ${settings.felixName} remembers") }, onClick = {
                                    menu = false
                                    remembering = true
                                })
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
                    Tab(selected = tab == 0, onClick = { showingChat(true) }, text = { Text("Chat") })
                    Tab(selected = tab == 1, onClick = { showingChat(false) }, text = {
                        // How many wait to be checked, as a badge: the label stays one line.
                        BadgedBox(badge = { if (toCheck.isNotEmpty()) Badge { Text("${toCheck.size}") } }) { Text("Your changes") }
                    })
                }
                if (tab == 0) {
                    Chat(graph, screen, chat.messages, archive.messages, byId, now, zone, claudeOn, onEdit = { m, p -> editing = "$m|$p" })
                    // His replies to offer (UI-06), under his last message: a tap answers it, as a reply to
                    // it from anywhere does (a check-in's answer, an event's, with no call).
                    // His question still waiting for its answer since your last turn (with several asked together,
                    // the one before it once that's answered): the box and the chips answer it (D-98); else his last
                    // message's replies, where it's his.
                    val checkIn = chat.felix.checkInDraft != null
                    val pending = Replies.pending(chat.messages, checkIn)
                    val last = pending ?: chat.messages.lastOrNull { it.from == Speaker.You || it.text.isNotBlank() }?.takeIf { it.from == Speaker.Felix && it.answeredAt == null }
                    LaunchedEffect(pending?.id, checkIn) { screen.offer(pending, checkIn) }
                    if (last != null && last.suggestedReplies.isNotEmpty()) {
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            last.suggestedReplies.forEach { reply ->
                                androidx.compose.material3.SuggestionChip(onClick = { graph.scope.launch { graph.conversation.ask(reply, replyTo = last.id) } }, label = { Text(reply) })
                            }
                        }
                    }
                    InputBar(
                        name = settings.felixName,
                        draft = draft,
                        onDraft = { draft = it },
                        about = about.value,
                        onForget = { about.value = About() },
                        replyingTo = screen.replyingTo,
                        onNotReplying = { screen.notReplying() },
                        now = now,
                        zone = zone,
                    ) {
                        // Kept whatever the screen does meanwhile (Back at once): sent from the app's scope, the
                        // box cleared at once and filled again only if it couldn't be.
                        val text = draft
                        val subject = about.value
                        val replying = screen.replyingTo
                        draft = ""
                        about.value = About()
                        screen.sent()
                        graph.scope.launch {
                            if (!graph.conversation.ask(text, subject, replyTo = replying?.id)) {
                                draft = text
                                about.value = subject
                                screen.replyingTo = replying
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
            // Added as it came (16 Oct): it's changed as any task of yours is, in the form.
            val added = proposal?.made?.takeIf { proposal.state == ProposalState.Applied }?.let { id -> tasks.tasks.firstOrNull { it.id == id } }
            if (added != null) {
                OwnTaskForm(graph, added) { editing = null }
            } else if (task == null || proposal.state == ProposalState.Applied) {
                LaunchedEffect(editing) { editing = null }
            } else {
                OwnTaskForm(graph, editing = null, initial = task, onSave = { changed ->
                    graph.conversation.applyProposal(messageId, proposalId, changed)
                    graph.chat.value.messages.firstOrNull { it.id == messageId }?.proposals?.firstOrNull { it.id == proposalId }?.state == ProposalState.Applied
                }) { editing = null }
            }
        }
        if (remembering) {
            androidx.compose.material3.ModalBottomSheet(onDismissRequest = { remembering = false }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    Text("What ${settings.felixName} remembers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
                    MemorySection(graph)
                }
            }
        }
        if (clearing) {
            AlertDialog(
                onDismissRequest = { clearing = false },
                title = { Text("Clear the chat?") },
                text = { Text("Its messages go, the earlier ones too. What you added or applied from it stays: your tasks, and your changes (taken back under Your changes).") },
                confirmButton = {
                    TextButton(onClick = {
                        clearing = false
                        screen.cleared()
                        scope.launch { graph.conversation.clearChat() }
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
        private const val EXTRA_SEND = "send"
        private const val EXTRA_TASK = "task"
        private const val EXTRA_TASK_TITLE = "taskTitle"
        private const val EXTRA_EVENT = "event"
        private const val EXTRA_EVENT_TITLE = "eventTitle"
        private const val EXTRA_EVENT_START = "eventStart"
        private const val EXTRA_DAY = "day"
        private const val EXTRA_OPEN_AT = "openAt"
        private const val STATE_EARLIER = "earlier"
        private const val STATE_REPLYING = "replyingTo"
        private const val STATE_OFFERED = "offered"
        private const val STATE_CHAT_TAB = "chatTab"

        /**
         * The chat, about [about], with [text] to send; with [send] false, only put in the box, to be
         * changed or sent. From his notification, opened at
         * the message [openAt], what the notification held read.
         */
        fun intent(context: Context, about: About = About(), text: String? = null, send: Boolean = true, openAt: String? = null): Intent = Intent(context, AskActivity::class.java)
            .putExtra(EXTRA_OPEN_AT, openAt)
            .putExtra(EXTRA_TEXT, text)
            .putExtra(EXTRA_SEND, send)
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

/**
 * The conversation, newest at the foot, kept in view as it grows; each day's date over its first
 * message, and the divider over the first unread, for this visit (docs/plan_v4/05-the-chat.md §4, §5).
 * Opened at his notification's message, else at the first unread, else at the foot; scrolled back to
 * the top, the archive's earlier messages, a page at a time. A message swiped right, or held for Reply,
 * is the one the next replies to. His silences (he chose not to write) aren't shown.
 */
@Composable
private fun ColumnScope.Chat(
    graph: AppGraph,
    screen: ChatScreenState,
    all: List<ChatMessage>,
    archive: List<ChatMessage>,
    tasks: Map<String, TaskItem>,
    now: Long,
    zone: ZoneId,
    claudeOn: Boolean,
    onEdit: (messageId: String, proposalId: String) -> Unit,
) {
    val list = rememberLazyListState()
    val name = graph.settings.value.felixName
    val rows = remember(all, archive, screen.earlier, screen.firstUnread, zone) { screen.items(all, archive, zone) }
    val shown = all.filter { it.from == Speaker.You || it.text.isNotBlank() }
    // Yours waiting for his answer, with nothing to say why (Claude off, resting, a failed call): he's
    // typing, whatever he's written unasked since.
    val waiting = all.lastOrNull { it.from == Speaker.You && it.answeredAt == null }
    val typing = waiting != null && waiting.note == null
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    // At the foot once it's been put somewhere: what follows slides into view.
    var settled by remember { mutableStateOf(false) }
    // Where it opens (his notification's message, else the first unread, with the divider over it), or a
    // quote tapped: that message, once; one no longer there (the chat cleared), the foot.
    LaunchedEffect(screen.scrollTo, rows.size) {
        val target = screen.scrollTo ?: return@LaunchedEffect
        val at = rows.indexOfFirst { it.key == target }
        if (at < 0) list.scrollToItem(rows.size + 1) else list.scrollToItem(1 + if (at > 0 && rows[at - 1] == ChatScreenState.Item.Divider) at - 1 else at)
        settled = true
        screen.scrolled()
    }
    // Else at the foot, kept there as the conversation grows (not as earlier pages load over it). Where it
    // opens at a message, not: read as the list is drawn, as that message's scroll clears it at once.
    val opening = screen.scrollTo
    LaunchedEffect(shown.lastOrNull()?.id, waiting?.note, typing) {
        if (shown.isEmpty() || opening != null) return@LaunchedEffect
        val foot = rows.size + if (typing) 2 else 1
        if (settled) list.animateScrollToItem(foot) else list.scrollToItem(foot)
        settled = true
    }
    // Scrolled back near the top by you: the archive's next page, as a messaging app loads its history;
    // a few rows before the top, so the list keeps your place by the message you're at as it grows over it.
    val archived by rememberUpdatedState(archive.size)
    LaunchedEffect(list) {
        var dragged = false
        launch { list.interactionSource.interactions.collect { if (it is DragInteraction.Start) dragged = true } }
        snapshotFlow { list.firstVisibleItemIndex }.collect { if (dragged && it <= EARLIER_WITHIN) screen.loadEarlier(archived) }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "intro") {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!claudeOn) {
                    Text(
                        "Claude is off, or has no key: switch it on in Settings (its key goes in Setup). What you write here waits for it; meanwhile, New task in Tasks adds one with the form.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (shown.isEmpty()) {
                    Text(
                        "Tell ${graph.settings.value.felixName} about your work: a task to add, a deadline or how long something takes, a day you can't work, a step done. Whatever he does is made at once, and its Undo takes it back.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    EXAMPLES.forEach { Text("“$it”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        items(rows, key = { it.key }) { row ->
            when (row) {
                ChatScreenState.Item.Earlier -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { screen.loadEarlier(archive.size) }) { Text("Earlier messages") }
                }
                is ChatScreenState.Item.Day -> DayLine(row.date, today)
                ChatScreenState.Item.Divider -> NewMessages()
                is ChatScreenState.Item.Message -> {
                    val message = row.message
                    val flash = screen.flash == message.id
                    LaunchedEffect(flash) {
                        if (flash) {
                            delay(FLASH_MS)
                            screen.flashed()
                        }
                    }
                    SwipeToReply(onReply = { screen.replyTo(message) }) {
                        if (message.from == Speaker.You) {
                            Yours(graph, message, row.quote, name, flash, now, zone, onQuote = { screen.reveal(it, all, archive) }) { screen.replyTo(message) }
                        } else {
                            Claudes(graph, screen, message, tasks, flash, now, zone, onEdit, live = !row.archived) { screen.replyTo(message) }
                        }
                    }
                }
            }
        }
        if (typing) item(key = "typing") { Typing(graph) }
        item(key = "end") { Box(Modifier.size(1.dp)) }
    }
}

/** How long the message his notification opened the chat at stays tinted. */
private const val FLASH_MS = 1_000L

/** How near the top of the list (in rows) scrolling back loads the archive's next page. */
private const val EARLIER_WITHIN = 5

/** The line over a day's first message (5b): "Today", "Yesterday", or its date. */
@Composable
private fun DayLine(date: LocalDate, today: LocalDate) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            ChatScreenState.dayLine(date, today),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** The line over the first message unread when the chat opened, for that visit only (5b), as messaging apps draw it. */
@Composable
private fun NewMessages() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
        Text("New messages", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * A message's row, swiped right to reply to it (docs/plan_v4/05-the-chat.md §5): the reply arrow shown
 * as it's dragged, a haptic tick past a third of the width, and let go there, [onReply]; it springs back
 * either way. A drag more down than across scrolls the list instead.
 */
@Composable
private fun SwipeToReply(onReply: () -> Unit, content: @Composable () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var width by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    var past by remember { mutableStateOf(false) }
    val reach = width / 3f
    Box(
        Modifier.fillMaxWidth()
            .onSizeChanged { width = it.width }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    offset = (offset + delta).coerceIn(0f, reach * 1.2f)
                    val over = reach > 0f && offset >= reach
                    if (over && !past) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    past = over
                },
                onDragStopped = {
                    if (past) onReply()
                    past = false
                    animate(offset, 0f) { value, _ -> offset = value }
                },
            ),
    ) {
        Icon(
            painterResource(R.drawable.ic_reply),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp).graphicsLayer { alpha = if (reach > 0f) (offset / reach).coerceIn(0f, 1f) else 0f },
        )
        Box(Modifier.fillMaxWidth().offset { IntOffset(offset.roundToInt(), 0) }) { content() }
    }
}

/**
 * A message's bubble, [colour] in [shape]: held, Reply and Copy (5b); the one his notification opened
 * the chat at ([flash]) tinted for a moment.
 */
@Composable
private fun Bubble(message: ChatMessage, colour: Color, shape: Shape, flash: Boolean, onReply: () -> Unit, onTap: () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val tint by animateColorAsState(if (flash) MaterialTheme.colorScheme.tertiaryContainer else colour, label = "flash")
    Box {
        Surface(
            color = tint,
            contentColor = contentColorFor(colour),
            shape = shape,
            modifier = Modifier.widthIn(max = 320.dp).clip(shape).combinedClickable(onClick = onTap, onLongClick = { menu = true }),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), content = content)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Reply") }, onClick = {
                menu = false
                onReply()
            })
            DropdownMenuItem(text = { Text("Copy") }, onClick = {
                menu = false
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Message", message.text))
            })
        }
    }
}

/** When [message] was sent, in small type under its bubble (5b). */
@Composable
private fun Time(message: ChatMessage, zone: ZoneId) {
    Text(Format.time(message.at, zone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
}

/** The message a reply answers, over its words (5b): who wrote it and its first line, in a tinted block; tapped, [onOpen] goes to it. */
@Composable
private fun Quote(quote: ChatMessage, name: String, onOpen: () -> Unit) {
    Row(
        Modifier.padding(bottom = 6.dp).height(IntrinsicSize.Min).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)).clickable(onClick = onOpen),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(if (quote.from == Speaker.Felix) name else "You", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(quote.text.trim().lineSequence().first(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A message of yours, on the right: what it replies to where that isn't the message above ([quote],
 * tapped, [onQuote] goes to it), what it's about, your words and their time; and, while it waits for a
 * reason (Claude off, a failed call), why. Held, [onReply] is Reply.
 */
@Composable
private fun Yours(graph: AppGraph, message: ChatMessage, quote: ChatMessage?, name: String, flash: Boolean, now: Long, zone: ZoneId, onQuote: (String) -> Unit, onReply: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Bubble(message, MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), flash, onReply) {
            quote?.let { Quote(it, name) { onQuote(it.id) } }
            if (message.about != About()) Text(aboutLine(message.about, now, zone), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(message.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Time(message, zone)
        // While it's answered, he's typing ([Typing]), under it.
        if (message.answeredAt == null && message.note != null) {
            Text(message.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
            Row {
                TextButton(onClick = { graph.scope.launch { graph.conversation.answerChat(retry = true) } }) { Text("Try again") }
                TextButton(onClick = { scope.launch { graph.conversation.withdrawQuestion() } }) { Text("Take back") }
            }
        }
    }
}

/**
 * Felix answering, on his side (your call, 16 Oct, in place of "Felix is thinking… 12 s"): three
 * dots rising in turn in a bubble of his, as a messaging app shows someone typing; and once the
 * call has run [CANCEL_AFTER_S], a way to stop waiting (AC-08).
 */
@Composable
private fun Typing(graph: AppGraph) {
    val since by graph.conversation.answeringSince.collectAsStateWithLifecycle()
    var clock by remember { mutableStateOf(graph.clock.now()) }
    LaunchedEffect(since) {
        while (since != null) {
            clock = graph.clock.now()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val long = since?.let { (clock - it) / 1_000 >= CANCEL_AFTER_S } == true
    val name = graph.settings.value.felixName
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)) {
            Dots(Modifier.padding(horizontal = 16.dp, vertical = 15.dp).semantics { contentDescription = "$name is typing" })
        }
        if (long) TextButton(onClick = { graph.conversation.cancelAnswer() }) { Text("Cancel") }
    }
}

/** Three dots, each rising and brightening in turn, [DOT_STEP_MS] after the one before: a wave every [DOT_CYCLE_MS]. */
@Composable
private fun Dots(modifier: Modifier) {
    val wave = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val up by wave.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(DOT_CYCLE_MS / 2, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(i * DOT_STEP_MS),
                ),
                label = "dot $i",
            )
            Box(
                Modifier.size(8.dp)
                    .graphicsLayer {
                        translationY = -up * 3.dp.toPx()
                        alpha = 0.35f + 0.65f * up
                    }
                    .background(colour, androidx.compose.foundation.shape.CircleShape),
            )
        }
    }
}

/** The typing dots' wave ([Dots]): one rise and fall, and how far behind the one before each dot rises. */
private const val DOT_CYCLE_MS = 1_200
private const val DOT_STEP_MS = 200

/**
 * A message of Felix's, on the left: his words and their time; what he did under them folded behind a
 * chevron at the bubble's corner, red where something went wrong, and unfolded as it always was, with
 * every Undo (docs/plan_v4/05-the-chat.md §6); the label that says it in a line ([Labelled]); and
 * outside the fold, a task he shows you and a button in the app's words. Held, [onReply] is Reply.
 */
@Composable
private fun Claudes(graph: AppGraph, screen: ChatScreenState, message: ChatMessage, tasks: Map<String, TaskItem>, flash: Boolean, now: Long, zone: ZoneId, onEdit: (String, String) -> Unit, live: Boolean, onReply: () -> Unit) {
    val folds = ChatSummary.folds(message)
    val open = message.id in screen.open
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Labelled(screen, message) {
                // A tap on the bubble opens or closes what's folded, as its chevron does: a bigger place to tap.
                Bubble(message, MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp), flash, onReply, onTap = { if (folds) screen.toggle(message.id) }) {
                    Text(message.text, style = MaterialTheme.typography.bodyLarge)
                    if (folds) Chevron(open, ChatSummary.problems(message) > 0, Modifier.align(Alignment.End)) { screen.toggle(message.id) }
                }
            }
            Time(message, zone)
        }
        // A message in the app's words carries its own button: the week's review to open, its summary to send.
        Opens(message)
        // A task he shows you (AI-14), the point of the message: never folded.
        message.proposals.filter { it.open != null }.forEach { ProposalCard(graph, message.id, it, tasks, now, zone, onEdit, live) }
        AnimatedVisibility(folds && open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // What he searched the web for, and the pages it found, each to open (16 Oct).
                Searched(message)
                message.proposals.filter { it.open == null }.forEach { ProposalCard(graph, message.id, it, tasks, now, zone, onEdit, live) }
                // What it remembered or forgot (AI-21), in small type under its words, each with its Undo.
                Remembered(graph, message, live)
                // What it did beyond its cards (AI-14; 16 Oct, everything he does), likewise, each with its Undo;
                // and what it says of them with nothing to undo (a change refused, where a time went).
                Acts(graph, message, live)
                message.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp)) }
            }
        }
    }
}

/** The corner of a bubble with something folded under it (5c): down while it's closed, up while it's open; red where something under it went wrong. */
@Composable
private fun Chevron(open: Boolean, problem: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    Icon(
        painterResource(R.drawable.ic_expand_more),
        contentDescription = if (open) "Hide what he did" else "Show what he did",
        tint = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(24.dp).clip(CircleShape).clickable(onClick = onToggle).graphicsLayer { rotationZ = if (open) 180f else 0f },
    )
}

/**
 * [bubble] with [message]'s label behind it (Q5: A), where it gets one ([ChatScreenState.labels]):
 * [LABEL_IN_MS] after it's shown the label slides out from under the bubble's edge, to its right where
 * there's room, else below it, from its start; it stays [LABEL_STAYS_MS] and slides back, and is gone.
 * Drawn under the bubble, so it comes out from behind it, and over nothing else, so the list doesn't move;
 * never wider than the row, its end cut. Tapped, it opens what's folded.
 */
@Composable
private fun Labelled(screen: ChatScreenState, message: ChatMessage, bubble: @Composable () -> Unit) {
    val line = remember(message) { ChatSummary.line(message) }
    // Looked at again once its line has something in it (an answer's changes and acts are kept after its
    // words), and at each opening of the chat, as what's unread then has changed.
    val shows = remember(message.id, line.isEmpty, screen.visit) { screen.labels(message) }
    if (!shows) {
        bubble()
        return
    }
    var out by remember { mutableStateOf(false) }
    // Its time counted from when its row is on screen, not when the list made it ready, a little ahead.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(message.id, placed) {
        if (!placed) return@LaunchedEffect
        screen.labelled(message.id)
        delay(LABEL_IN_MS)
        out = true
        delay(LABEL_STAYS_MS)
        out = false
    }
    val slide by animateFloatAsState(if (out) 1f else 0f, tween(LABEL_SLIDE_MS), label = "label")
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    BoxWithConstraints(Modifier.onPlaced { placed = true }) {
        val room = constraints.maxWidth
        val widest = maxWidth
        var bubbleSize by remember { mutableStateOf(IntSize.Zero) }
        var labelSize by remember { mutableStateOf(IntSize.Zero) }
        val beside = bubbleSize.width + gap + labelSize.width <= room
        Box {
            // Only while it's out or sliding: back behind the bubble, it's gone, for a tap and TalkBack too.
            if (out || slide > 0f) {
                Box(Modifier.matchParentSize()) {
                    Label(
                        line,
                        Modifier.align(Alignment.TopStart)
                            .wrapContentSize(Alignment.TopStart, unbounded = true)
                            .widthIn(max = widest)
                            .onSizeChanged { labelSize = it }
                            .offset {
                                // From behind the bubble's end, its bottom edge level with the bubble's, to beside it;
                                // or from behind its bottom, at its start, to below it.
                                val x = maxOf(0, bubbleSize.width - labelSize.width)
                                val y = maxOf(0, bubbleSize.height - labelSize.height)
                                if (beside) IntOffset(x + ((bubbleSize.width + gap - x) * slide).roundToInt(), y) else IntOffset(0, y + ((bubbleSize.height + gap - y) * slide).roundToInt())
                            }
                            .graphicsLayer { alpha = (slide * 3f).coerceAtMost(1f) },
                    ) { screen.unfold(message.id) }
                }
            }
            Box(Modifier.onSizeChanged { bubbleSize = it }) { bubble() }
        }
    }
}

/** A message's label (Q5: A): what he did, and in red what went wrong, in a small pill. */
@Composable
private fun Label(line: ChatSummary.Line, modifier: Modifier, onOpen: () -> Unit) {
    val red = MaterialTheme.colorScheme.error
    val shape = RoundedCornerShape(10.dp)
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = shape, modifier = modifier.clip(shape).clickable(onClick = onOpen)) {
        Text(
            buildAnnotatedString {
                append(line.did)
                if (line.wrong.isNotEmpty()) {
                    if (line.did.isNotEmpty()) append(" · ")
                    withStyle(SpanStyle(color = red)) { append(line.wrong) }
                }
            },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** When a label slides out after its bubble's shown, how long it stays, and how long it takes to slide. */
private const val LABEL_IN_MS = 300L
private const val LABEL_STAYS_MS = 5_000L
private const val LABEL_SLIDE_MS = 250

/** [message]'s own button ([ChatMessage.opens]): the week's review, or a week's summary in an email to send. */
@Composable
private fun Opens(message: ChatMessage) {
    val opens = message.opens ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val week = opens.removePrefix(Conversation.OPENS_SUMMARY).takeIf { opens.startsWith(Conversation.OPENS_SUMMARY) }
    OutlinedButton(onClick = {
        context.startActivity(Intent(context, ReviewsActivity::class.java).apply { week?.let { putExtra(ReviewsActivity.EXTRA_SEND, it) } })
    }) { Text(if (week != null) "Send it" else "Open the review") }
}

/** What [message]'s searches were for (16 Oct), in small type, and the pages they found, each a link to open. */
@Composable
private fun Searched(message: ChatMessage) {
    if (message.searched.isEmpty() && message.sources.isEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (message.searched.isNotEmpty()) {
            Text("Searched the web for " + message.searched.joinToString(", ") { "“$it”" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        message.sources.forEach { source ->
            Text(
                source.title.ifBlank { android.net.Uri.parse(source.url).host ?: source.url },
                style = MaterialTheme.typography.bodySmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(source.url))) }
                },
            )
        }
    }
}

/**
 * What [message] remembered or forgot (AI-21), a line each in small type, each with its Undo while
 * it can be taken back (your call, 16 Oct): a fact gone, and the one it replaced back.
 */
@Composable
private fun Remembered(graph: AppGraph, message: ChatMessage, live: Boolean) {
    val memory by graph.memory.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var said by remember(message.id) { mutableStateOf<String?>(null) }
    val edits = message.memoryEdits
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    edits.forEachIndexed { i, edit ->
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                edit.line,
                style = MaterialTheme.typography.bodySmall,
                color = quiet,
                textDecoration = if (edit.undone) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f),
            )
            when {
                edit.undone -> Text("Undone", style = MaterialTheme.typography.labelMedium, color = quiet, modifier = Modifier.padding(horizontal = 12.dp))
                // From the archive, nothing to do: only the window's are acted on.
                live && Memories.undo(memory, edit) != null -> TextButton(onClick = { scope.launch { said = graph.conversation.undoMemory(message.id, i) } }) { Text("Undo") }
            }
        }
    }
    said?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 4.dp)) }
}

/**
 * What [message]'s actions did (16 Oct: a step ticked or to do again, a task done, open again or
 * deleted, a setting set), a line each in small type, each with its Undo while it can be taken back;
 * and the lines with nothing to undo (a change not made, where a time went, a text read in full).
 */
@Composable
private fun Acts(graph: AppGraph, message: ChatMessage, live: Boolean) {
    if (message.acts.isEmpty()) return
    // Read as they change: whether each can still be taken back.
    val tasks by graph.tasks.state.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val chat by graph.chat.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var said by remember(message.id) { mutableStateOf<String?>(null) }
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    message.acts.forEachIndexed { i, act ->
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                act.line,
                style = MaterialTheme.typography.bodySmall,
                color = quiet,
                textDecoration = if (act.undone) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f),
            )
            when {
                act.undone -> Text("Undone", style = MaterialTheme.typography.labelMedium, color = quiet, modifier = Modifier.padding(horizontal = 12.dp))
                live && com.thomaswcode.decrastination.core.ChatActs.canUndo(act, tasks.tasks, settings, chat.messages) -> TextButton(onClick = { scope.launch { said = graph.conversation.undoAct(message.id, i) } }) { Text("Undo") }
            }
        }
    }
    said?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 4.dp)) }
}

/**
 * One thing Felix did, made as it came (your call, 16 Oct), with what you can do about it: undo it,
 * change a task it added, or make it again once undone. One from before then still waits to be added
 * or applied, changed first, or set aside.
 */
@Composable
private fun ProposalCard(graph: AppGraph, messageId: String, proposal: Proposal, tasks: Map<String, TaskItem>, now: Long, zone: ZoneId, onEdit: (String, String) -> Unit, live: Boolean) {
    val scope = rememberCoroutineScope()
    val applied = proposal.state == ProposalState.Applied
    val discarded = proposal.state == ProposalState.Discarded
    Card(
        modifier = Modifier.fillMaxWidth().padding(end = 24.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (discarded) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // A task shown (AI-14): its name, and Open, to its sheet.
            proposal.open?.let { id ->
                val context = androidx.compose.ui.platform.LocalContext.current
                Text("Task", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(tasks[id]?.title ?: "A task no longer listed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (id in tasks) {
                    Button(onClick = {
                        context.startActivity(
                            android.content.Intent(context, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_TASKS)
                                .putExtra(MainActivity.EXTRA_TASK, id)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        )
                    }) { Text("Open") }
                }
                return@Column
            }
            // Added, the task as it is now (changed since with Change, or on its page); else as he gave it.
            val task = proposal.made?.takeIf { applied }?.let { tasks[it] }?.takeIf { it.source == com.thomaswcode.decrastination.core.Source.Own }?.let(com.thomaswcode.decrastination.core.OwnTasks::draftOf) ?: proposal.task
            if (task != null) {
                Text(if (task.kind == Kind.Event) "New event of yours" else "New task of yours", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(taskLine(task, now, zone), style = MaterialTheme.typography.bodyMedium)
                if (task.steps.isNotEmpty()) {
                    task.steps.forEach { s ->
                        val dates = listOfNotNull(s.from?.let { "from " + Format.at(it, now, zone) }, s.dueAt?.let { Format.due(it, hard = false, now, zone) }).joinToString(", ")
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
                    // From the archive: what became of it, with nothing to do, as only the window's are acted on.
                    !live -> Text(
                        when {
                            applied -> if (proposal.task != null) "Added" else if (proposal.instant) "Done" else "Applied"
                            discarded -> "Set aside"
                            proposal.instant -> if (proposal.note == null) "Undone" else "Not made"
                            else -> "Not added"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    applied -> {
                        Text(
                            when {
                                proposal.task != null -> "Added"
                                proposal.instant -> "Done"
                                else -> "Applied"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        if (proposal.made != null || proposal.before.isNotEmpty() || proposal.schoolBefore != null) TextButton(onClick = { scope.launch { graph.conversation.undoProposal(messageId, proposal.id) } }) { Text("Undo") }
                        // A task it added is yours to change, in the form, from here too.
                        if (proposal.task != null && proposal.made in tasks) TextButton(onClick = { onEdit(messageId, proposal.id) }) { Text("Change") }
                    }
                    discarded -> {
                        Text("Set aside", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                        TextButton(onClick = { scope.launch { graph.conversation.discardProposal(messageId, proposal.id, discarded = false) } }) { Text("Bring back") }
                    }
                    // Made as it came, and since undone, or not made (its note says why): made again here.
                    proposal.instant -> {
                        if (proposal.note == null) Text("Undone", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                        TextButton(onClick = { scope.launch { graph.conversation.applyProposal(messageId, proposal.id) } }) { Text(if (proposal.note == null) "Redo" else "Try again") }
                        if (proposal.task != null) TextButton(onClick = { onEdit(messageId, proposal.id) }) { Text("Change first") }
                    }
                    // From before everything was made as it came (16 Oct): still to add or apply.
                    else -> {
                        Button(onClick = { scope.launch { graph.conversation.applyProposal(messageId, proposal.id) } }) { Text(if (task != null) "Add" else "Apply") }
                        if (task != null) OutlinedButton(onClick = { onEdit(messageId, proposal.id) }) { Text("Change first") }
                        TextButton(onClick = { scope.launch { graph.conversation.discardProposal(messageId, proposal.id) } }) { Text("Set aside") }
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
    // Your own task's date is yours, so a real deadline.
    task.dueAt?.let { if (task.kind == Kind.Event) Format.at(it, now, zone) else Format.due(it, hard = true, now, zone) } ?: "No due date",
    task.startFrom?.let { "not before " + Format.at(it, now, zone) },
    if (task.kind == Kind.Event) null else Format.minutes(if (task.steps.isEmpty()) task.minutes else task.steps.sumOf { it.minutes }),
).joinToString(" · ")

/** The box you write in, with the message it replies to and what it's about over it, and Send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(
    name: String,
    draft: String,
    onDraft: (String) -> Unit,
    about: About,
    onForget: () -> Unit,
    replyingTo: ChatMessage?,
    onNotReplying: () -> Unit,
    now: Long,
    zone: ZoneId,
    onSend: () -> Unit,
) {
    // Your words by voice (UI-07): put in the box after what's there, not sent, so they're read first.
    val context = androidx.compose.ui.platform.LocalContext.current
    val speech = remember {
        Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
    }
    val canSpeak = remember { speech.resolveActivity(context.packageManager) != null }
    val listen = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { heard ->
            onDraft((if (draft.isBlank()) heard else "${draft.trimEnd()} $heard").take(ChatPrompts.MAX_TEXT))
        }
    }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            // The message this one replies to (5b): whose, its first line, and ✕ to reply to none.
            replyingTo?.let { r ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(if (r.from == Speaker.Felix) "Replying to $name" else "Replying to you", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(r.text.trim().lineSequence().first(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onNotReplying) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Not replying", modifier = Modifier.size(18.dp)) }
                }
            }
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
                    placeholder = { Text("Tell $name…") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
                if (canSpeak) {
                    IconButton(onClick = { listen.launch(speech) }) {
                        Icon(painterResource(R.drawable.ic_mic), contentDescription = "Say it")
                    }
                }
                IconButton(enabled = draft.isNotBlank(), onClick = onSend) {
                    Icon(painterResource(R.drawable.ic_send), contentDescription = "Send")
                }
            }
        }
    }
}

/** How long the chat's call runs before Cancel is offered (AC-08). */
private const val CANCEL_AFTER_S = 10L

