package me.kuku.telegram.logic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.contains
import me.kuku.pojo.CommonResult
import me.kuku.pojo.ResultStatus
import me.kuku.pojo.UA
import me.kuku.telegram.entity.WeiboEntity
import me.kuku.utils.*
import org.jsoup.Jsoup

object WeiboLogic {

    suspend fun getIdByName(name: String, page: Int = 1): CommonResult<List<WeiboPojo>> {
        val newName = name.toUrlEncode()
        val response = OkHttpKtUtils.get("https://m.weibo.cn/api/container/getIndex?containerid=100103type%3D3%26q%3D$newName%26t%3D0&page_type=searchall&page=$page",
            OkUtils.referer("https://m.weibo.cn/search?containerid=100103type%3D1%26q%3D$newName"))
        return if (response.code == 200) {
            val jsonNode = OkUtils.json(response)
            val cardsJsonArray = jsonNode.get("data").get("cards")
            var jsonArray: JsonNode? = null
            for (obj in cardsJsonArray) {
                val cardGroupJsonArray = obj.get("card_group")
                if (cardGroupJsonArray != null) {
                    jsonArray = cardGroupJsonArray
                    break
                }
            }
            if (jsonArray == null) return CommonResult.failure("没有找到该用户")
            val list = mutableListOf<WeiboPojo>()
            for (obj in jsonArray) {
                if (obj.has("user") || obj.has("users")) {
                    val userJsonNode = obj.get("user")
                    if (userJsonNode != null) {
                        val username = userJsonNode.get("name")?.asText() ?:
                        userJsonNode.get("screen_name").asText()
                        list.add(WeiboPojo(name = username, userid = userJsonNode.getString("id")))
                    } else {
                        val usersJsonArray = obj.get("users")
                        for (any in usersJsonArray) {
                            val username = any.get("name")?.asText()
                                ?: any.getString("screen_name")
                            list.add(WeiboPojo(name = username, userid = any.getString("id")))
                        }
                    }
                }
            }
            if (list.isEmpty()) CommonResult.failure("未找到该用户")
            else CommonResult.success(list)
        } else CommonResult.failure("查询失败，请稍后再试！")
    }

    private fun convert(jsonNode: JsonNode): WeiboPojo {
        val weiboPojo = WeiboPojo()
        val userJsonNode = jsonNode.get("user")
        weiboPojo.id = jsonNode.getLong("id")
        weiboPojo.name = userJsonNode.getString("screen_name")
        weiboPojo.created = jsonNode.getString("created_at")
        weiboPojo.text = Jsoup.parse(jsonNode.getString("text")).text()
        weiboPojo.bid = jsonNode.getString("bid")
        weiboPojo.userid = userJsonNode.getString("id")
        val picNum = jsonNode.getInteger("pic_num")
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
        if (jsonNode.contains("retweeted_status")) {
            val forwardJsonNode = jsonNode.get("retweeted_status")
            weiboPojo.isForward = true
            weiboPojo.forwardId = forwardJsonNode.getString("id")
            weiboPojo.forwardTime = forwardJsonNode.getString("created_at")
            val forwardUserJsonNode = forwardJsonNode.get("user")
            val name = forwardUserJsonNode?.getString("screen_name") ?: "原微博删除"
            weiboPojo.forwardName = name
            weiboPojo.forwardText = Jsoup.parse(forwardJsonNode.getString("text")).text()
            weiboPojo.forwardBid = forwardJsonNode.getString("bid")
        }
        return weiboPojo
    }

    fun convert(weiboPojo: WeiboPojo): String {
        val sb = StringBuilder()
        sb.append("""
            ${weiboPojo.name}
            发布时间：${weiboPojo.created}
            内容：${weiboPojo.text}
            链接：https://m.weibo.cn/status/${weiboPojo.bid}
        """.trimIndent())
        if (weiboPojo.isForward) {
            sb.append("\n")
            sb.append("""
                转发自：${weiboPojo.forwardName}
                发布时间：${weiboPojo.forwardTime}
                内容：${weiboPojo.forwardText}
                链接：https://m.weibo.cn/status/${weiboPojo.forwardBid}
            """.trimIndent())
        }
        return sb.toString()
    }

