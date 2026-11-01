package com.github.avbox.osc.sourcedata

import com.github.catvod.crawler.SpiderReaper
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.bean.AbsSortXml
import com.github.avbox.osc.bean.Movie
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.player.thirdparty.RemoteTVBox
import com.github.avbox.osc.util.BoundedCall
import com.github.avbox.osc.util.AppLog
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.io.IOException
import java.net.URLEncoder
import java.util.ArrayList
import java.util.HashMap
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SortLoader(
    private val gson: Gson,
    private val extendCache: ConcurrentHashMap<String, String>,
    private val sortCache: MutableMap<String, AbsSortXml?>,
    private val sortResult: SourceChannel<AbsSortXml?>,
    private val listLoader: ListLoader,
    private val resultParser: SourceResultParser,
) {

    private fun cacheSort(sourceKey: String, sortXml: AbsSortXml?) {
        attachSortSource(sourceKey, sortXml)
        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (!hasHomeRecVideos(sortXml)) {
            return
        }
        if (!shouldBypassSortCache(sourceKey, sourceBean) && !hasActionSort(sortXml)) {
            synchronized(sortCache) {
                sortCache[sourceKey] = sortXml
            }
            // 落到磁盘，供下次冷启动/切源时先渲染出首屏
            SortDiskCache.save(SortDiskCache.keyOf(sourceKey), sortXml)
        }
    }

    private fun attachSortSource(sourceKey: String?, sortXml: AbsSortXml?): AbsSortXml? {
        if (sortXml != null) {
            sortXml.sourceKey = sourceKey
        }
        return sortXml
    }

    private fun postSortResult(sourceKey: String?, sortXml: AbsSortXml?) {
        var data = sortXml
        if (data == null) {
            data = AbsSortXml()
        }
        sortResult.postValue(attachSortSource(sourceKey, data))
    }

    private fun postSortFailure(sourceKey: String?) {
        val sortXml = AbsSortXml()
        sortXml.loadFailed = true
        sortResult.postValue(attachSortSource(sourceKey, sortXml))
    }

    private fun hasActionSort(sortXml: AbsSortXml?): Boolean {
        if (sortXml == null) return false
        if (hasActionVideo(sortXml.videoList)) return true
        val list = sortXml.list
        return list != null && hasActionVideo(list.videoList)
    }

    private fun hasHomeRecVideos(sortXml: AbsSortXml?): Boolean {
        val videoList = sortXml?.videoList
        return videoList != null && videoList.isNotEmpty()
    }

    private fun hasActionVideo(videos: List<Movie.Video?>?): Boolean {
        if (videos == null) return false
        for (video in videos) {
            if (video?.action != null) return true
        }
        return false
    }

    private fun shouldBypassSortCache(sourceKey: String?, sourceBean: SourceBean?): Boolean {
        return SourceHelper.isHomeSource(sourceKey) && SourceHelper.isDoubanSource(sourceBean)
    }

    suspend fun getSort(sourceKey: String?) {
        getSort(sourceKey, true)
    }

    suspend fun getSort(sourceKey: String?, withRec: Boolean) {
        if (sourceKey == null) {
            sortResult.postValue(AbsSortXml())
            return
        }

        val sourceBean = ApiConfig.get().getSource(sourceKey)
        if (sourceBean == null) {
            AppLog.i("echo--getSort-source-null--$sourceKey")
            postSortResult(sourceKey, null)
            return
        }
        val name = sourceBean.name ?: return
        if (name.length <= 3 && name.endsWith("搜")) { // i18n: keep
            postSortResult(sourceKey, null)
            return
        }

        if (!shouldBypassSortCache(sourceKey, sourceBean)) {
            val cached: AbsSortXml? = synchronized(sortCache) {
                sortCache[sourceKey]
            }
            if (cached != null) {
                val cachedVideoList = cached.videoList
                if (cachedVideoList != null && cachedVideoList.isNotEmpty()) {
                    attachSortSource(sourceKey, cached)
                    postSortResult(sourceKey, cached)
                    return
                }
            }

            // 内存没命中（冷启动 / 刚切订阅源）：先用磁盘快照把首屏铺出来，
            // 再继续往下走真实请求，拿到后覆盖。注意这里不写回内存缓存，
            // 否则后续 getSort 会一直命中快照、永远不刷新。
            val snapshot = withContext(Dispatchers.IO) {
                SortDiskCache.load(SortDiskCache.keyOf(sourceKey))
            }
            if (snapshot != null && !snapshot.videoList.isNullOrEmpty()) {
                attachSortSource(sourceKey, snapshot)
                postSortResult(sourceKey, snapshot)
            }
        }

        val type = sourceBean.type
        if (type == 3 || type == 6) {
            sortFromSpider(sourceKey, sourceBean, withRec)
        } else if (type == 0 || type == 1) {
            sortFromApi(sourceKey, sourceBean, withRec)
        } else if (type == 4) {
            sortFromExtendedApi(sourceKey, sourceBean)
        } else {
            postSortResult(sourceKey, null)
        }
    }

    private suspend fun sortFromSpider(sourceKey: String, sourceBean: SourceBean, withRec: Boolean) {
        val sortJson = withContext(Dispatchers.IO) {
            BoundedCall.call(Callable<String> {
                val sp = ApiConfig.get().getCSP(sourceBean)
                val json = SpiderReaper.track(sp) { sp.homeContent(true) }
                json
            }, sourceBean.playTimeoutSeconds() * 1000L, "echo--getSort--" + sourceBean.key)
        }
        if (sortJson == null) {
            AppLog.i("echo--getSort-spider-null:$sourceKey")
            postSortFailure(sourceKey)
            return
        }
        val sortXml = withContext(Dispatchers.IO) { resultParser.sortJson(sortResult, sortJson) }
        attachSortSource(sourceKey, sortXml)
        if (sortXml == null) {
            postSortFailure(sourceKey)
            return
        }
        val absVideoList = withContext(Dispatchers.IO) { resultParser.json(null, sortJson, sourceBean.key)?.movie?.videoList }
        if (!withRec) {
            postSortResult(sourceKey, sortXml)
            cacheSort(sourceKey, sortXml)
        } else if (absVideoList != null && absVideoList.size > 0) {
            sortXml.videoList = absVideoList
            postSortResult(sourceKey, sortXml)
            cacheSort(sourceKey, sortXml)
        } else if (sortXml.classes != null) {
            postSortResult(sourceKey, sortXml)
            cacheSort(sourceKey, sortXml)
        } else {
            sortXml.videoList = listLoader.homeRecList(sourceBean, null)
            postSortResult(sourceKey, sortXml)
            cacheSort(sourceKey, sortXml)
        }
    }

    private suspend fun sortFromApi(sourceKey: String, sourceBean: SourceBean, withRec: Boolean) {
        val type = sourceBean.type
        try {
            val body = SourceHelper.siteGet(sourceBean) {}
            val sortXml: AbsSortXml? = withContext(Dispatchers.IO) {
                if (type == 0) {
                    resultParser.sortXml(sortResult, body)
                } else if (type == 1) {
                    resultParser.sortJson(sortResult, body)
                } else {
                    null
                }
            }
            attachSortSource(sourceKey, sortXml)
            if (sortXml == null) {
                postSortFailure(sourceKey)
                return
            }
            val recVideoList = sortXml.list?.videoList
            if (withRec && recVideoList != null && recVideoList.size > 0) {
                val ids = ArrayList<String?>()
                for (vod in recVideoList) {
                    ids.add(vod.id)
                }
                sortXml.videoList = listLoader.homeRecList(sourceBean, ids)
                postSortResult(sourceKey, sortXml)
                cacheSort(sourceKey, sortXml)
            } else if (sortXml.classes != null) {
                postSortResult(sourceKey, sortXml)
                cacheSort(sourceKey, sortXml)
            } else {
                postSortFailure(sourceKey)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.i(
                "echo--getSort-api-error:" + sourceKey + " ex=" + e
            )
            postSortFailure(sourceKey)
        }
    }

    private suspend fun sortFromExtendedApi(sourceKey: String, sourceBean: SourceBean) {
        val extend = withContext(Dispatchers.IO) {
            SourceHelper.getFixUrl(extendCache, gson, sourceBean.ext, sourceBean.playTimeoutSeconds().toLong())
        }
        if (URLEncoder.encode(extend).length < 1000) {
            try {
                val body = SourceHelper.siteGet(sourceBean) {
                    params("filter", "true")
                    if (extend != null && extend.isNotEmpty()) {
                        params("extend", extend)
                    }
                }
                val sortXml = withContext(Dispatchers.IO) { resultParser.sortJson(sortResult, body) }
                attachSortSource(sourceKey, sortXml)
                if (sortXml == null) {
                    postSortFailure(sourceKey)
                    return
                }
                val absVideoList = withContext(Dispatchers.IO) { resultParser.json(null, body, sourceBean.key)?.movie?.videoList }
                if (absVideoList != null && absVideoList.size > 0) {
                    sortXml.videoList = absVideoList
                    postSortResult(sourceKey, sortXml)
                    cacheSort(sourceKey, sortXml)
                } else {
                    sortXml.videoList = listLoader.homeRecList(sourceBean, null)
                    postSortResult(sourceKey, sortXml)
                    cacheSort(sourceKey, sortXml)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.i(
                    "echo--getSort-ext-error:" + sourceKey + " ex=" + e
                )
                postSortFailure(sourceKey)
            }
        } else {
            try {
                val params = HashMap<String, String>()
                params["filter"] = "true"
                if (extend != null && !extend.isEmpty()) {
                    params["extend"] = extend
                }
                val sortJson = suspendCancellableCoroutine<String?> { cont ->
                    RemoteTVBox.post(sourceBean.api, params, sourceBean.header, object : okhttp3.Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            AppLog.i("echo--getSort-post-fail:" + sourceKey + " ex=" + e)
                            cont.resume(null)
                        }

                        override fun onResponse(call: Call, response: okhttp3.Response) {
                            val body = try {
                                response.use { it.body.string() }
                            } catch (e: IOException) {
                                AppLog.i("echo--getSort-post-fail:" + sourceKey + " ex=" + e)
                                cont.resumeWithException(e)
                                return
                            }
                            cont.resume(body)
                        }
                    })
                }
                if (sortJson == null) {
                    postSortFailure(sourceKey)
                    return
                }
                val sortXml = withContext(Dispatchers.IO) { resultParser.sortJson(sortResult, sortJson) }
                attachSortSource(sourceKey, sortXml)
                if (sortXml == null) {
                    postSortFailure(sourceKey)
                    return
                }
                val absVideoList = withContext(Dispatchers.IO) { resultParser.json(null, sortJson, sourceBean.key)?.movie?.videoList }
                if (absVideoList != null && absVideoList.size > 0) {
                    sortXml.videoList = absVideoList
                    postSortResult(sourceKey, sortXml)
                    cacheSort(sourceKey, sortXml)
                } else if (sortXml.classes != null) {
                    postSortResult(sourceKey, sortXml)
                    cacheSort(sourceKey, sortXml)
                } else {
                    postSortFailure(sourceKey)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: Exception) {
                postSortFailure(sourceKey)
            }
        }
    }
}
