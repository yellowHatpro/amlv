package com.dokar.amlv

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.packInts
import androidx.compose.ui.util.unpackInt1
import androidx.compose.ui.util.unpackInt2
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@JvmInline
@Immutable
private value class ItemInfo(val packedValue: Long) {
    val offsetY: Int get() = unpackInt1(packedValue)

    val height: Int get() = unpackInt2(packedValue)
}

private fun ItemInfo(offsetY: Int, height: Int): ItemInfo {
    return ItemInfo(packInts(offsetY, height))
}

@Composable
fun LyricsView(
    state: LyricsViewState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    darkTheme: Boolean = false,
    fadingEdges: FadingEdges = FadingEdges.None,
    fontSize: TextUnit = 32.sp,
    fontWeight: FontWeight = FontWeight.Bold,
    lineHeight: TextUnit = 1.2.em,
) {
    val scrollState = rememberScrollState()

    var lyricsHeight by remember { mutableStateOf(0) }

    val itemsInfo = remember { mutableMapOf<Int, ItemInfo>() }

    var startItemsOffsetY by remember { mutableStateOf(0) }

    var currItemsOffsetY by remember { mutableStateOf(0) }

    var animationItemsRange by remember { mutableStateOf(-1..-1) }

    LaunchedEffect(scrollState, state) {
        fun getAnimationItemsRange(currentIndex: Int): IntRange {
            val lines = state.lyrics?.lines ?: return -1..-1
            val currItemInfo = itemsInfo[currentIndex] ?: return -1..-1
            val scrollY = scrollState.value
            var start = -1
            var end = -1
            for (i in lines.indices) {
                val itemInfo = itemsInfo[i] ?: continue

                val itemTop = itemInfo.offsetY
                val itemHeight = itemInfo.height
                val itemBottom = itemTop + itemHeight

                if (itemBottom < scrollY) {
                    continue
                } else if (start == -1) {
                    start = i
                }

                if (itemTop > currItemInfo.offsetY + lyricsHeight) {
                    break
                } else {
                    end = i
                }
            }
            return start..end
        }

        var animationJob: Job? = null

        snapshotFlow { state.currentLine }
            .filter { it >= 0 }
            .collect { index ->
                val targetItemTop = itemsInfo[index]?.offsetY ?: return@collect
                animationJob?.cancel()
                animationJob = launch {
                    val targetScrollY = targetItemTop.coerceAtMost(scrollState.maxValue)
                    val diff = targetScrollY - scrollState.value
                    // 1) Find items to animate
                    animationItemsRange = getAnimationItemsRange(index)

                    // 2) Scroll the lyrics to the target position
                    scrollState.scrollTo(targetScrollY)

                    // 3) Apply an offset to items so the lyric looks like it hasn't moved
                    Snapshot.withoutReadObservation { startItemsOffsetY = diff }
                    currItemsOffsetY = diff

                    // 4) Animate items to the target position
                    animate(
                        initialValue = diff.toFloat(),
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 1000),
                    ) { value, _ ->
                        currItemsOffsetY = value.toInt()
                    }
                }
            }
    }

    Box(
        modifier = modifier
            .fadingEdges(edges = fadingEdges)
            .onSizeChanged { lyricsHeight = it.height },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = scrollState)
                .padding(contentPadding),
        ) {
            val lines = state.lyrics?.lines ?: emptyList()
            for ((index, line) in lines.withIndex()) {
                LyricsViewLine(
                    isCurrentLine = index == state.currentLine,
                    content = line.content,
                    textColor = if (darkTheme) Color.White else Color.Black,
                    rippleColor = if (darkTheme) Color.White else Color.Black,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    lineHeight = lineHeight,
                    onClick = { state.seekToLine(index) },
                    offsetYProvider = {
                        if (index in animationItemsRange) {
                            val value = currItemsOffsetY
                            if (index > state.currentLine) {
                                // These lines produce the animation delay
                                val factor = (1f + (index - state.currentLine) * 0.08f)
                                val progress = currItemsOffsetY.toFloat() / startItemsOffsetY
                                val finalProgress = (progress * factor).coerceAtMost(1f)
                                (startItemsOffsetY * finalProgress).toInt()
                            } else {
                                value
                            }
                        } else {
                            0
                        }
                    },
                    modifier = Modifier.onGloballyPositioned {
                        itemsInfo[index] = ItemInfo(
                            offsetY = it.positionInParent().y.toInt(),
                            height = it.size.height,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LyricsViewLine(
    isCurrentLine: Boolean,
    content: String,
    textColor: Color,
    rippleColor: Color,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    lineHeight: TextUnit,
    onClick: () -> Unit,
    offsetYProvider: () -> Int,
    modifier: Modifier = Modifier,
) {
    val transitionValue = animateFloatAsState(
        targetValue = if (isCurrentLine) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessLow,
        )
    )

    val interactionSource = remember { MutableInteractionSource() }
    val indication = rememberRipple(color = rippleColor)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .offset { IntOffset(0, offsetYProvider()) }
            .clip(MaterialTheme.shapes.medium)
            .indication(interactionSource, indication)
            .pointerInput(interactionSource) {
                detectTapGestures(
                    onPress = {
                        val press = PressInteraction.Press(it)
                        try {
                            withTimeout(timeMillis = 100) {
                                tryAwaitRelease()
                            }
                        } catch (e: TimeoutCancellationException) {
                            interactionSource.emit(press)
                            tryAwaitRelease()
                        }
                        interactionSource.emit(PressInteraction.Release(press))
                    },
                    onTap = { onClick() },
                )
            }
            .padding(
                start = 16.dp,
                top = 8.dp,
                end = 32.dp,
                bottom = 16.dp,
            ),
    ) {
        Text(
            text = content,
            modifier = Modifier
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 1f)
                    scaleX = 1.0f + 0.1f * transitionValue.value
                    scaleY = scaleX
                    alpha = (0.35f + 0.65f * transitionValue.value).coerceIn(0f, 1f)
                },
            color = textColor,
            fontSize = fontSize,
            fontWeight = fontWeight,
            lineHeight = lineHeight,
        )
    }
}