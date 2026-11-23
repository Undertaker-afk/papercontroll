package com.papercontrol.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papercontrol.app.data.Document
import com.papercontrol.app.data.Filter
import com.papercontrol.app.data.Label
import com.papercontrol.app.data.LabelKind
import com.papercontrol.app.data.SavedView
import com.papercontrol.app.data.SortBy
import com.papercontrol.app.data.Store
import com.papercontrol.app.processing.Pdf
import com.papercontrol.app.ui.theme.Ink
import com.papercontrol.app.ui.theme.Metrics
import com.papercontrol.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    route: Route.Home,
    onRoute: (Route.Home) -> Unit,
    onOpen: (Long) -> Unit,
    onScan: () -> Unit,
    onImport: (List<Uri>) -> Unit,
    onLabels: (LabelKind) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) onImport(uris) }
    val importAction = { picker.launch(arrayOf("image/*", "application/pdf")) }
    Box(Modifier.fillMaxSize().background(Ink.Black)) {
        AnimatedContent(route.tab, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "tab") { tab ->
            when (tab) {
                Tab.DOCUMENTS -> DocumentsTab(route.filter, { onRoute(route.copy(filter = it)) }, onOpen, importAction)
                Tab.FOLDERS -> FoldersTab { onRoute(Route.Home(Filter(folderId = it), Tab.DOCUMENTS)) }
                Tab.LABELS -> LabelsHub(onLabels) { onRoute(Route.Home(it, Tab.DOCUMENTS)) }
                Tab.SETTINGS -> SettingsContent(Modifier.statusBarsPadding())
            }
        }
        BottomBar(route.tab, { onRoute(route.copy(tab = it)) }, onScan, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun BottomBar(tab: Tab, onTab: (Tab) -> Unit, onScan: () -> Unit, modifier: Modifier) {
    Box(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.Black.copy(alpha = 0.92f), Ink.Black)))
            .navigationBarsPadding().padding(top = 18.dp, bottom = 10.dp, start = 20.dp, end = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).clip(CircleShape).background(Ink.Surface).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(Icons.Outlined.Description, "Documents", tab == Tab.DOCUMENTS, Modifier.weight(1f)) { onTab(Tab.DOCUMENTS) }
            NavItem(Icons.Outlined.Folder, "Folders", tab == Tab.FOLDERS, Modifier.weight(1f)) { onTab(Tab.FOLDERS) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                // The shutter from the scan screen, as the way in.
                Box(Modifier.size(50.dp).border(3.dp, Color.White, CircleShape).padding(5.dp).clip(CircleShape).background(Color.White).pressable(onScan), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.DocumentScanner, "Scan", tint = Ink.Black, modifier = Modifier.size(20.dp))
                }
            }
            NavItem(Icons.Outlined.Label, "Labels", tab == Tab.LABELS, Modifier.weight(1f)) { onTab(Tab.LABELS) }
            NavItem(Icons.Outlined.Settings, "Settings", tab == Tab.SETTINGS, Modifier.weight(1f)) { onTab(Tab.SETTINGS) }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.pressable(onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = if (selected) Ink.Text else Ink.Tertiary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, style = Type.Caption.copy(color = if (selected) Ink.Text else Ink.Tertiary, fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)))
    }
}

