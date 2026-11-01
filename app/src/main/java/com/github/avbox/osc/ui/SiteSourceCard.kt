package com.github.avbox.osc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.github.avbox.osc.ui.components.marqueeOnOverflow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 站点卡片：搜索站点 / 首页站点 / 站点管理 三处共用同一套元素与风格。
 * 名称单行截断不换行，选中态为端点圆点（不是整卡变色），左右两卡拼成一颗胶囊。
 */
val SiteCardShapeLeft = RoundedCornerShape(
    topStart = 28.dp,
    bottomStart = 28.dp,
    topEnd = 4.dp,
    bottomEnd = 4.dp,
)

val SiteCardShapeRight = RoundedCornerShape(
    topStart = 4.dp,
    bottomStart = 4.dp,
    topEnd = 28.dp,
    bottomEnd = 28.dp,
)

val SiteCardShapeSingle = RoundedCornerShape(28.dp)

@Composable
fun SiteSourceCard(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dotOnLeft: Boolean = true,
    shape: Shape = SiteCardShapeSingle,
    subtitle: String? = null,
) {
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceBright,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (subtitle.isNullOrEmpty()) 12.dp else 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .marqueeOnOverflow(),
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .marqueeOnOverflow()
                            .padding(top = 2.dp),
                    )
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(if (dotOnLeft) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 12.dp)
                        .size(14.dp)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                }
            }
        }
    }
}

/** 站点脚本类型：全部 / py / js / html / 其他 */
enum class SiteKind(val label: String) {
    ALL("全部"),
    PY("py"),
    JS("js"),
    HTML("html"),
    OTHER("其他"),
}

fun siteKindOf(api: String?): SiteKind {
    val raw = api.orEmpty().trim()
    if (raw.isEmpty()) return SiteKind.OTHER
    val lower = raw.substringBefore('?').substringBefore('#').lowercase()
    return when {
        lower.endsWith(".py") -> SiteKind.PY
        lower.endsWith(".js") -> SiteKind.JS
        lower.endsWith(".html") || lower.endsWith(".htm") -> SiteKind.HTML
        else -> SiteKind.OTHER
    }
}
