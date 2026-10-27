package com.lanhang.miuix.campusnet

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lanhang.miuix.R
import com.lanhang.miuix.core.ApiException
import com.lanhang.miuix.core.TextRef
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 深澜（Srun）5.x 认证接口。
 *
 * 三次 GET 就把人送上外网：
 * ```
 * GET /cgi-bin/get_challenge            → {challenge}      （60 秒、一次一用）
 * 本地算 hmd5 + info + chksum
 * GET /cgi-bin/srun_portal?action=login → {error:"ok"}     （失败回 error 码）
 * GET /cgi-bin/rad_user_info            → 在线状态/时长/流量
 * ```
 *
 * 三个设计决定（都写在 `docs/oa-hall-and-campus-network-research.md` §3.4）：
 *
 * 1. **不读 SSID**。判断「在不在校园网」用「能不能连上认证服务器」当探针，
 *    这样不需要 `ACCESS_FINE_LOCATION` / `NEARBY_WIFI_DEVICES`，用户不会看到定位权限弹窗；
 * 2. **不做后台保活、不自动重试**。厂商 ROM 一定会杀后台，前台手动认证最可靠；
 * 3. **明文 HTTP 直连**。门户是 `http://10.1.88.4`，且只在校园网内可达，
 *    本类所有请求都**绕开** [com.lanhang.miuix.net.ApiClient]（那个客户端带着蓝航 Cookie，
 *    没必要发给认证服务器）。
 *
 * 全是阻塞调用，调用方必须放在 `Dispatchers.IO`。
 */
object SrunApi {

    private const val TAG = "SrunApi"

    /** 认证门户：仅校园网内可达（本机在校外时表现为连不上，这是判定依据） */
    private const val PORTAL = "http://10.1.88.4"

    /** 网络认证自助服务（改密码 / 查在线设备 / 踢设备）。公网可达，但 SSO 回跳要校内网 */
    const val SELF_SERVICE_URL = "http://srunwebserver.device.nchu.edu.cn:8800/site/nchu"

    /** [login] 的返回值：这个 IP 已经在线上（没有新建会话，也不需要密码） */
    const val ALREADY_ONLINE = "ip_already_online"

    /**
     * 门户回的「用户不存在」错误码。
     *
     * **这是本次账号不存在的真正原因**：深澜的账号是 LDAP 条目 `账号@域`，
     * 只发裸学号服务端在域里查不到 → E2531。所以拿到它要**换身份域重试**，
     * 而不是当成「密码错误」直接报错。
     */
    private const val ECODE_USER_NOT_FOUND = "E2531"

    /**
     * 门户页面读不出身份域时的兜底（学校当前配置：学生 / 教职工 / 其他）。
     *
     * 正常路径是从门户 HTML 里读（见 [detectDomains]），这里只是兜底，
     * 免得门户改版导致整个功能不可用。
     */
    private val FALLBACK_DOMAINS = listOf("@stu.nchu.edu.cn", "@nchu.edu.cn", "@tmp")

    private const val ENC_VER = "srun_bx1"
    private const val NS = "200"
    private const val TYPE = "1"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * `hmd5` 变体开关。
     *
     * 深澜 5.x 存在两种历史变体：`HMAC-MD5(challenge, 密码)`（5.2 默认）与
     * `MD5(密码 + challenge)`（老版）。NCHU 是 5.2.2，按 HMAC-MD5 实现；
     * 真机若稳定报密码错误而密码确实正确，把这里改成 true 即可切换。
     */
    private const val USE_LEGACY_HMD5 = false

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * 探针用的短超时客户端：用户站在教学楼里等 10 秒才告诉他「不在校园网」太难受。
     *
     * 但别太短：校园网在 **Wi-Fi 刚重连**的头几秒里网关响应会明显变慢
     * （2026-10-26 真机实测：设备重连 Wi-Fi 后 `curl` 打门户要 4~5 秒，
     * 偶尔还直接超时）。原来 3s 的 read 超时在这种时刻必然失败，
     * 用户看到的却是「未连接校园网」这种**与实情不符**的提示。
     * 5s/8s 既不至于让人干等，又能扛过重连后的抖动。
     */
    private val probeHttp: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** 只用 `disableHtmlEscaping`：info 的键序必须是 username/password/ip/acid/enc_ver，不能变 */
    private val json = GsonBuilder().disableHtmlEscaping().create()