@Composable
private fun DocumentsTab(filter: Filter, onFilter: (Filter) -> Unit, onOpen: (Long) -> Unit, onImport: () -> Unit) {
    val docs = query(filter, initial = emptyList<Document>()) { Store.documents(filter) }
    val labels = query(initial = emptyMap<Long, Label>()) { Store.allLabels() }
    val inbox = query(initial = 0) { Store.count(inboxOnly = true) }
    val views = query(initial = emptyList<SavedView>()) { Store.savedViews() }
    var sortMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val month = remember(docs) {
        val now = LocalDate.now()
        docs.filter { it.amount != null && it.created?.let { d -> d.year == now.year && d.month == now.month } == true }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(horizontal = Metrics.Gutter)) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(titleFor(filter, labels), style = Type.LargeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${docs.size} document${if (docs.size == 1) "" else "s"}", style = Type.Caption)
                    }
                    CircleButton(Icons.Outlined.UploadFile, "Import", onImport)
                    Spacer(Modifier.width(8.dp))
                    Box {
                        CircleButton(Icons.Outlined.SwapVert, "Sort", { sortMenu = true })
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            SortBy.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.title + if (filter.sort == s) (if (filter.descending) "  ↓" else "  ↑") else "") },
                                    onClick = { sortMenu = false; onFilter(filter.copy(sort = s, descending = if (filter.sort == s) !filter.descending else true)) },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                SearchField(filter.query, { onFilter(filter.copy(query = it)) }, "Search, or tag:… from:… type:…")
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelChip("Inbox · $inbox", Ink.Iris[2], selected = filter.inboxOnly) { onFilter(filter.copy(inboxOnly = !filter.inboxOnly)) }
                    filter.folderId?.let { id -> ActiveChip("Folder · " + (labels[id]?.name ?: "?")) { onFilter(filter.copy(folderId = null)) } }
                    filter.correspondentId?.let { id -> ActiveChip(labels[id]?.name ?: "?") { onFilter(filter.copy(correspondentId = null)) } }
                    filter.docTypeId?.let { id -> ActiveChip(labels[id]?.name ?: "?") { onFilter(filter.copy(docTypeId = null)) } }
                    filter.tagIds.forEach { id -> ActiveChip("#" + (labels[id]?.name ?: "?")) { onFilter(filter.copy(tagIds = filter.tagIds - id)) } }
                    views.forEach { v -> LabelChip(v.name, Ink.Secondary, selected = v.filter == filter) { onFilter(v.filter) } }
                    if (!filter.isEmpty && views.none { it.filter == filter }) {
                        PillButton("Save view", {
                            scope.launch(Dispatchers.IO) { Store.saveView(SavedView(name = titleFor(filter, labels), filter = filter)) }
                        }, icon = Icons.Outlined.BookmarkAdd)
                    }
                }
                if (month.isNotEmpty() && filter.isEmpty) {
                    Spacer(Modifier.height(16.dp))
                    SpendCard(month)
                }
            }
        }
        if (docs.isEmpty()) {
            item { EmptyState(filter.isEmpty) }
        }
        val groups = docs.groupBy { d ->
            val date = if (filter.sort == SortBy.ADDED) java.time.Instant.ofEpochMilli(d.addedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate() else d.created
            date?.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())) ?: "Undated"
        }
        groups.forEach { (month, list) ->
            item(key = "h$month") { SectionTitle(month, Modifier.padding(horizontal = Metrics.Gutter)) }
            item(key = "c$month") {
                PaperCard(Modifier.padding(horizontal = Metrics.Gutter), padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    list.forEachIndexed { i, d ->
                        if (i > 0) HairLine(Modifier.padding(start = 62.dp))
                        DocRow(d, labels) { onOpen(d.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveChip(text: String, onRemove: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(Ink.Text).pressable(onRemove).padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.Caption.copy(color = Ink.Black), maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Default.Close, "Remove", tint = Ink.Black, modifier = Modifier.size(14.dp))
    }
}

private fun titleFor(f: Filter, labels: Map<Long, Label>): String = when {
    f.folderId != null -> labels[f.folderId]?.name ?: "Folder"
    f.correspondentId != null -> labels[f.correspondentId]?.name ?: "Documents"
    f.docTypeId != null -> labels[f.docTypeId]?.name ?: "Documents"
    f.tagIds.size == 1 -> labels[f.tagIds.first()]?.name ?: "Documents"
    f.inboxOnly -> "Inbox"
    f.query.isNotBlank() -> f.query
    else -> "Documents"
}

@Composable
fun DocRow(d: Document, labels: Map<Long, Label>, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumb(d.thumbPath, Modifier.size(width = 46.dp, height = 58.dp), radius = 8.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.title, style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(d.correspondentId?.let { labels[it]?.name }, d.created?.let { Fmt.date(it) }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val tags = d.tagIds.mapNotNull { labels[it] }
            if (tags.isNotEmpty() || d.pageCount > 1) {
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    tags.take(3).forEach { t ->
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Color(t.color)))
                        Text(t.name, style = Type.Caption.copy(fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)), maxLines = 1)
                    }
                    if (d.pageCount > 1) Text("${d.pageCount} pages", style = Type.Caption.copy(color = Ink.Tertiary, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)))
                }
            }
        }
        if (d.amount != null) {
            Spacer(Modifier.width(8.dp))
            Text(Fmt.money(d.amount, d.currency), style = Type.Body)
        }
    }
}

@Composable
private fun SpendCard(docs: List<Document>) {
    val byCurrency = docs.groupBy { it.currency ?: "" }.mapValues { (_, l) -> l.sumOf { it.amount ?: 0.0 } }
    val main = byCurrency.maxByOrNull { it.value }
    PaperCard {
        Text("This month", style = Type.Caption)
        Spacer(Modifier.height(4.dp))
        Text(Fmt.money(main?.value, main?.key?.ifEmpty { null }), style = Type.Amount)
        Text("across ${docs.size} receipt${if (docs.size == 1) "" else "s"} and invoices", style = Type.Caption)
    }
}

