package com.github.avbox.osc.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.R
import com.github.avbox.osc.ui.components.AVBoxAlertDialog
import com.github.avbox.osc.ui.components.AppTopBarScaffold
import com.github.avbox.osc.ui.components.CapsuleSegmentedButton
import com.github.avbox.osc.ui.components.DenseSegmentContentPadding
import com.github.avbox.osc.ui.SiteCardShapeSingle
import com.github.avbox.osc.ui.SiteKind
import com.github.avbox.osc.ui.SiteSourceCard
import com.github.avbox.osc.ui.siteKindOf
import com.github.avbox.osc.ui.components.SegmentOption
import com.github.avbox.osc.ui.components.TopBarActionBox
import com.github.avbox.osc.util.LocalScriptSource
import org.json.JSONObject
import com.github.avbox.osc.ui.components.marqueeOnOverflow



private fun JSONObject.api(): String = optString("api").orEmpty()
private fun JSONObject.name(): String = optString("name").orEmpty()
private fun JSONObject.key(): String = optString("key").orEmpty()
private fun JSONObject.ext(): String = optString("ext").orEmpty()
private fun JSONObject.proxy(): String = optString("proxy").orEmpty()

private fun JSONObject.isRemote(): Boolean =
    api().startsWith("http://") || api().startsWith("https://")



/** 站点管理只显示主行 name，副标题（文件名 / url）已按要求去掉 */
private fun JSONObject.sub(): String {
    val a = api()
    return if (isRemote()) a else a.substringAfterLast('/')
}

/**
 * 站点管理子页：配置管理 → 长按本地脚本订阅源 → 站点管理。
 *
 * 顶部：在线 html 录入 / 位置互换 / 上移 / 下移
 * 分类：全部 / py / js / html / url（同时作用于左右两栏）
 * 选择：全选[左] 反选[左] 全选[右] 反选[右]
 * 列表：左「工作区」右「回收站」，两侧可同时多选，互换一律移到对面尾部，
 *       两侧各自支持多选上移 / 下移。
 * 所有改动即时写回目录下 lscript.json，脚本文件一个字节不动。
 */
