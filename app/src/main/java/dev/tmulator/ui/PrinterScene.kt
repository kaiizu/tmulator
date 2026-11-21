package dev.tmulator.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import dev.tmulator.data.RuntimeState
import dev.tmulator.data.ListenerStatus
import dev.tmulator.ui.theme.Paper
import dev.tmulator.ui.theme.PrinterBlue
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

@Composable
private fun PrinterIndicator(icon: ImageVector, label: String, lit: Boolean, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, "$label ${if (lit) "on" else "off"}", Modifier.size(16.dp),
            tint = if (lit) Color(0xFFC7D1D9) else Color(0xFF69737B))
        Box(Modifier.size(width = 8.dp, height = 3.dp)
            .background(if (lit) color else Color(0xFF0D1012), CircleShape))
    }
}

private val FeedEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** A live receipt, emerging through the cutter of a small matte-black printer. */
@Composable
fun PrinterScene(
    runtime: RuntimeState,
    autoScroll: Boolean,
    onTest: () -> Unit,
    canTest: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = remember(runtime.preview) { runtime.preview?.asImageBitmap() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val feed = remember { Animatable(1f) }
    val tear = remember { Animatable(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var tearing by remember { mutableStateOf(false) }
    var tearDirection by remember { mutableFloatStateOf(1f) }
    var fullscreen by remember { mutableStateOf(false) }
    val latestRevision by rememberUpdatedState(runtime.previewRevision)
    val latestClear by rememberUpdatedState(onClear)
    val paperShape = remember(tearing) { ReceiptEdge(tearing) }
    var seenCut by remember { mutableLongStateOf(runtime.cutEventAt) }
    var seenJob by remember { mutableStateOf(runtime.activeJob?.id) }
    var hadImage by remember { mutableStateOf(image != null) }

    LaunchedEffect(runtime.previewRevision) {
        if (image == null) {
            hadImage = false
            fullscreen = false
            return@LaunchedEffect
        }
        val newJob = runtime.activeJob?.id?.let { it != seenJob } ?: false
        val shouldFeed = !hadImage || runtime.cutEventAt != seenCut || newJob
        seenCut = runtime.cutEventAt
        seenJob = runtime.activeJob?.id ?: seenJob
        hadImage = true
        if (shouldFeed) {
            scroll.scrollTo(0)
            feed.snapTo(0f)
        } else if (autoScroll && runtime.activeJob != null) {
            withFrameNanos { }
            scroll.animateScrollTo(scroll.maxValue, tween(480, easing = FeedEasing))
        }
        // A streamed preview can replace the bitmap mid-feed. Continue from the
        // current position instead of leaving a cancelled animation half-fed.
        if (feed.value < 1f) feed.animateTo(1f, tween(900, easing = FeedEasing))
    }
    fun tearReceipt(direction: Float, animate: Boolean = true) {
        if (tearing || image == null) return
        val revisionToClear = runtime.previewRevision
        fullscreen = false
        tearDirection = if (direction == 0f) 1f else sign(direction)
        tearing = true
        scope.launch {
            if (animate) tear.animateTo(1f, tween(420, easing = FeedEasing))
            // A receipt arriving during the gesture belongs to the next job.
            if (latestRevision == revisionToClear) latestClear()
            dragOffset = 0f
            tear.snapTo(0f)
            tearing = false
        }
    }
    val onTearUpdated by rememberUpdatedState<(Float) -> Unit>({ tearReceipt(it) })

    BoxWithConstraints(modifier) {
        val compact = maxHeight < 300.dp
        val slotTop = if (compact) 60.dp else 68.dp
        val paperTop = slotTop + 10.dp
        val paperHeight = if (image == null) (maxHeight - paperTop - 64.dp).coerceIn(100.dp, 192.dp)
            else (maxHeight - paperTop - 32.dp).coerceAtLeast(80.dp)
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height - 8.dp.toPx()
            val radius = CornerRadius(20.dp.toPx())
            drawRoundRect(Color(0xFF202427), size = Size(w, h), cornerRadius = radius)
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF2B3034), Color(0xFF202427)),
                endY = slotTop.toPx()), size = Size(w, h), cornerRadius = radius)
            drawRoundRect(Color(0xFF3B4248), Offset(0.5.dp.toPx(), 0.5.dp.toPx()),
                Size(w - 1.dp.toPx(), h - 1.dp.toPx()), radius, style = Stroke(1.dp.toPx()))
            // Equal side margins keep the cutter and paper centred on the housing.
            drawRoundRect(Color(0xFF101315), Offset(20.dp.toPx(), slotTop.toPx() - 3.dp.toPx()),
                Size(w - 40.dp.toPx(), 22.dp.toPx()), CornerRadius(6.dp.toPx()))
            drawRoundRect(Color(0xFF030405), Offset(24.dp.toPx(), slotTop.toPx() + 2.dp.toPx()),
                Size(w - 48.dp.toPx(), 12.dp.toPx()), CornerRadius(3.dp.toPx()))
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PrinterIndicator(Icons.Rounded.PowerSettingsNew, "Power", runtime.serviceRunning, PrinterBlue)
            PrinterIndicator(Icons.Rounded.Wifi, "Wi-Fi",
                runtime.network.enabled && runtime.network.status in listOf(ListenerStatus.LISTENING, ListenerStatus.CONNECTED), PrinterBlue)
            PrinterIndicator(Icons.Rounded.Bluetooth, "Bluetooth",
                runtime.bluetooth.enabled && runtime.bluetooth.status in listOf(ListenerStatus.LISTENING, ListenerStatus.CONNECTED), PrinterBlue)
            PrinterIndicator(Icons.Rounded.ReceiptLong, "Paper out", runtime.paperOut, StatusAmber)
            PrinterIndicator(Icons.Rounded.ErrorOutline, "Error", runtime.coverOpen ||
                runtime.network.status == ListenerStatus.ERROR || runtime.bluetooth.status == ListenerStatus.ERROR, StatusRed)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onTest, enabled = canTest, modifier = Modifier.size(32.dp)
                .background(Color(0xFF1B1F22), CircleShape).border(1.dp, Color(0xFF444C53), CircleShape)) {
                Icon(Icons.Rounded.Print, "Feed a test receipt", Modifier.size(16.dp), tint = Color(0xFFBCC6CE))
            }
        }

        // Clip at the slot, so translating the sheet reveals it from inside the housing.
        Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp).padding(top = paperTop)
            .height(paperHeight + 12.dp).drawWithContent {
                clipRect(left = -size.width, right = size.width * 2f,
                    bottom = size.height * 2f) { this@drawWithContent.drawContent() }
            }) {
            Box(Modifier.fillMaxWidth().height(paperHeight)
                .pointerInput(image != null, tearing) {
                    if (image != null && !tearing) detectTapGestures(onTap = { fullscreen = true })
                }
                .pointerInput(image != null, tearing) {
                    if (image != null && !tearing) detectHorizontalDragGestures(
                        onHorizontalDrag = { change, amount -> change.consume(); dragOffset += amount },
                        onDragEnd = {
                            if (abs(dragOffset) > minOf(90.dp.toPx(), size.width * 0.3f)) onTearUpdated(dragOffset)
                            else dragOffset = 0f
                        },
                        onDragCancel = { dragOffset = 0f },
                    )
                }
                .semantics {
                    if (image != null) {
                        onClick("Open receipt full screen") { fullscreen = true; true }
                        customActions = listOf(CustomAccessibilityAction("Tear off and clear receipt") {
                            tearReceipt(1f); true
                        })
                    }
                }
                .graphicsLayer {
                    translationX = dragOffset * 0.3f + tearDirection * tear.value * size.width * 1.4f
                    translationY = -(1f - feed.value) * (size.height + 16.dp.toPx()) + tear.value * size.height * 0.65f
                    rotationZ = dragOffset / size.width * 8f + tearDirection * tear.value * 18f
                    alpha = 1f - tear.value * 0.5f
                    rotationX = -2f * (1f - feed.value)
                    cameraDistance = 18f * density
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .shadow(12.dp, paperShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(paperShape).background(Paper)
                .drawWithContent {
                    drawContent()
                    // Compression shadow at the feed rollers, side falloff and a curled lip.
                    drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.06f), Color.Transparent,
                        Color.Transparent, Color.Black.copy(alpha = 0.045f))))
                    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent),
                        endY = 16.dp.toPx()), size = Size(size.width, 16.dp.toPx()))
                    val curl = 18.dp.toPx()
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.5f),
                        Color(0xFFD8D3C9).copy(alpha = 0.7f)), startY = size.height - curl, endY = size.height),
                        topLeft = Offset(0f, size.height - curl), size = Size(size.width, curl))
                }) {
                if (image != null) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val imageHeight = maxWidth * image.height.toFloat() / image.width.toFloat()
                        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                            Image(image, "Printed receipt; swipe to inspect", Modifier.fillMaxWidth().height(imageHeight),
                                contentScale = ContentScale.FillWidth, filterQuality = FilterQuality.None)
                            Spacer(Modifier.height(20.dp))
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text("Ready to print", color = Color(0xFF30363B), fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(8.dp))
                        Text("Your next receipt\nappears here", color = Color(0xFF72787C), fontSize = 12.sp,
                            lineHeight = 18.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        // A small front lip occludes the paper's root and sells the depth of the slit.
        Canvas(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = paperTop - 2.dp).height(3.dp)) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF060708), Color.Black.copy(alpha = 0.12f))))
        }
        if (image == null) {
            Text("Thermal printer / 203 dpi", modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp),
                color = Color(0xFF606A73), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
    if (fullscreen && image != null) {
        val expandedRevision = remember { runtime.previewRevision }
        FullscreenReceipt(image, onDismiss = { fullscreen = false }, onTear = {
            if (latestRevision == expandedRevision) tearReceipt(it, animate = false)
            else fullscreen = false
        })
    }
}

