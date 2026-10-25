package io.github.docmorphic.cmuxapp

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Actual production question windows, retained computer and Activity lifecycle; isolated Mac API. */
class NativeChiefQuestionRuntimeTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(instrument)
    private lateinit var scope:CoroutineScope
    private lateinit var computer:NativeChiefComputerSession
    private var scenario:ActivityScenario<NativeChiefLifecycleTestActivity>?=null
    private var current=true
    private val api=Api()
    private var oldLocales:android.os.LocaleList?=null
    private var oldFont:String?=null
    private fun question(id:String)=NativeChiefQuestion(id,NativeChiefQuestion.Source(NativeChiefQuestion.Harness.CHIEF,"main",null,null,null,null,null),listOf(
        NativeChiefQuestion.Item("region","Region","Where $id?",listOf(NativeChiefQuestion.Option("us","US","Default region",
            NativeChiefQuestion.Preview("PREVIEW MUST STAY HIDDEN",NativeChiefQuestion.Preview.Format.MONOSPACE)),NativeChiefQuestion.Option("ca","Canada",null,null)),false,true),
        NativeChiefQuestion.Item("checks","Checks","Which checks $id?",listOf(NativeChiefQuestion.Option("unit","Unit tests","Focused tests",null),
            NativeChiefQuestion.Option("integration","Integration tests",null,null)),true,false)),NativeChiefQuestion.State.PENDING,null,null)
    private fun message(id:String,seq:ULong,q:NativeChiefQuestion)=NativeChiefMessage(id,seq,"chief","Chief","chief","",Instant.parse("2026-10-20T12:00:00Z"),
        emptyList(),listOf(NativeChiefPart("question",null,null,q)),null,null)
    @Before fun start() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        oldLocales=instrument.targetContext.getSystemService(android.app.LocaleManager::class.java).applicationLocales
        oldFont=device.executeShellCommand("settings get system font_scale").trim()
        instrument.runOnMainSync {
            scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            computer=NativeChiefComputerSession(scope,api) { current }
            api.rows=listOf(message("a",1uL,question("a")),message("b",2uL,question("b")))
            NativeChiefLifecycleTestActivity.computer=computer
        }
        scenario=ActivityScenario.launch(NativeChiefLifecycleTestActivity::class.java)
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.messages?.size==2 }
    }
    @After fun close() {
        try {
            oldLocales?.let { locales -> instrument.runOnMainSync {
                instrument.targetContext.getSystemService(android.app.LocaleManager::class.java).applicationLocales=locales
            } }
            oldFont?.let { device.executeShellCommand(if(it=="null") "settings delete system font_scale" else "settings put system font_scale $it") }
            instrument.waitForIdleSync()
            assertEquals(oldLocales,instrument.targetContext.getSystemService(android.app.LocaleManager::class.java).applicationLocales)
        } finally {
            scenario?.close()
            if(::scope.isInitialized) instrument.runOnMainSync { computer.close();scope.cancel() }
            NativeChiefLifecycleTestActivity.computer=null
            NativeChiefLifecycleTestActivity.darkThemeOverride=null
        }
    }
    private fun open(id:String="a",expand:Boolean=true) {
        compose.onNodeWithTag("ChiefAnswer:$id").performScrollTo().performClick()
        compose.onNodeWithTag("ChiefQuestionSheet").assertExists()
        if(expand) this.expand()
    }
    private fun expand() {
        // Lift the actual sheet by its upper edge. Source exposes medium/large detents.
        compose.waitForIdle()
        val box=compose.onNodeWithTag("ChiefQuestionDragHandle", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        if(box.top>device.displayHeight*.25) device.swipe(box.center.x.toInt(),box.center.y.toInt(),box.center.x.toInt(),100,25)
        compose.waitForIdle()
    }
    private fun unit() { compose.onNodeWithTag("ChiefQuestionOption:checks:unit").performScrollTo().performClick() }
    private fun capture(name:String) {
        compose.waitForIdle()
        val dir=java.io.File(instrument.targetContext.getExternalFilesDir(null),"rich-composer").apply { mkdirs() }
        val bitmap=instrument.uiAutomation.takeScreenshot()
        java.io.File(dir,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        java.io.File(dir,"$name.txt").writeText(compose.onAllNodesWithTag("ChiefQuestionSheet").fetchSemanticsNodes().singleOrNull()?.let {
            compose.onNodeWithTag("ChiefQuestionSheet").printToString()
        }?:"Sheet absent")
    }
    @Test fun pendingAnswerKeepsItsPayloadAllowsEditingAndCancelAndCannotDismissAnotherQuestion() {
        open();unit()
        compose.onNodeWithTag("ChiefQuestionOther:region").performScrollTo().performTextInput(" Canada ")
        compose.onNodeWithTag("ChiefQuestionSubmit").performClick()
        compose.waitUntil(10_000) { api.answers.size==1 }
        compose.onNodeWithTag("ChiefQuestionSubmit").assertIsNotEnabled()
        compose.onNodeWithTag("ChiefQuestionOther:region").assertIsEnabled().performTextInput(" later")
        compose.onNodeWithTag("ChiefQuestionOption:checks:integration").performScrollTo().assertIsEnabled().performClick()
        device.pressBack();compose.waitForIdle();device.pressBack();compose.waitForIdle()
        compose.onNodeWithTag("ChiefQuestionSheet").assertExists()
        assertEquals(Call("a",listOf("checks\u001funit"),"Canada"),api.answers.single())
        compose.onNodeWithText("Cancel").assertIsEnabled().performClick()
        compose.onNodeWithTag("ChiefQuestionSheet").assertDoesNotExist()
        open("b")
        compose.onNodeWithTag("ChiefQuestionOther:region").performScrollTo().performTextInput("B draft")
        instrument.runOnMainSync { api.replies.trySend(Result.success(Unit)) }
        compose.waitUntil(10_000) { "a" !in computer.transcript()!!.state.value.deciding }
        compose.onNode(hasText("Where b?") and hasAnyAncestor(hasTestTag("ChiefQuestionSheet"))).assertExists()
        compose.onNodeWithTag("ChiefQuestionOther:region").assertTextContains("B draft")
        assertEquals(1,api.answers.size)
        capture("chief-question-replacement-survives")
    }
    @Test fun failedAnswerKeepsPendingEditsAndRetryCapturesTheEditedPayload() {
        open();unit();compose.onNodeWithTag("ChiefQuestionSubmit").performClick()
        compose.waitUntil(10_000) { api.answers.size==1 }
        compose.onNodeWithTag("ChiefQuestionOther:region").performScrollTo().performTextInput("Canada")
        instrument.runOnMainSync { api.replies.trySend(Result.failure(java.io.IOException("Try once more"))) }
        compose.onNodeWithTag("ChiefQuestionError").assertTextContains("Try once more")
        compose.onNodeWithTag("ChiefQuestionOther:region").assertTextContains("Canada")
        compose.onNodeWithTag("ChiefQuestionSubmit").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { api.answers.size==2 }
        assertEquals(Call("a",listOf("region\u001fus","checks\u001funit"),null),api.answers[0])
        assertEquals(Call("a",listOf("checks\u001funit"),"Canada"),api.answers[1])
        instrument.runOnMainSync { api.replies.trySend(Result.success(Unit)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefQuestionSheet").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Answered in chat").assertExists()
    }
    @Test fun aDismissedSheetsFailureCannotAppearInAnotherSheetOrTheTranscript() {
        open();unit();compose.onNodeWithTag("ChiefQuestionSubmit").performClick()
        compose.waitUntil(10_000) { api.answers.size==1 }
        compose.onNodeWithTag("ChiefQuestionCancel").performClick();open("b")
        instrument.runOnMainSync { api.replies.trySend(Result.failure(java.io.IOException("Old request failed"))) }
        compose.waitUntil(10_000) { "a" !in computer.transcript()!!.state.value.deciding }
        compose.onNodeWithTag("ChiefQuestionSheet").assertExists()
        compose.onNodeWithTag("ChiefQuestionError").assertDoesNotExist()
        compose.onAllNodesWithText("Old request failed").assertCountEquals(0)
        assertEquals(1,api.answers.size)
    }
    @Test fun activityRecreationKeepsTheEditedFormAndInFlightAnswerWithItsOriginalOwner() {
        open();unit();compose.onNodeWithTag("ChiefQuestionSubmit").performClick()
        compose.waitUntil(10_000) { api.answers.size==1 }
        compose.onNodeWithTag("ChiefQuestionOther:region").performScrollTo().performTextInput("Unsent edit")
        val session=computer.transcript();scenario!!.recreate()
        compose.onNodeWithTag("ChiefQuestionSheet").assertExists()
        compose.onNodeWithTag("ChiefQuestionOther:region").assertTextContains("Unsent edit")
        compose.onNodeWithTag("ChiefQuestionSubmit").assertIsNotEnabled()
        assertSame(session,computer.transcript());assertEquals(1,api.answers.size);assertEquals(0,api.cancellations)
        instrument.runOnMainSync { api.replies.trySend(Result.failure(java.io.IOException("After recreation"))) }
        compose.onNodeWithTag("ChiefQuestionError").assertTextContains("After recreation")
        compose.onNodeWithTag("ChiefQuestionSubmit").assertIsEnabled()
        capture("chief-question-recreated")
    }
    @Test fun liveQuestionUpdatesDoNotResetTheSheetAndRetirementRejectsCapturedSubmit() {
        open();unit()
        compose.onNodeWithTag("ChiefQuestionOther:region").performScrollTo().performTextInput("Original answer")
        val captured=compose.onNodeWithTag("ChiefQuestionSubmit").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        instrument.runOnMainSync {
            api.rows=api.rows.map { row -> if(row.id!="a") row else row.copy(partPayloads=row.partPayloads.map { part ->
                part.copy(question=part.question!!.copy(items=part.question.items.map { it.copy(prompt="Changed prompt") }))
            }) }
            api.changes.trySend(api.snapshot())
        }
        compose.waitUntil(10_000) { computer.transcript()!!.state.value.messages.first().partPayloads.first().question!!.items.first().prompt=="Changed prompt" }
        compose.onNodeWithText("Where a?").assertExists()
        compose.onNodeWithTag("ChiefQuestionOther:region").assertTextContains("Original answer")
        compose.onNodeWithTag("ChiefQuestionSubmit").performClick()
        compose.waitUntil(10_000) { api.answers.size==1 }
        assertEquals("Original answer",api.answers.single().text)
        instrument.runOnMainSync { current=false;computer.setActive(false);captured() }
        compose.waitForIdle()
        compose.onNodeWithTag("ChiefQuestionSheet").assertDoesNotExist()
        assertEquals(1,api.answers.size);assertTrue(api.cancellations>0)
    }
    @Test fun sourceSheetDoesNotRenderOptionPreviewsAndExplicitCancelDoesNotAnswerTheMac() {
        open()
        compose.onAllNodesWithText("PREVIEW MUST STAY HIDDEN").assertCountEquals(0)
        compose.onNodeWithTag("ChiefQuestionOption:region:us").assertIsSelected()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("ChiefQuestionSheet").assertDoesNotExist()
        assertTrue(api.answers.isEmpty())
    }
    @Test fun questionLabelsFollowNineActualAppLocalesAndMediumLargeTextAndRtlWindows() {
        open(expand=false);compose.waitForIdle()
        capture("chief-question-medium")
        val grip=compose.onNodeWithTag("ChiefQuestionDragHandle", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        assertTrue("Default source medium detent",grip.center.y>device.displayHeight*.35 && grip.center.y<device.displayHeight*.65)
        expand()
        val manager=instrument.targetContext.getSystemService(android.app.LocaleManager::class.java)
        val records=org.json.JSONArray()
        for(language in listOf("en","ja","de","es","fr","ar","ko","zh-Hans","zh-Hant")) {
            val large=language in setOf("ja","ar")
            instrument.runOnMainSync {
                NativeChiefLifecycleTestActivity.darkThemeOverride=language!="ja"
                manager.applicationLocales=android.os.LocaleList.forLanguageTags(language)
            }
            device.executeShellCommand("settings put system font_scale "+if(large) "2.0" else "1.0")
            // En may already be the system locale, so recreate for the explicit style profile.
            if(language=="en") scenario!!.recreate()
            var resolved="";var expectedTitle="";var expectedCancel="";var expectedOther=""
            compose.waitUntil(15_000) {
                runCatching { scenario!!.onActivity { activity ->
                    val config=activity.resources.configuration;resolved=config.locales[0].toLanguageTag()
                    expectedTitle=activity.getString(R.string.chief_question_title);expectedCancel=activity.getString(R.string.chief_question_cancel)
                    expectedOther=activity.getString(R.string.chief_question_other)
                } }.isSuccess && java.util.Locale.forLanguageTag(resolved).let {
                    val wanted=java.util.Locale.forLanguageTag(language)
                    it.language==wanted.language && (wanted.script.isEmpty() || it.script==wanted.script)
                } && runCatching { var actual=0f;scenario!!.onActivity { actual=it.resources.configuration.fontScale };actual==(if(large) 2f else 1f) }.getOrDefault(false)
            }
            compose.onNodeWithTag("ChiefQuestionTitle").assertTextEquals(expectedTitle)
            compose.onNodeWithTag("ChiefQuestionCancel").assertTextContains(expectedCancel)
            compose.onNodeWithTag("ChiefQuestionOther:region").assertContentDescriptionEquals(expectedOther)
            expand()
            records.put(org.json.JSONObject().put("requested",language).put("resolved",resolved).put("scale",if(large) 2 else 1)
                .put("title",expectedTitle).put("cancel",expectedCancel).put("other",expectedOther))
            if(language in setOf("en","ja","ar")) capture("chief-question-$language")
        }
        val dir=java.io.File(instrument.targetContext.getExternalFilesDir(null),"rich-composer").apply { mkdirs() }
        java.io.File(dir,"chief-question-locales.json").writeText(records.toString(2))
        compose.onNodeWithTag("ChiefQuestionCancel").performClick();assertTrue(api.answers.isEmpty())
    }
    private data class Call(val message:String,val choices:List<String>,val text:String?)
    private inner class Api:NativeChiefApi {
        var rows=emptyList<NativeChiefMessage>()
        val answers=CopyOnWriteArrayList<Call>()
        val replies=Channel<Result<Unit>>(Channel.UNLIMITED)
        val changes=Channel<NativeChiefRead>(Channel.UNLIMITED)
        @Volatile var cancellations=0
        fun snapshot()=NativeChiefRead("chief-main",false,rows,false,0uL,0,rows.maxOfOrNull { it.seq },"read")
        override suspend fun conversations(afterCursor:String?,waitForChange:Boolean):NativeChiefSnapshot {
            if(waitForChange) awaitCancellation()
            return NativeChiefSnapshot(listOf(NativeChiefConversation("chief-main","Chief","Questions",0,true,false,Instant.EPOCH)),"home")
        }
        override suspend fun status()=NativeChiefStatus(true,null,null,"chief","Chief","","",0,1,emptyList(),"chief-main")
        override suspend fun read(conversation:String?,beforeSeq:ULong?,limit:Int,afterSeq:ULong?,afterCursor:String?,waitForChange:Boolean)=if(waitForChange) changes.receive() else snapshot()
        override suspend fun markRead(conversation:String?,upToSeq:ULong)=NativeChiefMarked("chief-main",upToSeq,true)
        override suspend fun answer(conversation:String?,message:String?,choices:List<String>,text:String?):NativeChiefAnswered {
            answers+=Call(message!!,choices.toList(),text)
            try { replies.receive().getOrThrow() } catch(e:CancellationException) { cancellations++;throw e }
            rows=rows.map { row -> if(row.id!=message) row else row.copy(partPayloads=row.partPayloads.map { part -> part.copy(question=part.question!!.copy(state=NativeChiefQuestion.State.CANCELLED)) }) }
            return NativeChiefAnswered("chief-main",message,choices)
        }
        override suspend fun send(text:String,conversation:String?,from:String?,startTurn:Boolean,clientMessageId:String?):NativeChiefSent=error("Unexpected send")
        override suspend fun stop(conversation:String?):NativeChiefStopped=error("Unexpected stop")
        override suspend fun approve(conversation:String?,request:String?,choice:String):NativeChiefApproved=error("Unexpected approval")
        override suspend fun open(windowId:String?,workspaceId:String?):NativeChiefOpened=error("Unexpected open")
    }
}
