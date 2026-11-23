package com.papercontrol.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papercontrol.app.data.Palette
import com.papercontrol.app.processing.Importer
import com.papercontrol.app.processing.Proposal
import com.papercontrol.app.ui.theme.Ink
import com.papercontrol.app.ui.theme.Metrics
import com.papercontrol.app.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * Paper mapping results: every proposed document as a stack of its pages, with the evidence
 * that tied them together. Pages can be moved, split off, joined or dropped before saving.
 */
@Composable
fun ImportScreen(onDone: () -> Unit) {
    val state by Importer.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Column(Modifier.fillMaxSize().background(Ink.Black).statusBarsPadding()) {
        TopBar(left = { CircleButton(Icons.Default.Close, "Close", { Importer.clear(discard = true); onDone() }) }, title = "Import")
        Column(Modifier.padding(horizontal = Metrics.Gutter)) {
            Text(if (state.running) state.phase else "Paper mapping", style = Type.LargeTitle)
            Spacer(Modifier.height(4.dp))
            val pages = state.proposals.sumOf { it.pages.size }
            Text(
                when {
                    state.error != null -> state.error!!
                    state.running -> "${state.done} of ${state.total}"
                    else -> "$pages page${if (pages == 1) "" else "s"} → ${state.proposals.size} document${if (state.proposals.size == 1) "" else "s"}. Tap a page to move it."
                },
                style = Type.Caption,
            )
            if (state.running) {
                Spacer(Modifier.height(14.dp))
                IrisProgress(if (state.total == 0) 0f else state.done / state.total.toFloat())
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(Metrics.Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(state.proposals) { i, p ->
                ProposalCard(
                    index = i,
                    p = p,
                    selected = selected,
                    onSelect = { pi ->
                        val sel = selected
                        selected = when {
                            sel == null -> i to pi
                            sel.first != i -> { Importer.movePage(sel.first, sel.second, i); null }
                            else -> null
                        }
                    },
                    canMerge = i < state.proposals.lastIndex,
                    busy = state.running,
                )
            }
        }
        Column(Modifier.padding(horizontal = Metrics.Gutter, vertical = 12.dp).navigationBarsPadding()) {
            selected?.let { (pi, pg) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp).horizontalScroll(rememberScrollState())) {
                    PillButton("Own document", { Importer.splitOff(pi, pg); selected = null }, icon = Icons.Outlined.CallSplit)
                    PillButton("Earlier", { Importer.movePageWithin(pi, pg, -1); selected = null }, icon = Icons.Outlined.ChevronLeft)
                    PillButton("Later", { Importer.movePageWithin(pi, pg, 1); selected = null }, icon = Icons.Outlined.ChevronRight)
                    PillButton("Cancel", { selected = null })
                }
                Text("Tap a page in another document to move it there.", style = Type.Caption, modifier = Modifier.padding(bottom = 10.dp))
            }
            PrimaryButton(
                if (state.proposals.size == 1) "Add document" else "Add ${state.proposals.size} documents",
                { scope.launch { Importer.saveAll(context); onDone() } },
                enabled = !state.running && state.proposals.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun ProposalCard(index: Int, p: Proposal, selected: Pair<Int, Int>?, onSelect: (Int) -> Unit, canMerge: Boolean, busy: Boolean) {
    val s = p.sorting
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Metrics.CardRadius)).background(Ink.Surface).padding(16.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s?.title ?: p.pages.first().extraction.suggestedTitle() ?: "Document ${index + 1}", style = Type.Headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.pages.size} page${if (p.pages.size == 1) "" else "s"}" + (s?.amount?.let { " · " + Fmt.money(it, s.currency) } ?: ""), style = Type.Caption)
            }
            if (!busy) {
                if (canMerge) Icon(Icons.Outlined.CallMerge, "Join with next", tint = Ink.Secondary, modifier = Modifier.size(36.dp).clip(CircleShape).pressable({ Importer.mergeWithNext(index) }).padding(8.dp))
                Icon(Icons.Outlined.DeleteOutline, "Leave out", tint = Ink.Secondary, modifier = Modifier.size(36.dp).clip(CircleShape).pressable({ Importer.remove(index) }).padding(8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            p.pages.forEachIndexed { pi, page ->
                val isSel = selected == index to pi
                Box {
                    Thumb(
                        page.file.path,
                        Modifier.size(width = 74.dp, height = 96.dp)
                            .then(if (isSel) Modifier.background(Color.White, RoundedCornerShape(10.dp)).padding(2.dp) else Modifier)
                            .pressable({ onSelect(pi) }),
                    )
                    Text(
                        "${pi + 1}",
                        style = Type.Caption.copy(color = Ink.Text),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(CircleShape).background(Ink.Black.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
        if (p.reasons.isNotEmpty() && p.pages.size > 1) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.AutoAwesome, null, tint = Ink.Secondary, modifier = Modifier.size(14.dp).padding(top = 2.dp))
                Spacer(Modifier.width(6.dp))
                Text(p.reasons.joinToString(" · "), style = Type.Caption)
            }
        }
        if (s != null) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.folder?.let { LabelChip(it.name, Color(Palette.forName(it.name))) }
                s.correspondent?.let { LabelChip(it.name, Color(Palette.forName(it.name))) }
                s.docType?.let { LabelChip(it.name, Color(Palette.forName(it.name))) }
                s.tags.filter { !it.why.startsWith("New documents") }.forEach { LabelChip(it.name, Color(Palette.forName(it.name))) }
            }
        }
    }
}

/** A thin progress bar with the scan glow running through it. */
@Composable
fun IrisProgress(progress: Float) {
    val t = rememberInfiniteTransition(label = "p")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "shift")
    Canvas(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Ink.Surface)) {
        val w = size.width * progress.coerceIn(0.03f, 1f)
        drawRect(
            Brush.horizontalGradient(Ink.Iris + Ink.Iris, startX = -size.width * shift, endX = size.width * (2 - shift)),
            size = size.copy(width = w),
        )
        drawRect(Color.White.copy(alpha = 0.15f), Offset.Zero, size.copy(width = w, height = size.height / 2))
    }
}
