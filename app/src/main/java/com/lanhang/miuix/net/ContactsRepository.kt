package com.lanhang.miuix.net

import com.lanhang.miuix.schedule.AppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 通讯录的取数 + 本地缓存。
 *
 * 组织架构树一次 300KB+、1500 多个节点，以前每进一次通讯录都现拉，
 * 网慢的时候一直压在「加载中」，甚至直接超时（用户 2026-10-26 反馈）。
 *
 * 现在把树和每个部门的成员页都落到 `filesDir/contacts/`：
 * - 有未过期的缓存 → 立刻返回，进页面基本是秒开；
 * - 缓存过期或没有 → 请求网络，成功后再写盘（失败不影响已展示的旧数据）。
 *
 * 缓存只存「服务端给的原始结构」，不做业务加工，过期时间也是保守值
 * （组织架构 12 小时、部门成员 6 小时），避免展示过时名单。
 */
object ContactsRepository {

    private const val TREE_TTL_MS = 12 * 60 * 60 * 1000L
    private const val DEPT_TTL_MS = 6 * 60 * 60 * 1000L

    private fun dir(): File? = runCatching {
        File(AppContext.instance.filesDir, "contacts").apply { mkdirs() }
    }.getOrNull()

    private fun file(key: String): File? = dir()?.let { File(it, "$key.json") }

    private suspend fun readCache(key: String, ttlMs: Long, force: Boolean): String? =
        withContext(Dispatchers.IO) {
            if (force) return@withContext null
            val f = file(key) ?: return@withContext null
            if (!f.isFile) return@withContext null
            if (System.currentTimeMillis() - f.lastModified() > ttlMs) return@withContext null
            runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        }

    private suspend fun writeCache(key: String, json: String) = withContext(Dispatchers.IO) {
        val f = file(key) ?: return@withContext
        runCatching { f.writeText(json) }
        Unit
    }

    /** 清缓存（设置里「清除缓存」用得到，暂时只在强制刷新时用） */
    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { dir()?.listFiles()?.forEach { it.delete() } }
        Unit
    }

    /** 整棵组织树；[force] = 忽略缓存重新拉 */
    suspend fun tree(force: Boolean = false): List<Department> {
        val key = "tree_v1"
        readCache(key, TREE_TTL_MS, force)?.let { json ->
            runCatching {
                ApiClient.gson.fromJson(json, Array<Department>::class.java)?.toList()
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val fresh = RceApi.departmentTree()
        if (fresh.isNotEmpty()) writeCache(key, ApiClient.gson.toJson(fresh))
        return fresh
    }

    /** 某个部门的子部门 + 成员（按 offset/limit 分页，翻过的页同样落盘） */
    suspend fun branches(
        deptId: String?,
        offset: Int,
        limit: Int,
        force: Boolean = false,
    ): DepartmentBranches {
        val key = "dept_${deptId?.takeIf { it.isNotBlank() } ?: "root"}_${offset}_$limit"
        readCache(key, DEPT_TTL_MS, force)?.let { json ->
            runCatching { ApiClient.gson.fromJson(json, DepartmentBranches::class.java) }
                .getOrNull()?.let { return it }
        }
        val fresh = RceApi.departmentBranches(deptId, offset, limit)
        writeCache(key, ApiClient.gson.toJson(fresh))
        return fresh
    }
}
