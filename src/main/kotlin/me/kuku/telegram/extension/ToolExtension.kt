package me.kuku.telegram.extension

import me.kuku.telegram.logic.YgoLogic
import me.kuku.telegram.utils.ability
import me.kuku.telegram.utils.callback
import me.kuku.telegram.utils.callbackStartWith
import me.kuku.telegram.utils.execute
import me.kuku.utils.OkHttpKtUtils
import okhttp3.internal.closeQuietly
import org.springframework.stereotype.Service
import org.telegram.abilitybots.api.util.AbilityExtension
import org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.InputFile
import org.telegram.telegrambots.meta.api.objects.media.InputMedia
import org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton
import java.io.InputStream

@Service
class ToolExtension(
    private val ygoLogic: YgoLogic
): AbilityExtension {

    fun queryYgoCard() = ability("ygo", "游戏王查卡", 1) {
        val cardList = ygoLogic.search(firstArg())
        val list = mutableListOf<List<InlineKeyboardButton>>()
        for (i in cardList.indices) {
            val card = cardList[i]
            list.add(listOf(InlineKeyboardButton(card.chineseName).apply { callbackData = "ygoCard-${card.cardPassword}" }))
        }
        val sendMessage =
            SendMessage.builder().chatId(chatId()).text("请选择查询的卡片").replyMarkup(InlineKeyboardMarkup(list))
                .build()
        execute(sendMessage)
    }

    fun selectCard() = callbackStartWith("ygoCard") {
        val id = it.data.split("-")[1]
        val card = ygoLogic.searchDetail(id.toLong())
        val sendPhoto = SendPhoto()
        sendPhoto.chatId = it.message.chatId.toString()
        sendPhoto.photo = InputFile(OkHttpKtUtils.getByteStream(card.imageUrl), "${card.japaneseName}.jpg")
        sendPhoto.caption = "中文名：${card.chineseName}\n日文名：${card.japaneseName}\n英文名：${card.englishName}\n效果：\n${card.effect}"
        execute(sendPhoto)
    }

    private fun toolKeyboardMarkup(): InlineKeyboardMarkup {
        val loLiConButton = InlineKeyboardButton("LoLiCon").also { it.callbackData = "LoLiConTool" }
        val fishermanCalendarButton = InlineKeyboardButton("摸鱼日历").also { it.callbackData = "FishermanCalendarTool" }
        return InlineKeyboardMarkup(listOf(
            listOf(loLiConButton, fishermanCalendarButton)
        ))
    }

    fun manager() = ability("tool", "工具") {
        val markup = toolKeyboardMarkup()
        val sendMessage = SendMessage()
        sendMessage.replyMarkup = markup
        sendMessage.chatId = chatId().toString()
        sendMessage.text = "请选择小工具"
        execute(sendMessage)
    }

    fun colorPic() = callback("LoLiConTool") {
        val chatId = it.message.chatId
        val jsonNode = OkHttpKtUtils.getJson("https://api.lolicon.app/setu/v2?num=10&r18=2")
        val list = jsonNode["data"].map { it["urls"]["original"].asText() }
        val inputMediaList = mutableListOf<InputMedia>()
        val ii = mutableListOf<InputStream>()
        val sendMessage = SendMessage(chatId.toString(), "正在上传第0张图片")
        val message = execute(sendMessage)
        for (i in list.indices) {
            val s = list[i]
            val editMessageText = EditMessageText.builder().text("正在上传第${i + 1}张图片").chatId(chatId).messageId(message.messageId).build()
            execute(editMessageText)
            val bis = OkHttpKtUtils.getByteStream(s)
            val name = s.substring(s.lastIndexOf('/') + 1)
            val mediaPhoto =
                    InputMediaPhoto.builder().newMediaStream(bis).media("attach://$name").mediaName(name).isNewMedia(true).build()
                inputMediaList.add(mediaPhoto)
            ii.add(bis)
        }
        val sendMediaGroup = SendMediaGroup(chatId.toString(), inputMediaList)
        try {
            execute(sendMediaGroup)
        } finally {
            for (inputStream in ii) {
                inputStream.closeQuietly()
            }
            val deleteMessage = DeleteMessage(chatId.toString(), message.messageId)
            execute(deleteMessage)
        }
    }

    fun fishermanCalendar() = callback("FishermanCalendarTool") {
        OkHttpKtUtils.getByteStream("https://api.kukuqaq.com/fishermanCalendar?preview").use { iis ->
            val sendPhoto = SendPhoto(it.message.chatId.toString(), InputFile(iis, "FishermanCalendarTool.jpg"))
            execute(sendPhoto)
        }
    }



}