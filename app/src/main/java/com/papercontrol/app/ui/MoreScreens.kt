package com.papercontrol.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.papercontrol.app.core.Csv
import com.papercontrol.app.core.Expense
import com.papercontrol.app.core.MonthTotal
import com.papercontrol.app.core.Stats
import com.papercontrol.app.data.Document
import com.papercontrol.app.data.Filter
import com.papercontrol.app.data.Label
import com.papercontrol.app.data.Reminder
import com.papercontrol.app.data.SortBy
import com.papercontrol.app.data.Store
import com.papercontrol.app.processing.Answer
import com.papercontrol.app.processing.Assistant
import com.papercontrol.app.ui.theme.Ink
import com.papercontrol.app.ui.theme.Metrics
import com.papercontrol.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// ------------------------------------------------------------------------------------ trash

@Composable
fun TrashScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val docs = query(initial = emptyList<Document>()) { Store.documents(Filter(trash = true, sort = SortBy.ADDED), Int.MAX_VALUE) }
    val labels = query(initial = emptyMap<Long, Label>()) { Store.allLabels() }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(Ink.Black).statusBarsPadding()) {
        TopBar(left = { CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) }, right = {
            if (docs.isNotEmpty()) PillButton("Empty", { scope.launch(Dispatchers.IO) { Store.emptyTrash(all = true) } }, icon = Icons.Outlined.DeleteForever)
        })
        Text("Trash", style = Type.LargeTitle, modifier = Modifier.padding(horizontal = Metrics.Gutter))
        Text("Deleted documents stay here for ${Store.TRASH_DAYS} days, then they are removed for good.", style = Type.Caption, modifier = Modifier.padding(horizontal = Metrics.Gutter))
        LazyColumn(contentPadding = PaddingValues(Metrics.Gutter)) {
            if (docs.isEmpty()) item { Text("The trash is empty.", style = Type.BodySecondary, modifier = Modifier.padding(vertical = 30.dp)) }
            else item {
                PaperCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    docs.forEachIndexed { i, d ->
                        if (i > 0) HairLine(Modifier.padding(start = 62.dp))
                        val left = Store.TRASH_DAYS - ((System.currentTimeMillis() - (d.deletedAt ?: 0)) / 86_400_000L).toInt()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { DocRow(d.copy(amount = null), labels) { onOpen(d.id) } }
                            Column(horizontalAlignment = Alignment.End) {
                                Icon(Icons.Outlined.RestoreFromTrash, "Restore", tint = Ink.Text, modifier = Modifier.size(36.dp).clip(CircleShape).pressable({ scope.launch(Dispatchers.IO) { Store.restore(listOf(d.id)) } }).padding(7.dp))
                                Text("$left d left", style = Type.Caption)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ insights

enum class Period(val title: String) { MONTHS_12("12 months"), THIS_YEAR("This year"), LAST_YEAR("Last year"), ALL("All time") }

@Composable
fun InsightsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onFilter: (Filter) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val all = query(initial = emptyList<Pair<Document, Expense>>()) { Store.expenses() }
    val upcoming = query(initial = emptyList<Reminder>()) { Store.reminders(openOnly = true, until = LocalDate.now().plusDays(60)) }
    var period by remember { mutableStateOf(Period.MONTHS_12) }
    var month by remember { mutableStateOf<YearMonth?>(null) }
    val today = LocalDate.now()
    val range: Pair<LocalDate, LocalDate> = when (period) {
        Period.MONTHS_12 -> YearMonth.from(today).minusMonths(11).atDay(1) to today
        Period.THIS_YEAR -> today.withDayOfYear(1) to today
        Period.LAST_YEAR -> LocalDate.of(today.year - 1, 1, 1) to LocalDate.of(today.year - 1, 12, 31)
        Period.ALL -> (all.minOfOrNull { it.second.date } ?: today) to today
    }
    val inRange = all.filter { (_, e) -> !e.date.isBefore(range.first) && !e.date.isAfter(range.second) }
    val currency = Stats.mainCurrency(inRange.map { it.second })
    val main = inRange.filter { it.second.currency == currency }
    val selected = month?.let { m -> main.filter { YearMonth.from(it.second.date) == m } } ?: main
    val lastMonth = YearMonth.from(range.second)
    val months = Stats.monthly(main.map { it.second }, lastMonth, minOf(24, ChronoUnit.MONTHS.between(YearMonth.from(range.first), lastMonth).toInt() + 1).coerceAtLeast(1))

    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) {
                val labels = Store.allLabels()
                val rows = inRange.map { (d, _) ->
                    listOf(
                        d.created?.toString(), d.title, d.correspondentId?.let { labels[it]?.name }, d.docTypeId?.let { labels[it]?.name },
                        d.folderId?.let { labels[it]?.name }, d.tagIds.mapNotNull { labels[it]?.name }.joinToString("; "),
                        d.amount?.let { String.format(Locale.ROOT, "%.2f", it) }, d.currency, d.asn?.toString(), d.id.toString(),
                    )
                }
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(Csv.write(listOf("date", "title", "correspondent", "type", "folder", "tags", "amount", "currency", "asn", "id"), rows).toByteArray())
                }
            }
            Toast.makeText(context, "Exported ${inRange.size} rows", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(Modifier.fillMaxSize().background(Ink.Black).statusBarsPadding(), contentPadding = PaddingValues(start = Metrics.Gutter, end = Metrics.Gutter, bottom = 40.dp)) {
        item {
            TopBar(left = { CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) }, right = {
                PillButton("CSV", { csv.launch("PaperControl-${range.first}-${range.second}.csv") }, icon = Icons.Outlined.FileDownload)
            }, modifier = Modifier.padding(horizontal = 0.dp))
            Text("Overview", style = Type.LargeTitle)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Period.entries.forEach { p -> LabelChip(p.title, Ink.Secondary, selected = period == p) { period = p; month = null } }
            }
            Spacer(Modifier.height(16.dp))
            PaperCard {
                Text(month?.let { it.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + it.year } ?: period.title, style = Type.Caption)
                Spacer(Modifier.height(4.dp))
                Text(Fmt.money(selected.sumOf { it.second.amount }, currency).ifEmpty { "—" }, style = Type.Amount)
                Text("${selected.size} receipts and invoices" + if (inRange.size > main.size) " · other currencies not included" else "", style = Type.Caption)
                Spacer(Modifier.height(18.dp))
                MonthBars(months, month) { m -> month = if (month == m) null else m }
            }
            SectionTitle("By type")
            Breakdown(Stats.by(selected.map { it.second }, { it.category }), currency)
            SectionTitle("By sender")
            Breakdown(Stats.by(selected.map { it.second }, { it.sender }, "Unknown sender").take(8), currency)
            if (upcoming.isNotEmpty()) {
                SectionTitle("Coming up")
                PaperCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    upcoming.forEachIndexed { i, r ->
                        if (i > 0) HairLine()
                        ReminderRow(r) { onOpen(r.docId) }
                    }
                }
            }
            SectionTitle("Documents")
        }
        items(selected.sortedByDescending { it.second.date }.take(50), key = { it.first.id }) { (d, _) ->
            DocRow(d, emptyMap()) { onOpen(d.id) }
        }
        item { if (selected.size > 50) PillButton("Show all ${selected.size}", { onFilter(Filter(from = range.first, to = range.second, sort = SortBy.CREATED)) }) }
    }
}

