package com.lanhang.miuix.im

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.lanhang.miuix.schedule.AppContext
import java.io.File

/**
 * 私信本地快照（2026-10-26 第七轮）。
 *
 * 用户反馈「第一次点进私信严重卡顿，姓名与群聊名也显示不出来」：进页面时什么都是空的，
 * 得等 融云 connect → 拉会话列表 → `POST /staffs/batch` 取姓名 → `GET /groups/{id}` 取群名
 * 这一串网络往返走完才有内容，中间整页空白 + 动画掉帧。
 *
 * 这里把上一次的结果落盘，**下次进页面先渲染缓存**，连接成功后再用最新的覆盖：
 *  - `conversations.json`：会话列表（含最后的预览文案、时间、未读数）
 *  - `msg_<peer>.json`：每个会话最近 [MAX_MESSAGES] 条消息
 *  - `group_titles.json`：群 id → 群名
 *
 * 只写本机 `filesDir/im_cache/`，随退出登录 / 卸载一起消失。
 * 写入走单线程串行 + 临时文件改名，避免同一条会话两个线程同时写坏。
 */
object ImCache {

    /** 每个会话最多留多少条（够首屏铺满即可，下拉/刷新会补最新的） */
    private const val MAX_MESSAGES = 60

    private val gson = Gson()

    /** 文件名里只允许这些字符，其余替换掉（peerId 一般是 userId/groupId，还是要防脏字符） */
    private fun safeName(peerId: String): String {
        val cleaned = peerId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return if (cleaned == peerId && cleaned.isNotEmpty()) cleaned else cleaned + "_" + Integer.toHexString(peerId.hashCode())
    }

    private fun dir(): File = File(AppContext.instance.filesDir, "im_cache").apply { if (!exists()) mkdirs() }

    private fun file(name: String): File = File(dir(), name)

    // ------------------------------------------------------------------ 会话列表

    fun saveConversations(list: List<ImConversation>) {
        val arr = JsonArray()
        list.forEach { c ->
            val o = JsonObject()
            o.addProperty("peerId", c.peerId)
            o.addProperty("title", c.title)
            o.addProperty("isGroup", c.isGroup)
            o.addProperty("preview", c.preview)
            o.addProperty("time", c.time)
            o.addProperty("unread", c.unread)
            o.addProperty("previewSenderId", c.previewSenderId)
            arr.add(o)
        }
        write("conversations.json", arr.toString())
    }

