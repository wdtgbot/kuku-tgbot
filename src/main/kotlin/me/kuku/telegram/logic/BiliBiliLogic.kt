package me.kuku.telegram.logic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.contains
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import me.kuku.pojo.CommonResult
import me.kuku.pojo.UA
import me.kuku.telegram.entity.BiliBiliEntity
import me.kuku.telegram.utils.ffmpeg
import me.kuku.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import java.io.File

object BiliBiliLogic {

    suspend fun getIdByName(username: String): CommonResult<List<BiliBiliPojo>> {
        val enUsername = username.toUrlEncode()
        val jsonNode = OkHttpKtUtils.getJsonp("https://api.bilibili.com/x/web-interface/search/type?context=&search_type=bili_user&page=1&order=&keyword=$enUsername&category_id=&user_type=&order_sort=&changing=mid&__refresh__=true&_extra=&highlight=1&single_column=0&jsonp=jsonp&callback=__jp2",
            OkUtils.referer("https://search.bilibili.com/topic?keyword=$enUsername"))
        val dataJsonNode = jsonNode["data"]
        return if (dataJsonNode.getInteger("numCommonResults") != 0) {
            val jsonArray = dataJsonNode["result"]
            val list = mutableListOf<BiliBiliPojo>()
            for (obj in jsonArray) {
                list.add(
                    BiliBiliPojo(userId = obj.getString("mid"),
                        name = obj.getString("uname"))
                )
            }
            CommonResult.success(list)
        } else CommonResult.failure("not result")
    }

