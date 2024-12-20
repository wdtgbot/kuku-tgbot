package me.kuku.telegram.logic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.contains
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import me.kuku.telegram.entity.WeiboEntity
import me.kuku.telegram.exception.qrcodeExpire
import me.kuku.telegram.exception.qrcodeNotScanned
import me.kuku.telegram.exception.qrcodeScanned
import me.kuku.telegram.utils.*
import org.jsoup.Jsoup

object WeiboLogic {

    suspend fun getIdByName(name: String, page: Int = 1): List<WeiboPojo> {
        val newName = name.toUrlEncode()
        val response = client.get("https://m.weibo.cn/api/container/getIndex?containerid=100103type%3D3%26q%3D$newName%26t%3D0&page_type=searchall&page=$page") {
            referer("https://m.weibo.cn/search?containerid=100103type%3D1%26q%3D$newName")
        }
        return if (response.status == HttpStatusCode.OK) {
            val jsonNode = response.body<JsonNode>()
            val cardsJsonArray = jsonNode.get("data").get("cards")
            var jsonArray: JsonNode? = null
            for (obj in cardsJsonArray) {
                val cardGroupJsonArray = obj.get("card_group")
                if (cardGroupJsonArray != null) {
                    jsonArray = cardGroupJsonArray
                    break
                }
            }
            if (jsonArray == null) error("没有找到该用户")
            val list = mutableListOf<WeiboPojo>()
            for (obj in jsonArray) {
                if (obj.has("user") || obj.has("users")) {
                    val userJsonNode = obj.get("user")
                    if (userJsonNode != null) {
                        val username = userJsonNode.get("name")?.asText() ?:
                        userJsonNode.get("screen_name").asText()
                        list.add(WeiboPojo(name = username, userid = userJsonNode["id"].asText()))
                    } else {
                        val usersJsonArray = obj.get("users")
                        for (any in usersJsonArray) {
                            val username = any.get("name")?.asText()
                                ?: any["screen_name"].asText()
                            list.add(WeiboPojo(name = username, userid = any["id"].asText()))
                        }
                    }
                }
            }
            if (list.isEmpty()) error("未找到该用户")
            else list
        } else error("查询失败，请稍后再试！")
    }

    private fun convert(jsonNode: JsonNode): WeiboPojo {
        val weiboPojo = WeiboPojo()
        val userJsonNode = jsonNode.get("user")
        weiboPojo.id = jsonNode["id"].asLong()
        weiboPojo.name = userJsonNode["screen_name"].asText()
        weiboPojo.created = jsonNode["created_at"].asText()
        weiboPojo.text = Jsoup.parse(jsonNode["text"].asText()).text()
        weiboPojo.bid = jsonNode["bid"].asText()
        weiboPojo.userid = userJsonNode["id"].asText()
        weiboPojo.ipFrom = jsonNode["region_name"]?.asText()?.split(" ")?.get(1) ?: "无"
        weiboPojo.url = "https://m.weibo.cn/status/${weiboPojo.bid}"
        val picNum = jsonNode["pic_num"].asInt()
        if (picNum != 0) {
            val list = weiboPojo.imageUrl
            val pics = jsonNode["pics"]
            if (pics is ArrayNode) {
                pics.map { it.get("large").get("url")}.forEach {
                    it?.let { list.add(it.asText()) }
                }
            } else if (pics is ObjectNode) {
                jsonNode.forEach { node ->
                    node.get("large")?.get("url")?.asText()?.let {
                        list.add(it)
                    }
                }
            }
        }
        jsonNode["page_info"]?.get("urls")?.get("mp4_720p_mp4")?.asText()?.let {
            weiboPojo.videoUrl = it
        }
        if (jsonNode.contains("retweeted_status")) {
            val forwardJsonNode = jsonNode.get("retweeted_status")
            weiboPojo.isForward = true
            weiboPojo.forwardId = forwardJsonNode["id"].asText()
            weiboPojo.forwardTime = forwardJsonNode["created_at"].asText()
            val forwardUserJsonNode = forwardJsonNode.get("user")
            val name = forwardUserJsonNode?.get("screen_name")?.asText() ?: "原微博删除"
            weiboPojo.forwardName = name
            weiboPojo.forwardText = Jsoup.parse(forwardJsonNode["text"].asText()).text()
            weiboPojo.forwardBid = forwardJsonNode["bid"].asText()
            weiboPojo.forwardIpFrom = forwardJsonNode["region_name"]?.asText()?.split(" ")?.get(1) ?: "无"
            forwardJsonNode["pic_ids"]?.forEach {
                val id = it.asText()
                weiboPojo.forwardImageUrl.add("https://wx1.sinaimg.cn/large/$id.jpg")
            }
            forwardJsonNode["page_info"]?.get("urls")?.get("mp4_720p_mp4")?.asText()?.let {
                weiboPojo.forwardVideoUrl = it
            }
            weiboPojo.forwardUrl = "https://m.weibo.cn/status/${weiboPojo.forwardBid}"
        }
        return weiboPojo
    }

