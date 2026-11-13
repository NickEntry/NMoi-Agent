package com.nmoi.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * 一条在等回答的命令（D329）。**判定在 core**，这里只负责把那一行原样摆出来。
 *
 * 命令是原文、等宽：用户要能一眼看出它到底要删什么，
 * 而不是看一句"模型想执行一条命令"。
 */
internal data class CommandAsk(
    val callId: String,
    val command: String,
    val label: String,
    val group: String,
    val secondsLeft: Int,
)

/** `turn_busy.asks` → 这一拍在等的询问。同时只会有一条（core 那一侧一次只挂一条）。 */
internal fun asksOf(info: JSONObject): List<CommandAsk> {
    val array = info.optJSONArray("asks") ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val callId = row.text("call_id")
            if (callId.isEmpty()) continue
            add(
                CommandAsk(
                    callId = callId,
                    command = row.text("command"),
                    label = row.text("label").ifBlank { "命令" },
                    group = row.text("group"),
                    secondsLeft = row.optInt("seconds_left", 0),
                ),
            )
        }
    }
}

/**
 * 询问面版（D329）。
 *
 * 三颗键就是三种决定，**没有第四种**：
 * 允许这一次 / 这个会话不再问这一类 / 拒绝。永久放行去设置里改——弹窗只做临时决定。
 * 60 秒没人点等于拒绝，那一句写在面板上，不写进脑子里。
 */
@Composable
internal fun CommandAskSheet(
    ask: CommandAsk,
    onAnswer: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    SheetFrame(title = "这条命令要跑吗", onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
            Text(
                text = if (ask.group.isBlank()) ask.label else "${ask.group} · ${ask.label}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            ) {
                Text(
                    text = ask.command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
            Text(
                text = "允许这一次 = 照跑；不再问这一类 = 同一个会话里同类规则直接跑；" +
                    "拒绝 = 命令不执行，模型收到「用户拒绝」。" +
                    "没人点就是拒绝（还剩 ${ask.secondsLeft} 秒）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                ChipButton("拒绝", primary = false) { onAnswer("deny") }
                Spacer(Modifier.width(8.dp))
                ChipButton("这个会话不再问这一条", primary = false) { onAnswer("allow_rule") }
                Spacer(Modifier.width(8.dp))
                ChipButton("允许这一次", primary = true) { onAnswer("allow_once") }
            }
        }
    }
}