@Composable
private fun EmptyState(fresh: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconCircle(Icons.Outlined.Inbox, size = 64.dp)
        Spacer(Modifier.height(18.dp))
        Text(if (fresh) "Nothing filed yet" else "No matches", style = Type.Title)
        Spacer(Modifier.height(6.dp))
        Text(
            if (fresh) "Scan a receipt or letter with the button below, or import photos and PDFs: pages of the same document are found and put together for you."
            else "Try other words or remove a filter.",
            style = Type.BodySecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun FoldersTab(onFolder: (Long) -> Unit) {
    val folders = query(initial = emptyList<Label>()) { Store.labels(LabelKind.FOLDER) }
    val previews = query(folders, initial = emptyMap<Long, List<Document>>()) {
        folders.associate { f -> f.id to Store.documents(Filter(folderId = f.id), 3) }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LazyVerticalGrid(
        GridCells.Fixed(2),
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Metrics.Gutter, end = Metrics.Gutter, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column(Modifier.statusBarsPadding().padding(top = 12.dp)) {
                Text("Folders", style = Type.LargeTitle)
                Text("Documents are filed here automatically. Pages of one document stay together.", style = Type.Caption)
            }
        }
        items(folders, key = { it.id }) { f ->
            Column(Modifier.clip(RoundedCornerShape(Metrics.CardRadius)).background(Ink.Surface).pressable({ onFolder(f.id) }).padding(14.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1.25f)) {
                    val docs = previews[f.id].orEmpty()
                    if (docs.isEmpty()) {
                        Icon(Icons.Outlined.Folder, null, tint = Color(f.color), modifier = Modifier.size(34.dp).align(Alignment.Center))
                    }
                    docs.reversed().forEachIndexed { i, d ->
                        val n = docs.size - 1 - i
                        Thumb(
                            d.thumbPath,
                            Modifier.align(Alignment.Center).offset(x = ((n - 1) * 16).dp, y = (n * -4).dp).size(width = 58.dp, height = 74.dp)
                                .border(1.dp, Ink.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                            radius = 8.dp,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(f.color)))
                    Spacer(Modifier.width(8.dp))
                    Text(f.name, style = Type.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${f.documentCount} document${if (f.documentCount == 1) "" else "s"}", style = Type.Caption)
            }
        }
        item(span = { GridItemSpan(2) }) {
            SecondaryButton("Save as PDFs to Downloads", {
                scope.launch {
                    val n = withContext(Dispatchers.IO) { Pdf.exportAll(context) }
                    Toast.makeText(context, if (n > 0) "Saved $n PDFs" else "Needs Android 10 or newer", Toast.LENGTH_SHORT).show()
                }
            }, icon = Icons.Outlined.Bookmark)
        }
    }
}

@Composable
private fun LabelsHub(onKind: (LabelKind) -> Unit, onFilter: (Filter) -> Unit) {
    val counts = query(initial = emptyMap<LabelKind, Int>()) { LabelKind.entries.associateWith { Store.labels(it).size } }
    val views = query(initial = emptyList<SavedView>()) { Store.savedViews() }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Metrics.Gutter, end = Metrics.Gutter, bottom = 120.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 12.dp)) {
                Text("Labels", style = Type.LargeTitle)
                Text("How documents are sorted. Each one can match by words, a pattern, or learn from your documents (Auto).", style = Type.Caption)
            }
            SectionTitle("Sorting")
            PaperCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                LabelKind.entries.forEachIndexed { i, k ->
                    if (i > 0) HairLine()
                    ListRow(k.title, subtitle = "${counts[k] ?: 0}", onClick = { onKind(k) })
                }
            }
            SectionTitle("Custom fields")
            CustomFieldsCard()
            SectionTitle("Saved views")
        }
        if (views.isEmpty()) item { Text("Filter the document list and tap “Save view” to keep it here.", style = Type.Caption, modifier = Modifier.padding(start = 4.dp)) }
        else item {
            PaperCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                views.forEachIndexed { i, v ->
                    if (i > 0) HairLine()
                    ListRow(v.name, onClick = { onFilter(v.filter) }, trailing = {
                        Icon(Icons.Default.Close, "Delete", tint = Ink.Tertiary, modifier = Modifier.size(20.dp).pressable({ scope.launch(Dispatchers.IO) { Store.deleteView(v.id) } }))
                    })
                }
            }
        }
    }
}