    fun convert(weiboPojo: WeiboPojo, text: String = weiboPojo.text, forwardText: String = weiboPojo.forwardText): String {
        val sb = StringBuilder()
        val ipFrom = weiboPojo.ipFrom
        sb.appendLine("#${weiboPojo.name}")
            .appendLine("来自：${ipFrom.ifEmpty { "无" }}")
            .appendLine("发布时间：${weiboPojo.created}")
            .appendLine("内容：$text")
            .append("链接：${weiboPojo.url}")
        if (weiboPojo.isForward) {
            sb.appendLine()
                .appendLine("转发自：#${weiboPojo.forwardName}")
                .appendLine("发布时间：${weiboPojo.forwardTime}")
                .appendLine("内容：$forwardText")
                .appendLine("链接：${weiboPojo.forwardUrl}")
        }
        return sb.toString()
    }

    suspend fun getWeiboById(id: String): List<WeiboPojo> {
        val response = client.get("https://m.weibo.cn/api/container/getIndex?type=uid&uid=$id&containerid=107603$id")
        return if (response.status == HttpStatusCode.OK) {
            val jsonNode = response.body<JsonNode>()
            val cardJsonArray = jsonNode.get("data").get("cards")
            val list = mutableListOf<WeiboPojo>()
            for (any in cardJsonArray) {
                val blogJsonNode = any.get("mblog") ?: continue
                if (1 == any.get("isTop")?.asInt()) continue
                list.add(convert(blogJsonNode))
            }
            list
        } else error("查询失败，请稍后重试！")
    }

    suspend fun login1(): WeiboQrcode {
        val jsonNode = client.get("https://passport.weibo.com/sso/v2/qrcode/image?entry=wapsso&size=180") {
            referer("https://passport.weibo.com/sso/signin?entry=wapsso&source=wapssowb&url=https%3A%2F%2Fm.weibo.cn%2F")
        }.body<JsonNode>()
        val data = jsonNode["data"]
        return WeiboQrcode(data["qrid"].asText(), data["image"].asText())
    }

    @Suppress("DuplicatedCode")
    suspend fun login2(weiboQrcode: WeiboQrcode): WeiboEntity {
        val jsonNode = client.get("https://passport.weibo.com/sso/v2/qrcode/check?entry=wapsso&source=wapssowb&url=https:%2F%2Fm.weibo.cn%2F&qrid=${weiboQrcode.qrId}") {
            referer("https://passport.weibo.com/sso/signin?entry=wapsso&source=wapssowb&url=https%3A%2F%2Fm.weibo.cn%2F")
        }.body<JsonNode>()
        val code = jsonNode["retcode"].asInt()
        return when(code) {
            50114001 -> qrcodeNotScanned()
            50114002 -> qrcodeScanned()
            20000000 -> {
                val url = jsonNode["data"]["url"].asText()
                val firstResponse = client.get(url)
                val secondUrl = firstResponse.headers["location"]!!
                val secondResponse = client.get(secondUrl) {
                    referer("https://passport.weibo.com/")
                }
                val thirdUrl = secondResponse.headers["location"]!!
                val thirdResponse = client.get(thirdUrl) { referer("https://passport.weibo.com/") }
                val fourUrl = thirdResponse.headers["location"]!!
                val fourResponse = client.get(fourUrl) { referer("https://passport.weibo.com/") }
                val fiveUrl = fourResponse.headers["location"]!!
                val fiveResponse = client.get(fiveUrl) { referer("https://passport.weibo.com/") }
                WeiboEntity().also { it.cookie = fiveResponse.setCookie().renderCookieHeader() }
            }
            else -> qrcodeExpire()
        }
    }


    suspend fun friendWeibo(weiboEntity: WeiboEntity): List<WeiboPojo> {
        val str = client.get("https://m.weibo.cn/feed/friends?") {
            cookieString(weiboEntity.cookie)
        }.bodyAsText()
        return if ("" != str) {
            val jsonArray = kotlin.runCatching {
                Jackson.readTree(str).get("data").get("statuses")
            }.onFailure {
                error("查询微博失败，请稍后再试！！")
            }.getOrNull()!!
            val list = mutableListOf<WeiboPojo>()
            for (any in jsonArray) {
                list.add(convert(any))
            }
            list
        } else error("您的cookie已失效，请重新绑定微博")
    }