    suspend fun getWeiboById(id: String): CommonResult<List<WeiboPojo>> {
        val response = OkHttpKtUtils.get("https://m.weibo.cn/api/container/getIndex?type=uid&uid=$id&containerid=107603$id")
        return if (response.code == 200) {
            val jsonNode = OkUtils.json(response)
            val cardJsonArray = jsonNode.get("data").get("cards")
            val list = mutableListOf<WeiboPojo>()
            for (any in cardJsonArray) {
                val blogJsonNode = any.get("mblog") ?: continue
                if (1 == any.get("isTop")?.asInt()) continue
                list.add(convert(blogJsonNode))
            }
            CommonResult.success(list)
        } else CommonResult.failure("查询失败，请稍后重试！")
    }

    private suspend fun mobileCookie(pcCookie: String): String {
        val response = OkHttpKtUtils.get("https://login.sina.com.cn/sso/login.php?url=https%3A%2F%2Fm.weibo.cn%2F%3F%26jumpfrom%3Dweibocom&_rand=1588483688.7261&gateway=1&service=sinawap&entry=sinawap&useticket=1&returntype=META&sudaref=&_client_version=0.6.33",
            OkUtils.cookie(pcCookie)).apply { close() }
        return OkUtils.cookie(response)
    }

    suspend fun loginByQr1(): WeiboQrcode {
        val jsonNode = OkHttpKtUtils.getJsonp("https://login.sina.com.cn/sso/qrcode/image?entry=weibo&size=180&callback=STK_16010457545441",
            OkUtils.referer("https://weibo.com/"))
        val dataJsonNode = jsonNode.get("data")
        return WeiboQrcode(dataJsonNode.getString("qrid"), dataJsonNode.getString("image"))
    }

    suspend fun loginByQr2(weiboQrcode: WeiboQrcode): CommonResult<WeiboEntity> {
        val jsonNode = OkHttpKtUtils.getJsonp("https://login.sina.com.cn/sso/qrcode/check?entry=weibo&qrid=${weiboQrcode.id}&callback=STK_16010457545443",
            OkUtils.referer("https://weibo.com/"))
        return when (jsonNode.getInteger("retcode")) {
            20000000 -> {
                val dataJsonNode = jsonNode.get("data")
                val alt = dataJsonNode.getString("alt")
                val response = OkHttpKtUtils.get("https://login.sina.com.cn/sso/login.php?entry=weibo&returntype=TEXT&crossdomain=1&cdult=3&domain=weibo.com&alt=$alt&savestate=30&callback=STK_160104719639113")
                val cookie = OkUtils.cookie(response)
                val resultJsonNode = OkUtils.jsonp(response)
                val jsonArray = resultJsonNode.get("crossDomainUrlList")
                val url = jsonArray.getString(2)
                val finallyResponse = OkHttpKtUtils.get(url).apply { close() }
                OkUtils.cookie(finallyResponse)
                val mobileCookie = mobileCookie(cookie)
                CommonResult.success(WeiboEntity().also {
                    it.pcCookie = cookie
                    it.mobileCookie = mobileCookie
                })
            }
            50114001 -> CommonResult.failure(code = 201, message = "未扫码")
            50114003 -> CommonResult.failure("您的微博登录二维码已失效")
            50114002 -> CommonResult.failure(code = 202, message = "已扫码")
            else -> CommonResult.failure(jsonNode.getString("msg"), null)
        }
    }

    suspend fun friendWeibo(weiboEntity: WeiboEntity): CommonResult<List<WeiboPojo>> {
        val str = OkHttpKtUtils.getStr("https://m.weibo.cn/feed/friends?",
            OkUtils.cookie(weiboEntity.mobileCookie))
        return if ("" != str) {
            val jsonArray = kotlin.runCatching {
                Jackson.parse(str).get("data").get("statuses")
            }.onFailure {
                return CommonResult.failure("查询微博失败，请稍后再试！！", null)
            }.getOrNull()!!
            val list = mutableListOf<WeiboPojo>()
            for (any in jsonArray) {
                list.add(convert(any))
            }
            CommonResult.success(list)
        } else CommonResult.failure("您的cookie已失效，请重新绑定微博")
    }

