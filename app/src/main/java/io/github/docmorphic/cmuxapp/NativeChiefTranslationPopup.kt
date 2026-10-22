package io.github.docmorphic.cmuxapp

import android.graphics.Region
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import kotlin.math.ceil
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.min
import kotlin.math.roundToInt

internal data class NativeChiefTranslationPopupProbe(val anchor: Rect?, val body: Rect?, val side: String?, val backdrop: Boolean)
internal val ChiefTranslationPopupProbe = SemanticsPropertyKey<() -> NativeChiefTranslationPopupProbe>("ChiefTranslationPopupProbe")

private class TranslationPopoverShape(private val side: NativeChiefTranslationPopoverGeometry.Side,
    private val arrow: Double) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val margin=13.dp.toPx();val radius=min(36.dp.toPx(),min(size.width-2*margin,size.height-2*margin)/2).coerceAtLeast(0f)
        val box=Rect(margin,margin,size.width-margin,size.height-margin)
        val body=Path().apply { addRoundRect(RoundRect(box,CornerRadius(radius))) }
        val triangle=Path()
        val center=(arrow.toFloat()*this.density)
        // Smooth the tip and the two joins; the shared edge is removed by the path union.
        fun add(a: Offset,b: Offset,tip: Offset) {
            triangle.moveTo(a.x,a.y)
            triangle.cubicTo(a.x+(tip.x-a.x)*.55f,a.y+(tip.y-a.y)*.15f,
                tip.x-(b.x-a.x)*.08f,tip.y-(b.y-a.y)*.08f,tip.x,tip.y)
            triangle.cubicTo(tip.x+(b.x-a.x)*.08f,tip.y+(b.y-a.y)*.08f,
                b.x+(tip.x-b.x)*.55f,b.y+(tip.y-b.y)*.15f,b.x,b.y)
            triangle.close()
        }
        when(side) {
            NativeChiefTranslationPopoverGeometry.Side.BELOW -> add(Offset(box.left+center-margin,box.top),Offset(box.left+center+margin,box.top),Offset(box.left+center,0f))
            NativeChiefTranslationPopoverGeometry.Side.ABOVE -> add(Offset(box.left+center+margin,box.bottom),Offset(box.left+center-margin,box.bottom),Offset(box.left+center,size.height))
            NativeChiefTranslationPopoverGeometry.Side.LEFT -> add(Offset(box.right,box.top+center-margin),Offset(box.right,box.top+center+margin),Offset(size.width,box.top+center))
            NativeChiefTranslationPopoverGeometry.Side.RIGHT -> add(Offset(box.left,box.top+center+margin),Offset(box.left,box.top+center-margin),Offset(0f,box.top+center))
        }
        Outline.Generic(Path.combine(PathOperation.Union,body,triangle))
    }
}

/** A focusable native popup owns Back/outside dismissal; main-window pixels are sampled
 * separately, so the material never samples its own controls or captures another app. */
