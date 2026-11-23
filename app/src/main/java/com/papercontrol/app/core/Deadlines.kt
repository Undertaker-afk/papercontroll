package com.papercontrol.app.core

import java.time.LocalDate

enum class DeadlineKind(val label: String, val remindDaysBefore: List<Int>) {
    PAYMENT("Payment due", listOf(3, 0)),
    WARRANTY("Warranty ends", listOf(30, 7)),
    CANCEL("Cancel by", listOf(30, 7, 1)),
    EXPIRY("Expires", listOf(30, 7)),
}

data class Deadline(val kind: DeadlineKind, val date: LocalDate, val note: String = "")

/**
 * Dates a person must act on, read from the text: payment due dates (fixed or "within 14
 * days"), warranty ends ("2 years warranty" from the purchase date), cancellation
 * deadlines and expiry dates. English, German, French, Spanish, Italian and Dutch wording.
 */
object Deadlines {
    private val dateAfter = """[:\s]*(?:am|on|le|el|il|op|the|den)?\s*"""
    private val payWords = """(?:due\s*date|payment\s*due|due\s*(?:by|on)|pay(?:able)?\s*by|fällig(?:keitsdatum|\s*am)?|zahlbar\s*bis(?:\s*zum)?|bitte\s*(?:überweisen|zahlen)\s*sie\s*(?:den\s*betrag\s*)?bis(?:\s*zum)?|zahlungsfrist|échéance|date\s*d'échéance|à\s*payer\s*avant\s*le|fecha\s*de\s*vencimiento|vencimiento|scadenza|vervaldatum|te\s*betalen\s*voor)"""
    private val payDate = Regex("(?i)$payWords$dateAfter(.{6,24})")
    private val payWithin = Regex("""(?i)(?:within|innerhalb\s*von|innerhalb|binnen|sous|dans\s*un\s*délai\s*de|en|entro|net)\s*(\d{1,3})\s*(?:days|tagen|tage|jours|días|dias|giorni|dagen)?""")
    private val payNet = Regex("""(?i)(?:zahlungsziel|payment\s*terms?|conditions?\s*de\s*paiement)[:\s]*(?:net\s*)?(\d{1,3})\s*(?:days|tage|tagen|jours)""")
    private val warranty = Regex("""(?i)(?:warranty|guarantee|garantie|gewährleistung|garantía|garanzia)[^.\n]{0,30}?(\d{1,2})\s*(years?|jahre?n?|ans?|años?|anni|jaar|months?|monate?n?|mois|meses|mesi|maanden)""")
    private val warrantyLead = Regex("""(?i)(\d{1,2})\s*(years?|jahre?n?|ans?|años?|anni|jaar|months?|monate?n?|mois|meses|mesi|maanden)\s*(?:of\s*)?(?:warranty|guarantee|garantie|gewährleistung|garantía|garanzia)""")
    private val warrantyUntil = Regex("""(?i)(?:warranty|garantie|gewährleistung)\s*(?:until|bis|valid\s*until|gültig\s*bis)$dateAfter(.{6,24})""")
    private val cancel = Regex("""(?i)(?:cancel(?:lation)?\s*(?:by|before|until|deadline)|notice\s*(?:by|until)|kündigung\s*(?:bis|spätestens\s*(?:bis|am)?)|kündbar\s*(?:bis|zum)|résiliation\s*avant\s*le|disdetta\s*entro\s*il|opzeggen\s*voor)$dateAfter(.{6,24})""")
    private val expiry = Regex("""(?i)(?:valid\s*until|expires?(?:\s*on)?|expiry\s*date|gültig\s*bis|ablaufdatum|läuft\s*ab\s*am|vertragsende|contract\s*end(?:s)?|valable\s*jusqu'au|válido\s*hasta|valido\s*fino\s*al|geldig\s*tot)$dateAfter(.{6,24})""")

    fun find(text: String, documentDate: LocalDate?, monthFirst: Boolean = false): List<Deadline> {
        val out = mutableListOf<Deadline>()
        fun dateIn(s: String) = ReceiptParser.parseDate(s, monthFirst)?.first

        payDate.find(text)?.let { m -> dateIn(m.groupValues[1])?.let { out += Deadline(DeadlineKind.PAYMENT, it) } }
        if (out.none { it.kind == DeadlineKind.PAYMENT } && documentDate != null) {
            val m = payNet.find(text) ?: Regex("(?i)(?:zahlbar|payable|due|pay|zahlen|payer|pagar|pagare|betalen)[^.\\n]{0,25}").find(text)?.let { lead ->
                payWithin.find(text.substring(lead.range.first, minOf(text.length, lead.range.last + 30)))
            }
            m?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..180 }?.let { out += Deadline(DeadlineKind.PAYMENT, documentDate.plusDays(it.toLong()), "${it} days after the invoice date") }
        }

        warrantyUntil.find(text)?.let { m -> dateIn(m.groupValues[1])?.let { out += Deadline(DeadlineKind.WARRANTY, it) } }
        if (out.none { it.kind == DeadlineKind.WARRANTY } && documentDate != null) {
            (warranty.find(text) ?: warrantyLead.find(text))?.let { m ->
                val n = m.groupValues[1].toLong()
                val unit = m.groupValues[2].lowercase()
                val months = unit.startsWith("mon") || unit.startsWith("mois") || unit.startsWith("mes") || unit.startsWith("maand")
                if (n in 1..30) out += Deadline(DeadlineKind.WARRANTY, if (months) documentDate.plusMonths(n) else documentDate.plusYears(n), "$n ${if (months) "months" else "years"} from purchase")
            }
        }

        cancel.find(text)?.let { m -> dateIn(m.groupValues[1])?.let { out += Deadline(DeadlineKind.CANCEL, it) } }
        expiry.find(text)?.let { m -> dateIn(m.groupValues[1])?.let { out += Deadline(DeadlineKind.EXPIRY, it) } }
        return out.distinctBy { it.kind to it.date }
    }
}
