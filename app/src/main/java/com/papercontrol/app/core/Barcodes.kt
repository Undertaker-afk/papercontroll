package com.papercontrol.app.core

/** Bank transfer details read from a payment QR code on an invoice. */
data class Payment(
    val iban: String,
    val name: String? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val reference: String? = null,
    val message: String? = null,
    val bic: String? = null,
    /** "EPC" (GiroCode, SEPA) or "Swiss QR". */
    val standard: String,
) {
    /** IBAN in groups of four, as printed. */
    val ibanPretty: String get() = iban.chunked(4).joinToString(" ")
}

/** What the barcodes on one page mean. */
data class PageCodes(
    /** Archive serial number from an "ASN…" sticker. */
    val asn: Int? = null,
    /** The page is a separator sheet: a new document starts after it, the sheet itself is dropped. */
    val separator: Boolean = false,
    val payment: Payment? = null,
)

/**
 * Barcode meanings, as in Paperless-ngx (ASN stickers, separator sheets) plus payment QR
 * codes (EPC "GiroCode" used across the SEPA area, and the Swiss QR bill).
 */
object Barcodes {
    const val DEFAULT_ASN_PREFIX = "ASN"
    const val DEFAULT_SEPARATOR = "PATCHT"

    fun interpret(values: List<String>, asnPrefix: String = DEFAULT_ASN_PREFIX, separator: String = DEFAULT_SEPARATOR): PageCodes {
        var asn: Int? = null
        var sep = false
        var payment: Payment? = null
        for (raw in values) {
            val v = raw.trim()
            if (v.equals(separator, ignoreCase = true)) sep = true
            if (asn == null && v.startsWith(asnPrefix, ignoreCase = true)) {
                asn = v.substring(asnPrefix.length).trimStart(' ', '-', ':', '#').takeWhile { it.isDigit() }.toIntOrNull()
            }
            if (payment == null) payment = epc(v) ?: swiss(v)
        }
        return PageCodes(asn, sep, payment)
    }

    /** EPC069-12 "BCD" payload (GiroCode). */
    fun epc(raw: String): Payment? {
        val l = raw.replace("\r\n", "\n").split('\n')
        if (l.size < 7 || l[0].trim() != "BCD" || l.getOrNull(3)?.trim() != "SCT") return null
        val iban = l[6].replace(" ", "").uppercase()
        if (!validIban(iban)) return null
        val amountRaw = l.getOrNull(7)?.trim().orEmpty()
        val currency = amountRaw.take(3).takeIf { it.all(Char::isLetter) && it.length == 3 }
        val amount = (if (currency != null) amountRaw.drop(3) else amountRaw).toDoubleOrNull()
        return Payment(
            iban = iban,
            name = l[5].trim().ifEmpty { null },
            amount = amount,
            currency = currency ?: if (amount != null) "EUR" else null,
            reference = l.getOrNull(9)?.trim()?.ifEmpty { null },
            message = l.getOrNull(10)?.trim()?.ifEmpty { null },
            bic = l[4].trim().ifEmpty { null },
            standard = "EPC",
        )
    }

    /** Swiss QR bill "SPC" payload. */
    fun swiss(raw: String): Payment? {
        val l = raw.replace("\r\n", "\n").split('\n')
        if (l.size < 29 || l[0].trim() != "SPC") return null
        val iban = l[3].replace(" ", "").uppercase()
        if (!validIban(iban)) return null
        return Payment(
            iban = iban,
            name = l[5].trim().ifEmpty { null },
            amount = l[18].trim().toDoubleOrNull(),
            currency = l[19].trim().ifEmpty { null },
            reference = l[28].trim().ifEmpty { null },
            message = l.getOrNull(29)?.trim()?.ifEmpty { null },
            standard = "Swiss QR",
        )
    }

    /** ISO 13616 check digits. */
    fun validIban(iban: String): Boolean {
        if (iban.length !in 15..34 || !iban.all { it.isLetterOrDigit() }) return false
        val r = iban.substring(4) + iban.substring(0, 4)
        var mod = 0
        for (c in r) {
            val n = if (c.isDigit()) c - '0' else c.uppercaseChar() - 'A' + 10
            mod = if (n >= 10) (mod * 100 + n) % 97 else (mod * 10 + n) % 97
        }
        return mod == 1
    }
}