@Composable
internal fun NativeChiefTranslationPopup(ui: NativeChiefTranslationUi, backdropRevision: NativeChiefTranscriptState) {
    val request=ui.sheet ?: return
    val density=LocalDensity.current
    val direction=LocalLayoutDirection.current
    val host=LocalView.current
    val configuration=LocalConfiguration.current
    val dark=MaterialTheme.colorScheme.background.luminance()<.5f
    val ink=if(dark) Color.White else Color.Black
    val locale=configuration.locales[0]
    val points=with(density) { 17.sp.toDp().value }
    val progress=((points-17)/11).coerceIn(0f,1f)
    val advance=22+12*progress
    val topInset=22+(92f/3)*progress
    val promptGap=18+progress/3
    val labelHeight=ceil(points*1.193359375*density.density).toFloat()/density.density
    val buttonHeight=maxOf(48f,labelHeight+74f/3)
    val style=nativeChiefTextStyle(17).copy(lineHeight=with(density) { advance.dp.toSp() },
        lineHeightStyle=LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.Both))
    val titles=if(request is NativeChiefTranslationSheet.Choice)listOf(stringResource(R.string.chief_translation_message),stringResource(R.string.chief_translation_conversation))
        else (request as NativeChiefTranslationSheet.From).languages.map { chiefTranslationLanguageName(it,locale) }
    val measurer=rememberTextMeasurer()
    val widest=titles.maxOfOrNull { measurer.measure(it,style.copy(fontWeight=FontWeight.Medium),maxLines=1,softWrap=false).size.width/density.density } ?: 0f
    val minimum=if(request is NativeChiefTranslationSheet.Choice)240+126*progress else 240f
    val width=maxOf(minimum,widest+32+2*(12+9*progress)).toInt().toFloat()
        .coerceAtMost((configuration.screenWidthDp-26).coerceAtLeast(1).toFloat()).dp
    val maxHeight=(configuration.screenHeightDp-60).coerceAtLeast(1).dp
    var origin by remember { mutableStateOf(Offset.Zero) }
    var source by remember(ui,request) { mutableStateOf(request.anchor) }
    LaunchedEffect(ui,request,configuration,backdropRevision) {
        withFrameNanos { };withFrameNanos { }
        source=ui.anchor(request)
    }
    val parentOffset=source?.topLeft?.minus(origin) ?: Offset.Zero
    val parentSize=source?.size ?: Size(1f,1f)
    var pose by remember(ui,request) { mutableStateOf<NativeChiefTranslationPopoverGeometry.Pose?>(null) }
    var backdrop by remember(ui,request) { mutableStateOf<NativeChiefTranslationBackdrop?>(null) }
    val provider=remember(ui,request,density,host,source) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect,windowSize: IntSize,layoutDirection: LayoutDirection,popupContentSize: IntSize): IntOffset {
            val scale=density.density.toDouble();val margin=13*scale
            val screen=android.graphics.Rect();host.getWindowVisibleDisplayFrame(screen)
            val onScreen=IntArray(2);val inWindow=IntArray(2);host.getLocationOnScreen(onScreen);host.getLocationInWindow(inWindow)
            val windowX=onScreen[0]-inWindow[0];val windowY=onScreen[1]-inWindow[1]
            val bounds=if(screen.width()>0 && screen.height()>0) NativeChiefTranslationPopoverGeometry.Rect((screen.left-windowX)/scale,(screen.top-windowY)/scale,screen.width()/scale,screen.height()/scale)
                else NativeChiefTranslationPopoverGeometry.Rect(0.0,0.0,windowSize.width/scale,windowSize.height/scale)
            val anchor=if(source!=null) NativeChiefTranslationPopoverGeometry.Rect(anchorBounds.left/scale,anchorBounds.top/scale,anchorBounds.width/scale,anchorBounds.height/scale)
                else NativeChiefTranslationPopoverGeometry.Rect(bounds.centerX,bounds.centerY,1.0,1.0)
            val next=NativeChiefTranslationPopoverGeometry.place(bounds,anchor,
                (popupContentSize.width-2*margin)/scale,(popupContentSize.height-2*margin)/scale)
            if(pose!=next)pose=next
            return IntOffset(((next.body.x-13)*scale).roundToInt(),((next.body.y-13)*scale).roundToInt())
        }
    } }
    LaunchedEffect(ui,request,pose,backdropRevision) {
        val placed=pose ?: return@LaunchedEffect
        // Let the dismissed menu and normal backing row reach the Activity surface.
        withFrameNanos { };withFrameNanos { }
        val box=placed.body;val scale=density.density
        val wanted=Rect(((box.x-45)*scale).toFloat(),((box.y-45)*scale).toFloat(),
            ((box.right+45)*scale).toFloat(),((box.bottom+45)*scale).toFloat())
        val captured=chiefTranslationBackdrop(host.context,wanted)
        if(ui.sheet===request)backdrop=captured
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin=it.positionInWindow();source=ui.anchor(request) },contentAlignment=AbsoluteAlignment.TopLeft) {
        Box(Modifier.absoluteOffset { IntOffset(parentOffset.x.roundToInt(),parentOffset.y.roundToInt()) }
            .requiredSize(with(density) { parentSize.width.toDp() },with(density) { parentSize.height.toDp() })) {
            Popup(popupPositionProvider=provider,onDismissRequest=ui::dismiss,
                properties=PopupProperties(focusable=true,usePlatformDefaultWidth=false,clippingEnabled=false)) {
                // A native popup creates a new AndroidComposeView. Preserve the caller's
                // font scaling and direction instead of that window's default locals.
                CompositionLocalProvider(LocalDensity provides density,LocalLayoutDirection provides direction) {
                val placed=pose
                val shape=remember(ui,request,placed) { TranslationPopoverShape(placed?.side ?: NativeChiefTranslationPopoverGeometry.Side.BELOW,placed?.arrow ?: 120.0) }
                val tint=if(dark)Color(0xA61A201E)else Color(0xC9FAFAFA)
                Box(Modifier.width(width+26.dp).clip(shape).testTag(if(request is NativeChiefTranslationSheet.From)"ChiefTranslationFrom" else "ChiefTranslationChoice")
                    .semantics { paneTitle=if(request is NativeChiefTranslationSheet.From)"Translate From" else "Translate";isTraversalGroup=true
                        if(BuildConfig.DEBUG)this[ChiefTranslationPopupProbe]={
                            val p=pose;val scale=density.density
                            NativeChiefTranslationPopupProbe(source,p?.body?.let { Rect((it.x*scale).toFloat(),(it.y*scale).toFloat(),(it.right*scale).toFloat(),(it.bottom*scale).toFloat()) },p?.side?.name,backdrop!=null)
                        }
                    }.pointerInput(ui,request,shape) {
                        awaitEachGesture {
                            val down=awaitFirstDown(requireUnconsumed=false)
                            val outline=shape.createOutline(Size(size.width.toFloat(),size.height.toFloat()),LayoutDirection.Ltr,this@pointerInput) as Outline.Generic
                            val region=Region().apply { setPath(outline.path.asAndroidPath(),Region(0,0,size.width,size.height)) }
                            if(!region.contains(down.position.x.toInt(),down.position.y.toInt())) {
                                down.consume();if(waitForUpOrCancellation()!=null)ui.dismiss()
                            }
                        }
                    }) {
                    Box(Modifier.matchParentSize().graphicsLayer { this.shape=shape;clip=true }) {
                        val sample=backdrop;val p=placed
                        if(sample!=null && p!=null) Layout(modifier=Modifier.matchParentSize(),content={
                            Canvas(Modifier.graphicsLayer {
                                renderEffect=BlurEffect(16*density.density,16*density.density,TileMode.Clamp)
                            }) {
                                val x=sample.frame.left-((p.body.x-45)*density.density).toFloat()
                                val y=sample.frame.top-((p.body.y-45)*density.density).toFloat()
                                drawImage(sample.image,topLeft=Offset(x,y))
                            }
                        }) { children,constraints ->
                            val pad=32.dp.roundToPx()
                            val child=children.single().measure(Constraints.fixed(constraints.maxWidth+2*pad,constraints.maxHeight+2*pad))
                            layout(constraints.maxWidth,constraints.maxHeight) { child.place(-pad,-pad) }
                        }
                        Box(Modifier.matchParentSize().background(if(sample==null)tint.copy(alpha=1f)else tint))
                    }
                    Box(Modifier.matchParentSize().border(.5.dp,if(dark)Color.White.copy(alpha=.14f)else Color.Black.copy(alpha=.09f),shape))
                    Column(Modifier.padding(13.dp).width(width).heightIn(max=maxHeight).verticalScroll(rememberScrollState())
                        .testTag("ChiefTranslationPlatter").padding(start=16.dp,end=16.dp,top=topInset.dp,bottom=16.dp)) {
                        TranslationPopoverLabel(if(request is NativeChiefTranslationSheet.Choice)stringResource(R.string.chief_translation_prompt)else stringResource(R.string.chief_translation_from),
                            points,advance,style,ink,request is NativeChiefTranslationSheet.Choice,
                            Modifier.fillMaxWidth().padding(horizontal=14.dp).padding(bottom=promptGap.dp).testTag("ChiefTranslationPrompt")
                                .then(if(request is NativeChiefTranslationSheet.From)Modifier.semantics { heading() }else Modifier),
                            fill=true,center=request is NativeChiefTranslationSheet.From)
                        @Composable fun Action(title: String,tag: String,click: () -> Unit) {
                            Box(Modifier.fillMaxWidth().height(buttonHeight.dp).clip(RoundedCornerShape(50)).background(if(dark)Color.White.copy(alpha=.11f)else Color.Black.copy(alpha=.06f),RoundedCornerShape(50))
                                .testTag(tag).clickable(role=Role.Button,onClick=click).padding(horizontal=(12+9*progress).dp),contentAlignment=Alignment.Center) {
                                TranslationPopoverLabel(title,points,advance,style.copy(fontWeight=FontWeight.Medium),ink,false,
                                    Modifier.testTag("$tag:Label"),fill=false,center=true)
                            }
                        }
                        if(request is NativeChiefTranslationSheet.Choice) {
                            Action(stringResource(R.string.chief_translation_message),"ChiefTranslateMessage"){ui.choose(request,false)}
                            Spacer(Modifier.height(8.dp))
                            Action(stringResource(R.string.chief_translation_conversation),"ChiefTranslateConversation"){ui.choose(request,true)}
                        } else if(request is NativeChiefTranslationSheet.From) {
                            request.languages.forEachIndexed { index,language ->
                                if(index>0)Spacer(Modifier.height(8.dp))
                                Action(chiefTranslationLanguageName(language,locale),"ChiefTranslateFrom:${language.tag}"){ui.choose(request,request.conversation,language)}
                            }
                        }
                    }
                }
                }
            }
        }
    }
}