    private suspend fun group(weiboEntity: WeiboEntity): List<WeiboGroup> {
        val response = client.get("https://weibo.com/ajax/feed/allGroups?is_new_segment=1&fetch_hot=1") {
            headers { cookieString(weiboEntity.cookie) }
        }
        if (response.status == HttpStatusCode.Found) error("登陆失效，请重新登陆")
        val jsonNode = response.body<JsonNode>()
        val list = mutableListOf<WeiboGroup>()
        val groups = jsonNode["groups"]
        for (groupsNode in groups) {
            for (groupNode in groupsNode["group"]) {
                val gid = groupNode["gid"].asInt()
                val title = groupNode["title"].asText()
                list.add(WeiboGroup(title, gid))
            }
        }
        return list
    }

    private fun followConvert(jsonNode: JsonNode): WeiboPojo {
        val weiboPojo = WeiboPojo()
        val createdAt = jsonNode["created_at"].asText()
        val blogId = jsonNode["mblogid"].asText()
        val id = jsonNode["id"].asLong()
        val text = jsonNode["text_raw"].asText()
        val ipFrom = jsonNode["region_name"]?.asText() ?: ""
        val user = jsonNode["user"]
        val userid = user["id"].asLong()
        val username = user["screen_name"].asText()
        val videoUrl = jsonNode["page_info"]?.get("media_info")?.get("stream_url_hd")?.asText() ?: ""
        weiboPojo.id = id
        weiboPojo.name = username
        weiboPojo.userid = userid.toString()
        weiboPojo.created = createdAt
        weiboPojo.text = text
        weiboPojo.bid = blogId
        weiboPojo.ipFrom = ipFrom
        weiboPojo.url = "https://weibo.com/$userid/$blogId"
        weiboPojo.videoUrl = videoUrl
        val picIdNode = jsonNode["pic_ids"]
        val picInfoNode = jsonNode["pic_infos"]
        for (picNode in picIdNode) {
            val pic = picNode.asText()
            val url = picInfoNode[pic]["original"]["url"].asText()
            weiboPojo.imageUrl.add(url)
        }
        weiboPojo.longText = user["isLongText"]?.asBoolean() == true
        val retweeted = jsonNode["retweeted_status"]
        if (retweeted != null) {
            val forwardId = retweeted["id"].asLong()
            val forwardCreatedAt = retweeted["created_at"].asText()
            val forwardBlogId = retweeted["mblogid"].asText()
            val forwardUser = retweeted["user"]
            if (forwardUser != null) {
                val forwardUserid = forwardUser["id"].asLong()
                val forwardUsername = forwardUser["screen_name"].asText()
                val forwardIpFrom = retweeted["region_name"]?.asText() ?: ""
                val forwardText = retweeted["text_raw"].asText()
                val forwardPic = retweeted["pic_infos"]
                val picId = retweeted["pic_ids"]
                for (picNode in picId) {
                    val pic = picNode.asText()
                    val url = forwardPic[pic]["original"]["url"].asText()
                    weiboPojo.forwardImageUrl.add(url)
                }
                val forwardVideoUrl = retweeted["page_info"]?.get("media_info")?.get("stream_url_hd")?.asText() ?: ""
                weiboPojo.forwardName = forwardUsername
                weiboPojo.forwardUserid = forwardUserid
                weiboPojo.forwardText = forwardText
                weiboPojo.forwardIpFrom = forwardIpFrom
                weiboPojo.forwardVideoUrl = forwardVideoUrl
                weiboPojo.forwardUrl = "https://weibo.com/$forwardUserid/$forwardBlogId"
            }
            weiboPojo.isForward = true
            weiboPojo.forwardId = forwardId.toString()
            weiboPojo.forwardTime = forwardCreatedAt
            weiboPojo.forwardBid = forwardBlogId
            weiboPojo.forwardLongText = retweeted["isLongText"]?.asBoolean() == true
        }
        return weiboPojo
    }

    suspend fun followWeibo(weiboEntity: WeiboEntity): List<WeiboPojo> {
        val gid = try {
            group(weiboEntity).find { it.title == "最新微博" }?.gid ?: error("未找到最新微博的id")
        } catch (e: Exception) {
            "110003308815491".toInt()
        }
        val jsonNode = client.get("https://weibo.com/ajax/feed/friendstimeline?list_id=$gid&refresh=4&since_id=0&count=25&fid=$gid") {
            headers {
                cookieString(weiboEntity.cookie)
            }
        }.body<JsonNode>()
        val list = mutableListOf<WeiboPojo>()
        for (status in jsonNode["statuses"]) {
            if (status["user"]["following"].asBoolean()) {
                list.add(followConvert(status))
            }
        }
        return list
    }

