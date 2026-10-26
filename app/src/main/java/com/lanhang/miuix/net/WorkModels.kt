package com.lanhang.miuix.net

import com.google.gson.annotations.SerializedName

/**
 * 工作（OA 办事大厅）的一个应用。
 * 字段照抄官方 `OAWebAppBean.Type2Bean.TypesBean.AppsBean`。
 */
data class WorkApp(
    val id: String? = null,
    val name: String? = null,
    @SerializedName("logo_url") val logoUrl: String? = null,
    val description: String? = null,
    @SerializedName("home_page_url") val homePageUrl: String? = null,
    @SerializedName("pc_home_page_url") val pcHomePageUrl: String? = null,
    @SerializedName("order_num") val orderNum: Int? = null,
    val state: Int? = null,
    val type: Int? = null,
    /**
     * 服务端标注的适用范围。官方 bean 里是 `List<?>`，实测结构不固定
     * （可能是 `["本科生"]`、`[1,2]` 或对象），所以统一当 Any 收，比对时再转字符串。
     */
    val range: List<Any?>? = null,
) {
    /** 打开时优先用移动端地址 */
    val openUrl: String? get() = homePageUrl?.takeIf { it.isNotBlank() }
        ?: pcHomePageUrl?.takeIf { it.isNotBlank() }

    /**
     * 展示用名称。
     *
     * 学校 OA 的数据本身就会把名字截断：例如「数据中心机房托管设备申请」这条，
     * `name` 只给了 `数据中心机房托管设`，而 `description` 是完整标题。
     * 判据是「description 以 name 开头且只多几个字」——这样才不会把某些
     * 应用的真实描述（一整句话）当成标题；其余情况仍然是 name。
     */
    val displayName: String
        get() {
            val short = name.orEmpty().trim()
            val full = description.orEmpty().trim()
            val looksLikeFullTitle = short.isNotEmpty() &&
                full.length > short.length &&
                full.length <= short.length + 4 &&
                full.startsWith(short)
            return if (looksLikeFullTitle) full else short
        }
}

/** `type2.types[]` 一个分类 */
data class WorkCategory(
    val id: Int? = null,
    @SerializedName("type_name") val typeName: String? = null,
    @SerializedName("orderNum") val orderNum: Int? = null,
    @SerializedName("order_num") val orderNumAlt: Int? = null,
    val apps: List<WorkApp>? = null,
) {
    val sortKey: Int get() = orderNum ?: orderNumAlt ?: 0
}

/** `POST /apps/all` 的 result */
data class WorkDirectory(
    val type1: WorkAppsOnly? = null,
    val type2: WorkGrouped? = null,
) {
    data class WorkAppsOnly(val apps: List<WorkApp>? = null)

    data class WorkGrouped(val apps: List<WorkApp>? = null, val types: List<WorkCategory>? = null)

    /** 展平成「分类名 → 应用」；分类名为 null 表示服务端没归类的零散应用 */
    fun sections(): List<Pair<String?, List<WorkApp>>> {
        val out = ArrayList<Pair<String?, List<WorkApp>>>()
        type2?.types?.sortedBy { it.sortKey }?.forEach { category ->
            val apps = category.apps.orEmpty().sortedBy { it.orderNum ?: 0 }
            if (apps.isNotEmpty()) out.add((category.typeName ?: "") to apps)
        }
        val loose = type2?.apps.orEmpty() + type1?.apps.orEmpty()
        if (loose.isNotEmpty()) out.add(null to loose.sortedBy { it.orderNum ?: 0 })
        return out
    }

    val isEmpty: Boolean get() = sections().isEmpty()
}
