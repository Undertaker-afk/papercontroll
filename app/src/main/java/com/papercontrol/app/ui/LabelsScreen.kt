package com.papercontrol.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.unit.dp
import com.papercontrol.app.core.MatchAlgorithm
import com.papercontrol.app.data.CustomField
import com.papercontrol.app.data.FieldType
import com.papercontrol.app.data.Label
import com.papercontrol.app.data.LabelKind
import com.papercontrol.app.data.Palette
import com.papercontrol.app.data.Store
import com.papercontrol.app.ui.theme.Ink
import com.papercontrol.app.ui.theme.Metrics
import com.papercontrol.app.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun LabelsScreen(kind: LabelKind, onBack: () -> Unit, onKind: (LabelKind) -> Unit) {
    val labels = query(kind, initial = emptyList<Label>()) { Store.labels(kind) }
    var editing by remember { mutableStateOf<Label?>(null) }
    Column(Modifier.fillMaxSize().background(Ink.Black).statusBarsPadding()) {
        TopBar(
            left = { CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
            right = { CircleButton(Icons.Default.Add, "Add", { editing = Label(kind = kind, name = "", color = Palette.colors.random()) }) },
        )
        Text(kind.title, style = Type.LargeTitle, modifier = Modifier.padding(horizontal = Metrics.Gutter))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Metrics.Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelKind.entries.forEach { k -> LabelChip(k.title, Ink.Secondary, selected = k == kind) { onKind(k) } }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Metrics.Gutter)) {
            item {
                PaperCard(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    if (labels.isEmpty()) Text("None yet.", style = Type.BodySecondary, modifier = Modifier.padding(vertical = 14.dp))
                    labels.forEachIndexed { i, l ->
                        if (i > 0) HairLine()
                        ListRow(
                            l.name,
                            subtitle = describe(l),
                            leading = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(l.color))) },
                            trailing = { Text("${l.documentCount}", style = Type.BodySecondary) },
                            onClick = { editing = l },
                        )
                    }
                }
            }
        }
    }
    editing?.let { LabelEditor(it, onDismiss = { editing = null }) }
}

private fun describe(l: Label): String = when (l.algorithm) {
    MatchAlgorithm.NONE -> if (l.isInbox) "Inbox tag · added to every new document" else "Assigned by hand"
    MatchAlgorithm.AUTO -> "Auto · learns from your documents"
    else -> "${l.algorithm.label}: ${l.match}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelEditor(initial: Label, onDismiss: () -> Unit) {
    var l by remember { mutableStateOf(initial) }
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Ink.Surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Metrics.Gutter).navigationBarsPadding().padding(bottom = 20.dp)) {
            Text(if (initial.id == 0L) "New ${initial.kind.singular.lowercase()}" else initial.kind.singular, style = Type.Title)
            Spacer(Modifier.height(12.dp))
            Field("Name", l.name, { l = l.copy(name = it) })
            Spacer(Modifier.height(8.dp))
            Text("Color", style = Type.Caption)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Palette.colors.forEach { c ->
                    Box(
                        Modifier.size(26.dp).clip(CircleShape).background(Color(c))
                            .then(if (l.color == c) Modifier.border(2.dp, Ink.Text, CircleShape) else Modifier)
                            .pressable({ l = l.copy(color = c) }),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Matching", style = Type.Caption)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MatchAlgorithm.entries.forEach { a -> LabelChip(a.label, Ink.Secondary, selected = l.algorithm == a) { l = l.copy(algorithm = a) } }
            }
            Spacer(Modifier.height(6.dp))
            Text(l.algorithm.help, style = Type.Caption)
            if (l.algorithm !in setOf(MatchAlgorithm.NONE, MatchAlgorithm.AUTO)) {
                Field(if (l.algorithm == MatchAlgorithm.REGEX) "Pattern" else "Words", l.match, { l = l.copy(match = it) }, placeholder = if (l.algorithm == MatchAlgorithm.ANY) "rewe edeka \"whole foods\"" else "")
                ToggleRow("Ignore upper and lower case", l.caseInsensitive, { l = l.copy(caseInsensitive = it) })
            }
            if (l.kind == LabelKind.TAG) ToggleRow("Inbox tag", l.isInbox, { l = l.copy(isInbox = it) }, subtitle = "Added to every new document until you have looked at it.")
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Save", {
                if (l.name.isBlank()) return@PrimaryButton
                scope.launch(Dispatchers.IO) { Store.saveLabel(l) }
                onDismiss()
            }, enabled = l.name.isNotBlank())
            if (initial.id != 0L) {
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Delete", {
                    scope.launch(Dispatchers.IO) { Store.deleteLabel(initial) }
                    onDismiss()
                })
            }
        }
    }
}

@Composable
fun CustomFieldsCard() {
    val fields = query(initial = emptyList<CustomField>()) { Store.customFields() }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(FieldType.TEXT) }
    val scope = rememberCoroutineScope()
    PaperCard(padding = PaddingValues(16.dp)) {
        fields.forEach { f ->
            ListRow(f.name, subtitle = f.type.title, trailing = {
                Icon(Icons.Default.Close, "Delete", tint = Ink.Tertiary, modifier = Modifier.size(20.dp).pressable({ scope.launch(Dispatchers.IO) { Store.deleteCustomField(f) } }))
            })
            HairLine()
        }
        Field("New field", name, { name = it }, placeholder = "Warranty until, Project, …")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldType.entries.forEach { t -> LabelChip(t.title, Ink.Secondary, selected = type == t) { type = t } }
        }
        Spacer(Modifier.height(12.dp))
        PillButton("Add field", {
            if (name.isNotBlank()) {
                val n = name
                scope.launch(Dispatchers.IO) { Store.saveCustomField(CustomField(name = n, type = type)) }
                name = ""
            }
        }, icon = Icons.Default.Check, background = Ink.SurfaceHigh)
    }
}
