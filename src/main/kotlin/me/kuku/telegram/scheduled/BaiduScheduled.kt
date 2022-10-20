package me.kuku.telegram.scheduled

import kotlinx.coroutines.delay
import me.kuku.telegram.entity.*
import me.kuku.telegram.logic.BaiduLogic
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class BaiduScheduled(
    private val baiduService: BaiduService,
    private val baiduLogic: BaiduLogic,
    private val logService: LogService
) {

    @Scheduled(cron = "0 41 2 * * ?")
    suspend fun sign() {
        val list = baiduService.findBySign(Status.ON)
        for (baiduEntity in list) {
            val logEntity = LogEntity().apply {
                tgId = baiduEntity.tgId
                type = LogType.Baidu
            }
            kotlin.runCatching {
                baiduLogic.tieBaSign(baiduEntity)
                for (i in 0 until 12) {
                    delay(1000 * 15)
                    baiduLogic.ybbWatchAd(baiduEntity)
                }
                for (i in 0 until 4) {
                    delay(1000 * 30)
                    baiduLogic.ybbWatchAd(baiduEntity, "v3")
                }
                baiduLogic.ybbSign(baiduEntity)
                logEntity.text = "成功"
            }.onFailure {
                logEntity.text = "失败"
            }
            logService.save(logEntity)
            delay(3000)
        }
    }


}