    data class NetStatus(
        val online: Boolean,
        val username: String? = null,
        val ip: String? = null,
        val onlineSeconds: Long = 0,
        val bytes: Long = 0,
        val balance: String? = null,
        /**
         * 服务端记的完整账号名（带身份域，如 `24085322@stu.nchu.edu.cn`）。
         *
         * 下线时用它最准：**服务端自己认的会话名**，比本地拼的可靠。
         */
        val sessionUser: String? = null,
    )

    /**
     * 登录结果。
     *
     * @param message 服务端提示（成功时通常是 `login_ok`）
     * @param domain 实际生效的身份域（成功后可缓存，下次直接用，少一次失败尝试）
     */
    data class LoginResult(
        val message: String,
        val domain: String,
    )

    // ==================== 查询 ====================

    /**
     * 在线状态。**连不上门户会抛 [ApiException]**（文案 = 未连接校园网），
     * 连得上但没登录则返回 `online = false` —— 这两种情况对用户的含义完全不同。
     */
    fun status(context: Context): NetStatus {
        val root = call("/cgi-bin/rad_user_info", emptyMap(), portalClient(context, probe = true))
        if (root.str("error") != "ok") return NetStatus(online = false)
        return NetStatus(
            online = true,
            // 服务端回的是 24085322@stu.nchu.edu.cn，界面上只要学号
            username = root.str("user_name")?.substringBefore('@')?.takeIf { it.isNotBlank() },
            // 带身份域的完整账号名：下线时用它（服务端自己认的会话名）
            sessionUser = root.str("user_name")?.takeIf { it.isNotBlank() },
            ip = root.str("online_ip"),
            // 真名是 sum_seconds（2026-10-25 校内实测 32 个字段里没有 online_time）
            onlineSeconds = (root.str("sum_seconds") ?: root.str("online_time"))?.toLongOrNull() ?: 0,
            bytes = root.str("sum_bytes")?.toLongOrNull() ?: 0,
            balance = root.str("user_balance"),
        )
    }

