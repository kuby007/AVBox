package com.github.avbox.osc.ui.page

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.github.avbox.osc.ui.DetailActivity
import com.github.avbox.osc.ui.SearchActivity
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys

fun Context.jumpToDetail(id: String?, sourceKey: String?, title: String?, picture: String?, collect: Boolean = false) {
    val bundle = Bundle()
    bundle.putString("id", id.orEmpty())
    bundle.putString("sourceKey", sourceKey.orEmpty())
    bundle.putString("title", title)
    bundle.putString("picture", picture)
    bundle.putBoolean("collect", collect)
    // 「详情页播放」开关：开启（默认）时点宫格直接进带播放的详情页，关闭时按原逻辑
    bundle.putBoolean(
        "detail_auto_play",
        KV.get(KVKeys.DETAIL_PAGE_PLAY, KVKeys.DETAIL_PAGE_PLAY_DEFAULT),
    )
    startActivity(Intent(this, DetailActivity::class.java).putExtras(bundle))
}

fun Context.jumpToSearch(title: String) {
    val bundle = Bundle()
    bundle.putString("title", title)
    startActivity(Intent(this, SearchActivity::class.java).putExtras(bundle))
}
