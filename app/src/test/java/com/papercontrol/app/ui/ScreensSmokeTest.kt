package com.papercontrol.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.runtime.mutableStateOf
import androidx.test.core.app.ApplicationProvider
import com.papercontrol.app.core.DeadlineKind
import com.papercontrol.app.core.Payment
import com.papercontrol.app.core.Quad
import com.papercontrol.app.core.ReceiptParser
import com.papercontrol.app.core.ocrOf
import com.papercontrol.app.data.Document
import com.papercontrol.app.data.ExtractionJson
import com.papercontrol.app.data.Filter
import com.papercontrol.app.data.LabelKind
import com.papercontrol.app.data.Page
import com.papercontrol.app.data.Reminder
import com.papercontrol.app.data.Store
import com.papercontrol.app.processing.Pick
import com.papercontrol.app.processing.PreparedPage
import com.papercontrol.app.processing.Sorting
import com.papercontrol.app.ui.scan.Capture
import com.papercontrol.app.ui.scan.RevealAnim
import com.papercontrol.app.ui.scan.RevealLayout
import com.papercontrol.app.ui.scan.ScanSession
import com.papercontrol.app.ui.scan.Stage
import com.papercontrol.app.ui.theme.PaperTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * Composes the main screens and the scan reveal and checks what they say, without rendering any
 * pixels (legacy graphics), so it is reliable on any machine. See ScreenshotTest for pictures.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreensSmokeTest {
    @get:Rule
    val compose = createComposeRule()

    private val receiptOcr = ocrOf(
        "NORDLIT COFFEE", "214 Harbor St, Portland", "11/18/2026|09:42", "Order #2231|Table 6",
        "Flat white|4.50", "Oat cappuccino|5.25", "Subtotal|9.75", "Tax 8%|0.78", "TOTAL|10.53", "VISA **** 4821|10.53",
    )

    /** The screens read the archive on a background thread; give it real time to arrive. */
    private fun waitFor(text: String, substring: Boolean = false) {
        repeat(200) {
            compose.mainClock.advanceTimeByFrame()
            if (compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()) return
            Thread.sleep(20)
        }
        throw AssertionError("never saw \"$text\"")
    }

    private fun insertDocument(): Long {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val img = File(ctx.cacheDir, "page.jpg").apply { writeBytes(ByteArray(8)) }
        val ex = ReceiptParser.parse(receiptOcr, java.util.Locale.US).copy(
            payment = Payment("DE33100205000001194700", "Nordlit Coffee GmbH", 10.53, "EUR", "RE-2231", null, "BFSWDE33BER", "EPC"),
        )
        val type = Store.labels(LabelKind.DOCTYPE).first { it.name == "Receipt" }.id
        val id = Store.insertDocument(
            Document(title = "Nordlit Coffee", docTypeId = type, created = LocalDate.of(2026, 11, 18), amount = 10.53, currency = "USD", content = receiptOcr.text, fields = ExtractionJson.write(ex), pageCount = 1, thumbPath = img.path),
            listOf(Page(docId = 0, index = 0, path = img.path, originalPath = null, text = receiptOcr.text, capturedAt = 0)),
        )
        Store.saveReminder(Reminder(docId = id, date = LocalDate.now().plusDays(5), kind = DeadlineKind.PAYMENT, note = "14 days after the invoice date"))
        return id
    }

    @Test
    fun homeListsTheArchive() {
        insertDocument()
        compose.mainClock.autoAdvance = false
        compose.setContent { PaperTheme { HomeScreen(Route.Home(Filter()), {}, {}, {}, {}, {}) } }
        waitFor("Nordlit Coffee")
        assertTrue(compose.onAllNodesWithText("Documents").fetchSemanticsNodes().isNotEmpty())
        assertTrue(Store.count() == 1)
    }

    @Test
    fun documentScreenShowsPaymentCodeAndDeadlines() {
        val id = insertDocument()
        compose.setContent { PaperTheme { DocumentScreen(id, {}, {}) } }
        // The screen reads the archive on a background thread; with the normal clock this waits for it.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Nordlit Coffee")).fetchSemanticsNodes().isNotEmpty() }
        val list = compose.onAllNodes(hasScrollAction())[0]
        list.performScrollToNode(hasText("Pay with EPC code"))
        compose.onNodeWithText("Pay with EPC code").assertExists()
        compose.onNodeWithText("DE33 1002 0500 0001 1947 00").assertExists()
        list.performScrollToNode(hasText("Deadlines", ignoreCase = true))
        compose.onNodeWithText("Payment due").assertExists()
    }

    private fun checkSettings() {
        compose.mainClock.autoAdvance = false
        compose.setContent { PaperTheme { SettingsContent() } }
        repeat(60) { compose.mainClock.advanceTimeByFrame(); Thread.sleep(5) }
        // The page is one Column, so every section is composed whether or not it is scrolled into view.
        for (section in listOf("Scanning", "Reading", "Names and folders", "Barcodes", "Search by meaning", "On-device AI model", "Privacy", "Backup", "Paperless-ngx server")) {
            assertTrue("missing section $section", compose.onAllNodesWithText(section, ignoreCase = true).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun settingsListsEveryFeatureSection() = checkSettings()

    /** The phone this ran on is set to German. */
    @Test
    @Config(sdk = [35], qualifiers = "de-rDE-w393dp-h852dp-xxhdpi")
    fun settingsInGerman() = checkSettings()

    @Test
    fun revealComposesThroughEveryStage() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val shot = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        val flat = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        val file = File(ctx.cacheDir, "clean.jpg").apply { writeBytes(ByteArray(8)) }
        val ex = ReceiptParser.parse(receiptOcr, java.util.Locale.US)
        val page = PreparedPage(file, null, receiptOcr, ex, floatArrayOf(0.8f, 0.8f, 0.8f), 0)
        val capture = Capture(shot, Quad.FULL, flat, flat, ex.highlights, page)
        val session = ScanSession(ctx, CoroutineScope(Dispatchers.Unconfined)).apply {
            pages += page; extraction = ex; this.capture = capture; stage = Stage.REVEAL
            sorting = Sorting("Nordlit Coffee", correspondent = Pick(null, "Nordlit Coffee", ""), amount = 10.53, currency = "USD")
        }
        val anim = RevealAnim()
        compose.mainClock.autoAdvance = false
        compose.setContent { PaperTheme { RevealLayout(session, capture, anim, {}, {}) } }
        fun at(block: suspend RevealAnim.() -> Unit) { runBlocking { anim.block() }; repeat(3) { compose.mainClock.advanceTimeByFrame() } }
        at { photo.snapTo(1f); peel.snapTo(0f) }
        at { photo.snapTo(0.4f); peel.snapTo(0.5f) }
        at { photo.snapTo(0f); peel.snapTo(1f); bandGlow.snapTo(1f); band.snapTo(0.4f) }
        at { bandGlow.snapTo(0f); band.snapTo(1.12f) }
        at { header.snapTo(1f); morph.snapTo(0.5f) }
        at { morph.snapTo(1f); aura.snapTo(0.5f) }
        at { aura.snapTo(0f); footer.snapTo(1f); done = true }
        compose.onNodeWithText("Add expense").assertExists()
        compose.onNodeWithText("Nordlit Coffee").assertExists()
    }
}
