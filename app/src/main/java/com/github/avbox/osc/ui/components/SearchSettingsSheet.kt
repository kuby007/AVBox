package com.github.avbox.osc.ui.components

import androidx.compose.runtime.LaunchedEffect

import com.github.avbox.osc.api.SearchSourcesState

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.util.SearchSettingsState
import com.github.avbox.osc.util.HomeSettings
import com.github.avbox.osc.ui.SiteCardShapeLeft
import com.github.avbox.osc.ui.SiteCardShapeRight
import com.github.avbox.osc.ui.SiteCardShapeSingle
import com.github.avbox.osc.ui.SiteSourceCard
import com.github.avbox.osc.ui.SiteKind
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.ui.siteKindOf
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight

@Composable
fun SearchSettingsSheet(onDismiss: () -> Unit, onSelectionChanged: () -> Unit) {
    var reloadTick by remember { mutableStateOf(0) }
    var kind by remember { mutableStateOf(SiteKind.ALL) }
    val searchSources by SearchSourcesState.state.collectAsStateWithLifecycle()
    LaunchedEffect(reloadTick) { SearchSourcesState.refresh() }
    val selected: Set<String> = searchSources.pickedKeys
    val sources = searchSources.sources
    val visible = remember(sources, kind) { sources.filter { kind == SiteKind.ALL || siteKindOf(it.api) == kind } }
    val allKeys = remember(sources) { sources.map { it.key.orEmpty() }.toSet() }
    val applySelection: (Set<String>) -> Unit = { SearchSourcesState.applySelection(it) }
    val onApplySelection: (Set<String>) -> Unit = { SearchSourcesState.applySelection(it) }
    val homeLayout by HomeSettings.layoutFlow.collectAsStateWithLifecycle()
    var exactMatch by remember { mutableStateOf(SearchSettingsState.exactMatch) }

    val cfg = LocalConfiguration.current
    // 横屏时屏高很短，0.7 会把内容压成一条缝，改按 0.92 取
    val sheetRatio = if (cfg.screenWidthDp > cfg.screenHeightDp) 0.92f else 0.7f
    val maxSheetHeight = (cfg.screenHeightDp * sheetRatio).dp

    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.search_settings),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.heightIn(max = maxSheetHeight),
        headerContent = {
            Text(
                text = stringResource(R.string.search_home_poster),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
            CapsuleSegmentedButton(
                options = listOf(
                    SegmentOption(
                        label = stringResource(R.string.search_layout_horizontal),
                        value = HomeSettings.HomeLayout.Horizontal,
                        iconPainter = painterResource(R.drawable.ic_layout_horizontal),
                    ),
                    SegmentOption(
                        label = stringResource(R.string.search_layout_vertical),
                        value = HomeSettings.HomeLayout.Vertical,
                        iconPainter = painterResource(R.drawable.ic_layout_vertical),
                    ),
                ),
                selectedValue = homeLayout,
                onOptionSelected = { HomeSettings.layout = it },
                style = SegmentStyle.Separated,
                containerColor = MaterialTheme.colorScheme.surfaceBright,
                pressMorph = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
        },
    ) {
        val sheetLandscape = cfg.screenWidthDp > cfg.screenHeightDp && cfg.screenWidthDp >= 640
        val matchToggle: @Composable () -> Unit = {
                SettingsGroup(title = null) {
                    SettingsCard(
                        position = SettingsCardPosition.SINGLE,
                        color = MaterialTheme.colorScheme.surfaceBright,
                        shape = SiteCardShapeSingle,
                    ) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.search_exact),
                            checked = exactMatch,
                            onCheckedChange = {
                                exactMatch = it
                                SearchSettingsState.exactMatch = it
                            },
                        )
                    }
                }
        }
        val kindChips: @Composable () -> Unit = {
                CapsuleSegmentedButton(
                    options = SiteKind.values().map { SegmentOption(it.label, it) },
                    selectedValue = kind,
                    onOptionSelected = { kind = it },
                    style = SegmentStyle.Separated,
                    containerColor = MaterialTheme.colorScheme.surfaceBright,
                    pressMorph = true,
                    textStyle = MaterialTheme.typography.labelMedium,
                    contentPadding = DenseSegmentContentPadding,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                )
        }
        val siteHeader: @Composable () -> Unit = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.search_sites),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (selected.isEmpty()) {
                                stringResource(R.string.search_sites_none_hint)
                            } else {
                                stringResource(R.string.search_sites_selected_hint, selected.size, allKeys.size)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { reloadTick++ }) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.common_refresh),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    TextButton(onClick = { applySelection(allKeys) }) { Text(stringResource(R.string.common_select_all)) }
                    TextButton(onClick = { applySelection(allKeys - selected) }) { Text(stringResource(R.string.common_invert_selection)) }
                }
        }
        val siteGrid: @Composable (Modifier) -> Unit = { panelModifier ->
                if (visible.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_sites_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        visible.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                val left = pair[0]
                                val leftKey = left.key.orEmpty()
                                SiteSourceCard(
                                    name = left.name?.takeIf { it.isNotEmpty() } ?: leftKey,
                                    selected = leftKey in selected,
                                    dotOnLeft = true,
                                    shape = SiteCardShapeLeft,
                                    onClick = {
                                        onApplySelection(
                                            if (leftKey in selected) selected - leftKey else selected + leftKey,
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                val right = pair.getOrNull(1)
                                if (right != null) {
                                    val rightKey = right.key.orEmpty()
                                    SiteSourceCard(
                                        name = right.name?.takeIf { it.isNotEmpty() } ?: rightKey,
                                        selected = rightKey in selected,
                                        dotOnLeft = false,
                                        shape = SiteCardShapeRight,
                                        onClick = {
                                            onApplySelection(
                                                if (rightKey in selected) selected - rightKey else selected + rightKey,
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
        }
        if (sheetLandscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(0.64f)
                        .fillMaxHeight()
                        .padding(start = 16.dp, end = 8.dp),
                ) {
                    siteHeader()
                    siteGrid(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
                }
                Column(
                    modifier = Modifier
                        .weight(0.36f)
                        .fillMaxHeight()
                        .padding(start = 8.dp, end = 16.dp, top = 12.dp),
                ) {
                    matchToggle()
                    kindChips()
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                matchToggle()
                kindChips()
                siteHeader()
                siteGrid(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun SearchActionButton(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