    suspend fun longText(weiboEntity: WeiboEntity, bid: String): String {
        val response = client.get("https://weibo.com/ajax/statuses/longtext?id=$bid") {
            headers {
                cookieString(weiboEntity.cookie)
            }
        }
        if (response.status == HttpStatusCode.Found) error("登陆已失效，请重新登陆")
        val jsonNode = response.body<JsonNode>()
        if (jsonNode["ok"].asInt() == 0) error(jsonNode["message"].asText())
        return jsonNode["data"]?.get("longTextContent")?.asText() ?: ""
    }

    suspend fun myWeibo(weiboEntity: WeiboEntity): List<WeiboPojo> {
        val jsonNode = client.get("https://m.weibo.cn/profile/info") {
            cookieString(weiboEntity.cookie)
        }.body<JsonNode>()
        return if (jsonNode["ok"].asInt() == 1) {
            val jsonArray = jsonNode.get("data").get("statuses")
            val list = mutableListOf<WeiboPojo>()
            for (any in jsonArray) {
                list.add(convert(any))
            }
            list
        } else error("您的cookie已失效，请重新绑定微博")
    }

    private suspend fun getToken(weiboEntity: WeiboEntity): WeiboToken {
        val response = client.get("https://m.weibo.cn/api/config") {
            cookieString(weiboEntity.cookie)
        }
        val jsonNode = response.body<JsonNode>().get("data")
        return if (jsonNode["login"].asBoolean()) {
            val cookie = response.setCookie().renderCookieHeader()
            WeiboToken(jsonNode["st"].asText(), cookie + weiboEntity.cookie)
        } else error("cookie已失效")
    }

    suspend fun superTalkSign(weiboEntity: WeiboEntity) {
        val weiboToken = getToken(weiboEntity)
        val response = client.get("https://m.weibo.cn/api/container/getIndex?containerid=100803_-_followsuper&luicode=10000011&lfid=231093_-_chaohua") {
            cookieString(weiboToken.cookie)
            headers { append("x-xsrf-token", weiboToken.token) }
        }
        if (response.status != HttpStatusCode.OK) error("cookie已失效")
        val cookie = response.setCookie().renderCookieHeader()
        val jsonNode = response.body<JsonNode>()
        if (jsonNode["ok"].asInt() == 1) {
            val cardsJsonArray = jsonNode.get("data").get("cards").get(0).get("card_group")
            for (any in cardsJsonArray) {
                if (any.contains("buttons")) {
                    val buttonJsonArray = any.get("buttons")
                    for (bu in buttonJsonArray) {
                        if (bu["name"].asText() == "签到") {
                            val scheme = "https://m.weibo.cn${bu["scheme"].asText()}"
                            client.submitForm(scheme, parameters {
                                append("st", weiboToken.token)
                                append("_spr", "screen:393x851")
                            }) {
                                headers {
                                    mapOf("x-xsrf-token" to weiboToken.token, "cookie" to weiboToken.cookie + cookie,
                                        "referer" to "https://m.weibo.cn/p/tabbar?containerid=100803_-_followsuper&luicode=10000011&lfid=231093_-_chaohua&page_type=tabbar",
                                        "mweibo-pwa" to "1").forEach { append(it.key, it.value) }
                                }
                            }
                        }
                    }
                }
            }
        } else error("获取关注超话列表失败")
    }

}

data class WeiboGroup(
    var title: String = "",
    var gid: Int = 0
)

data class WeiboPojo(
    var id: Long = 0,
    var name: String = "",
    var userid: String = "",
    var created: String = "",
    var text: String = "",
    var bid: String = "",
    var ipFrom: String = "",
    var url: String = "",
    var longText: Boolean = false,
    var imageUrl: MutableList<String> = mutableListOf(),
    var videoUrl: String = "",
    var isForward: Boolean = false,
    var forwardId: String = "",
    var forwardTime: String = "",
    var forwardName: String = "",
    var forwardUserid: Long = 0,
    var forwardText: String = "",
    var forwardBid: String = "",
    var forwardIpFrom: String = "",
    var forwardLongText: Boolean = false,
    var forwardImageUrl: MutableList<String> = mutableListOf(),
    var forwardVideoUrl: String = "",
    var forwardUrl: String = ""
)

data class WeiboToken(
    var token: String = "",
    var cookie: String = ""
)

data class WeiboLoginVerify(val cookie: String, val phone: String, val id: String)

data class WeiboQrcode(val qrId: String, val image: String)