    suspend fun myWeibo(weiboEntity: WeiboEntity): CommonResult<List<WeiboPojo>> {
        val jsonNode = OkHttpKtUtils.getJson("https://m.weibo.cn/profile/info",
            OkUtils.cookie(weiboEntity.mobileCookie))
        return if (jsonNode.getInteger("ok") == 1) {
            val jsonArray = jsonNode.get("data").get("statuses")
            val list = mutableListOf<WeiboPojo>()
            for (any in jsonArray) {
                list.add(convert(any))
            }
            CommonResult.success(list)
        } else CommonResult.failure("您的cookie已失效，请重新绑定微博")
    }

    private suspend fun getToken(weiboEntity: WeiboEntity): WeiboToken {
        val response = OkHttpKtUtils.get("https://m.weibo.cn/api/config",
            OkUtils.cookie(weiboEntity.mobileCookie))
        val jsonNode = OkUtils.json(response).get("data")
        return if (jsonNode.getBoolean("login")) {
            val cookie = OkUtils.cookie(response)
            WeiboToken(jsonNode.getString("st"), cookie + weiboEntity.mobileCookie)
        } else throw WeiboCookieExpiredException("cookie已失效")
    }

    suspend fun superTalkSign(weiboEntity: WeiboEntity): CommonResult<Void> {
        val weiboToken = getToken(weiboEntity)
        val response = OkHttpKtUtils.get("https://m.weibo.cn/api/container/getIndex?containerid=100803_-_followsuper&luicode=10000011&lfid=231093_-_chaohua",
            mapOf("cookie" to weiboToken.cookie, "x-xsrf-token" to weiboToken.token)
        )
        if (response.code != 200) return CommonResult.failure(ResultStatus.COOKIE_EXPIRED)
        val cookie = OkUtils.cookie(response)
        val jsonNode = OkUtils.json(response)
        return if (jsonNode.getInteger("ok") == 1) {
            val cardsJsonArray = jsonNode.get("data").get("cards").get(0).get("card_group")
            for (any in cardsJsonArray) {
                if (any.contains("buttons")) {
                    val buttonJsonArray = any.get("buttons")
                    for (bu in buttonJsonArray) {
                        if (bu.getString("name") == "签到") {
                            val scheme = "https://m.weibo.cn${bu.getString("scheme")}"
                            OkHttpKtUtils.postJson(scheme,
                                mapOf("st" to weiboToken.token, "_spr" to "screen:393x851"),
                                mapOf("x-xsrf-token" to weiboToken.token, "cookie" to weiboToken.cookie + cookie,
                                    "referer" to "https://m.weibo.cn/p/tabbar?containerid=100803_-_followsuper&luicode=10000011&lfid=231093_-_chaohua&page_type=tabbar",
                                    "user-agent" to UA.PC.value, "mweibo-pwa" to "1")
                            )
                        }
                    }
                }
            }
            CommonResult.success()
        } else CommonResult.failure("获取关注超话列表失败")
    }

}

data class HotSearch(
    var count: Int = 0,
    var content: String = "",
    var heat: Long = 0,
    var tag: String = "",
    var url: String = ""
)

data class WeiboPojo(
    var id: Long = 0,
    var name: String = "",
    var userid: String = "",
    var created: String = "",
    var text: String = "",
    var bid: String = "",
    var imageUrl: MutableList<String> = mutableListOf(),
    var isForward: Boolean = false,
    var forwardId: String = "",
    var forwardTime: String = "",
    var forwardName: String = "",
    var forwardText: String = "",
    var forwardBid: String = ""
)

data class WeiboQrcode(
    var id: String = "",
    var url: String = ""
)

data class WeiboToken(
    var token: String = "",
    var cookie: String = ""
)

class WeiboCookieExpiredException(msg: String): RuntimeException(msg)