    fun loadConversations(): List<ImConversation> = runCatching {
        val f = file("conversations.json")
        if (!f.exists()) return emptyList()
        val arr = JsonParser.parseString(f.readText()).takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        arr.mapNotNull { e ->
            runCatching {
                val o = e.asJsonObject
                ImConversation(
                    peerId = o.str("peerId"),
                    title = o.str("title"),
                    isGroup = o.bool("isGroup"),
                    preview = o.str("preview"),
                    time = o.long("time"),
                    unread = o.num("unread"),
                    previewSenderId = o.str("previewSenderId"),
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ 每个会话的消息

    /**
     * 消息的落盘/读取**手写 JSON 映射**，不走 Gson 反射。
     *
     * 踩过的坑（2026-10-26 第七轮）：用 `gson.fromJson(..., ThreadSnapshot::class.java)` 时，
     * R8 会把 `ThreadSnapshot` 的泛型签名（`List<ImMessage>`）当无用属性删掉，Gson 于是把每条消息
     * 反成 `LinkedTreeMap`，release 包一进会话页就
     * `ClassCastException: LinkedTreeMap cannot be cast to ImMessage`
     * （堆栈被混淆成 `q7.n cannot be cast to f8.l`，靠 mapping.txt 才认出来）。
     * 手写映射不依赖反射、不依赖泛型签名，混淆/压缩都不会再出这种事。
     */
    private fun encodeMessages(peerId: String, list: List<ImMessage>): String {
        val root = JsonObject()
        root.addProperty("peerId", peerId)
        val arr = JsonArray()
        list.forEach { m ->
            val o = JsonObject()
            o.addProperty("key", m.key)
            o.addProperty("text", m.text)
            o.addProperty("mine", m.mine)
            o.addProperty("time", m.time)
            o.addProperty("state", m.state)
            o.addProperty("senderId", m.senderId)
            o.addProperty("kind", m.kind)
            o.addProperty("uri", m.uri)
            o.addProperty("thumb", m.thumb)
            o.addProperty("duration", m.duration)
            o.addProperty("size", m.size)
            o.addProperty("name", m.name)
            o.addProperty("lat", m.lat)
            o.addProperty("lng", m.lng)
            o.addProperty("poi", m.poi)
            arr.add(o)
        }
        root.add("messages", arr)
        return root.toString()
    }

    private fun JsonObject.str(key: String): String = get(key)?.takeIf { !it.isJsonNull }?.asString ?: ""
    private fun JsonObject.num(key: String): Int = get(key)?.takeIf { !it.isJsonNull }?.asInt ?: 0
    private fun JsonObject.long(key: String): Long = get(key)?.takeIf { !it.isJsonNull }?.asLong ?: 0L
    private fun JsonObject.dbl(key: String): Double = get(key)?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
    private fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: false

    private fun decodeMessages(text: String): Pair<String, List<ImMessage>> = runCatching {
        val root = JsonParser.parseString(text).asJsonObject
        val peerId = root.str("peerId")
        val list = root.getAsJsonArray("messages")?.mapNotNull { e ->
            runCatching {
                val o = e.asJsonObject
                ImMessage(
                    key = o.str("key"),
                    text = o.str("text"),
                    mine = o.bool("mine"),
                    time = o.long("time"),
                    state = o.num("state"),
                    senderId = o.str("senderId"),
                    kind = o.num("kind"),
                    uri = o.str("uri"),
                    thumb = o.str("thumb"),
                    duration = o.num("duration"),
                    size = o.long("size"),
                    name = o.str("name"),
                    lat = o.dbl("lat"),
                    lng = o.dbl("lng"),
                    poi = o.str("poi"),
                )
            }.getOrNull()
        }.orEmpty()
        peerId to list
    }.getOrDefault("" to emptyList())

    fun saveMessages(peerId: String, list: List<ImMessage>) {
        if (peerId.isBlank()) return
        write("msg_" + safeName(peerId) + ".json", encodeMessages(peerId, list.takeLast(MAX_MESSAGES)))
    }

    fun loadMessages(peerId: String): List<ImMessage> = runCatching {
        val f = file("msg_" + safeName(peerId) + ".json")
        if (!f.exists()) return emptyList()
        decodeMessages(f.readText()).second
    }.getOrDefault(emptyList())

    /** 启动时一次性把所有会话的缓存读进来（文件小、数量少，几十毫秒） */
    fun loadAllMessages(): Map<String, List<ImMessage>> {
        val out = HashMap<String, List<ImMessage>>()
        val files = dir().listFiles { f -> f.isFile && f.name.startsWith("msg_") && f.name.endsWith(".json") }
            ?: return out
        files.forEach { f ->
            runCatching {
                val (id, list) = decodeMessages(f.readText())
                if (id.isNotBlank() && list.isNotEmpty()) out[id] = list
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 群名

    fun saveGroupTitles(map: Map<String, String>) {
        write("group_titles.json", gson.toJson(map))
    }

    fun loadGroupTitles(): Map<String, String> = runCatching {
        val f = file("group_titles.json")
        if (!f.exists()) return emptyMap()
        gson.fromJson<Map<String, String>>(
            f.readText(),
            object : TypeToken<Map<String, String>>() {}.type,
        ) ?: emptyMap()
    }.getOrDefault(emptyMap())

    // ------------------------------------------------------------------ 落盘

    /** 先写 `.tmp` 再改名：进程被杀在中间也不会留下半个 JSON */
    private fun write(name: String, json: String) {
        runCatching {
            val target = file(name)
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(json)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }
    }
}
