package com.github.avbox.osc.ui.page

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.github.avbox.osc.ui.DetailActivity
import com.github.avbox.osc.ui.SearchActivity

fun Context.jumpToDetail(id: String?, sourceKey: String?, title: String?, picture: String?, collect: Boolean = false) {
    val bundle = Bundle()
    bundle.putString("id", id.orEmpty())
    bundle.putString("sourceKey", sourceKey.orEmpty())
    bundle.putString("title", title)
    bundle.putString("picture", picture)
    bundle.putBoolean("collect", collect)
    startActivity(Intent(this, DetailActivity::class.java).putExtras(bundle))
}

fun Context.jumpToSearch(title: String) {
    val bundle = Bundle()
    bundle.putString("title", title)
    startActivity(Intent(this, SearchActivity::class.java).putExtras(bundle))
}