    /**
     * 本机在校园网内的 IPv4；拿不到（没连校园网 / 只有 IPv6）返回 null。
     *
     * **只认 WiFi / 以太网，不看系统「默认网络」**。2026-10-26 真机实测的手机状态：
     * ```
     * wlan2        10.74.105.115/24     校园网，未认证 → 没有默认路由
     * rmnet_data1  10.228.216.175/27    蜂窝，Active default network = 它
     * ```
     * 系统把默认网络判成蜂窝，`getActiveNetwork()` 给的是运营商地址；拿它去 Srun 认证
     * 一定失败（服务端按 IP 找会话），而请求也会从运营商出口走，`10.1.88.4` 根本不可达。
     *
     * 这里不抛异常：调用方拿它算签名参数，抛出去等于认证一进门就失败
     * （清单漏 `ACCESS_NETWORK_STATE` 时就是这样，表现为「认证失败」但一个请求都没发）。
     */
    fun localIpv4(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm != null) {
            val fromCampusNet = runCatching {
                val network = campusNetwork(cm) ?: return@runCatching null
                cm.getLinkProperties(network)?.linkAddresses
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress }
                    ?.hostAddress
            }.getOrNull()
            if (fromCampusNet != null) return fromCampusNet
        }
        return interfaceIpv4()
    }

    /**
     * 校园网网卡：`allNetworks` 里挑 WiFi / 以太网，以太网优先（宿舍网线最稳）。
     *
     * 为什么不用 `activeNetwork`：见 [localIpv4] 的实测记录 —— 未认证的校园网没有默认路由，
     * 系统会把默认网络判成蜂窝，`activeNetwork` 指的就是蜂窝。
     *
     * `allNetworks` 在 API 31 被标了 deprecated，官方替代是 `registerNetworkCallback` 的异步回调；
     * 认证是「用户点一下就要结果」的同步流程，为此改成回调会引入状态机，不值得。
     * 它的行为在新系统上没变，继续用，仅压掉警告。
     */
    @Suppress("DEPRECATION")
    private fun campusNetwork(cm: ConnectivityManager): Network? {
        val ranked = runCatching { cm.allNetworks }.getOrNull().orEmpty().mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                else -> return@mapNotNull null
            } to network
        }
        return ranked.minByOrNull { it.first }?.second
    }

    /**
     * 无权限退路：直接枚举网卡，只认 `wlan*` / `eth*`。
     *
     * 蜂窝接口（`rmnet*` / `ccmni*` / `pdp*`）拿到的是运营商内网地址，拿去认证一定错，
     * 所以这里**不把它们当备选**——宁可返回 null 让界面报「没拿到校园网 IP」，
     * 也好过拿错地址去试、然后回一句含糊的「密码错误」。
     * 排除 link-local（169.254.x.x）——那是没拿到 DHCP 时的自配地址。
     */
    private fun interfaceIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /**
     * 给门户请求用的客户端：**绑定到校园网网卡**。
     *
     * 光有正确的 IP 不够 —— socket 还得从那张网卡出去。不绑的话，双网卡时系统会把
     * 请求丢给蜂窝（默认路由在它那儿），`10.1.88.4` 连不上，用户看到的就是「未连接校园网」
     * 或者一直转圈。`Network.getSocketFactory()` 创建的 socket 会自动绑到该网络（API 23+）。
     *
     * 找不到 WiFi/以太网时退回普通客户端（比直接失败好，至少行为不比原来差）。
     */
    private fun portalClient(context: Context, probe: Boolean): OkHttpClient {
        val base = if (probe) probeHttp else http
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return base
        val network = runCatching { campusNetwork(cm) }.getOrNull()
        if (network == null) {
            // 没有 WiFi/以太网：多半是只用流量，或者手机还没连校园网。走默认网络至少能给出
            // 「未连接校园网」而不是卡死；这条日志是排查时区分「没连网」和「连了但绑错网卡」的关键。
            Log.d(TAG, "portalClient: 未找到 WiFi/以太网网卡，使用默认网络")
            return base
        }
        val interfaceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching { cm.getLinkProperties(network)?.interfaceName }.getOrNull()
        } else {
            null
        }
        Log.d(TAG, "portalClient: 绑定校园网网卡 ${interfaceName ?: "unknown"}")
        return runCatching {
            base.newBuilder().socketFactory(network.socketFactory).build()
        }.getOrDefault(base)
    }

    /**
     * 从门户首页的跳转地址里读 `ac_id`（多数学校是 1，但写错必然认证失败）。
     * 读不到返回 null，由调用方退回已保存的值 / 默认值。
     */
    fun detectAcId(context: Context): String? {
        val client = portalClient(context, probe = true).newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder().url("$PORTAL/").header("User-Agent", USER_AGENT).get().build()
        return try {
            client.newCall(request).execute().use { resp ->
                val location = resp.header("Location").orEmpty()
                val body = resp.body?.string().orEmpty()
                val pattern = Regex("ac_id=(\\d+)")
                val id = (pattern.find(location) ?: pattern.find(body))?.groupValues?.get(1)
                // ac_id 抽不到会让后面拿空串去登录 → 门户只回一句 login_error，看不出根因
                Log.d(
                    TAG,
                    "detectAcId: http=${resp.code} ac_id=${id ?: "<未取到>"} " +
                        "location=${location.take(160)}"
                )
                id
            }
        } catch (e: IOException) {
            Log.d(TAG, "detectAcId 失败: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    // ==================== 认证 ====================

    /**
     * 探测门户的「身份域」下拉。
     *
     * **这是「账号不存在」的根因所在（2026-10-26 真机实测）**：
     *
     * 深澜这边接的是 **LDAP**，账号在 LDAP 里是「学号 + 身份域」拼起来的
     * （学生 `24085322@stu.nchu.edu.cn`、教职工 `工号@nchu.edu.cn`、其他 `@tmp`）。
     * 只发裸学号 → 服务端在域里查不到 → 回 `E2531: User not found`（=「账号不存在」）。
     * 带上域 → 同一个账号立刻变成 `E2901: ldap_bind error`（= 账号找到了，只是密码错）。
     *
     * 门户登录页 HTML 里就有这个下拉，直接读它，不要写死：
     * ```html
     * <select id="domain">
     *   <option value="0-@nchu.edu.cn">教职工</option>
     *   <option value="1-@stu.nchu.edu.cn" selected>学生用户</option>
     *   <option value="2-@tmp">其他用户</option>
     * </select>
     * ```
     * `value` 的格式是「序号-@域」，取 `@` 后面那段即可；带 `selected` 的是门户默认项，
     * 排在最前（学生优先，命中概率最高，也少一次失败尝试）。
     *
     * 读不到（门户改版 / 网络抖动）返回 null，由调用方退回 [FALLBACK_DOMAINS]。
     */
    fun detectDomains(context: Context, acId: String): List<String>? {
        val client = portalClient(context, probe = true)
        val request = Request.Builder()
            .url("$PORTAL/srun_portal_pc?ac_id=$acId")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                val domains = parseDomains(html)
                Log.d(TAG, "detectDomains: ac_id=$acId domains=$domains")
                domains.takeIf { it.isNotEmpty() }
            }
        } catch (e: IOException) {
            Log.d(TAG, "detectDomains 失败: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 从门户 HTML 里解析身份域列表。
     *
     * 解析 `<select id="domain">` 里的 `<option value="N-@域" ...>`：
     * - `@` 后面的部分是域后缀（`@stu.nchu.edu.cn`）；
     * - `guestaccess` 这种没有 `@` 的不是域，跳过；
     * - 同一个域出现多次（`@tmp` 有「其他用户」和「科院学生」两项）只留一个；
     * - 带 `selected` 的（门户默认）排最前。
     */
    private fun parseDomains(html: String): List<String> {
        val select = DOMAIN_SELECT.find(html)?.groupValues?.get(1) ?: return emptyList()
        val default = ArrayList<String>()
        val rest = ArrayList<String>()
        for (match in DOMAIN_OPTION.findAll(select)) {
            val value = match.groupValues[1]
            val at = value.indexOf('@')
            if (at < 0) continue
            val domain = value.substring(at)
            if (domain !in default && domain !in rest) {
                if (match.groupValues[2].contains("selected")) default.add(domain) else rest.add(domain)
            }
        }
        return default + rest
    }

    /**
     * 用户名密码登录。成功返回服务端提示（`login_ok` 之类），失败抛 [ApiException]，
     * 文案已经按错误码翻译过（密码错误 / 已在线 / 欠费 / 终端超限 …）。
     *
     * **用户名必须是「账号 + 身份域」**（如 `24085322@stu.nchu.edu.cn`），
     * 见 [detectDomains] 的说明：只发裸学号会被判成「账号不存在」。
     *
     * @param domains 候选身份域，按顺序尝试；命中即返回，其余不再试。
     * @return 成功提示 + 实际命中的身份域（调用方存下来，下次直接用）。
     */
    fun login(
        context: Context,
        username: String,
        password: String,
        acId: String,
        ip: String,
        domains: List<String> = FALLBACK_DOMAINS,
    ): LoginResult {
        // 三个请求共用一个绑定到校园网的客户端，避免各建一份连接池
        val client = portalClient(context, probe = false)
        // 用户自己填了完整账号（`学号@域`）就按它来，不再拼域、也不再换域重试：
        // 否则会对同一个名字重复发好几次，可能撞上服务端的失败次数限制（E2533）。
        val explicit = username.contains('@')
        var lastError: ApiException? = null
        for (domain in domains) {
            // 门户把「输入框里的账号」和「下拉框里的域」拼起来发给服务端
            // （Portal.js: `portal.userInfo.username + portal.userInfo.domain`）
            val full = if (explicit) username else username + domain
            val root = loginOnce(client, full, password, acId, ip)
            val error = root.str("error").orEmpty()
            if (error == "ok") {
                val suc = root.str("suc_msg").orEmpty()
                // `error=ok` 只代表「签名链路对」，是否真的上线要看 suc_msg：
                // 同一 IP 已有会话时回 ip_already_online_error，既不是失败也不是新登录成功
                val message = if (suc.contains("already_online", ignoreCase = true)) {
                    ALREADY_ONLINE
                } else {
                    suc
                }
                return LoginResult(message, if (explicit) "" else domain)
            }
            // 失败必须留下完整响应：error / error_msg 是判断「hmd5 变体对不对、ac_id 对不对」的
            // 唯一线索（日志里没有密码，info 是密文，可以整条打出来）
            Log.d(TAG, "login 被拒: error=$error ip=$ip ac_id=${acId.ifBlank { "<空>" }} body=$root")
            val failure = ApiException(-1, errorText(error, root))
            // 只有「用户不存在」才换域重试：说明这个域里没有这个人，
            // 换一个域才是对的账号。其它错误（密码错 / 欠费 / 已在线）重试没意义，
            // 而且服务端有失败次数限制，多试反而会触发 E2533 锁定。
            if (explicit || errorCodeOf(root) != ECODE_USER_NOT_FOUND) throw failure
            lastError = failure
            Log.d(TAG, "login: $full 不存在（E2531），换下一个身份域重试")
        }
        throw lastError ?: ApiException(-1, TextRef.Res(R.string.campus_net_err_account))
    }

    /**
     * 单次登录尝试（用户名已带身份域），返回门户的原始响应。
     *
     * 拆出来是为了让 [login] 能按域重试，而重试时**必须重新取 challenge**
     * ——challenge 是一次性的（60 秒、一次一用），复用会直接失败。
     *
     * 这里**不抛异常**：成功与否由调用方看 `error` 字段判断，
     * 因为「用户不存在」需要换域重试，不能当成异常直接抛出去。
     */
    private fun loginOnce(
        client: OkHttpClient,
        username: String,
        password: String,
        acId: String,
        ip: String,
    ): JsonObject {
        val challenge = challenge(client, username, ip)
        val hmd5 = if (USE_LEGACY_HMD5) {
            SrunCrypto.md5Hex(password + challenge)
        } else {
            SrunCrypto.hmacMd5Hex(challenge, password)
        }
        // info 里的 username 也要带身份域：服务端解出 info 后拿它去查 LDAP
        val info = SrunCrypto.srunInfo(buildInfoJson(username, password, ip, acId), challenge)
        // 拼接口令：每段前面都带一次 challenge，顺序不能调
        val chksum = SrunCrypto.sha1Hex(
            challenge + username + challenge + hmd5 + challenge + acId +
                challenge + ip + challenge + NS + challenge + TYPE + challenge + info
        )
        return call(
            "/cgi-bin/srun_portal",
            mapOf(
                "action" to "login",
                "username" to username,
                "password" to "{MD5}$hmd5",
                "ac_id" to acId,
                "ip" to ip,
                "chksum" to chksum,
                "info" to info,
                "n" to NS,
                "type" to TYPE,
                "os" to "Android",
                "name" to "Android",
                "double_stack" to "0",
            ),
            client = client,
        )
    }

    /**
     * 下线。
     *
     * [username] 必须是**带身份域的完整账号名**（如 `24085322@stu.nchu.edu.cn`），
     * 和登录时用的是同一个 —— 门户按账号名找会话，名字对不上就下不掉。
     * 调用方应该优先用 [status] 返回的 `sessionUser`（服务端自己认的会话名）。
     */
    fun logout(context: Context, username: String, ip: String, acId: String): String {
        val root = call(
            "/cgi-bin/srun_portal",
            mapOf("action" to "logout", "username" to username, "ip" to ip, "ac_id" to acId),
            client = portalClient(context, probe = false),
        )
        val error = root.str("error").orEmpty()
        if (error != "ok") throw ApiException(-1, errorText(error, root))
        return root.str("suc_msg").orEmpty()
    }

    private fun challenge(client: OkHttpClient, username: String, ip: String): String {
        val root = call("/cgi-bin/get_challenge", mapOf("username" to username, "ip" to ip), client)
        val error = root.str("error").orEmpty()
        if (error != "ok") {
            Log.d(TAG, "get_challenge 被拒: error=$error ip=$ip body=$root")
            throw ApiException(-1, errorText(error, root))
        }
        // client_ip 是门户看到的来源地址。与本机取到的 ip 不一致，说明请求没从校园网网卡出去
        // （绑错网卡 / 被别的网络抢了默认路由），这种失败只看 login_error 是看不出来的
        val serverIp = root.str("client_ip")
        if (serverIp != null && serverIp != ip) {
            Log.d(TAG, "client_ip 不一致: 本机 $ip，门户看到 $serverIp")
        }
        return root.str("challenge")?.takeIf { it.isNotBlank() }
            ?: throw ApiException(-1, TextRef.Res(R.string.campus_net_bad_payload))
    }

    /** info 的明文：紧凑 JSON，键序固定（服务端按 `acid` 字段读 ac_id，不要改名） */
    private fun buildInfoJson(username: String, password: String, ip: String, acId: String): String {
        val obj = JsonObject()
        obj.addProperty("username", username)
        obj.addProperty("password", password)
        obj.addProperty("ip", ip)
        obj.addProperty("acid", acId)
        obj.addProperty("enc_ver", ENC_VER)
        return json.toJson(obj)
    }

    // ==================== 请求与解析 ====================

    /**
     * 发一次 JSONP 请求。
     *
     * 连不上门户（`IOException`）统一翻译成「未连接校园网」，因为这是最常见的原因；
     * 解析失败单独给「返回数据异常」，避免把服务端改版误报成网络问题。
     */
    private fun call(
        path: String,
        params: Map<String, String>,
        client: OkHttpClient = http,
    ): JsonObject {
        val ts = System.currentTimeMillis()
        val urlBuilder = (PORTAL + path).toHttpUrl().newBuilder()
            .addQueryParameter("callback", "jsonp_$ts")
        params.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        urlBuilder.addQueryParameter("_", ts.toString())

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        val text = try {
            client.newCall(request).execute().use { resp -> resp.body?.string().orEmpty() }
        } catch (e: IOException) {
            Log.d(TAG, "call $path 失败: ${e.javaClass.simpleName} ${e.message}")
            throw ApiException(-1, TextRef.Res(R.string.campus_net_unreachable), e)
        }
        return unwrap(text)
    }

    /** 剥掉 `jsonp_123({...})` 外壳；也兼容服务端直接回纯 JSON */
    private fun unwrap(body: String): JsonObject {
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw ApiException(-1, TextRef.Res(R.string.campus_net_bad_payload))
        }
        return try {
            JsonParser.parseString(body.substring(start, end + 1)).asJsonObject
        } catch (e: Exception) {
            throw ApiException(-1, TextRef.Res(R.string.campus_net_bad_payload))
        }
    }

    /**
     * 错误码 → 人话。
     *
     * 只翻译能确认含义的几个（深澜公开的通用码），其余原样回给用户——
     * 猜错了会把人引向错误的排查方向（比如把「欠费」说成「密码错误」）。
     */
    /**
     * 把门户的错误响应翻译成人话。
     *
     * **详细原因在 `error_msg` 里，不在 `error` 里**（2026-10-26 真机踩到）：登录被拒时
     * 门户回的是 `{"error":"login_error","error_msg":"E2553: 密码错误",...}`，
     * `error` 只是个笼统分类。只读 `error` 的话界面上永远只有「操作失败：login_error」，
     * 用户不知道错在哪，排查也拿不到任何线索。
     *
     * 所以顺序是：先从 `error_msg` 里抽 `E####` 码走映射表 → 抽不到再用 `error` 本身当码
     * → 都没有就原样展示服务端那句话。
     */
    private fun errorText(error: String, root: JsonObject): TextRef {
        val detail = root.str("error_msg")?.trim()?.takeIf { it.isNotEmpty() && it != "ok" }
        val code = (detail?.let { ERROR_CODE.find(it)?.value } ?: ERROR_CODE.find(error)?.value)
            ?.uppercase()
        code?.let { ERROR_MESSAGES[it] }?.let { return TextRef.Res(it) }
        if (error.equals("challenge_expired", ignoreCase = true)) {
            return TextRef.Res(R.string.campus_net_err_expired)
        }
        val raw = detail ?: root.str("suc_msg") ?: root.str("msg")
        return TextRef.Raw(raw?.takeIf { it.isNotBlank() } ?: error.ifBlank { "error" })
    }

    /**
     * 从门户响应里取出 `E####` 错误码。
     *
     * 服务端把码放在两个地方：顶层 `ecode` 字段（`"E2531"`），
     * 以及 `error_msg` 里（`"E2531: User not found."`）。两个都读，取到的就是同一个码。
     *
     * [login] 靠它判断「要不要换身份域重试」（只有 E2531 才换）。
     */
    private fun errorCodeOf(root: JsonObject): String? {
        val direct = root.str("ecode")?.trim()?.takeIf { it.isNotEmpty() }
        if (direct != null) return direct.uppercase()
        val detail = root.str("error_msg")
        return detail?.let { ERROR_CODE.find(it)?.value }?.uppercase()
    }

    /**
     * 门户登录页里身份域下拉的 HTML 结构。
     *
     * `<select id="domain">` + `<option value="1-@stu.nchu.edu.cn" selected>`。
     * 选项标签（教职工 / 学生用户 / 其他用户）只在服务端 HTML 里，这里用不到。
     */
    private val DOMAIN_SELECT = Regex(
        """<select[^>]*\bid=["']domain["'][^>]*>(.*?)</select>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** `<option value="1-@stu.nchu.edu.cn" selected>`：第 1 组是 value，第 2 组是其余属性 */
    private val DOMAIN_OPTION = Regex(
        """<option\s+value=["']([^"']*)["']([^>]*)>""",
        setOf(RegexOption.IGNORE_CASE),
    )

    /** 门户详细错误码（形如 `E2553: 密码错误`） */
    private val ERROR_CODE = Regex("E\\d{4}", RegexOption.IGNORE_CASE)

    private val ERROR_MESSAGES = mapOf(
        "E2531" to R.string.campus_net_err_account,
        "E2553" to R.string.campus_net_err_password,
        "E2901" to R.string.campus_net_err_password,
        "E2532" to R.string.campus_net_err_too_frequent,
        "E2533" to R.string.campus_net_err_locked,
        "E2616" to R.string.campus_net_err_arrears,
        "E2620" to R.string.campus_net_err_online,
        "E2806" to R.string.campus_net_err_disabled,
        "E2842" to R.string.campus_net_err_devices,
    )

    private fun JsonObject.str(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return element.asString
    }
}
