package com.github.avbox.osc.sourcedata

import android.util.Base64
import com.github.catvod.crawler.SpiderReaper
import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.bean.AbsXml
import com.github.avbox.osc.bean.Movie
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.util.BoundedCall
import com.github.avbox.osc.util.AppLog
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.util.ArrayList
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap

class DetailLoader(
    private val gson: Gson,
    private val extendCache: ConcurrentHashMap<String, String>,
    private val detailResult: SourceChannel<AbsXml?>,
    private val resultParser: SourceResultParser,
) {

    suspend fun getDetail(sourceKey: String?, urlid: String) {
        getDetail(sourceKey, urlid, false)
    }

    suspend fun getDetail(sourceKey: String?, urlid: String, fallback: Boolean) {
        getDetail(sourceKey, urlid, fallback, null)
    }

    suspend fun getDetail(sourceKey: String?, urlid: String, fallback: Boolean, requestToken: Int?) {
        var key = sourceKey
        var id = urlid
        if (id.startsWith("push://") && ApiConfig.get().getSource(PushUrlParser.PUSH_AGENT) != null) {
            var pushUrl = id.substring(7)
            if (pushUrl.startsWith("b64:")) {
                try {
                    pushUrl = String(Base64.decode(pushUrl.substring(4), Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
                } catch (e: UnsupportedEncodingException) {
                    AppLog.e("SourceViewModel", e)
                }
            } else {
                pushUrl = URLDecoder.decode(pushUrl)
            }
            key = if (PushUrlParser.isCastPushUrl(pushUrl)) PushUrlParser.PUSH_FALLBACK else PushUrlParser.PUSH_AGENT
            id = pushUrl
        } else if (PushUrlParser.PUSH_AGENT == key && PushUrlParser.isCastPushUrl(id)) {
            key = PushUrlParser.PUSH_FALLBACK
        }

        val sourceBean = ApiConfig.get().getSource(key)
        if (PushUrlParser.isPushFallback(key, sourceBean)) {
            detailResult.postValue(createPushDetail(id, key, requestToken))
            return
        }
        if (sourceBean == null) {
            AppLog.i("echo--getDetail--source-null--$key")
            detailResult.postValue(createEmptyDetail(key, requestToken))
            return
        }
        val type = sourceBean.type
        if (type == 3) {
            detailFromSpider(sourceBean, id, fallback, requestToken)
        } else if (type == 0 || type == 1 || type == 4) {
            detailFromApi(sourceBean, id, fallback, requestToken)
        } else {
            detailResult.postValue(createEmptyDetail(key, requestToken))
        }
    }

    private suspend fun detailFromSpider(sourceBean: SourceBean, id: String, fallback: Boolean, requestToken: Int?) {
        withContext(Dispatchers.IO) {
            val json = BoundedCall.call(Callable<String> {
                val sp = ApiConfig.get().getCSP(sourceBean)
                val ids = ArrayList<String>()
                ids.add(id)
                try {
                    SpiderReaper.track(sp) { sp.detailContent(ids) }
                } catch (e: Exception) {
                    AppLog.i("echo--getDetail--error: " + e.message)
                    ""
                }
            }, if (fallback) 6_000L else sourceBean.playTimeoutSeconds() * 1000L, "echo--getDetail--" + sourceBean.key)
            val body = json.orEmpty()
            AppLog.i("echo-detail-body key=" + sourceBean.key + " id=" + id + " len=" + body.length
                    + " fallback=" + fallback + " head=" + body.take(200).replace('\n', ' '))
            resultParser.json(detailResult, json, sourceBean.key, "", requestToken)
        }
    }

    private suspend fun detailFromApi(sourceBean: SourceBean, id: String, fallback: Boolean, requestToken: Int?) {
        val type = sourceBean.type

        val extend = withContext(Dispatchers.IO) {
            if (fallback) {
                SourceHelper.getFixUrl(extendCache, gson, sourceBean.ext, 6L)
            } else {
                SourceHelper.getFixUrl(extendCache, gson, sourceBean.ext, sourceBean.playTimeoutSeconds().toLong())
            }
        }

        try {
            val body = SourceHelper.siteGet(sourceBean) {
                params("ac", if (type == 0) "videolist" else "detail")
                params("ids", id)
                if (extend != null && extend.isNotEmpty()) {
                    params("extend", extend)
                }
            }
            withContext(Dispatchers.IO) {
                if (type == 0) {
                    resultParser.xml(detailResult, body, sourceBean.key, "", requestToken)
                } else {
                    AppLog.i(body)
                    resultParser.json(detailResult, body, sourceBean.key, "", requestToken)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { resultParser.json(detailResult, "", sourceBean.key, "", requestToken) }
        }
    }

    private fun createEmptyDetail(sourceKey: String?, requestToken: Int?): AbsXml {
        val data = AbsXml()
        data.sourceKey = sourceKey
        data.detailToken = requestToken
        return data
    }

    private fun createPushDetail(url: String?, sourceKey: String?, requestToken: Int?): AbsXml {
        val data = AbsXml()
        data.sourceKey = sourceKey
        data.detailToken = requestToken
        val movie = Movie()
        val videoList = ArrayList<Movie.Video>()
        movie.videoList = videoList
        val video = Movie.Video()
        video.id = url
        video.name = url
        // i18n: keep —— 以下是合成 Movie 的结构化数据(type/flag/`线路名$地址` 格式),会被持久化与比较,不能翻
        video.type = "推送"
        video.sourceKey = sourceKey
        val urlBean = Movie.Video.UrlBean()
        video.urlBean = urlBean
        val infoList = ArrayList<Movie.Video.UrlBean.UrlInfo>()
        urlBean.infoList = infoList
        val urlInfo = Movie.Video.UrlBean.UrlInfo()
        urlInfo.flag = "推送" // i18n: keep
        urlInfo.urls = "播放$url" // i18n: keep
        val beanList = ArrayList<Movie.Video.UrlBean.UrlInfo.InfoBean>()
        urlInfo.beanList = beanList
        beanList.add(Movie.Video.UrlBean.UrlInfo.InfoBean("播放", url)) // i18n: keep
        infoList.add(urlInfo)
        videoList.add(video)
        data.movie = movie
        return data
    }

}