/** Match the observed UILabel frame/baseline while retaining Android's own typeface.
 * Text layout supplies line count; a platform font's ascent must not change the popup geometry. */
@Composable
private fun TranslationPopoverLabel(text: String,points: Float,advance: Float,style: androidx.compose.ui.text.TextStyle,
    color: Color,wrap: Boolean,modifier: Modifier,fill: Boolean,center: Boolean) {
    val density=LocalDensity.current
    val measurer=rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val raw=measurer.measure(text,style,maxLines=if(wrap)Int.MAX_VALUE else 1,softWrap=wrap)
        val fitted=if(!wrap && raw.size.width>constraints.maxWidth)style.copy(fontSize=style.fontSize*(constraints.maxWidth.toFloat()/raw.size.width))else style
        Layout(content={ Text(text,Modifier.testTag("ChiefTranslationLabelText"),style=fitted,color=color,
            maxLines=if(wrap)Int.MAX_VALUE else 1,softWrap=wrap,textAlign=if(center)TextAlign.Center else TextAlign.Left) }) { children,limits ->
            val metrics=measurer.measure(text,fitted,maxLines=if(wrap)Int.MAX_VALUE else 1,softWrap=wrap,constraints=Constraints(maxWidth=limits.maxWidth))
            val child=children.single().measure(Constraints(maxWidth=limits.maxWidth))
            val natural=points*1.193359375+(metrics.lineCount-1)*advance
            val h=ceil(natural*density.density).toInt()
            val baseline=((points*.9521484375+(h/density.density-natural)/2)*density.density).roundToInt()
            val w=if(fill)limits.maxWidth else metrics.size.width
            layout(w,h,mapOf(FirstBaseline to baseline)) { child.place(if(center)(w-child.width)/2 else 0,baseline-child[FirstBaseline]) }
        }
    }
}