    private fun convert(jsonNode: JsonNode): BiliBiliPojo {
        val biliBiliPojo = BiliBiliPojo()
        val descJsonNode = jsonNode["desc"]
        val infoJsonNode = descJsonNode["user_profile"]?.get("info")
        val forwardJsonNode = descJsonNode["origin"]
        biliBiliPojo.userId = infoJsonNode?.get("uid")?.asText() ?: ""
        biliBiliPojo.name = infoJsonNode?.get("uname")?.asText() ?: ""
        biliBiliPojo.id = descJsonNode.getString("dynamic_id")
        biliBiliPojo.rid = descJsonNode.getString("rid")
        biliBiliPojo.time = (descJsonNode.getString("timestamp") + "000").toLong()
        biliBiliPojo.bvId = descJsonNode.get("bvid")?.asText() ?: ""
        biliBiliPojo.isForward = !forwardJsonNode.isNull
        if (!forwardJsonNode.isNull) {
            biliBiliPojo.forwardBvId = forwardJsonNode["bvid"]?.asText() ?: ""
            if (biliBiliPojo.forwardBvId.isEmpty()) {
                val rid = forwardJsonNode["rid"].asInt()
                if (rid != 0)
                    biliBiliPojo.forwardBvId = "av$rid"
            }
            forwardJsonNode.get("timestamp")?.asText()?.let {
                biliBiliPojo.forwardTime = (it + "000").toLong()
            }
            biliBiliPojo.forwardId = forwardJsonNode.getString("dynamic_id")
        }
        var text: String? = null
        jsonNode["card"]?.asText()?.let { Jackson.parse(it) }?.let { cardJsonNode ->
            if (biliBiliPojo.userId.isEmpty()) {
                val collectionJsonNode = cardJsonNode["collection"]
                biliBiliPojo.userId = collectionJsonNode.getString("id")
                biliBiliPojo.name = collectionJsonNode["name"]?.asText() ?: ""
            }
            val itemJsonNode = cardJsonNode["item"]
            text = cardJsonNode["dynamic"]?.asText()
            val picList = biliBiliPojo.picList
            if (biliBiliPojo.bvId.isNotEmpty()) {
                cardJsonNode["pic"]?.asText()?.let {
                    picList.add(it)
                }
            }
            if (itemJsonNode != null) {
                if (text == null) text = itemJsonNode["description"]?.asText()
                if (text == null) text = itemJsonNode["content"]?.asText()
                itemJsonNode["pictures"]?.forEach {
                    picList.add(it.getString("img_src"))
                }
            }
            if (text == null) {
                cardJsonNode["vest"]?.let {
                    text = it.getString("content")
                }
            }
            if (text == null && cardJsonNode.contains("title")) {
                text = cardJsonNode.getString("title") + "------" + cardJsonNode.getString("summary")
            }
            cardJsonNode["pub_location"]?.asText()?.let { location ->
                biliBiliPojo.ipFrom = location
            }
            val originStr = cardJsonNode["origin"]?.asText()
            if (originStr != null && (originStr.startsWith("{") || originStr.startsWith("["))) {
                val forwardPicList = biliBiliPojo.forwardPicList
                val forwardContentJsonNode = originStr.toJsonNode()
                if (biliBiliPojo.forwardBvId.isNotEmpty()) {
                    forwardContentJsonNode["pic"]?.let {
                        forwardPicList.add(it.asText())
                    }
                }
                val ctime = forwardContentJsonNode["ctime"].asInt()
                biliBiliPojo.forwardTime = ctime * 1000L
                if (forwardContentJsonNode.contains("item")) {
                    val forwardItemJsonNode = forwardContentJsonNode["item"]
                    biliBiliPojo.forwardText = forwardItemJsonNode["description"]?.asText() ?: ""
                    if (biliBiliPojo.forwardText.isEmpty())
                        biliBiliPojo.forwardText = forwardItemJsonNode.getString("content")
                    val forwardPicJsonArray = forwardItemJsonNode["pictures"]
                    if (forwardPicJsonArray != null) {
                        for (obj in forwardPicJsonArray) {
                            forwardPicList.add(obj.getString("img_src"))
                        }
                    }
                    val forwardUserJsonNode = forwardContentJsonNode["user"]
                    if (forwardUserJsonNode != null) {
                        biliBiliPojo.forwardUserId = forwardUserJsonNode.getString("uid")
                        biliBiliPojo.forwardName = forwardUserJsonNode["name"]?.asText() ?: forwardUserJsonNode.getString("uname")
                    } else {
                        val forwardOwnerJsonNode = forwardContentJsonNode["owner"]
                        if (forwardOwnerJsonNode != null) {
                            biliBiliPojo.forwardUserId = forwardOwnerJsonNode.getString("mid")
                            biliBiliPojo.forwardName = forwardOwnerJsonNode.getString("name")

                        }
                    }
                } else {
                    biliBiliPojo.forwardText = forwardContentJsonNode["dynamic"]?.asText() ?: "没有动态内容"
                    val forwardOwnerJsonNode = forwardContentJsonNode["owner"]
                    if (forwardOwnerJsonNode != null) {
                        biliBiliPojo.forwardUserId = forwardOwnerJsonNode["mid"]?.asText() ?: ""
                        biliBiliPojo.forwardName = forwardOwnerJsonNode["name"]?.asText() ?: ""
                    } else {
                        biliBiliPojo.forwardName = forwardContentJsonNode["uname"]?.asText() ?: ""
                        biliBiliPojo.forwardUserId = forwardContentJsonNode["uid"]?.asText() ?: ""
                        biliBiliPojo.forwardText = forwardContentJsonNode["title"]?.asText() ?: ""
                    }
                }
            }
            cardJsonNode["title"]?.asText()?.takeIf { it.isNotEmpty() }?.let {
                text += "|$it"
            }
            cardJsonNode["desc"]?.asText()?.takeIf { it.isNotEmpty() }?.let {
                text += "|$it"
            }
        }
        biliBiliPojo.text = text ?: "无"
        val type = if (biliBiliPojo.bvId.isEmpty()) {
            if (biliBiliPojo.picList.isEmpty()) 17
            else 11
        }else 1
        biliBiliPojo.type = type
        return biliBiliPojo
    }

