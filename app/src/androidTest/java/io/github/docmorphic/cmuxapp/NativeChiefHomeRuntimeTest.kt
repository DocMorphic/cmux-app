package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.util.Locale

/** Actual Home/computer owner with isolated API responses; no account, host or physical AX acceptance. */
class NativeChiefHomeRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var computer: NativeChiefComputerSession
    private lateinit var owner: CoroutineScope
    private val api = Api()
    private var locale by mutableStateOf("en")
    private var scale by mutableFloatStateOf(1f)
    private var light by mutableStateOf(false)
    private val past = Instant.ofEpochSecond(-62135769600L)
    private fun row(id: String, title: String, preview: String = "", unread: Long = 0, pinned: Boolean = false, working: Boolean = false) =
        NativeChiefConversation(id,title,preview,unread,pinned,working,past)

    @Before fun start() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        compose.runOnUiThread {
            owner = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            computer = NativeChiefComputerSession(owner,api) { true }
            api.rows = listOf(row("chief","Chief",unread=5,pinned=true),row("build","Build","Preview",unread=3,working=true))
        }
    }
    @After fun stop() { if (::owner.isInitialized) compose.runOnUiThread { computer.close();owner.cancel() } }
    private fun mount() {
        compose.setContent {
            val original = LocalContext.current
            val config = remember(locale) { android.content.res.Configuration(original.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(this@NativeChiefHomeRuntimeTest.locale));setLayoutDirection(Locale.forLanguageTag(this@NativeChiefHomeRuntimeTest.locale))
            } }
            // Preserve the Activity owner chain used by launchers/BackHandler while
            // changing only this subtree's resources, not the device/app locale.
            val localized = remember(config) { android.view.ContextThemeWrapper(original,0).apply { applyOverrideConfiguration(config) } }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized,LocalConfiguration provides config,
                LocalDensity provides Density(density.density,scale),LocalLayoutDirection provides if (locale=="ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                CmuxTheme(darkTheme = !light) {
                    Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                        NativeChiefView(computer,true,Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
    private fun ready() { compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefPinnedConversation").fetchSemanticsNodes().size==1 } }
    private fun label(name: Int): String {
        val config = android.content.res.Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(this@NativeChiefHomeRuntimeTest.locale)) }
        return compose.activity.createConfigurationContext(config).getString(name)
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrument = InstrumentationRegistry.getInstrumentation()
        val directory = java.io.File(compose.activity.getExternalFilesDir(null),"rich-composer").apply { mkdirs() }
        val bitmap = instrument.uiAutomation.takeScreenshot()
        java.io.File(directory,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        java.io.File(directory,"$name.txt").writeText(compose.onRoot().printToString()+"\nState: "+computer.home.state.value)
    }

    @Test fun workingAndUnreadSummariesUseOneActionableNodeAndPreserveRowNavigation() {
        mount();ready()
        compose.onNodeWithTag("ChiefPinnedConversation").assertContentDescriptionEquals("Chief, 5 unread").assertHasClickAction()
        compose.onNodeWithTag("ChiefConversation:build").assertContentDescriptionEquals("Build, Preview, Working")
        compose.onNodeWithTag("ChiefRecentWorking:build",useUnmergedTree=true).assertExists()
        compose.onNodeWithTag("ChiefRecentUnread:build",useUnmergedTree=true).assertWidthIsEqualTo(androidx.compose.ui.unit.Dp(7f))
        compose.onAllNodesWithText("Preview").assertCountEquals(0)
        compose.runOnUiThread { api.changes.trySend(Result.success(NativeChiefSnapshot(listOf(row("chief","Chief",unread=7,pinned=true,working=true),
            row("build","Build","Preview",unread=1)),"next"))) }
        compose.waitUntil(10_000) { computer.home.state.value.pinned?.working==true }
        compose.onNodeWithTag("ChiefPinnedConversation").assertContentDescriptionEquals("Chief, Working")
        compose.onNodeWithTag("ChiefPinnedUnread",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithTag("ChiefConversation:build").assertContentDescriptionEquals("Build, Preview, 1 unread").performClick()
        compose.onNodeWithTag("ChiefTranscript").assertIsDisplayed()
        compose.runOnUiThread { assertEquals("build",computer.selected?.id);assertEquals(0,api.writes) }
    }

    @Test fun searchingControlsPinnedInvitationAndRetainsQueryAcrossTranscriptNavigation() {
        mount();ready()
        compose.onNodeWithTag("ChiefSearch").performTextInput("Chief")
        compose.onNodeWithTag("ChiefPinnedOnlyInvitation").assertExists()
        compose.onNodeWithTag("ChiefConversation:build").assertDoesNotExist()
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.onNodeWithTag("ChiefBack").performClick()
        compose.onNodeWithTag("ChiefSearch").assertTextContains("Chief").performTextClearance()
        compose.onNodeWithTag("ChiefSearch").performTextInput("Build")
        compose.onNodeWithTag("ChiefPinnedConversation").assertDoesNotExist()
        compose.onNodeWithTag("ChiefPinnedOnlyInvitation").assertDoesNotExist()
        compose.onNodeWithTag("ChiefConversation:build").assertExists()
        compose.onNodeWithTag("ChiefSearch").performTextClearance()
        compose.onNodeWithTag("ChiefSearch").performTextInput("missing")
        compose.onNodeWithTag("ChiefPinnedConversation").assertDoesNotExist()
        compose.onAllNodesWithText("No matching conversations").assertCountEquals(0)
        compose.onNodeWithTag("ChiefConversations").assertExists()
    }

    @Test fun toolbarAndPullRefreshUseTheLiveOwnerAndCachedRowsSurviveReadErrors() {
        mount();ready()
        compose.runOnUiThread { api.changes.trySend(Result.failure(java.io.IOException("socket lost"))) }
        compose.waitUntil(10_000) { computer.home.state.value.error=="socket lost" }
        compose.onNodeWithTag("ChiefPinnedConversation").assertExists()
        compose.onAllNodesWithText("socket lost").assertCountEquals(0)
        compose.runOnUiThread { api.rows = listOf(row("chief","Chief","Fresh",pinned=true)) }
        compose.onNodeWithTag("ChiefHomeRefresh").assertContentDescriptionEquals("Refresh Chief conversations").performClick()
        compose.waitUntil(10_000) { computer.home.state.value.pinned?.preview=="Fresh" }
        fun avatarPixels():IntArray {
            val bitmap=compose.onNodeWithTag("ChiefPinnedAvatar",useUnmergedTree=true).captureToImage().asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888,false)
            return IntArray(bitmap.width*bitmap.height).also { bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height);bitmap.recycle() }
        }
        compose.waitForIdle();val before=avatarPixels()
        val requests = api.loads
        compose.runOnUiThread { api.rows = listOf(row("chief","Chief","Pulled",pinned=true)) }
        compose.onNodeWithTag("ChiefConversations").performTouchInput { swipeDown(durationMillis=1_000) }
        compose.waitUntil(10_000) { api.loads>requests && computer.home.state.value.pinned?.preview=="Pulled" }
        compose.runOnUiThread { assertEquals(0,api.writes) }
        capture("chief-home-pull-refresh")
        val after=avatarPixels();assertEquals(before.size,after.size)
        assertEquals("Immediate refresh must dismiss its indicator without covering the avatar",0,before.indices.count { before[it]!=after[it] })
    }

    @Test fun initialLoadingFailureEmptyAndMacStartupFallbackRemainDistinct() {
        api.gate = CompletableDeferred();api.failure=java.io.IOException("Startup unavailable");mount()
        compose.onNodeWithText("Loading Chief").assertIsDisplayed()
        compose.runOnUiThread { api.gate!!.complete(Unit) }
        compose.waitUntil(10_000) { computer.home.state.value.error!=null }
        compose.onNodeWithText("Chief unavailable").assertIsDisplayed()
        compose.onNodeWithText("Startup unavailable").assertIsDisplayed()
        compose.runOnUiThread { api.failure=null;api.rows=emptyList() }
        compose.onNodeWithTag("ChiefHomeRefresh").performClick()
        compose.waitUntil(10_000) { !computer.home.state.value.loading && computer.home.state.value.error==null }
        compose.onNodeWithText("No Chief conversations").assertIsDisplayed()
        compose.onNodeWithText("Start a conversation from Chief on your Mac.").assertIsDisplayed()
        compose.runOnUiThread { api.startFailure="Chief could not start" }
        compose.onNodeWithTag("ChiefHomeRefresh").performClick()
        compose.waitUntil(10_000) { computer.home.state.value.error=="Chief could not start" }
        compose.onNodeWithTag("ChiefPinnedConversation").assertDoesNotExist()
        compose.runOnUiThread { api.startFailure=null;api.started=true }
        compose.onNodeWithTag("ChiefHomeRefresh").performClick();ready()
        compose.onNodeWithTag("ChiefPinnedOnlyInvitation").assertExists()
        capture("chief-home-started-empty-fallback")
    }

    @Test fun sourceCatalogLabelsRenderAcrossNineLocalesAndLargeTextLightAndRtlProfiles() {
        api.rows = listOf(row("chief","Chief",unread=1,pinned=true),row("build","Build","Preview"));mount();ready()
        for (language in listOf("en","ja","de","es","fr","ar","ko","zh-Hans","zh-Hant")) {
            compose.runOnUiThread { locale=language };compose.waitForIdle()
            compose.onNodeWithText(label(R.string.chief_home_title)).assertIsDisplayed()
            compose.onNodeWithTag("ChiefSearch").assertContentDescriptionEquals(label(R.string.chief_home_search))
            compose.onNodeWithTag("ChiefPinnedConversation").assertContentDescriptionEquals("Chief, "+label(R.string.chief_home_unread_one))
        }
        for ((language,big,bright) in listOf(Triple("en",1f,false),Triple("ja",2f,true),Triple("ar",2f,false))) {
            compose.runOnUiThread { locale=language;scale=big;light=bright };compose.waitForIdle()
            compose.onNodeWithTag("ChiefPinnedConversation").assertIsDisplayed()
            compose.onNodeWithTag("ChiefPinnedPreview",useUnmergedTree=true).getUnclippedBoundsInRoot().let {
                assertTrue("180-point preview maximum",(it.right-it.left).value<=180.1f)
            }
            if (language=="ar") for (tag in listOf("ChiefRecentTitle:build","ChiefRecentPreview:build")) {
                val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNodeWithTag(tag,useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                val layout=layouts.single()
                assertTrue("Actual English glyphs stay beside the RTL leading edge: $tag",layout.getBoundingBox(0).left>layout.size.width/2f)
                assertEquals("Last English character reaches the leading edge",layout.size.width.toFloat(),layout.getBoundingBox(layout.layoutInput.text.length-1).right,1f)
            }
            capture("chief-home-$language-$big")
        }
        compose.runOnUiThread { assertEquals(0,api.writes) }
    }

    private inner class Api : NativeChiefApi {
        var rows=emptyList<NativeChiefConversation>();var failure:Exception?=null;var gate:CompletableDeferred<Unit>?=null
        var started=false;var startFailure:String?=null
        @Volatile var loads=0
        var writes=0
        val changes=Channel<Result<NativeChiefSnapshot>>(Channel.UNLIMITED)
        override suspend fun conversations(afterCursor:String?,waitForChange:Boolean):NativeChiefSnapshot {
            if (waitForChange) return changes.receive().getOrThrow()
            loads++;gate?.await();failure?.let { throw it };return NativeChiefSnapshot(rows,"home-$loads")
        }
        override suspend fun status()=NativeChiefStatus(started,startFailure,null,"chief","Chief","","",0,rows.size.toLong(),emptyList(),"chief")
        override suspend fun read(conversation:String?,beforeSeq:ULong?,limit:Int,afterSeq:ULong?,afterCursor:String?,waitForChange:Boolean):NativeChiefRead {
            if (waitForChange) awaitCancellation()
            return NativeChiefRead(conversation?:"chief",false,emptyList(),false,0uL,0,0uL,"read")
        }
        override suspend fun markRead(conversation:String?,upToSeq:ULong)=NativeChiefMarked(conversation?:"chief",upToSeq,true)
        override suspend fun send(text:String,conversation:String?,from:String?,startTurn:Boolean,clientMessageId:String?):NativeChiefSent { writes++;error("Unexpected write") }
        override suspend fun stop(conversation:String?):NativeChiefStopped { writes++;error("Unexpected write") }
        override suspend fun approve(conversation:String?,request:String?,choice:String):NativeChiefApproved { writes++;error("Unexpected write") }
        override suspend fun answer(conversation:String?,message:String?,choices:List<String>,text:String?):NativeChiefAnswered { writes++;error("Unexpected write") }
        override suspend fun open(windowId:String?,workspaceId:String?):NativeChiefOpened { writes++;error("Unexpected write") }
    }
}
