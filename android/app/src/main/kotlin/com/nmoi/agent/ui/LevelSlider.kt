package com.nmoi.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 档位滑块（D402）：一条等距刻度轨 + 一个圆形旋钮，拖动或点哪儿跳哪儿。
 *
 * # 为什么自己画，不用 `Slider`
 *
 * `Slider` 是**连续值**：它会给中间那些不该存在的档位也留出位置（思考强度 7 档、
 * 速度 4 档，每一档都必须是"格"）。用 `steps` 能凑出来，但它的轨道两端会留
 * 半个旋钮的空，第一格和最后一格的圆点就对不齐刻度——这一条在演示里试过，
 * 差半格很明显。
 *
 * # 两端留旋钮半径
 *
 * 旋钮直径 44dp，所以刻度的可用区是 `宽度 - 44dp`，第一个刻度在 22dp 处、
 * 最后一个在 `宽度 - 22dp`。点击换算用同一套坐标，否则点两端会差一格。
 */
@Composable
internal fun LevelSlider(
    /** 每一格底下写什么（速度那边写全名，思考那边写缩写）。 */
    labels: List<String>,
    index: Int,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 轨道底色。默认走主题，星图那类特殊底色由调用方给。 */
    activeColor: androidx.compose.ui.graphics.Color? = null,
) {
    if (labels.isEmpty()) return
    val count = labels.size
    val density = LocalDensity.current
    val knob = 44.dp
    val half = knob / 2
    val accent = activeColor ?: MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    // 拖动时手里那一格：松手才回调，免得拖动过程中一路写盘。
    var dragging by remember { mutableStateOf(false) }
    var local by remember(index, count) { mutableStateOf(index) }
    val shown = if (dragging) local else index

    Column(modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(knob)) {
            val width = maxWidth
            val usable = (width - knob).coerceAtLeast(1.dp)
            fun xOf(i: Int) = half + usable * (if (count == 1) 0f else i.toFloat() / (count - 1))

            /** 从触摸点算该落在哪一格。与 [xOf] 共用同一套坐标。 */
            fun pickAt(x: androidx.compose.ui.unit.Dp): Int {
                val ratio = ((x - half) / usable).coerceIn(0f, 1f)
                return (ratio * (count - 1)).roundToInt()
            }

            // 轨道
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(knob)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(track),
            )
            // 已选段：从左边铺到当前格
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (count == 1) 0f else (shown.toFloat() / (count - 1)))
                    .height(knob)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(accent.copy(alpha = 0.55f)),
            )
            // 刻度点
            for (i in 0 until count) {
                Box(
                    modifier = Modifier
                        .offset(x = xOf(i) - 3.dp)
                        .align(Alignment.CenterStart)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (i <= shown) {
                                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                            },
                        ),
                )
            }
            // 旋钮
            Box(
                modifier = Modifier
                    .offset(x = xOf(shown) - half)
                    .align(Alignment.CenterStart)
                    .size(knob)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceBright),
            )
            // 触摸层压在最上面：整条轨道都能点、能拖。
            //
            // **一个 pointerInput 同时管点与拖**：两个识别器各挂一个 pointerInput 会抢事件
            // （drag 要等 slop，期间 tap 收不到 up，表现是"点了没反应"）。
            // 这里按下就先落格，拖动跟着走，松手再确认一次。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(knob)
                    .pointerInput(count) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            dragging = true
                            local = pickAt(with(density) { down.position.x.toDp() })
                            down.consume()
                            var moved = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.changedToUpIgnoreConsumed()) {
                                    change.consume()
                                    break
                                }
                                if (change.positionChanged()) {
                                    moved = true
                                    local = pickAt(with(density) { change.position.x.toDp() })
                                    change.consume()
                                }
                            }
                            dragging = false
                            // 点了（没拖动）也要落一次：按下即选，松手确认。
                            onPick(local)
                            if (moved) Unit
                        }
                    },
            )
        }
        // 刻度名：等距摆开，与上面的点对齐
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            labels.forEachIndexed { i, text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (i == shown) FontWeight.Bold else FontWeight.Normal,
                    color = if (i == shown) {
                        accent
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 档位的大字标题（D402）：`max (最大)`。
 *
 * 速度那一排用 `small = true`：它是副轴，字号比思考强度低一档，不抢主标题。
 */
@Composable
internal fun LevelHeadline(
    title: String,
    note: String? = null,
    small: Boolean = false,
    color: androidx.compose.ui.graphics.Color? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = if (small) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.titleLarge
            },
            color = color ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (note != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