/** Fine cutter teeth rather than a decorative zig-zag the size of the text. */
private class ReceiptEdge(private val tornTop: Boolean = false) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val tooth = with(density) { 2.dp.toPx() }
        val path = Path().apply {
            moveTo(0f, 0f)
            if (tornTop) {
                val count = (size.width / tooth).toInt().coerceAtLeast(1)
                for (i in 0..count) lineTo(size.width * i / count, if (i % 2 == 0) tooth else 0f)
            }
            lineTo(size.width, 0f)
            lineTo(size.width, size.height - tooth)
            val count = (size.width / tooth).toInt().coerceAtLeast(1)
            for (i in count downTo 0) {
                lineTo(size.width * i / count, size.height - if (i % 2 == 0) tooth else 0f)
            }
            close()
        }
        return Outline.Generic(path)
    }
}

@Composable
private fun FullscreenReceipt(image: ImageBitmap, onDismiss: () -> Unit, onTear: (Float) -> Unit) {
    val latestTear by rememberUpdatedState(onTear)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Receipt", style = MaterialTheme.typography.titleMedium)
                        Text("Swipe sideways to tear off", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close full-screen receipt", Modifier.size(20.dp)) }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    val imageHeight = maxWidth * image.height.toFloat() / image.width.toFloat()
                    val cardX = remember { Animatable(0f) }
                    val scope = rememberCoroutineScope()
                    var fingerX by remember { mutableFloatStateOf(0f) }
                    var dragging by remember { mutableStateOf(false) }
                    var dismissing by remember { mutableStateOf(false) }
                    val offset = if (dragging) fingerX else cardX.value
                    Column(Modifier.fillMaxSize()
                        .graphicsLayer {
                            translationX = offset
                            translationY = abs(offset) * 0.08f
                            rotationZ = offset / size.width * 18f
                            alpha = 1f - (abs(offset) / size.width).coerceIn(0f, 1f) * 0.25f
                        }
                        .shadow(12.dp, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .pointerInput(Unit) {
                            fun settle(allowDismiss: Boolean) {
                                val released = fingerX
                                val width = size.width.toFloat()
                                scope.launch {
                                    cardX.snapTo(released)
                                    dragging = false
                                    if (allowDismiss && abs(released) > minOf(90.dp.toPx(), width * 0.28f)) {
                                        dismissing = true
                                        cardX.animateTo(sign(released) * width * 1.5f, tween(300, easing = FeedEasing))
                                        latestTear(released)
                                    } else {
                                        cardX.animateTo(0f, tween(240, easing = FeedEasing))
                                    }
                                }
                            }
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    if (!dismissing) {
                                        fingerX = cardX.value
                                        dragging = true
                                        scope.launch { cardX.stop() }
                                    }
                                },
                                onHorizontalDrag = { change, amount ->
                                    if (!dismissing) { change.consume(); fingerX += amount }
                                },
                                onDragEnd = { if (!dismissing) settle(true) },
                                onDragCancel = { if (!dismissing) settle(false) },
                            )
                        }
                        .verticalScroll(rememberScrollState()).background(Paper)) {
                        Image(image, "Full receipt", Modifier.fillMaxWidth().height(imageHeight),
                            contentScale = ContentScale.FillWidth, filterQuality = FilterQuality.None)
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}
