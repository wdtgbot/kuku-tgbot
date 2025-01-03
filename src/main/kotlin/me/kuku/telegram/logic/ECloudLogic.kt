package me.kuku.telegram.logic

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.request.headers
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.delay
import me.kuku.telegram.entity.ECloudEntity
import me.kuku.telegram.entity.ECloudService
import me.kuku.telegram.utils.*
import org.springframework.stereotype.Service

@Service
class ECloudLogic(
    private val eCloudService: ECloudService
) {

    suspend fun login(username: String, password: String): ECloudEntity {
        val (cookie, lt, reqId, refererUrl) = client.get("https://cloud.189.cn/api/portal/loginUrl.action?redirectURL=https%3A%2F%2Fcloud.189.cn%2Fweb%2Fredirect.html&defaultSaveName=3&defaultSaveNameCheck=uncheck&browserId=c7044c4577d2d903bbb74a956c11274d")
            .let {
                val location = it.headers["location"] ?: error("未能成功跳转")
                client.get(location).let { response ->
                    val ltUrl = response.headers["location"] ?: error("未能成功跳转")
                    val lt = RegexUtils.extract(ltUrl, "lt=", "&") ?: error("未能成功获取lt")
                    val reqId = RegexUtils.extract(ltUrl, "reqId=", "&") ?: error("未能成功获取reqId")
                    listOf(response.setCookie().renderCookieHeader(), lt, reqId, ltUrl)
                }
            }
        val headers = StringValues.build {
            append("cookie", cookie)
            append("lt", lt)
            append("referer", refererUrl)
            append("reqId", reqId)
        }
        val configJsonNode = client.submitForm("https://open.e.189.cn/api/logbox/oauth2/appConf.do",
            parameters {
                append("version", "2.0")
                append("appKey", "cloud")
            }) {
            headers {
                appendAll(headers)
            }
        }.bodyAsText().toJsonNode()
        val encryptJsonNode = client.submitForm("https://open.e.189.cn/api/logbox/config/encryptConf.do",
            parameters { append("appId", "cloud") }).bodyAsText().toJsonNode()
        val paramId = configJsonNode["data"]?.get("paramId")?.asText() ?: error("not found paramId")
        val encryptData = encryptJsonNode["data"]
        val pre = encryptData["pre"].asText()
        val pubKey = encryptData["pubKey"].asText()
        client.submitForm("https://open.e.189.cn/api/logbox/oauth2/needcaptcha.do",
            parameters {
                append("accountType", "01")
                append("userName", pre + username.rsaEncrypt(pubKey))
                append("appKey", "cloud")
            }).bodyAsText().takeIf { it == "0" } ?: error("需要验证码，请在任意设备成功登陆一次再试")
        val response = client.submitForm("https://open.e.189.cn/api/logbox/oauth2/loginSubmit.do",
            parameters {
                append("version", "v2.0")
                append("apToken", "")
                append("appKey", "cloud")
                append("accountType", "01")
                append("userName", pre + username.rsaEncrypt(pubKey).decodeBase64Bytes().hex())
                append("epd", pre + password.rsaEncrypt(pubKey).decodeBase64Bytes().hex())
                append("captchaType", "")
                append("validateCode", "")
                append("smsValidateCode", "")
                append("captchaToken", "")
                append(
                    "returnUrl",
                    "https%3A%2F%2Fcloud.189.cn%2Fapi%2Fportal%2FcallbackUnify.action%3FredirectURL%3Dhttps%253A%252F%252Fcloud.189.cn%252Fweb%252Fredirect.html"
                )
                append("mailSuffix", "@189.cn")
                append("dynamicCheck", "FALSE")
                append("clientType", "1")
                append("cb_SaveName", "3")
                append("isOauth2", "false")
                append("state", "")
                append("paramId", paramId)
            }) {
            headers { appendAll(headers) }
        }
        val jsonNode = response.bodyAsText().toJsonNode()
        if (jsonNode["result"].asText() != "0") error(jsonNode["msg"].asText())
        val toUrl = jsonNode["toUrl"].asText()
        val eCookie = response.setCookie().renderCookieHeader()
        val loginResponse = client.get(toUrl)
        val resultCookie = loginResponse.setCookie().renderCookieHeader()
        return ECloudEntity().also {
            it.eCookie = eCookie
            it.cookie = resultCookie
        }
    }

    private fun JsonNode.check() {
        if (this.has("errorCode") && this["errorCode"].asText() != "User_Not_Chance") error(this["errorMsg"].asText())
    }

    private suspend fun updateCookie(entity: ECloudEntity) {
        val jsonNode = client.get("https://cloud.189.cn/api/portal/listFiles.action?noCache=0.${RandomUtils.num(16)}&fileId=-11")
            .body<JsonNode>()
        if (jsonNode.has("errorCode")) {
            val response1 =
                client.get("https://cloud.189.cn/api/portal/loginUrl.action?redirectURL=https%3A%2F%2Fcloud.189.cn%2Fweb%2Fredirect.html") {
                    cookieString(entity.cookie)
                }
            val location1 = response1.headers["location"] ?: error("未能成功跳转")
            val response2 = client.get(location1) {
                cookieString(entity.eCookie)
            }
            val location2 = response2.headers["location"] ?: error("未能成功跳转")
            val response3 = client.get(location2)
            val cookie = response3.setCookie().renderCookieHeader()
            entity.cookie = cookie
            eCloudService.save(entity)
        }
    }

    suspend fun sign(entity: ECloudEntity) {
        updateCookie(entity)
        // prizeName
        val sv=  StringValues.build {
            append("user-agent", "Mozilla/5.0 (Linux; Android 5.1.1; SM-G930K Build/NRD90M; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/74.0.3729.136 Mobile Safari/537.36 Ecloud/8.6.3 Android/22 clientId/355325117317828 clientModel/SM-G930K imsi/460071114317824 clientChannelId/qq proVersion/1.0.6")
            append("Referer", "https://m.cloud.189.cn/zhuanti/2016/sign/index.jsp?albumBackupOpened=1")
            append("cookie", entity.cookie)
        }
        val jsonNode1 = client.get("https://m.cloud.189.cn/v2/drawPrizeMarketDetails.action?taskId=TASK_SIGNIN&activityId=ACT_SIGNIN") {
            headers { appendAll(sv) }
        }.bodyAsText().toJsonNode()
        jsonNode1.check()
        delay(5000)
        val jsonNode2 = client.get("https://m.cloud.189.cn/v2/drawPrizeMarketDetails.action?taskId=TASK_SIGNIN_PHOTOS&activityId=ACT_SIGNIN") {
            headers { appendAll(sv) }
        }.bodyAsText().toJsonNode()
        jsonNode2.check()
        delay(5000)
        val jsonNode3 = client.get("https://m.cloud.189.cn/v2/drawPrizeMarketDetails.action?taskId=TASK_2022_FLDFS_KJ&activityId=ACT_SIGNIN") {
            headers { appendAll(sv) }
        }.bodyAsText().toJsonNode()
        jsonNode3.check()
    }

}