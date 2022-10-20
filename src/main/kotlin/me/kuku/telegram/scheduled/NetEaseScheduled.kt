package me.kuku.telegram.scheduled

import kotlinx.coroutines.delay
import me.kuku.telegram.entity.*
import me.kuku.telegram.logic.NetEaseLogic
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class NetEaseScheduled(
    private val netEaseService: NetEaseService,
    private val logService: LogService
) {

    @Scheduled(cron = "0 12 7 * * ?")
    suspend fun sign() {
        val list = netEaseService.findBySign(Status.ON)
        for (netEaseEntity in list) {
            val logEntity = LogEntity().also {
                it.type = LogType.NetEase
                it.tgId = netEaseEntity.tgId
            }
            kotlin.runCatching {
                delay(3000)
                NetEaseLogic.sign(netEaseEntity)
                delay(3000)
                NetEaseLogic.listenMusic(netEaseEntity)
                logEntity.text = "成功"
            }.onFailure {
                logEntity.text = "失败"
            }
            logService.save(logEntity)
        }
    }

    @Scheduled(cron = "0 32 8 * * ?")
    suspend fun musicianSign() {
        val list = netEaseService.findByMusicianSign(Status.ON)
        for (netEaseEntity in list) {
            val logEntity = LogEntity().also {
                it.type = LogType.NetEase
                it.tgId = netEaseEntity.tgId
            }
            kotlin.runCatching {
                for (i in 0..1) {
                    NetEaseLogic.musicianSign(netEaseEntity)
                    delay(3000)
                    NetEaseLogic.publish(netEaseEntity)
                    delay(3000)
                    NetEaseLogic.publishMLog(netEaseEntity)
                    delay(1000 * 60)
                }
                logEntity.text = "成功"
            }.onFailure {
                logEntity.text = "失败"
            }
            logService.save(logEntity)
        }
    }

}