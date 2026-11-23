package com.papercontrol.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.LocalGroceryStore
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papercontrol.app.core.DocKind
import com.papercontrol.app.core.Extraction
import com.papercontrol.app.ui.theme.Type
import java.util.Locale

/** The digital twin of a receipt or invoice: the card the paper turns into after the scan. */
@Composable
fun ReceiptCard(ex: Extraction, title: String, modifier: Modifier = Modifier, fallbackText: String = "", currency: String? = ex.currency) {
    val cur = currency
    PaperCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconCircle(iconFor(ex, title), size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(ex.merchant ?: title, style = Type.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = ex.address ?: when (ex.kind) {
                    DocKind.INVOICE -> ex.invoiceNumber?.let { "Invoice $it" } ?: "Invoice"
                    DocKind.LETTER -> "Letter"
                    else -> null
                }
                if (sub != null) Text(sub, style = Type.BodySecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        val hasMeta = ex.date != null || ex.time != null || ex.orderNumber != null || ex.table != null
        if (hasMeta) {
            Divided()
            if (ex.date != null || ex.time != null) KeyValue(Fmt.date(ex.date), Fmt.time(ex.time))
            if (ex.orderNumber != null || ex.table != null) {
                Spacer(Modifier.height(6.dp))
                KeyValue(ex.orderNumber?.let { "Order #$it" } ?: "", ex.table?.let { "Table $it" } ?: "", style = Type.BodySecondary)
            }
        }

        if (ex.items.isNotEmpty()) {
            Divided()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (item in ex.items.take(40)) {
                    KeyValue(if (item.quantity > 1) "${item.quantity}× ${item.name}" else item.name, Fmt.money(item.amount, cur))
                }
            }
        } else if (ex.total == null && fallbackText.isNotBlank()) {
            Divided()
            Text(fallbackText.trim().take(420), style = Type.BodySecondary, maxLines = 8, overflow = TextOverflow.Ellipsis)
        }

        if (ex.total != null || ex.subtotal != null || ex.tax != null) {
            Divided()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ex.subtotal?.let { KeyValue("Subtotal", Fmt.money(it, cur), style = Type.BodySecondary) }
                ex.tax?.let { KeyValue(ex.taxLabel ?: "Tax", Fmt.money(it, cur), style = Type.BodySecondary) }
            }
            ex.total?.let {
                Spacer(Modifier.height(10.dp))
                KeyValue("Total", Fmt.money(it, cur), style = Type.Headline, rightStyle = Type.Amount)
            }
        }

        if (ex.cardLast4 != null || ex.authCode != null) {
            Divided()
            KeyValue(
                ex.cardLast4?.let { "${ex.cardBrand ?: "Card"} •••• $it" } ?: "",
                ex.authCode?.let { "Auth $it" } ?: "",
                style = Type.BodySecondary,
            )
        }
    }
}

@Composable
private fun Divided() {
    Spacer(Modifier.height(18.dp))
    HairLine()
    Spacer(Modifier.height(18.dp))
}

fun iconFor(ex: Extraction, title: String): ImageVector {
    val t = ((ex.merchant ?: "") + " " + title + " " + ex.items.joinToString(" ") { it.name }).lowercase(Locale.ROOT)
    return when {
        Regex("coffee|café|cafe|kaffee|espresso|latte|cappuccino|bakery|bäcker").containsMatchIn(t) -> Icons.Outlined.Coffee
        Regex("restaurant|pizza|burger|sushi|grill|bistro|trattoria|kitchen").containsMatchIn(t) -> Icons.Outlined.Restaurant
        Regex("fuel|gas station|tankstelle|shell|aral|esso|benzin|diesel").containsMatchIn(t) -> Icons.Outlined.LocalGasStation
        Regex("market|grocery|rewe|edeka|aldi|lidl|supermarkt|kaufland|whole foods|walmart").containsMatchIn(t) -> Icons.Outlined.LocalGroceryStore
        ex.kind == DocKind.INVOICE -> Icons.Outlined.ReceiptLong
        ex.kind == DocKind.RECEIPT -> Icons.Outlined.Receipt
        else -> Icons.Outlined.Description
    }
}
