package com.papercontrol.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.papercontrol.app.core.DeadlineKind
import com.papercontrol.app.core.Payment
import com.papercontrol.app.data.FieldType
import com.papercontrol.app.data.Reminder
import com.papercontrol.app.processing.Consumer
import com.papercontrol.app.processing.Search
import java.time.temporal.ChronoUnit
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.papercontrol.app.data.CustomField
import com.papercontrol.app.data.Document
import com.papercontrol.app.data.ExtractionJson
import com.papercontrol.app.data.FieldValue
import com.papercontrol.app.data.Filter
import com.papercontrol.app.data.Label
import com.papercontrol.app.data.LabelKind
import com.papercontrol.app.data.Note
import com.papercontrol.app.data.Page
import com.papercontrol.app.data.Palette
import com.papercontrol.app.data.Store
import com.papercontrol.app.processing.Images
import com.papercontrol.app.processing.Pdf
import com.papercontrol.app.processing.Sorter
import com.papercontrol.app.sync.Paperless
import com.papercontrol.app.ui.theme.Ink
import com.papercontrol.app.ui.theme.Metrics
import com.papercontrol.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

@Composable
fun DocumentScreen(id: Long, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc = query(id, initial = null as Document?) { Store.document(id) }
    val pages = query(id, initial = emptyList<Page>()) { Store.pages(id) }
    val labels = query(initial = emptyMap<Long, Label>()) { Store.allLabels() }
    val fields = query(id, initial = emptyList<FieldValue>()) { Store.fieldValues(id) }
    val allFields = query(initial = emptyList<CustomField>()) { Store.customFields() }
    val notes = query(id, initial = emptyList<Note>()) { Store.notes(id) }
    val reminders = query(id, initial = emptyList<Reminder>()) { Store.reminders(docId = id) }
    val similar = query(id, initial = emptyList<Document>()) { Store.document(id)?.let { Search.similar(context, it) }.orEmpty() }
    val copyOf = query(id, initial = null as Document?) { Store.document(id)?.duplicateOf?.let { Store.document(it) } }
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<LabelKind?>(null) }
    var editTitle by remember { mutableStateOf(false) }
    var editMeta by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var showContent by remember { mutableStateOf(false) }
    var showPhoto by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var addReminder by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }

    val d = doc ?: run {
        Box(Modifier.fillMaxSize().background(Ink.Black))
        return
    }
    fun update(f: (Document) -> Document) { scope.launch(Dispatchers.IO) { Store.updateDocument(f(d)) } }
    val ex = remember(d.fields) { ExtractionJson.read(d.fields) }
    val inboxIds = labels.values.filter { it.isInbox }.map { it.id }.toSet()

    Column(Modifier.fillMaxSize().background(Ink.Black).statusBarsPadding()) {
        TopBar(
            left = { CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
            right = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircleButton(Icons.Outlined.IosShare, "Share", {
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { Pdf.toCache(context, d) }
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), d.title))
                        }
                    })
                    Box {
                        CircleButton(Icons.Default.MoreHoriz, "More", { menu = true })
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Sort again (AI)") }, onClick = {
                                menu = false
                                busy = "Sorting…"
                                scope.launch {
                                    withContext(Dispatchers.Default) {
                                        val image = pages.firstOrNull()?.let { Images.decode(File(it.path), 1024) }?.let { Images.jpegBytes(it) }
                                        val s = Sorter.sort(context, d.content, ex, image)
                                        Store.updateDocument(
                                            d.copy(
                                                title = s.title,
                                                correspondentId = Sorter.resolve(LabelKind.CORRESPONDENT, s.correspondent) ?: d.correspondentId,
                                                docTypeId = Sorter.resolve(LabelKind.DOCTYPE, s.docType) ?: d.docTypeId,
                                                folderId = Sorter.resolve(LabelKind.FOLDER, s.folder) ?: d.folderId,
                                                tagIds = (d.tagIds + s.tags.mapNotNull { Sorter.resolve(LabelKind.TAG, it) }.filter { it !in inboxIds }).distinct(),
                                                amount = d.amount ?: s.amount,
                                                currency = d.currency ?: s.currency,
                                            )
                                        )
                                    }
                                    busy = null
                                }
                            })
                            DropdownMenuItem(text = { Text("Merge with another document") }, onClick = { menu = false; merging = true })
                            if (Paperless.configured()) DropdownMenuItem(text = { Text("Upload to Paperless-ngx") }, onClick = {
                                menu = false
                                busy = "Uploading…"
                                scope.launch {
                                    val r = runCatching { Paperless.upload(context, d.id) }
                                    busy = null
                                    Toast.makeText(context, r.fold({ "Uploaded" }, { it.message ?: "Upload failed" }), Toast.LENGTH_LONG).show()
                                }
                            })
                            DropdownMenuItem(text = { Text("Edit date, ASN, amount") }, onClick = { menu = false; editMeta = true })
                            DropdownMenuItem(text = { Text("Delete", color = Ink.Danger) }, onClick = {
                                menu = false
                                scope.launch { withContext(Dispatchers.IO) { Store.trash(listOf(d.id)) }; onBack() }
                            })
                        }
                    }
                }
            },
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Metrics.Gutter, end = Metrics.Gutter, bottom = 40.dp)) {
            item {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.pressable({ editTitle = true }).padding(top = 4.dp)) {
                    Text(d.title, style = Type.LargeTitle, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.Edit, "Rename", tint = Ink.Tertiary, modifier = Modifier.padding(top = 10.dp).size(18.dp))
                }
                val sub = listOfNotNull(d.created?.let { Fmt.date(it) }, d.asn?.let { "ASN $it" }, "${d.pageCount} page${if (d.pageCount == 1) "" else "s"}").joinToString(" · ")
                Text(sub, style = Type.Caption)
                busy?.let { Text(it, style = Type.Caption.copy(color = Ink.Iris[3])) }
                if (d.tagIds.any { it in inboxIds }) {
                    Spacer(Modifier.height(12.dp))
                    PillButton("Done · remove from inbox", { update { it.copy(tagIds = it.tagIds.filter { t -> t !in inboxIds }) } }, icon = Icons.Default.Check, background = Ink.Text, contentColor = Ink.Black)
                }
                if (d.deletedAt != null) {
                    Spacer(Modifier.height(12.dp))
                    PaperCard(padding = PaddingValues(14.dp)) {
                        val left = Store.TRASH_DAYS - ((System.currentTimeMillis() - d.deletedAt) / 86_400_000L).toInt()
                        Text("In the trash · $left days left", style = Type.Body)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("Restore", { scope.launch(Dispatchers.IO) { Store.restore(listOf(d.id)) } }, icon = Icons.Outlined.RestoreFromTrash, background = Ink.Text, contentColor = Ink.Black)
                            PillButton("Delete forever", { scope.launch { withContext(Dispatchers.IO) { Store.purgeDocument(d.id) }; onBack() } }, icon = Icons.Outlined.DeleteOutline, background = Ink.SurfaceHigh)
                        }
                    }
                }
                copyOf?.let { other ->
                    Spacer(Modifier.height(12.dp))
                    PaperCard(padding = PaddingValues(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.ContentCopy, null, tint = Ink.Danger, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("May be a copy", style = Type.Body)
                                Text("Looks like “${other.title}”", style = Type.Caption, maxLines = 2)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("Open", { onOpen(other.id) }, background = Ink.SurfaceHigh)
                            PillButton("Not a copy", { scope.launch(Dispatchers.IO) { Store.setPageHash(d.id, d.pageHash, null) } }, background = Ink.SurfaceHigh)
                            PillButton("Delete this", { scope.launch { withContext(Dispatchers.IO) { Store.trash(listOf(d.id)) }; onBack() } }, background = Ink.SurfaceHigh)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            item { PagesView(d, pages, showPhoto, { showPhoto = !showPhoto }, onSplit = { p -> scope.launch { val n = withContext(Dispatchers.IO) { Store.splitPage(p) }; if (n != null) onOpen(n) } }) }
            item {
                Spacer(Modifier.height(16.dp))
                ReceiptCard(ex, d.title, currency = d.currency ?: ex.currency, fallbackText = d.content)
                ex.payment?.let { pay ->
                    Spacer(Modifier.height(12.dp))
                    PaymentCard(pay, d.currency ?: ex.currency)
                }
                SectionTitle("Deadlines")
                PaperCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    if (reminders.isEmpty()) Text("None found. Add one to be reminded.", style = Type.BodySecondary, modifier = Modifier.padding(vertical = 12.dp))
                    reminders.forEachIndexed { i, r ->
                        if (i > 0) HairLine()
                        val days = ChronoUnit.DAYS.between(java.time.LocalDate.now(), r.date)
                        ListRow(
                            r.kind.label,
                            subtitle = Fmt.date(r.date) + if (r.note.isNotBlank()) " · ${r.note}" else "",
                            leading = {
                                Box(
                                    Modifier.size(26.dp).clip(CircleShape).background(if (r.done) Ink.Text else Ink.SurfaceHigh)
                                        .pressable({ scope.launch(Dispatchers.IO) { Store.saveReminder(r.copy(done = !r.done)) } }),
                                    contentAlignment = Alignment.Center,
                                ) { if (r.done) Icon(Icons.Default.Check, "Done", tint = Ink.Black, modifier = Modifier.size(16.dp)) }
                            },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!r.done) Text(when { days < 0 -> "overdue"; days == 0L -> "today"; else -> "in ${days}d" }, style = Type.Caption.copy(color = if (days <= 3) Ink.Danger else Ink.Secondary))
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Default.Close, "Remove", tint = Ink.Tertiary, modifier = Modifier.size(20.dp).pressable({ scope.launch(Dispatchers.IO) { Store.deleteReminder(r.id) } }))
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                PillButton("Add deadline", { addReminder = true }, icon = Icons.Outlined.NotificationsActive)
            }
            item {
                SectionTitle("Filed as")
                PaperCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    MetaRow("Folder", d.folderId?.let { labels[it] }) { picking = LabelKind.FOLDER }
                    HairLine()
                    MetaRow("Correspondent", d.correspondentId?.let { labels[it] }) { picking = LabelKind.CORRESPONDENT }
                    HairLine()
                    MetaRow("Document type", d.docTypeId?.let { labels[it] }) { picking = LabelKind.DOCTYPE }
                    HairLine()
                    ListRow("Amount", trailing = { Text(Fmt.money(d.amount, d.currency).ifEmpty { "—" }, style = Type.BodySecondary) }, onClick = { editMeta = true })
                }
                SectionTitle("Tags")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.tagIds.mapNotNull { labels[it] }.forEach { t ->
                        LabelChip(t.name, Color(t.color)) { update { it.copy(tagIds = it.tagIds - t.id) } }
                    }
                    PillButton("Tag", { picking = LabelKind.TAG }, icon = Icons.Default.Add)
                }
                if (allFields.isNotEmpty()) {
                    SectionTitle("Custom fields")
                    PaperCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        allFields.forEachIndexed { i, f ->
                            if (i > 0) HairLine()
                            val v = fields.firstOrNull { it.field.id == f.id }?.value.orEmpty()
                            FieldEditor(f, v) { nv -> scope.launch(Dispatchers.IO) { Store.setFieldValue(d.id, f.id, nv?.ifBlank { null }) } }
                        }
                    }
                }
                SectionTitle("Notes")
                PaperCard(padding = PaddingValues(16.dp)) {
                    notes.forEach { n ->
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(n.text, style = Type.Body)
                                Text(Fmt.date(java.time.Instant.ofEpochMilli(n.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()), style = Type.Caption)
                            }
                            Icon(Icons.Default.Close, "Delete note", tint = Ink.Tertiary, modifier = Modifier.size(18.dp).pressable({ scope.launch(Dispatchers.IO) { Store.deleteNote(n.id) } }))
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Field("Add a note", note, { note = it }, singleLine = false)
                    if (note.isNotBlank()) PillButton("Add note", { val t = note; note = ""; scope.launch(Dispatchers.IO) { Store.addNote(d.id, t) } }, background = Ink.SurfaceHigh)
                }
                if (similar.isNotEmpty()) {
                    SectionTitle("Similar documents")
                    PaperCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                        similar.forEachIndexed { i, o ->
                            if (i > 0) HairLine(Modifier.padding(start = 62.dp))
                            DocRow(o, labels) { onOpen(o.id) }
                        }
                    }
                }
                SectionTitle("Text")
                PaperCard(padding = PaddingValues(16.dp), modifier = Modifier.pressable({ showContent = !showContent })) {
                    Text(d.content.ifBlank { "No text recognized." }, style = Type.BodySecondary, maxLines = if (showContent) Int.MAX_VALUE else 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    picking?.let { kind -> LabelPicker(kind, d, labels.values.filter { it.kind == kind }, onDismiss = { picking = null }) { updated -> update { updated } } }
    if (editTitle) TextSheet("Title", d.title, onDismiss = { editTitle = false }) { t -> update { it.copy(title = t) } }
    if (editMeta) MetaSheet(d, onDismiss = { editMeta = false }) { updated -> update { updated } }
    if (addReminder) ReminderSheet(d.id, onDismiss = { addReminder = false })
    if (merging) MergeSheet(d, onDismiss = { merging = false }) { other ->
        scope.launch(Dispatchers.IO) { Store.mergeDocuments(d.id, listOf(other)) }
        merging = false
    }
}

@Composable
private fun MetaRow(title: String, value: Label?, onClick: () -> Unit) {
    ListRow(title, onClick = onClick, trailing = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value != null) { Box(Modifier.size(8.dp).clip(CircleShape).background(Color(value.color))); Spacer(Modifier.width(8.dp)) }
            Text(value?.name ?: "—", style = Type.BodySecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    })
}

@Composable
private fun PagesView(d: Document, pages: List<Page>, showPhoto: Boolean, onTogglePhoto: () -> Unit, onSplit: (Page) -> Unit) {
    if (pages.isEmpty()) return
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Column {
        HorizontalPager(pager, Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(Metrics.CardRadius)).background(Ink.Surface), pageSpacing = 12.dp) { i ->
            val p = pages[i]
            AsyncImage(
                model = File(if (showPhoto && p.originalPath != null) p.originalPath else p.path),
                contentDescription = "Page ${i + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${pager.currentPage + 1} / ${pages.size}", style = Type.Caption, modifier = Modifier.weight(1f))
            PillButton(if (showPhoto) "Scan" else "Photo", onTogglePhoto, icon = Icons.Outlined.PhotoCamera)
            val here = pages.getOrNull(pager.currentPage)
            if (here != null) {
                CircleButton(Icons.Outlined.RotateLeft, "Rotate left", { scope.launch { Consumer.rotatePage(here, -90) } })
                CircleButton(Icons.Outlined.RotateRight, "Rotate right", { scope.launch { Consumer.rotatePage(here, 90) } })
            }
            if (pages.size > 1) {
                val cur = pager.currentPage
                CircleButton(Icons.Outlined.DeleteOutline, "Delete this page", { scope.launch(Dispatchers.IO) { Store.deletePage(pages[cur]) } })
                CircleButton(Icons.Outlined.ChevronLeft, "Move page left", {
                    if (cur > 0) scope.launch(Dispatchers.IO) {
                        val ids = pages.map { it.id }.toMutableList(); ids.add(cur - 1, ids.removeAt(cur)); Store.reorderPages(d.id, ids)
                    }
                })
                CircleButton(Icons.Outlined.ChevronRight, "Move page right", {
                    if (cur < pages.lastIndex) scope.launch(Dispatchers.IO) {
                        val ids = pages.map { it.id }.toMutableList(); ids.add(cur + 1, ids.removeAt(cur)); Store.reorderPages(d.id, ids)
                    }
                })
                CircleButton(Icons.Outlined.CallSplit, "Split page into its own document", { onSplit(pages[cur]) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabelPicker(kind: LabelKind, d: Document, options: List<Label>, onDismiss: () -> Unit, onPick: (Document) -> Unit) {
    var newName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun apply(id: Long?) {
        onPick(
            when (kind) {
                LabelKind.TAG -> if (id == null) d else d.copy(tagIds = if (id in d.tagIds) d.tagIds - id else d.tagIds + id)
                LabelKind.CORRESPONDENT -> d.copy(correspondentId = id)
                LabelKind.DOCTYPE -> d.copy(docTypeId = id)
                LabelKind.FOLDER -> d.copy(folderId = id)
            }
        )
        onDismiss()
    }
    val current: Set<Long> = when (kind) {
        LabelKind.TAG -> d.tagIds.toSet()
        LabelKind.CORRESPONDENT -> setOfNotNull(d.correspondentId)
        LabelKind.DOCTYPE -> setOfNotNull(d.docTypeId)
        LabelKind.FOLDER -> setOfNotNull(d.folderId)
    }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ink.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Metrics.Gutter).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(kind.singular, style = Type.Title)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Field("New ${kind.singular.lowercase()}", newName, { newName = it }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                CircleButton(Icons.Default.Add, "Create", {
                    val n = newName.trim()
                    if (n.isNotEmpty()) scope.launch { val id = withContext(Dispatchers.IO) { Store.labelNamed(kind, n, Palette.forName(n)) }; apply(id) }
                }, background = Ink.SurfaceHigh, modifier = Modifier.padding(bottom = 8.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                if (kind != LabelKind.TAG) item { ListRow("None", onClick = { apply(null) }, trailing = if (current.isEmpty()) ({ Icon(Icons.Default.Check, null, tint = Ink.Text) }) else null) }
                items(options, key = { it.id }) { l ->
                    ListRow(
                        l.name,
                        leading = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(l.color))) },
                        trailing = if (l.id in current) ({ Icon(Icons.Default.Check, null, tint = Ink.Text) }) else null,
                        onClick = { apply(l.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSheet(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismiss, containerColor = Ink.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Metrics.Gutter).navigationBarsPadding().padding(bottom = 16.dp)) {
            Field(title, v, { v = it })
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Save", { if (v.isNotBlank()) onSave(v.trim()); onDismiss() }, enabled = v.isNotBlank())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetaSheet(d: Document, onDismiss: () -> Unit, onSave: (Document) -> Unit) {
    var created by remember { mutableStateOf(d.created?.toString().orEmpty()) }
    var asn by remember { mutableStateOf(d.asn?.toString().orEmpty()) }
    var amount by remember { mutableStateOf(d.amount?.toString().orEmpty()) }
    var currency by remember { mutableStateOf(d.currency.orEmpty()) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismiss, containerColor = Ink.Surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Metrics.Gutter).navigationBarsPadding().padding(bottom = 16.dp)) {
            Field("Date (YYYY-MM-DD)", created, { created = it })
            Row(verticalAlignment = Alignment.Bottom) {
                Field("Archive serial number", asn, { asn = it }, number = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                PillButton("Next", { scope.launch { asn = withContext(Dispatchers.IO) { Store.nextAsn() }.toString() } }, background = Ink.SurfaceHigh, modifier = Modifier.padding(bottom = 10.dp))
            }
            Row {
                Field("Amount", amount, { amount = it }, number = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Field("Currency", currency, { currency = it.uppercase().take(3) }, modifier = Modifier.width(100.dp))
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Save", {
                onSave(
                    d.copy(
                        created = runCatching { LocalDate.parse(created.trim()) }.getOrNull() ?: d.created,
                        asn = asn.trim().toIntOrNull(),
                        amount = amount.replace(',', '.').trim().toDoubleOrNull(),
                        currency = currency.ifBlank { null },
                    )
                )
                onDismiss()
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MergeSheet(d: Document, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var q by remember { mutableStateOf("") }
    val docs = query(q, initial = emptyList<Document>()) { Store.documents(Filter(query = q), 60).filter { it.id != d.id } }
    val labels = query(initial = emptyMap<Long, Label>()) { Store.allLabels() }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ink.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Metrics.Gutter).navigationBarsPadding()) {
            Text("Merge into “${d.title}”", style = Type.Title)
            Text("The other document's pages are added at the end.", style = Type.Caption)
            Spacer(Modifier.height(12.dp))
            SearchField(q, { q = it }, "Find a document")
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                items(docs, key = { it.id }) { o -> DocRow(o, labels) { onPick(o.id) } }
            }
        }
    }
}


@Composable
private fun PaymentCard(p: Payment, currency: String?) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    fun copy(label: String, text: String) { clipboard.setText(AnnotatedString(text)); Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show() }
    PaperCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconCircle(Icons.Outlined.Payments, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Pay with ${p.standard} code", style = Type.Headline)
                Text("Read from the QR code on the paper", style = Type.Caption)
            }
        }
        Spacer(Modifier.height(14.dp))
        HairLine()
        Spacer(Modifier.height(12.dp))
        p.name?.let { KeyValue("To", it, style = Type.BodySecondary, rightStyle = Type.Body); Spacer(Modifier.height(8.dp)) }
        KeyValue("IBAN", p.ibanPretty, style = Type.BodySecondary, rightStyle = Type.Body)
        p.bic?.let { Spacer(Modifier.height(8.dp)); KeyValue("BIC", it, style = Type.BodySecondary, rightStyle = Type.Body) }
        p.amount?.let { Spacer(Modifier.height(8.dp)); KeyValue("Amount", Fmt.money(it, p.currency ?: currency), style = Type.BodySecondary, rightStyle = Type.Headline) }
        (p.reference ?: p.message)?.let { Spacer(Modifier.height(8.dp)); KeyValue(if (p.reference != null) "Reference" else "Message", it, style = Type.BodySecondary, rightStyle = Type.Body) }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            PillButton("IBAN", { copy("IBAN", p.iban) }, icon = Icons.Outlined.ContentCopy, background = Ink.SurfaceHigh)
            p.amount?.let { PillButton("Amount", { copy("Amount", String.format(java.util.Locale.ROOT, "%.2f", it)) }, icon = Icons.Outlined.ContentCopy, background = Ink.SurfaceHigh) }
            (p.reference ?: p.message)?.let { r -> PillButton("Reference", { copy("Reference", r) }, icon = Icons.Outlined.ContentCopy, background = Ink.SurfaceHigh) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderSheet(docId: Long, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf(java.time.LocalDate.now().plusDays(7).toString()) }
    var kind by remember { mutableStateOf(DeadlineKind.PAYMENT) }
    var note by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val parsed = runCatching { java.time.LocalDate.parse(date.trim()) }.getOrNull()
    ModalBottomSheet(onDismiss, containerColor = Ink.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Metrics.Gutter).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Add deadline", style = Type.Title)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DeadlineKind.entries.forEach { k -> LabelChip(k.label, Ink.Iris[2], selected = kind == k) { kind = k } }
            }
            Field("Date (YYYY-MM-DD)", date, { date = it })
            Field("Note", note, { note = it })
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Add", {
                parsed?.let { d -> scope.launch(Dispatchers.IO) { Store.saveReminder(Reminder(docId = docId, date = d, kind = kind, note = note.trim())) } }
                onDismiss()
            }, enabled = parsed != null)
        }
    }
}

/** An editor that fits the field's type. [onChange] gets null to clear the value. */
@Composable
private fun FieldEditor(f: com.papercontrol.app.data.CustomField, value: String, onChange: (String?) -> Unit) {
    when (f.type) {
        FieldType.BOOLEAN -> ListRow(
            f.name,
            trailing = {
                Switch(
                    checked = value == "true", onCheckedChange = { onChange(if (it) "true" else "false") },
                    colors = SwitchDefaults.colors(checkedThumbColor = Ink.Black, checkedTrackColor = Ink.Text, uncheckedThumbColor = Ink.Secondary, uncheckedTrackColor = Ink.SurfaceHigh, uncheckedBorderColor = Ink.SurfaceHigh),
                )
            },
            onClick = { onChange(if (value == "true") "false" else "true") },
        )
        FieldType.SELECT -> Column(Modifier.padding(vertical = 8.dp)) {
            Text(f.name, style = Type.Caption)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                f.options.forEach { o -> LabelChip(o, Ink.Iris[4], selected = value == o) { onChange(if (value == o) null else o) } }
                if (f.options.isEmpty()) Text("No choices yet. Add them under Labels → Custom fields.", style = Type.Caption)
            }
        }
        FieldType.DOCLINK -> {
            val linked = query(value, initial = null as Document?) { value.toLongOrNull()?.let { Store.document(it) } }
            var picking by remember { mutableStateOf(false) }
            ListRow(
                f.name, subtitle = linked?.title ?: "Not linked",
                trailing = { PillButton(if (linked == null) "Link" else "Change", { picking = true }, background = Ink.SurfaceHigh) },
            )
            if (picking) DocPicker(onDismiss = { picking = false }) { id -> onChange(id.toString()); picking = false }
        }
        else -> {
            var text by remember(f.id, value) { mutableStateOf(value) }
            Field(
                f.name + " · " + f.type.title.lowercase(), text, { text = it; onChange(it) },
                number = f.type == FieldType.NUMBER || f.type == FieldType.MONETARY,
                placeholder = if (f.type == FieldType.DATE) "YYYY-MM-DD" else "",
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocPicker(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var q by remember { mutableStateOf("") }
    val docs = query(q, initial = emptyList<Document>()) { Store.documents(Filter(query = q), 60) }
    val labels = query(initial = emptyMap<Long, Label>()) { Store.allLabels() }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ink.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Metrics.Gutter).navigationBarsPadding()) {
            Text("Link a document", style = Type.Title)
            Spacer(Modifier.height(12.dp))
            SearchField(q, { q = it }, "Find a document")
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) { items(docs, key = { it.id }) { o -> DocRow(o, labels) { onPick(o.id) } } }
        }
    }
}