    fun convertStr(biliBiliPojo: BiliBiliPojo): String {
        val pattern = "yyyy-MM-dd HH:mm:ss"
        val bvId = biliBiliPojo.bvId
        val ipFrom = biliBiliPojo.ipFrom
        val forwardBvId = biliBiliPojo.forwardBvId
        var ss = "#${biliBiliPojo.name}\n来自：${ipFrom.ifEmpty { "无" }}\n发布时间：${DateTimeFormatterUtils.format(biliBiliPojo.time, pattern)}" +
                "\n内容：${biliBiliPojo.text}\n动态链接：https://t.bilibili.com/${biliBiliPojo.id}\n视频链接：${if (bvId.isNotEmpty()) "https://www.bilibili.com/video/$bvId" else "无"}"
        if (biliBiliPojo.isForward) {
            ss += "\n转发自：#${biliBiliPojo.forwardName}\n发布时间：${DateTimeFormatterUtils.format(biliBiliPojo.forwardTime, pattern)}\n" +
                    "内容：${biliBiliPojo.forwardText}\n动态链接：https://t.bilibili.com/${biliBiliPojo.forwardId}\n视频链接：${if (forwardBvId.isNotEmpty()) "https://www.bilibili.com/video/$forwardBvId" else "无"}"
        }
        return ss
    }

    suspend fun videoByBvId(biliBiliEntity: BiliBiliEntity, bvId: String): File {
        val htmlUrl = "https://www.bilibili.com/video/$bvId/"
        val response = client.get(htmlUrl) {
            cookieString(biliBiliEntity.cookie)
            userAgent(UA.PC.value)
        }
        return if (response.status != HttpStatusCode.OK) {
            error("错误：${response.status}")
        } else {
            val html = response.bodyAsText()
            val jsonNode = MyUtils.regex("window.__playinfo__=", "</sc", html)?.toJsonNode() ?: error("未获取到内容")
            val videoUrl = jsonNode["data"]["dash"]["video"][0]["baseUrl"].asText()
            val audioUrl = jsonNode["data"]["dash"]["audio"][0]["baseUrl"].asText()
            val videoFile: File
            val audioFile: File
            OkHttpKtUtils.getByteStream(videoUrl, OkUtils.referer(htmlUrl)).use {
                videoFile = IOUtils.writeTmpFile("${bvId}.mp4", it, false)
            }
            OkHttpKtUtils.getByteStream(audioUrl, OkUtils.referer(htmlUrl)).use {
                audioFile = IOUtils.writeTmpFile("${bvId}.mp3", it, false)
            }
            val videoPath = videoFile.absolutePath
            val audioPath = audioFile.absolutePath
            val outputPath = videoPath.replace(bvId, "${bvId}output")
            ffmpeg("ffmpeg -i $videoPath -i $audioPath -c:v copy -c:a aac -strict experimental $outputPath")
            videoFile.delete()
            audioFile.delete()
            File(outputPath)
        }
    }

    suspend fun getDynamicById(id: String, offsetId: String = "0"): CommonResult<List<BiliBiliPojo>> {
        val jsonNode = OkHttpKtUtils.getJson("https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/space_history?visitor_uid=0&host_uid=$id&offset_dynamic_id=$offsetId&need_top=1",
            OkUtils.referer("https://space.bilibili.com/$id/dynamic"))
        // next_offset  下一页开头
        val dataJsonNode = jsonNode["data"]
        val jsonArray = dataJsonNode["cards"] ?: return CommonResult.failure("动态未找到")
        val list = mutableListOf<BiliBiliPojo>()
        for (obj in jsonArray) {
            val extraJsonNode = obj["extra"]
            if (extraJsonNode != null && 1 == extraJsonNode.getInteger("is_space_top")) continue
            list.add(convert(obj))
        }
        return CommonResult.success(message = dataJsonNode.getString("next_offset"), data = list)
    }

    suspend fun loginByQr1(): BiliBiliQrcode {
        val jsonNode = OkHttpKtUtils.getJson("https://passport.bilibili.com/x/passport-login/web/qrcode/generate?source=main-fe-header")
        val data = jsonNode["data"]
        return BiliBiliQrcode(data["url"].asText(), data["qrcode_key"].asText())
    }

