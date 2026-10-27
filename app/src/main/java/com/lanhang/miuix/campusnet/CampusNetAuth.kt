package com.lanhang.miuix.campusnet

import android.content.Context
import com.lanhang.miuix.R
import com.lanhang.miuix.core.ApiException
import com.lanhang.miuix.core.TextRef

/**
 * 校园网认证的**共享流程**。
 *
 * 存在的意义：认证逻辑现在有两个入口——应用内的「校园网」页和桌面小组件。
 * 两边必须**走同一条路径**，否则迟早出现「App 里能认证、小组件里不行」这类不一致
 * （域探测、身份域重试、记住命中域这些细节很容易只改一边）。
 *
 * 流程（与门户前端一致）：
 * 1. 取本机校园网 IP（拿不到 → 报「没有获取到本机 IP」）
 * 2. 探一次门户（不在校园网 → 报「未连接校园网」，别把密码送到不可达的地址上）
 * 3. 探测 `ac_id` 与**身份域**（学生 `@stu.nchu.edu.cn` / 教职工 `@nchu.edu.cn` …）
 * 4. 带上身份域登录；只有「用户不存在」才换域重试
 * 5. 记住命中的域，下次直接命中
 */
object CampusNetAuth {

    /** 认证结果 */
    data class Result(
        /** 服务端提示（`login_ok` 等） */
        val message: String,
        /** 实际命中的身份域；用户自己填了完整邮箱时为空串 */
        val domain: String,
        /** 该 IP 之前就已经在线上（不是本次新建的会话） */
        val alreadyOnline: Boolean,
    )

    /**
     * 执行一次认证。
     *
     * @param password 明文密码。调用方负责取（界面输入框 / Keystore 里存的）。
     * @throws ApiException 认证失败，文案已按错误码翻译好。
     */
    fun authenticate(
        context: Context,
        username: String,
        password: String,
    ): Result {
        val app = context.applicationContext
        val ip = SrunApi.localIpv4(app)
            ?: throw ApiException(-1, TextRef.Res(R.string.campus_net_no_ip))
        // 先探一次门户：不在校园网就直接报网络问题，别把密码送到不可达的地址上
        SrunApi.status(app)
        val acId = SrunApi.detectAcId(app)?.also { CampusNetStore.acId = it }
            ?: CampusNetStore.acId
        // 身份域（学生 @stu.nchu.edu.cn / 教职工 @nchu.edu.cn …）：
        // 少了它服务端在 LDAP 里查不到人，会回「账号不存在」（E2531）。
        // 读不到就退回上次命中的域 / 默认域，登录时会逐个试。
        val detected = SrunApi.detectDomains(app, acId).orEmpty()
        val domains = CampusNetStore.domains(detected)
        CampusNetStore.username = username
        val result = SrunApi.login(app, username, password, acId, ip, domains)
        // 记住命中的域，下次直接用它，少一次失败尝试
        // （用户自己填了完整邮箱时域为空串，别覆盖掉已记住的值）
        if (result.domain.isNotEmpty()) CampusNetStore.domain = result.domain
        return Result(
            message = result.message,
            domain = result.domain,
            alreadyOnline = result.message == SrunApi.ALREADY_ONLINE,
        )
    }

    /**
     * 执行一次下线。
     *
     * 和认证一样必须走共享流程：下线要按**服务端认的会话名**
     * （带身份域的完整账号名 `学号@stu.nchu.edu.cn`），名字对不上就下不掉。
     * 优先用门户 `rad_user_info` 回的会话名；查不到就退回
     * 「上次命中的域 + 当前账号」拼一个。
     *
     * @param username 用户输入的账号（可能是裸学号，也可能是完整邮箱）。
     * @throws ApiException 下线失败，文案已按错误码翻译好。
     */
    fun logout(context: Context, username: String): String {
        val app = context.applicationContext
        val ip = SrunApi.localIpv4(app)
            ?: throw ApiException(-1, TextRef.Res(R.string.campus_net_no_ip))
        val sessionUser = SrunApi.status(app).sessionUser
        val account = sessionUser
            ?: if (username.contains('@')) username else username + CampusNetStore.domain
        return SrunApi.logout(app, account, ip, CampusNetStore.acId)
    }
}
