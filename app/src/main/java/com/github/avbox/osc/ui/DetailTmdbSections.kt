package com.github.avbox.osc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.TmdbCastMember
import com.github.avbox.osc.bean.TmdbDetail
import com.github.avbox.osc.bean.TmdbPerson
import com.github.avbox.osc.ui.components.AVBoxBottomSheet
import com.github.avbox.osc.util.TmdbImages
import com.github.avbox.osc.ui.components.marqueeOnOverflow

private val CastAvatarSize = 80.dp

private val CastItemWidth = 92.dp

private val CastSheetAvatarSize = 96.dp

private val CastSheetLoadingSize = 40.dp

private const val CastSheetHeightFraction = 0.6f

@Composable
internal fun TmdbCastSection(vm: DetailViewModel, name: String?, altName: String?, year: Int) {
    val detail = rememberTmdbDetail(vm, name, altName, year) ?: return
    if (detail.cast.isNotEmpty()) TmdbCastBlock(vm, detail.cast)
}

@Composable
internal fun TmdbGenreSection(vm: DetailViewModel, name: String?, altName: String?, year: Int) {
    val detail = rememberTmdbDetail(vm, name, altName, year) ?: return
    if (detail.genres.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_genres))
            Text(
                text = stringResource(R.string.detail_genres),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(detail.genres) { genre -> TmdbGenreChip(genre) }
        }
    }
}

@Composable
private fun TmdbGenreChip(label: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = detailCardColor(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun rememberTmdbDetail(vm: DetailViewModel, name: String?, altName: String?, year: Int): TmdbDetail? {
    if (name.isNullOrBlank()) return null
    val epoch by TmdbImages.configEpoch.collectAsState()
    LaunchedEffect(vm, name, altName, year, epoch) {
        vm.ensureTmdbDetail(name, altName, year)
    }
    val detail by vm.tmdbDetail.collectAsState()
    return detail
}

@Composable
private fun TmdbCastBlock(vm: DetailViewModel, cast: List<TmdbCastMember>) {
    var selected by remember { mutableStateOf<TmdbCastMember?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_actors))
            Text(
                text = stringResource(R.string.detail_cast),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cast, key = { it.name + "|" + it.profilePath }) { member ->
                TmdbCastItem(member, onClick = { selected = member })
            }
        }
    }
    selected?.let { member ->
        TmdbCastSheet(vm = vm, member = member, onDismiss = { selected = null })
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TmdbCastItem(member: TmdbCastMember, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(CastItemWidth),
    ) {
        AsyncImage(
            model = TmdbImages.profileUrl(member.profilePath),
            contentDescription = member.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(CastAvatarSize)
                .clip(MaterialShapes.Cookie12Sided.toShape())
                .clickable(onClick = onClick)
                .background(detailCardColor()),
        )
        Text(
            text = member.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .marqueeOnOverflow()
                .padding(top = 6.dp),
        )
    }
}

private sealed interface CastPersonState {
    object Loading : CastPersonState

    data class Ready(val person: TmdbPerson?) : CastPersonState
}

@Composable
private fun rememberCastPerson(vm: DetailViewModel, id: Int): CastPersonState {
    if (id <= 0) return CastPersonState.Ready(null)
    val epoch by TmdbImages.configEpoch.collectAsState()
    LaunchedEffect(vm, id, epoch) {
        vm.ensureTmdbPerson(id)
    }
    val state by vm.tmdbPerson.collectAsState()
    val current = state?.takeIf { it.id == id && it.epoch == epoch }
    return if (current == null || current.loading) {
        CastPersonState.Loading
    } else {
        CastPersonState.Ready(current.person)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TmdbCastSheet(vm: DetailViewModel, member: TmdbCastMember, onDismiss: () -> Unit) {
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        isScrollable = false,
        modifier = Modifier.fillMaxHeight(CastSheetHeightFraction),
    ) {
        val state = rememberCastPerson(vm, member.id)
        val person = (state as? CastPersonState.Ready)?.person
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = TmdbImages.profileUrl(
                        person?.profilePath?.takeIf { it.isNotEmpty() } ?: member.profilePath,
                    ),
                    contentDescription = member.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(CastSheetAvatarSize)
                        .clip(MaterialShapes.Cookie12Sided.toShape())
                        .background(detailCardColor()),
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = person?.name?.takeIf { it.isNotBlank() } ?: member.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val meta = listOfNotNull(
                        person?.birthday?.takeIf { it.isNotBlank() },
                        person?.placeOfBirth?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            if (state is CastPersonState.Loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(top = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator(Modifier.size(CastSheetLoadingSize))
                }
            } else {
                val biography = person?.biography.orEmpty()
                if (biography.isNotBlank()) {
                    Text(
                        text = biography,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 16.dp),
                    )
                }
            }
        }
    }
}