    suspend fun loginByQr2(qrcode: BiliBiliQrcode): CommonResult<BiliBiliEntity> {
        val response = OkHttpKtUtils.get("https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=${qrcode.key}&source=main-fe-header")
        val jsonNode = OkUtils.json(response)
        val data = jsonNode["data"]
        return when(data["code"].asInt()) {
            86101 -> CommonResult.failure("二维码未扫描", code = 0)
            86090 -> CommonResult.failure("二维码已扫描", code = 0)
            86038 -> CommonResult.failure("您的二维码已过期！！", null)
            0 -> {
                val firstCookie = OkUtils.cookie(response)
                val url = data["url"].asText()
                val token = MyUtils.regex("bili_jct=", "\\u0026", url)!!
                val urlJsonNode =
                    OkHttpKtUtils.getJson("https://passport.bilibili.com/x/passport-login/web/sso/list?biliCSRF=$token",
                        OkUtils.cookie(firstCookie))
                val sso = urlJsonNode["data"]["sso"]
                var cookie = ""
                sso.forEach {
                    val innerUrl = it.asText()
                    val innerResponse = OkHttpKtUtils.post(innerUrl, "".toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                        mapOf("Referer" to "https://www.bilibili.com/", "Origin" to "https://www.bilibili.com",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"))
                    innerResponse.close()
                    cookie = OkUtils.cookie(innerResponse)
                }
                val userid = MyUtils.regex("DedeUserID=", "; ", cookie)!!
                val fingerJsonNode = OkHttpKtUtils.getJson("https://api.bilibili.com/x/frontend/finger/spi")
                val fingerData = fingerJsonNode["data"]
                val fingerCookie = "buvid3=${fingerData["b_3"].asText()}; buvid4=${fingerData["b_4"].asText()}; "
                val biliBiliEntity = BiliBiliEntity()
                biliBiliEntity.cookie = cookie + fingerCookie
                biliBiliEntity.userid = userid
                biliBiliEntity.token = token
                CommonResult.success(biliBiliEntity)
            }
            else -> CommonResult.failure(data["message"].asText(), null)
        }
    }

    suspend fun friendDynamic(biliBiliEntity: BiliBiliEntity): CommonResult<List<BiliBiliPojo>> {
        val jsonNode = OkHttpKtUtils.getJson("https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/dynamic_new?type_list=268435455",
            OkUtils.cookie(biliBiliEntity.cookie))
        return if (jsonNode.getInteger("code") != 0)  CommonResult.failure("cookie已失效")
        else {
            val list = mutableListOf<BiliBiliPojo>()
            jsonNode["data"]["cards"].forEach{
                list.add(convert(it))
            }
            CommonResult.success(list)
        }
    }


    suspend fun live(biliBiliEntity: BiliBiliEntity, page: Int = 1,
                     list: MutableList<BiliBiliLive> = mutableListOf()): List<BiliBiliLive> {
        val jsonNode = client.get("https://api.live.bilibili.com/xlive/web-ucenter/user/following?page=$page&page_size=29&ignoreRecord=1&hit_ab=true") {
            cookieString(biliBiliEntity.cookie)
        }.body<JsonNode>()
        jsonNode.check()
        val dataList = jsonNode["data"]["list"]
        return if (dataList.isEmpty) list
        else {
            for (node in dataList) {
                val title = node["title"].asText()
                val roomId = node["roomid"].asText()
                val url = "https://live.bilibili.com/$roomId"
                val id = node["uid"].asText()
                val imageUrl = node["room_cover"].asText()
                val status = node["live_status"].asInt()
                val uname = node["uname"].asText()
                list.add(BiliBiliLive(title, id, url, imageUrl, status == 1, uname))
            }
            delay(1000)
            live(biliBiliEntity, page + 1, list)
        }

    }

    suspend fun liveSign(biliBiliEntity: BiliBiliEntity): String {
        val jsonNode = OkHttpKtUtils.getJson("https://api.live.bilibili.com/xlive/web-ucenter/v1/sign/DoSign",
            OkUtils.cookie(biliBiliEntity.cookie))
        return if (jsonNode.getInteger("code") == 0) "成功"
        else error(jsonNode.getString("message"))
    }

    suspend fun like(biliBiliEntity: BiliBiliEntity, id: String, isLike: Boolean): CommonResult<Void> {
        val map = mapOf("uid" to biliBiliEntity.userid, "dynamic_id" to id,
            "up" to if (isLike) "1" else "2", "csrf_token" to biliBiliEntity.token)
        val jsonNode = OkHttpKtUtils.postJson("https://api.vc.bilibili.com/dynamic_like/v1/dynamic_like/thumb", map,
            OkUtils.cookie(biliBiliEntity.cookie))
        return if (jsonNode.getInteger("code") == 0) CommonResult.success()
        else CommonResult.failure("赞动态失败，${jsonNode.getString("message")}")
    }

    suspend fun comment(biliBiliEntity: BiliBiliEntity, rid: String, type: String, content: String): CommonResult<Void> {
        val map = mapOf("oid" to rid, "type" to type, "message" to content, "plat" to "1",
            "jsoup" to "jsoup", "csrf_token" to biliBiliEntity.token)
        val jsonNode = OkHttpKtUtils.postJson("https://api.bilibili.com/x/v2/reply/add", map, OkUtils.cookie(biliBiliEntity.cookie))
        return if (jsonNode.getInteger("code") == 0) CommonResult.success()
        else CommonResult.failure("评论动态失败，${jsonNode.getString("message")}")
    }

    suspend fun forward(biliBiliEntity: BiliBiliEntity, id: String, content: String): CommonResult<Void> {
        val map = mapOf("uid" to biliBiliEntity.userid, "dynamic_id" to id,
            "content" to content, "extension" to "{\"emoji_type\":1}", "at_uids" to "", "ctrl" to "[]",
            "csrf_token" to biliBiliEntity.token)
        val jsonNode = OkHttpKtUtils.postJson("https://api.vc.bilibili.com/dynamic_repost/v1/dynamic_repost/repost", map,
            OkUtils.cookie(biliBiliEntity.cookie))
        return if (jsonNode.getInteger("code") == 0) CommonResult.success()
        else CommonResult.failure("转发动态失败，${jsonNode.getString("message")}")
    }

    suspend fun tossCoin(biliBiliEntity: BiliBiliEntity, rid: String, count: Int = 1): CommonResult<Void> {
        val map = mapOf("aid" to rid, "multiply" to count.toString(), "select_like" to "1",
            "cross_domain" to "true", "csrf" to biliBiliEntity.token)
        val jsonNode = OkHttpKtUtils.postJson("https://api.bilibili.com/x/web-interface/coin/add", map,
            OkUtils.headers(biliBiliEntity.cookie, "https://www.bilibili.com/video/"))
        return if (jsonNode.getInteger("code") == 0) CommonResult.success()
        else CommonResult.failure("对该动态（视频）投硬币失败，${jsonNode.getString("message")}")
    }

    suspend fun favorites(biliBiliEntity: BiliBiliEntity, rid: String, name: String): CommonResult<Void> {
        val userid = biliBiliEntity.userid
        val cookie = biliBiliEntity.cookie
        val token = biliBiliEntity.token
        val firstJsonNode = OkHttpKtUtils.getJson("https://api.bilibili.com/x/v3/fav/folder/created/list-all?type=2&rid=$rid&up_mid=$userid",
            OkUtils.cookie(cookie))
        if (firstJsonNode.getInteger("code") != 0) return CommonResult.failure("收藏失败，请重新绑定哔哩哔哩")
        val jsonArray = firstJsonNode["data"]["list"]
        var favoriteId: String? = null
        for (obj in jsonArray) {
            if (obj.getString("title") == name) {
                favoriteId = obj.getString("id")
            }
        }
        if (favoriteId == null) {
            val map = mapOf("title" to name, "privacy" to "0", "jsonp" to "jsonp", "csrf" to token)
            val jsonNode = OkHttpKtUtils.postJson("https://api.bilibili.com/x/v3/fav/folder/add", map,
                OkUtils.cookie(cookie))
            if (jsonNode.getInteger("code") != 0) return CommonResult.failure("您并没有该收藏夹，而且创建该收藏夹失败，请重试！！")
            favoriteId = jsonNode["data"]["id"].asText()
        }
        val map = mapOf("rid" to rid, "type" to "2", "add_media_ids" to favoriteId!!,
            "del_media_ids" to "", "jsonp" to "jsonp", "csrf" to token)
        val jsonNode = OkHttpKtUtils.postJson("https://api.bilibili.com/x/v3/fav/resource/deal", map,
            OkUtils.cookie(cookie))
        return if (jsonNode.getInteger("code") == 0) CommonResult.success()
        else CommonResult.failure("收藏视频失败，" + jsonNode.getString("message"))
    }

    private suspend fun uploadImage(biliBiliEntity: BiliBiliEntity, byteString: ByteString): CommonResult<JsonNode> {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file_up", "123.jpg", OkUtils.stream(byteString))
            .addFormDataPart("biz", "draw")
            .addFormDataPart("category", "daily").build()
        val jsonNode = OkHttpKtUtils.postJson("https://api.vc.bilibili.com/api/v1/drawImage/upload", body,
            OkUtils.cookie(biliBiliEntity.cookie))
        return if (jsonNode.getInteger("code") == 0) CommonResult.success(jsonNode["data"])
        else CommonResult.failure("图片上传失败，" + jsonNode.getString("message"), null)
    }

    suspend fun publishDynamic(biliBiliEntity: BiliBiliEntity, content: String, images: List<String>): CommonResult<Void> {
        val jsonArray = Jackson.createArrayNode()
        images.forEach{
            jsonArray.addPOJO(uploadImage(biliBiliEntity, OkHttpKtUtils.getByteString(it)))
        }
        val map = mapOf("biz" to "3", "category" to "3", "type" to "0", "pictures" to jsonArray.toString(),
            "title" to "", "tags" to "", "description" to content, "content" to content, "setting" to "{\"copy_forbidden\":0,\"cachedTime\":0}",
            "from" to "create.dynamic.web", "up_choose_comment" to "0", "extension" to "{\"emoji_type\":1,\"from\":{\"emoji_type\":1},\"flag_cfg\":{}}",
            "at_uids" to "", "at_control" to "", "csrf_token" to biliBiliEntity.token)
        val jsonNode = OkHttpKtUtils.postJson("https://api.vc.bilibili.com/dynamic_svr/v1/dynamic_svr/create_draw", map,
            OkUtils.cookie(biliBiliEntity.cookie))
        return if (jsonNode.getInteger("code") == 0) CommonResult.success()
        else CommonResult.failure("发布动态失败，" + jsonNode.getString("message"))
    }

    suspend fun ranking(biliBiliEntity: BiliBiliEntity): List<BiliBiliRanking> {
        val jsonNode = client.get("https://api.bilibili.com/x/web-interface/ranking/v2?rid=0&type=all") {
            referer("https://www.bilibili.com")
            cookieString(biliBiliEntity.cookie)
            userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
        }.body<JsonNode>()
        val jsonArray = jsonNode["data"]["list"]
        val list = mutableListOf<BiliBiliRanking>()
        for (singleJsonNode in jsonArray) {
            val biliBiliRanking = BiliBiliRanking()
            biliBiliRanking.aid = singleJsonNode.getString("aid")
            biliBiliRanking.cid = singleJsonNode.getString("cid")
            biliBiliRanking.title = singleJsonNode.getString("title")
            biliBiliRanking.desc = singleJsonNode.getString("desc")
            biliBiliRanking.username = singleJsonNode["owner"]["name"].asText()
            biliBiliRanking.dynamic = singleJsonNode.getString("dynamic")
            biliBiliRanking.bv = singleJsonNode.getString("bvid")
            biliBiliRanking.duration = singleJsonNode["bvid"].asInt()
            list.add(biliBiliRanking)
        }
        return list
    }

    suspend fun report(biliBiliEntity: BiliBiliEntity, aid: String, cid: String, proGRes: Int): String {
        val map = mapOf("aid" to aid, "cid" to cid, "progres" to proGRes.toString(),
            "csrf" to biliBiliEntity.token)
        val jsonNode = OkHttpKtUtils.postJson("https://api.bilibili.com/x/v2/history/report", map,
            OkUtils.cookie(biliBiliEntity.cookie))
        return if (jsonNode.getInteger("code") == 0) "成功"
        else error(jsonNode.getString("message"))
    }

    private fun JsonNode.check() {
        if (this["code"].asInt() != 0) error(this["message"].asText())
    }

    suspend fun watchVideo(biliBiliEntity: BiliBiliEntity, biliBiliRanking: BiliBiliRanking) {
        val startTs = System.currentTimeMillis().toString()
        val map = mutableMapOf(
            "mid" to biliBiliEntity.userid,
            "aid" to biliBiliRanking.aid,
            "cid" to biliBiliRanking.cid,
            "part" to "1",
            "lv" to "5",
            "ftime" to System.currentTimeMillis().toString(),
            "stime" to startTs,
            "jsonp" to "jsonp",
            "type" to "3",
            "sub_type" to "0",
            "refer_url" to "",
            "spmid" to "333.788.0.0",
            "from_spmid" to "333.1007.tianma.1-1-1.click",
            "csrf" to biliBiliEntity.token,
        )
        val jsonNode = client.post("https://api.bilibili.com/x/click-interface/click/web/h5") {
            setFormDataContent {
                map.forEach { (t, u) -> append(t, u) }
            }
            cookieString(biliBiliEntity.cookie)
        }.body<JsonNode>()
        jsonNode.check()
        delay(3000)
        map["start_ts"] = startTs
        map["dt"] = "2"
        map["play_type"] = "0"
        map["realtime"] = (biliBiliRanking.duration - 5).toString()
        map["played_time"] = (biliBiliRanking.duration - 1).toString()
        map["real_played_time"] = (biliBiliRanking.duration - 1).toString()
        map["quality"] = "80"
        map["video_duration"] = biliBiliRanking.duration.toString()
        map["last_play_progress_time"] = (biliBiliRanking.duration - 2).toString()
        map["max_play_progress_time"] = (biliBiliRanking.duration - 2).toString()
        val watchJsonNode = client.post("https://api.bilibili.com/x/click-interface/web/heartbeat") {
            setFormDataContent {
                map.forEach { (t, u) -> append(t, u) }
            }
            cookieString(biliBiliEntity.cookie)
        }.body<JsonNode>()
        watchJsonNode.check()
    }

    suspend fun share(biliBiliEntity: BiliBiliEntity, aid: String): String {
        val jsonNode = client.post("https://api.bilibili.com/x/web-interface/share/add") {
            cookieString(biliBiliEntity.cookie)
            setFormDataContent {
                append("aid", aid)
                append("csrf", biliBiliEntity.token)
            }
        }.body<JsonNode>()
        return if (jsonNode.getInteger("code") in listOf(0, 71000)) "成功"
        else error(jsonNode.getString("message"))
    }

    suspend fun getReplay(biliBiliEntity: BiliBiliEntity, oid: String, page: Int): List<BiliBiliReplay> {
        val jsonNode = OkHttpKtUtils.getJsonp(
            "https://api.bilibili.com/x/v2/reply?callback=jQuery17207366906764958399_${System.currentTimeMillis()}&jsonp=jsonp&pn=$page&type=1&oid=$oid&sort=2&_=${System.currentTimeMillis()}",
            OkUtils.headers(biliBiliEntity.cookie, "https://www.bilibili.com/"))
        return if (jsonNode.getInteger("code") == 0) {
            val jsonArray = jsonNode["data"]["replies"]
            val list = mutableListOf<BiliBiliReplay>()
            for (obj in jsonArray) {
                val biliReplay = BiliBiliReplay(obj.getString("rpid"), obj["content"].getString("message"))
                list.add(biliReplay)
            }
            list
        }else listOf()
    }

    suspend fun reportComment(biliBiliEntity: BiliBiliEntity, oid: String, rpId: String, reason: Int): CommonResult<Void> {
        // 违法违规 9   色情  2    低俗 10    赌博诈骗  12
        // 人身攻击  7   侵犯隐私 15
        // 垃圾广告 1   引战 4    剧透   5    刷屏   3      抢楼 16    内容不相关   8     青少年不良信息  17
        //  其他 0
        val map = mapOf("oid" to oid, "type" to "1", "rpid" to rpId, "reason" to reason.toString(),
            "content" to "", "ordering" to "heat", "jsonp" to "jsonp", "csrf" to biliBiliEntity.token)
        val jsonNode = OkHttpKtUtils.postJson("https://api.bilibili.com/x/v2/reply/report", map,
            OkUtils.headers(biliBiliEntity.cookie, "https://www.bilibili.com/"))
        return if (jsonNode.getInteger("code") == 0) CommonResult.success(message = "举报评论成功！！")
        else CommonResult.failure("举报评论失败！！")
    }

    suspend fun getOidByBvId(bvId: String): String {
        val html = OkHttpKtUtils.getStr("https://www.bilibili.com/video/$bvId",
            OkUtils.ua(UA.PC))
        val jsonStr = MyUtils.regex("INITIAL_STATE__=", ";\\(function\\(\\)", html)!!
        val jsonNode = jsonStr.toJsonNode()
        return jsonNode.getString("aid")
    }

    suspend fun followed(biliBiliEntity: BiliBiliEntity): CommonResult<List<BiliBiliFollowed>> {
        val list = mutableListOf<BiliBiliFollowed>()
        var i = 1
        while (true) {
            val jsonNode = onceFollowed(biliBiliEntity, i++)
            if (jsonNode.getInteger("code") == 0) {
                val jsonArray = jsonNode["data"]["list"]
                if (jsonArray.size() == 0) break
                for (any in jsonArray) {
                    list.add(BiliBiliFollowed(any.getString("mid"), any.getString("uname")))
                }
            } else return CommonResult.failure(jsonNode.getString("message"))
        }
        return CommonResult.success(list)
    }

    private suspend fun onceFollowed(biliBiliEntity: BiliBiliEntity, i: Int): JsonNode {
        val headers = mapOf("referer" to "https://space.bilibili.com/${biliBiliEntity.userid}/fans/follow",
            "user-agent" to UA.PC.value, "cookie" to biliBiliEntity.cookie)
        return OkHttpKtUtils.getJsonp("https://api.bilibili.com/x/relation/followings?vmid=${biliBiliEntity.userid}&pn=$i&ps=100&order=desc&order_type=attention&jsonp=jsonp&callback=__jp5",
            headers)
    }


}

data class BiliBiliPojo(
    var userId: String = "",
    var name: String = "",
    var id: String = "",
    var rid: String = "",
    var type: Int = -1,
    var time: Long = 0,
    var text: String = "",
    var bvId: String = "",
    var ipFrom: String = "",
    var picList: MutableList<String> = mutableListOf(),
    var isForward: Boolean = false,
    var forwardUserId: String = "",
    var forwardName: String = "",
    var forwardId: String = "",
    var forwardTime: Long = 0,
    var forwardText: String = "",
    var forwardBvId: String = "",
    var forwardPicList: MutableList<String> = mutableListOf()
)

data class BiliBiliLive(
    var title: String = "",
    var id: String = "",
    var url: String = "",
    var imageUrl: String = "",
    var status: Boolean = false,
    var uname: String = ""
)

data class BiliBiliRanking(
    var aid: String = "",
    var cid: String = "",
    var title: String = "",
    var desc: String = "",
    var username: String = "",
    var dynamic: String = "",
    var bv: String = "",
    var duration: Int = 0
)

data class BiliBiliReplay(
    var id: String = "",
    var content: String = ""
)

data class BiliBiliFollowed(
    var id: String = "",
    var name: String = ""
)

data class BiliBiliQrcode(
    val url: String,
    val key: String
)
