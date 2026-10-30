package com.lanhang.miuix.work

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import com.lanhang.miuix.R
import com.lanhang.miuix.campusnet.CampusNetScreen
import com.lanhang.miuix.library.LibrarySeatScreen
import com.lanhang.miuix.library.SeatBookingsScreen
import com.lanhang.miuix.library.SeatSignCodeScreen
import com.lanhang.miuix.ui.MainActivity
import com.lanhang.miuix.ui.miuix.LanhangMiuixTheme
import com.lanhang.miuix.ui.miuix.MiuiNavStack
import com.lanhang.miuix.ui.miuix.isNightMode

/**
 * 「工作」Tab 宿主（2026-10-26 第六轮：工作与成绩互换，工作升为底栏一级 Tab）。
 *
 * 2026-10-27 第十六轮：这里从「只挂一个 [WorkPage]」改成**自带页面栈**，
 * 用来承载 App 自己的原生功能页（校园网登录、图书馆座位预约）。
 * 页面栈仍然只有一个入口 [MiuiNavStack]（转场）、顶栏仍然只有 `showToolbarMain/Sub`
 * 两个调用（收放由外壳统一），不新开 Activity、不写第二套顶栏 —— 这是 AGENTS §19 的硬规则。
 */
class WorkFragment : Fragment() {

    /** 页面栈：ROOT 永远在栈底，其余按推入顺序压栈 */
    private val stack = mutableStateListOf(WorkRoute.ROOT)

    private val page: WorkRoute get() = stack.last()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            LanhangMiuixTheme(darkTheme = com.lanhang.miuix.ui.miuix.ThemeState.isDark) {
                // 返回键：栈里还有上一页就先退一页，栈底交给系统（退出应用）
                BackHandler(enabled = stack.size > 1) { pop() }
                MiuiNavStack(
                    key = page,
                    depthOf = { (it as WorkRoute).depth },
                    // 2026-10-27 第十八轮：工作页原本没有铺底色，白卡片贴在白页面上，
                    // 卡片边界完全看不见，用户的感觉就是「内容贴着屏幕两侧、不好看」。
                    // 和「我的」一样用 page_bg（日间 #F7F7F7 / 夜间 #000000）铺底。
                    pageBackground = colorResource(R.color.page_bg),
                    modifier = Modifier.fillMaxSize(),
                ) { route ->
                    when (route as WorkRoute) {
                        WorkRoute.ROOT -> WorkPage(
                            modifier = Modifier.fillMaxSize(),
                            onOpenNative = { push(it) },
                        )

                        WorkRoute.CAMPUS_NET -> CampusNetScreen(modifier = Modifier.fillMaxSize())
                        WorkRoute.LIBRARY_SEAT -> LibrarySeatScreen(
                            onOpenBookings = { push(WorkRoute.LIBRARY_BOOKINGS) },
                            modifier = Modifier.fillMaxSize(),
                        )

                        WorkRoute.LIBRARY_BOOKINGS -> SeatBookingsScreen(
                            onOpenSignCode = { push(WorkRoute.LIBRARY_SIGN_CODE) },
                            modifier = Modifier.fillMaxSize(),
                        )

                        WorkRoute.LIBRARY_SIGN_CODE -> SeatSignCodeScreen(modifier = Modifier.fillMaxSize())
                    }
                }
                LaunchedEffect(page) { syncToolbar() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 切 Tab 时 MainActivity.applyTitle 会把顶栏复位成「工作」，这里按当前栈重刷一次
        syncToolbar()
    }

    private fun push(route: WorkRoute) {
        if (stack.lastOrNull() != route) stack.add(route)
    }

    private fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    private fun syncToolbar() {
        val act = activity as? MainActivity ?: return
        // 不是当前 Tab 就别写标题（抢写会让顶栏标题叠加，见 MainActivity.activeTab 注释）
        if (act.activeTab != com.lanhang.miuix.ui.shell.ShellState.TAB_WORK) return
        val route = page
        if (route == WorkRoute.ROOT) {
            act.showToolbarMain(getString(R.string.tab_work))
        } else {
            act.showToolbarSub(getString(titleRes(route)), onBack = { pop() })
        }
    }

    private fun titleRes(route: WorkRoute): Int = when (route) {
        WorkRoute.ROOT -> R.string.tab_work
        WorkRoute.CAMPUS_NET -> R.string.work_campus_net_title
        WorkRoute.LIBRARY_SEAT -> R.string.library_seat_title
        WorkRoute.LIBRARY_BOOKINGS -> R.string.library_seat_my_bookings
        WorkRoute.LIBRARY_SIGN_CODE -> R.string.seat_sign_code_title
    }
}