@Composable
private fun MonthBars(months: List<MonthTotal>, selected: YearMonth?, onSelect: (YearMonth) -> Unit) {
    val max = (months.maxOfOrNull { it.total } ?: 0.0).coerceAtLeast(1.0)
    val grow by animateFloatAsState(1f, tween(600), label = "bars")
    Canvas(
        Modifier.fillMaxWidth().height(120.dp).pointerInput(months) {
            detectTapGestures { p ->
                val i = (p.x / (size.width / months.size.toFloat())).toInt().coerceIn(0, months.lastIndex)
                onSelect(months[i].month)
            }
        }
    ) {
        val slot = size.width / months.size
        val bw = (slot * 0.62f).coerceAtMost(22.dp.toPx())
        months.forEachIndexed { i, m ->
            val h = ((m.total / max).toFloat() * (size.height - 4.dp.toPx()) * grow).coerceAtLeast(if (m.total > 0) 3.dp.toPx() else 1.dp.toPx())
            val x = slot * i + (slot - bw) / 2
            val isSel = selected == m.month || (selected == null && i == months.lastIndex)
            drawRoundRect(
                if (isSel) SolidColor(Color.White) else Brush.verticalGradient(listOf(Ink.SurfaceHigh.copy(alpha = 0.9f), Ink.SurfaceHigh)),
                Offset(x, size.height - h), Size(bw, h), CornerRadius(4.dp.toPx()),
            )
        }
    }
    Row(Modifier.fillMaxWidth()) {
        months.forEach { m ->
            Text(
                m.month.month.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                style = Type.Caption.copy(color = if (selected == m.month) Ink.Text else Ink.Tertiary),
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Breakdown(rows: List<Pair<String, Double>>, currency: String?) {
    val max = rows.maxOfOrNull { it.second }?.coerceAtLeast(0.01) ?: 1.0
    PaperCard(padding = PaddingValues(16.dp)) {
        if (rows.isEmpty()) Text("Nothing in this period.", style = Type.BodySecondary)
        rows.forEachIndexed { i, (name, total) ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            KeyValue(name, Fmt.money(total, currency))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Ink.SurfaceHigh)) {
                Box(Modifier.fillMaxWidth((total / max).toFloat()).height(4.dp).clip(CircleShape).background(Ink.Text))
            }
        }
    }
}

@Composable
fun ReminderRow(r: Reminder, onClick: () -> Unit) {
    val days = ChronoUnit.DAYS.between(LocalDate.now(), r.date)
    ListRow(
        r.docTitle.ifEmpty { r.kind.label },
        subtitle = r.kind.label + " · " + Fmt.date(r.date) + if (r.note.isNotBlank()) " · ${r.note}" else "",
        trailing = {
            Text(
                when { days < 0 -> "overdue"; days == 0L -> "today"; else -> "in ${days}d" },
                style = Type.Caption.copy(color = if (days <= 3) Ink.Danger else Ink.Secondary),
            )
        },
        onClick = onClick,
    )
}

// ------------------------------------------------------------------------------------ ask

private data class Turn(val question: String, val answer: Answer?)

@Composable
fun AskScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val turns = remember { mutableStateListOf<Turn>() }
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val busy = turns.lastOrNull()?.answer == null && turns.isNotEmpty()
    LaunchedEffect(turns.size, turns.lastOrNull()?.answer) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size) }

    fun send(q: String) {
        if (q.isBlank() || busy) return
        input = ""
        turns += Turn(q, null)
        val i = turns.lastIndex
        scope.launch {
            val a = runCatching { Assistant.ask(context, q) }.getOrElse { Answer("Something went wrong: ${it.message}", emptyList(), false) }
            turns[i] = Turn(q, a)
        }
    }

    Column(Modifier.fillMaxSize().background(Ink.Black).statusBarsPadding().imePadding()) {
        TopBar(left = { CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) }, title = "Ask your documents")
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(Metrics.Gutter), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                if (turns.isEmpty()) Column {
                    Spacer(Modifier.height(30.dp))
                    IconCircle(Icons.Default.AutoAwesome, size = 56.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("Ask about your papers", style = Type.LargeTitle)
                    Text("Answers come from your archive and name the documents they use. It all runs on this phone.", style = Type.BodySecondary)
                    Spacer(Modifier.height(20.dp))
                    listOf(
                        "How much did I spend on coffee last month?",
                        "When does my phone contract end?",
                        "Which invoices are still to be paid?",
                        "What warranty receipts do I have?",
                    ).forEach { q ->
                        Text(q, style = Type.Body, modifier = Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(18.dp)).background(Ink.Surface).pressable({ send(q) }).padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
            }
            items(turns.size) { i ->
                val t = turns[i]
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        t.question, style = Type.Body.copy(color = Ink.Black),
                        modifier = Modifier.align(Alignment.End).widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp)).background(Ink.Text).padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    val a = t.answer
                    if (a == null) {
                        IrisProgress(0.35f)
                        Text("Reading your documents…", style = Type.Caption, modifier = Modifier.padding(top = 6.dp))
                    } else {
                        Text(a.text, style = Type.Body)
                        if (a.sources.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            PaperCard(padding = PaddingValues(horizontal = 14.dp, vertical = 2.dp)) {
                                a.sources.take(6).forEachIndexed { j, d ->
                                    if (j > 0) HairLine(Modifier.padding(start = 62.dp))
                                    DocRow(d, emptyMap()) { onOpen(d.id) }
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.padding(horizontal = Metrics.Gutter, vertical = 10.dp).navigationBarsPadding().fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Ink.Surface).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (input.isEmpty()) Text("Ask a question", style = Type.Body.copy(color = Ink.Tertiary))
                BasicTextField(input, { input = it }, textStyle = Type.Body, cursorBrush = SolidColor(Ink.Text), maxLines = 4, modifier = Modifier.fillMaxWidth())
            }
            CircleButton(Icons.AutoMirrored.Filled.Send, "Ask", { send(input) }, background = if (input.isBlank() || busy) Ink.SurfaceHigh else Ink.Text, tint = if (input.isBlank() || busy) Ink.Tertiary else Ink.Black)
        }
    }
}

// ------------------------------------------------------------------------------------ lock

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink.Black).statusBarsPadding().navigationBarsPadding().padding(Metrics.Gutter)
            .pointerInput(Unit) { detectTapGestures { } },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        IconCircle(Icons.Outlined.Lock, size = 72.dp)
        Spacer(Modifier.height(18.dp))
        Text("PaperControl is locked", style = Type.Title)
        Text("Unlock with your fingerprint, face or screen lock.", style = Type.BodySecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        PrimaryButton("Unlock", onUnlock)
    }
}