@Composable
fun SiteManageScreen(
    dir: String,
    onBack: () -> Unit,
    onReloadSource: () -> Unit,
) {
    var reload by remember(dir) { mutableStateOf(0) }
    var filter by remember { mutableStateOf(SiteKind.ALL) }
    var addOpen by remember { mutableStateOf(false) }
    val keepSel = remember { mutableStateListOf<String>() }
    val goneSel = remember { mutableStateListOf<String>() }

    val data = remember(dir, reload) { LocalScriptSource.load(dir) }
    val sites = data.first
    val bac = data.second

    val visibleSites = sites.filter { filter == SiteKind.ALL || siteKindOf(it.api()) == filter }
    val visibleBac = bac.filter { filter == SiteKind.ALL || siteKindOf(it.api()) == filter }

    val refresh: () -> Unit = {
        reload++
        onReloadSource()
    }

    AppTopBarScaffold(
        titleContent = {
            Text(
                text = "站点管理",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(
                iconRes = R.drawable.ic_arrow_left,
                contentDescription = "返回",
                onClick = onBack,
            )
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPad, start = 12.dp, end = 12.dp),
        ) {
            // 顶部四个操作
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActionChip("在线html录入", modifier = Modifier.weight(1f)) { addOpen = true }
                ActionChip(
                    "位置互换",
                    modifier = Modifier.weight(1f),
                    enabled = keepSel.isNotEmpty() || goneSel.isNotEmpty(),
                ) {
                    if (LocalScriptSource.swap(dir, keepSel.toList(), goneSel.toList())) {
                        keepSel.clear()
                        goneSel.clear()
                        refresh()
                    }
                }
                ActionChip(
                    "上移",
                    modifier = Modifier.weight(1f),
                    enabled = keepSel.isNotEmpty() || goneSel.isNotEmpty(),
                ) {
                    var changed = false
                    if (keepSel.isNotEmpty()) {
                        changed = LocalScriptSource.reorder(dir, keepSel.toList(), true) || changed
                    }
                    if (goneSel.isNotEmpty()) {
                        changed = LocalScriptSource.reorder(dir, goneSel.toList(), true, true) || changed
                    }
                    if (changed) refresh()
                }
                ActionChip(
                    "下移",
                    modifier = Modifier.weight(1f),
                    enabled = keepSel.isNotEmpty() || goneSel.isNotEmpty(),
                ) {
                    var changed = false
                    if (keepSel.isNotEmpty()) {
                        changed = LocalScriptSource.reorder(dir, keepSel.toList(), false) || changed
                    }
                    if (goneSel.isNotEmpty()) {
                        changed = LocalScriptSource.reorder(dir, goneSel.toList(), false, true) || changed
                    }
                    if (changed) refresh()
                }
            }

            // 分类胶囊
            CapsuleSegmentedButton(
                options = SiteKind.values().map { SegmentOption(it.label, it) },
                selectedValue = filter,
                onOptionSelected = { filter = it },
                textStyle = MaterialTheme.typography.labelMedium,
                contentPadding = DenseSegmentContentPadding,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )

            // 选择操作
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActionChip("全选[左]", modifier = Modifier.weight(1f)) {
                    keepSel.clear()
                    keepSel.addAll(visibleSites.map { it.key() })
                }
                ActionChip("反选[左]", modifier = Modifier.weight(1f)) {
                    val all = visibleSites.map { it.key() }
                    val next = all.filter { !keepSel.contains(it) }
                    keepSel.clear()
                    keepSel.addAll(next)
                }
                ActionChip("全选[右]", modifier = Modifier.weight(1f)) {
                    goneSel.clear()
                    goneSel.addAll(visibleBac.map { it.key() })
                }
                ActionChip("反选[右]", modifier = Modifier.weight(1f)) {
                    val all = visibleBac.map { it.key() }
                    val next = all.filter { !goneSel.contains(it) }
                    goneSel.clear()
                    goneSel.addAll(next)
                }
            }

            // 左右两栏
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SiteColumn(
                    modifier = Modifier.weight(1f),
                    title = "工作区 (${visibleSites.size})",
                    list = visibleSites,
                    selected = keepSel,
                    onToggle = { k -> if (keepSel.contains(k)) keepSel.remove(k) else keepSel.add(k) },
                )
                SiteColumn(
                    modifier = Modifier.weight(1f),
                    title = "回收站 (${visibleBac.size})",
                    list = visibleBac,
                    selected = goneSel,
                    onToggle = { k -> if (goneSel.contains(k)) goneSel.remove(k) else goneSel.add(k) },
                )
            }
        }
    }

    if (addOpen) {
        AddUrlDialog(
            onDismiss = { addOpen = false },
            onSave = { name, url ->
                addOpen = false
                if (LocalScriptSource.addUrl(dir, name, url)) refresh()
            },
        )
    }
}

@Composable
private fun AddUrlDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val ok = name.isNotBlank() && url.isNotBlank()
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("在线html录入") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("名称") },
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("url 地址") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), url.trim()) }, enabled = ok) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ActionChip(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text,
                modifier = Modifier
                    .padding(horizontal = 6.dp, vertical = 8.dp)
                    .marqueeOnOverflow(),
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SiteColumn(
    modifier: Modifier,
    title: String,
    list: List<JSONObject>,
    selected: MutableList<String>,
    onToggle: (String) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(list, key = { it.key() }) { item ->
                val on = selected.contains(item.key())
                SiteSourceCard(
                    name = item.name().ifEmpty { item.key() },
                    selected = on,
                    dotOnLeft = true,
                    shape = SiteCardShapeSingle,
                    onClick = { onToggle(item.key()) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
