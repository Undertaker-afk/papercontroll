package com.papercontrol.app.ui.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.papercontrol.app.core.Box
import com.papercontrol.app.core.ReceiptParser
import com.papercontrol.app.core.ocrOf
import com.papercontrol.app.data.Document
import com.papercontrol.app.data.ExtractionJson
import com.papercontrol.app.data.Filter
import com.papercontrol.app.data.LabelKind
import com.papercontrol.app.data.Page
import com.papercontrol.app.data.Store
import com.papercontrol.app.processing.Images
import com.papercontrol.app.processing.Pick
import com.papercontrol.app.processing.PreparedPage
import com.papercontrol.app.processing.Sorting
import com.papercontrol.app.ui.DocumentScreen
import com.papercontrol.app.ui.HomeScreen
import com.papercontrol.app.ui.Route
import com.papercontrol.app.ui.theme.PaperTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * Renders the scan reveal at the moments that matter in the reference video, plus the main
 * screens, to PNG files in app/build/screens/. Uses the receipt from the video as input and
 * runs the real paper detection, flattening and clean-up on it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val receiptOcr = ocrOf(
        "NORDLIT COFFEE", "214 Harbor St, Portland", "Tel 503 555 0142", "11/18/2026|09:42", "Order #2231|Table 6",
        "Flat white|4.50", "Oat cappuccino|5.25", "Almond croissant|4.75", "Sparkling water|3.00",
        "Subtotal|17.50", "Tax 8%|1.40", "TOTAL|18.90", "VISA **** 4821|18.90", "Auth 048812", "Thank you!",
    )

    private fun photo(): Bitmap = javaClass.classLoader!!.getResourceAsStream("receipt_photo.jpg").use { BitmapFactory.decodeStream(it) }

    private fun capture(): Capture {
        val photo = photo()
        // OpenCV cannot load under Robolectric, so the plain detector and warp run here.
        val quad = Images.detect(photo)?.quad
        assertNotNull("paper should be found in the video frame", quad)
        val flat = Images.warp(photo, quad!!)
        val clean = Images.enhance(flat)
        // Field positions on the paper, as fractions of its size (merchant, date, time, total).
        fun b(l: Float, t: Float, r: Float, bo: Float) = Box(l * clean.width, t * clean.height, r * clean.width, bo * clean.height)
        val highlights = listOf(b(0.10f, 0.058f, 0.86f, 0.098f), b(0.07f, 0.228f, 0.40f, 0.262f), b(0.71f, 0.226f, 0.88f, 0.258f), b(0.68f, 0.658f, 0.93f, 0.698f))
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(ctx.cacheDir, "shots").apply { mkdirs() }
        val ex = ReceiptParser.parse(receiptOcr, java.util.Locale.US)
        val page = PreparedPage(Images.save(clean, File(dir, "clean.jpg")), Images.save(photo, File(dir, "photo.jpg")), receiptOcr, ex, floatArrayOf(0.8f, 0.8f, 0.8f), 0)
        return Capture(photo, quad, flat, clean, highlights, page)
    }

    private fun session(c: Capture): ScanSession {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val s = ScanSession(ctx, CoroutineScope(Dispatchers.Unconfined))
        val ex = ReceiptParser.parse(receiptOcr, java.util.Locale.US)
        s.pages += c.page
        s.extraction = ex
        s.capture = c
        s.stage = Stage.REVEAL
        s.sorting = Sorting(
            title = "Nordlit Coffee",
            correspondent = Pick(null, "Nordlit Coffee", ""),
            docType = Pick(1, "Receipt", ""),
            folder = Pick(2, "Receipts", ""),
            tags = listOf(Pick(3, "Inbox", "")),
            amount = 18.90,
            currency = "USD",
        )
        return s
    }

    private fun shoot(name: String) {
        compose.mainClock.advanceTimeBy(64)
        compose.onRoot().captureRoboImage("build/screens/$name.png", roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
    }

    @Test
    fun revealFrames() {
        val c = capture()
        val s = session(c)
        val anim = RevealAnim()
        compose.mainClock.autoAdvance = false
        compose.setContent { PaperTheme { RevealLayout(s, c, anim, {}, {}) } }

        fun at(name: String, block: suspend RevealAnim.() -> Unit) {
            runBlocking { anim.block() }
            shoot(name)
        }
        at("reveal_1_photo") { photo.snapTo(1f); peel.snapTo(0f) }
        at("reveal_2_peel") { photo.snapTo(0.45f); peel.snapTo(0.5f) }
        at("reveal_3_band_top") { photo.snapTo(0f); peel.snapTo(1f); bandGlow.snapTo(1f); band.snapTo(0.06f) }
        at("reveal_4_band_mid") { band.snapTo(0.45f) }
        at("reveal_5_band_low") { band.snapTo(0.8f) }
        at("reveal_6_scanned") { band.snapTo(1.12f); bandGlow.snapTo(0f) }
        at("reveal_7_morph_a") { header.snapTo(1f); morph.snapTo(0.4f) }
        at("reveal_8_morph_b") { morph.snapTo(0.65f); aura.snapTo(1f) }
        at("reveal_9_aura") { morph.snapTo(1f); aura.snapTo(0.55f) }
        at("reveal_10_final") { aura.snapTo(0f); footer.snapTo(1f); done = true }
    }

    @Test
    fun mainScreens() {
        val c = capture()
        val file = Images.save(c.clean, File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "doc.jpg"))
        val ex = ReceiptParser.parse(receiptOcr, java.util.Locale.US)
        val receipts = Store.labels(LabelKind.FOLDER).first { it.name == "Receipts" }.id
        val type = Store.labels(LabelKind.DOCTYPE).first { it.name == "Receipt" }.id
        val inbox = Store.inboxTagIds()
        val corr = Store.labelNamed(LabelKind.CORRESPONDENT, "Nordlit Coffee")
        var id = 0L
        listOf("Nordlit Coffee" to 18.90, "Bäckerei Müller" to 4.30, "Stadtwerke · Electricity March" to 84.00, "ACME Software · Invoice RE-2024-118" to 1680.00).forEachIndexed { i, (t, a) ->
            id = Store.insertDocument(
                Document(title = t, correspondentId = if (i == 0) corr else null, docTypeId = type, folderId = receipts, created = LocalDate.now().minusDays(i * 3L), amount = a, currency = "USD", content = receiptOcr.text, fields = ExtractionJson.write(ex), pageCount = if (i == 3) 2 else 1, thumbPath = file.path, tagIds = if (i < 2) inbox else emptyList()),
                listOf(Page(docId = 0, index = 0, path = file.path, originalPath = null, text = receiptOcr.text, capturedAt = 0)),
            )
        }
        compose.mainClock.autoAdvance = false
        val routeState = androidx.compose.runtime.mutableStateOf<Route>(Route.Home(Filter()))
        compose.setContent {
            PaperTheme {
                when (val r = routeState.value) {
                    is Route.Home -> HomeScreen(r, {}, {}, {}, {}, {})
                    is Route.Doc -> DocumentScreen(r.id, {}, {})
                    else -> {}
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        shoot("home")
        for (tab in listOf(com.papercontrol.app.ui.Tab.FOLDERS, com.papercontrol.app.ui.Tab.LABELS, com.papercontrol.app.ui.Tab.SETTINGS)) {
            compose.runOnUiThread { routeState.value = Route.Home(Filter(), tab) }
            repeat(40) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
            repeat(40) { compose.mainClock.advanceTimeByFrame() }
            shoot(tab.name.lowercase())
        }
        compose.runOnUiThread { routeState.value = Route.Doc(id) }
        compose.mainClock.advanceTimeBy(500)
        shoot("document")
    }
}
