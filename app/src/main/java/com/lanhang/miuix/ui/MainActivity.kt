package com.lanhang.miuix.ui

import android.Manifest
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import com.lanhang.miuix.R
import com.lanhang.miuix.databinding.ActivityMainBinding
import com.lanhang.miuix.work.WorkFragment
import com.lanhang.miuix.im.ImNotify
import com.lanhang.miuix.moments.MomentsFragment
import com.lanhang.miuix.net.MiniappApi
import com.lanhang.miuix.schedule.ScheduleFragment
import com.lanhang.miuix.mine.MineFragment
import com.lanhang.miuix.session.Session
import com.lanhang.miuix.ui.miuix.LanhangMiuixTheme
import com.lanhang.miuix.ui.miuix.isNightMode
import com.lanhang.miuix.ui.shell.LanhangBottomBar
import com.lanhang.miuix.ui.shell.LanhangTopBar
import com.lanhang.miuix.ui.shell.ScrollToTop
import com.lanhang.miuix.ui.shell.ShellScroll
import com.lanhang.miuix.ui.shell.ShellState
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost
import com.lanhang.miuix.util.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主界面外壳。
 *
 * 2026-10-24 起视觉层换成 Miuix：顶栏是 `SmallTopAppBar`，底栏是 `NavigationBar`
 * （替代原来的 MaterialToolbar + 液态玻璃底栏）。页面本身仍是 Fragment，
 * 课表那张自绘网格继续复用原有实现，只是外面套了 Miuix 的皮肤。
 *
 * 2026-10-24 二次改版（用户反馈「切页没有过渡动画」）：四个 Tab 从 hide/show 改为
 * **detach/attach + setCustomAnimations**——hide/show 是 Visibility 变化，Fragment 动画
 * 完全不生效；detach/attach 才会走 enter/exit 动画，而且 Fragment 实例与 View 状态
 * 都由 FragmentManager 保存，切回来仍是原来的滚动位置（replace 就做不到）。
 */
class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：用 createConfigurationContext 包 base context，
        // 切语言时由设置页显式 recreate()，不走系统的 per-app locale（会黑屏）
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityMainBinding

    /** 顶栏 / 底栏共享状态（Compose 状态，switchTab 与两个外壳组件共同读写） */
    val shell = ShellState()

    /**
     * 当前激活的 Tab（[ShellState.TAB_SCHEDULE] / [ShellState.TAB_WORK] / …）。
     *
     * 2026-10-28 第三十一轮（用户：「打开工作的图片预览→回桌面→用小组件进课表，顶栏就叠在一起」）：
     * 各个页面在 `onResume` 里都会调一次 `syncToolbar()` 把自己的标题写进顶栏。从小组件跳进来时，
     * 顺序是「MainActivity.onResume → applyPendingTab 把 Tab 切成课表」，而**别的 Fragment 也在
     * 同一轮 onResume 里抢着写标题** —— 于是同一两帧里标题被写了三四次
     * （课表 → 工作圈 → 课表 …）。顶栏标题用 AnimatedContent 做淡入淡出，连续换目标时
     * 前后两段过渡会短暂共存，这就是「两个标题叠在一起」。
     *
     * 现在子页面写标题前先问一句 activeTab：不是自己的 Tab 一律不写，抢写在源头就没有了。
     */
    val activeTab: Int get() = shell.tab

    private var scheduleFragment: Fragment? = null
    private var workFragment: Fragment? = null
    private var momentsFragment: Fragment? = null
    private var mineFragment: Fragment? = null

    /**
     * 夜间模式是否生效（顶栏 / 底栏 / overlay 三处 Compose 外壳共用）。
     *
     * 2026-10-30：不再用 `isNightMode()` 读 Activity 的缓存 configuration —— 系统切深浅色时
     * 它不会更新（语言被 `LocaleHelper` 包裹时更会被 `createConfigurationContext` 永久冻结），
     * 表现为「深色模式不跟随系统」。改读进程级 Compose 状态 [com.lanhang.miuix.ui.miuix.ThemeState]，
     * 它由 `Application.registerComponentCallbacks` 持续校正，值一变这几个 setContent 自动重组。
     */
    private val nightMode: Boolean
        get() = com.lanhang.miuix.ui.miuix.ThemeState.isDark

    /** 重建（旋转/夜间模式 recreate）时恢复选中 Tab，且不播放入场动画 */
    private var restoring = false

    /** 上一次切页动画里「正在滑出」的旧页（动画结束才 detach） */
    private var slidingOut: Fragment? = null
    /** Tab 横移动画（便于中途切页时取消）与其压暗层 */
    private var swapAnimator: android.animation.ValueAnimator? = null
    /** 切页兜底看门狗：动画超时/被打断时强制复位，绝不停在「半切」状态 */
    private var swapWatchdog: Runnable? = null

    /** Android 13+ 通知权限（第一次用到私信通知时再问，不在冷启动就弹） */
    private val notifyPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边：Miuix 的顶栏 / 底栏自己处理状态栏与导航栏 insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupShell()
        // 2026-10-25：切亮/暗色会走 recreate()，个别机型上顶栏区域会残留上一窗口的画面
        // （表现为标题叠影）。首帧之后再强制整窗重绘一次，顶栏/底栏颜色立即跟到新主题。
        binding.root.post { binding.root.invalidate() }

        scheduleFragment = supportFragmentManager.findFragmentByTag(TAG_SCHEDULE)
        workFragment = supportFragmentManager.findFragmentByTag(TAG_WORK)
        momentsFragment = supportFragmentManager.findFragmentByTag(TAG_MOMENTS)
        mineFragment = supportFragmentManager.findFragmentByTag(TAG_MINE)
        if (savedInstanceState == null) {
            switchTab(ShellState.TAB_SCHEDULE)
        } else {
            restoring = true
            switchTab(savedInstanceState.getInt(KEY_TAB, ShellState.TAB_SCHEDULE))
            restoring = false
        }
        handleWidgetIntent(intent)
        applyPendingTab()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, shell.tab)
    }

    private fun setupShell() {
        binding.shellTopBar.setContent {
            LanhangMiuixTheme(darkTheme = nightMode) {
                LanhangTopBar(
                        state = shell,
                        // 2026-10-25 用户要求：课表页的「课表」也要和「工作圈」一样是大标题
                        large = shell.backAction == null,
                    )
            }
        }
        // 全屏 overlay：只负责渲染 Miuix OverlayDialog（评论 / 发表 / 我的评论）。
        binding.shellOverlay.setContent {
            LanhangMiuixTheme(darkTheme = nightMode) {
                MiuixPopupHost()
            }
        }
        binding.shellBottomNav.setContent {
            LanhangMiuixTheme(darkTheme = nightMode) {
                LanhangBottomBar(
                    selectedIndex = shell.tab,
                    onSelect = { switchTab(it) },
                    onScrollTop = { onTabDoubleTap(it) },
                )
            }
        }
        // 键盘弹出时把底栏收起来（用户 2026-10-26 第六轮：「私信页面中输入框应紧贴输入法，现在有空隙」）。
        // 底栏占着屏幕底部（56dp + 导航条），键盘弹起后它卡在键盘上方，私信输入条又被 `imePadding()`
        // 顶一次，中间就空了 56dp。底栏 GONE 之后 `fragmentContainer` 的底边直接落到屏幕底，
        // 输入条正好贴在键盘上；键盘收起再恢复。
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val wanted = if (ime > nav) View.GONE else View.VISIBLE
            if (binding.shellBottomNav.visibility != wanted) {
                binding.shellBottomNav.visibility = wanted
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 亮/暗切换后：Compose 部分读 ThemeState 会自己重组，但自绘课表（View 网格）不会，
        // 这里显式让它按新配色重画一遍，否则课块/时间轴还是旧色。
        (scheduleFragment as? ScheduleFragment)?.onThemeChanged()
    }

    /**
     * 系统深浅色在「我们没收到 onConfigurationChanged」的情况下变了（通知栏快捷开关遮挡 Activity 那种），
     * 由 [com.lanhang.miuix.App] 的 ActivityLifecycleCallbacks 在回到前台对账后发现变化时调用。
     *
     * Compose 层读 `ThemeState.isDark` 会自己重组；这里负责非 Compose 的两块：
     * 窗口根 View 的背景（XML `@color/page_bg` 那份 resources 可能还是旧的）和自绘课表网格。
     */
    fun onSystemThemeChanged() {
        if (isFinishing || isDestroyed) return
        binding.root.invalidate()
        (scheduleFragment as? ScheduleFragment)?.onThemeChanged()
    }

    /**
     * 双击底栏某个 Tab：先切到该页，再把页面滚回顶部。
     *
     * 课表页的网格是自绘 View（不在 Compose 树里），只能直接喊 Fragment 滚；
     * 其余三个 Compose 页面走 [ScrollToTop] 信号，各自在 `LaunchedEffect` 里响应。
     * 单击已经选中的 Tab 仍是「回到该页主层级」（见 switchTab），双击在此之上再补「回到顶部」。
     */
    private fun onTabDoubleTap(tab: Int) {
        switchTab(tab)
        if (tab == ShellState.TAB_SCHEDULE) {
            (scheduleFragment as? ScheduleFragment)?.scrollToTop()
        } else {
            ScrollToTop.request(tab)
        }
    }

    /** 课表 Tab 的标题是「课表」——原来回落到 app_name，顶栏写着「蓝航Lite」（用户 2026-10-24 指出） */
    private fun titleForTab(tab: Int): String = when (tab) {
        ShellState.TAB_MOMENTS -> getString(R.string.tab_moments)
        ShellState.TAB_MINE -> getString(R.string.tab_mine)
        ShellState.TAB_WORK -> getString(R.string.tab_work)
        else -> getString(R.string.tab_schedule)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
        // 2026-10-27 第二十七轮（用户：「我提前切到非课表 Tab，再回桌面点小组件进程序，
        // 顶栏不会换成课表」）：onNewIntent 回调时窗口可能还停着（onRestart/onStart 之前），
        // 这时候做 Fragment 事务 + 顶栏收放动画会半途卡住 —— 实测页面和底栏都已经是课表，
        // 顶栏却还挂着「工作」。所以统一推迟到下一帧窗口可见时再切。
        binding.root.post { applyPendingTab() }
    }

    /**
     * 外部 intent 进来（桌面小部件「打开课表」、私信通知点开某个会话）。
     * 处理完把 extra 清掉，否则重建时会被再执行一次。
     */
    private fun handleWidgetIntent(intent: android.content.Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_SCHEDULE, false)) {
            // 一次性：不摘掉这个 extra，主题切换 recreate 时会被再执行一遍
            intent.removeExtra(EXTRA_OPEN_SCHEDULE)
            pendingTab = ShellState.TAB_SCHEDULE
        }
        val peer = intent.getStringExtra(ImNotify.EXTRA_IM_PEER).orEmpty()
        if (peer.isNotBlank()) {
            val title = intent.getStringExtra(ImNotify.EXTRA_IM_TITLE).orEmpty()
            openDirectChat(peer, title)
            intent.removeExtra(ImNotify.EXTRA_IM_PEER)
            intent.removeExtra(ImNotify.EXTRA_IM_TITLE)
        }
    }

    /** 由外部 intent（桌面小部件）指定、还没执行的 Tab 切换；-1 = 没有 */
    private var pendingTab = -1

    /**
     * 执行 [pendingTab]（桌面小部件点进来要落到课表）。
     *
     * 在三个时机都会试一次：`onCreate`（冷启动）、`onResume`（窗口马上要显示）、以及
     * `onNewIntent` 里 post 的下一帧（App 在前台时不会再来一次 onResume）。
     * 切的时候把 [restoring] 抬起一次 —— 从桌面跳进来算「重新进场」，不做左右滑动，
     * 也不给卡半截动画留机会；切完再把顶栏标题按最终 Tab 对齐一次，杜绝「页面是课表、顶栏写着工作」。
     */
    private fun applyPendingTab() {
        val tab = pendingTab
        if (tab < 0) return
        pendingTab = -1
        // 第三十三轮：进场前把容器里可能残留的位移动画（Tab 左右滑、转场被打断）
        // 就地归零，否则新旧两帧会错位叠在一起，看着像「整页画了两遍」。
        binding.fragmentContainer.animate().cancel()
        for (i in 0 until binding.fragmentContainer.childCount) {
            val child = binding.fragmentContainer.getChildAt(i)
            child.animate().cancel()
            if (child.translationX != 0f) child.translationX = 0f
            if (child.translationY != 0f) child.translationY = 0f
            if (child.alpha != 1f) child.alpha = 1f
        }
        val wasRestoring = restoring
        restoring = true
        switchTab(tab)
        restoring = wasRestoring
        applyTitle(shell.tab)
    }

    /** 前台标记：App 在前台且正看着这个会话时不弹通知（见 ImNotify / ImRepository.notifyIncoming） */
    override fun onResume() {
        super.onResume()
        applyPendingTab()
        ImNotify.appForeground = true
        ImNotify.ensureChannel(this)
    }

    override fun onPause() {
        super.onPause()
        ImNotify.appForeground = false
    }

    /**
     * 第一次进私信时申请通知权限（Android 13+）。已经授权/已拒绝过就什么也不做，
     * 拒绝也只是没有通知，聊天本身不受影响。
     */
    fun ensureNotifyPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        if (ImNotify.hasPermission(this)) return
        runCatching { notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    fun switchTab(tab: Int) {
        val from = shell.tab
        val fm0 = supportFragmentManager
        // 同一个 Tab 再点一次：不切页、不播动画，只把该页退回主层级
        // （用户 2026-10-24：本来就在设置页又点一下设置，不该有切换动效）
        // 注意：**首次挂载**时 shell.tab 的默认值就等于 TAB_SCHEDULE，此时容器里还没有任何页面，
        // 必须继续往下走正常流程，否则冷启动会停在空白页（2026-10-24 修）
        if (from == tab && currentAttachedFragment(fm0) != null) {
            applyTitle(tab)
            if (tab == ShellState.TAB_MINE) (mineFragment as? MineFragment)?.onTabSelected()
            if (tab == ShellState.TAB_MOMENTS) (momentsFragment as? MomentsFragment)?.onTabSelected()
            if (tab == ShellState.TAB_SCHEDULE) (scheduleFragment as? ScheduleFragment)?.onTabSelected()
            return
        }
        // 上一次切页动画可能还没收尾，先收干净，避免容器里同时挂三个页面
        settleSlidingOut()
        resetAllPageViews()

        shell.tab = tab
        // 每个 Tab 各自记着上次的顶栏档位：切过去直接贴回来，不会先闪一下大标题，
        // 也不会像旧版那样被「离开的页面」清零（见 ShellScroll.report 的注释）
        ShellScroll.switchTo(tab)
        val fm = supportFragmentManager
        val outgoing = currentAttachedFragment(fm)
        val animating = !restoring && outgoing?.view != null
        val targetTag = tagFor(tab)
        val tx = fm.beginTransaction()
        val existing = fm.findFragmentByTag(targetTag)
        val target: Fragment
        if (existing == null) {
            target = newFragment(tab)
            tx.add(R.id.fragmentContainer, target, targetTag)
        } else {
            target = existing
            if (existing.isDetached) tx.attach(existing)
        }
        cache(target, targetTag)
        for (tag in ALL_TAGS) {
            if (tag == targetTag) continue
            val f = fm.findFragmentByTag(tag) ?: continue
            // 动画期间保留「即将滑出的旧页」，动画结束再 detach——否则只有新页在动，很生硬
            if (animating && f === outgoing) continue
            if (f.isAdded && !f.isDetached) tx.detach(f)
        }
        // 顶栏标题跟着页面**同方向**滑入：否则点下去标题瞬间变、页面还在滑，观感就是「闪一下」
        applyTitle(tab)
        tx.commitNow()
        if (animating) startPagerSwap(outgoing!!, target, forward = tab > from)
        // 「我的」可能停在二级页（如主题切换 recreate 后），切回时把顶栏对齐到其内部状态。
        if (tab == ShellState.TAB_MINE) {
            (mineFragment as? MineFragment)?.syncToolbar()
        }
        if (tab == ShellState.TAB_MOMENTS) {
            (momentsFragment as? MomentsFragment)?.onTabSelected()
        }
        // 课表：detach/attach 复用视图，回来时清掉换周/切视图动画的残留并重画，
        // 否则会停在「一片空白」（用户 2026-10-25 反馈）
        if (tab == ShellState.TAB_SCHEDULE) {
            (scheduleFragment as? ScheduleFragment)?.onTabSelected()
        }
    }

    /**
     * Tab 之间的过渡 = Miuix `NavTransitions.MiuixDefault` 的几何 + 弹簧（2026-10-24 深夜八次改版）：
     *  · 新页从侧边**整幅**推入（translationX: +width → 0）
     *  · 旧页视差让位（-0.25×width）并淡到 0.9（alpha 1 → 0.9）
     *  · 旧页上方压一层 dim scrim（新页不做圆角裁剪，见下方说明）
     *  · 进度用临界阻尼弹簧（dampingRatio=1 / stiffness=146 → ω=√146，≈ Miuix NavDriverSpec），560ms
     *
     * 为什么改掉上一版的 320ms + `PathInterpolator(0.2,0,0,1)`：那条曲线前段极快，
     * 观感是「一下闪过去再干等」，用户判定「快速闪动」。另外这里**必须等新页画出第一帧**再起动画，
     * 否则滑进来的前几帧是空白（Compose 页面首帧晚于 fragment attach）。
     * 仍然**不能**用 `FragmentTransaction.setCustomAnimations`（detach/attach + ComposeView 会 100% 崩）。
     */
    private fun startPagerSwap(outgoing: Fragment, target: Fragment, forward: Boolean) {
        val container = findViewById<android.view.ViewGroup>(R.id.fragmentContainer)
        val inView = target.view
        if (container == null || inView == null || container.width <= 0) {
            finishSlidingOut(outgoing)
            return
        }
        slidingOut = outgoing
        val w = container.width.toFloat()
        val dir = if (forward) 1f else -1f
        // 先把新页摆到屏幕外：必须在它第一帧绘制**之前**完成，否则会闪一下。
        // 注意：Tab 横移**不加圆角裁剪**（用户 2026-10-24：tab 切换出现圆角不对），
        // 圆角只属于「二级页推入」（Miuix 的 corner clip，见 ui/miuix/NavPush.kt）。
        inView.bringToFront()
        inView.translationX = w * dir
        var started = false
        val begin = {
            if (!started) {
                started = true
                runSwap(outgoing, inView, w, dir)
            }
        }
        inView.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                inView.viewTreeObserver.removeOnPreDrawListener(this)
                begin()
                return true
            }
        })
        inView.invalidate()
        // 兜底：pre-draw 万一不来（视图已被 detach），别让旧页永远留在容器里
        container.postDelayed({ begin() }, 320L)
        // 再兜一层：整段动画超过 SWAP_MS + 1.6s 还没收尾（设备卡顿 / 组合迟迟不来），
        // 直接强制收尾。否则新页会一直停在屏幕外，用户看到的是「点了 tab 没反应」。
        cancelSwapWatchdog()
        val dog = Runnable {
            swapWatchdog = null
            swapAnimator?.cancel()
            swapAnimator = null
            inView.animate().cancel()
            inView.translationX = 0f
            inView.alpha = 1f
            finishSlidingOut(outgoing)
        }
        swapWatchdog = dog
        container.postDelayed(dog, TAB_SWAP_MS + 1600L)
    }

    private fun cancelSwapWatchdog() {
        swapWatchdog?.let { binding.fragmentContainer.removeCallbacks(it) }
        swapWatchdog = null
    }

    /**
     * 把四个页面根 View 复位（位移/透明度/残留压暗层/残留动画全部清掉）。
     * 每次切页开头调用一次：即使上一次动画因为设备卡顿没跑完，也不会把状态带进这一次。
     */
    private fun resetAllPageViews() {
        cancelSwapWatchdog()
        // 顶栏也可能停在上一次「滑入」的半路（alpha=0 等于顶栏消失），一起复位
        binding.shellTopBar.animate().cancel()
        binding.shellTopBar.alpha = 1f
        binding.shellTopBar.translationX = 0f
        swapAnimator?.cancel()
        swapAnimator = null
        ALL_TAGS.forEach { tag ->
            supportFragmentManager.findFragmentByTag(tag)?.view?.let {
                it.animate().cancel()
                it.translationX = 0f
                it.alpha = 1f
            }
        }
    }

    /**
     * 真正跑横移动画：**两页整幅反向平移**，没有淡入淡出 / 压暗 / 视差 / 圆角裁剪。
     *
     * 参照 KernelSU-Style-UI-Kit（`ui/component/bottombar/BottomBar.kt` 的 `MainPagerState.animateToPage`）：
     * 它内部是一个真实的 `HorizontalPager`，点击底栏就
     * `pagerState.animateScrollBy(scrollPixels, tween(100 × 距离 + 100ms, EaseInOut))`；
     * Pager 的语义就是「新旧两页各平移一整个屏宽、首尾相接」，既没有 dim 也没有 alpha 视差。
     * 我们前几版按 Miuix `NavDisplay`（那是**二级页推入**的规范）套了 dim 0.5 + 视差 0.25 +
     * 淡到 0.9 + 560ms 弹簧 —— 在 Tab 这种「平级切换」上就是用户连续两轮说的「快速闪动 / 不自然」。
     * **不要再加回 scrim / 视差 / 透明度**。
     */
    private fun runSwap(outgoing: Fragment, inView: android.view.View, w: Float, dir: Float) {
        if (findViewById<android.view.ViewGroup>(R.id.fragmentContainer) == null) {
            finishSlidingOut(outgoing)
            return
        }
        val outView = outgoing.view
        // 两页必须**不透明**：Compose 页面根没有底色，重叠时会互相透出重影
        val pageBg = androidx.core.content.ContextCompat.getColor(this, R.color.page_bg)
        inView.setBackgroundColor(pageBg)
        outView?.setBackgroundColor(pageBg)
        inView.bringToFront()
        inView.animate().cancel()
        outView?.animate()?.cancel()
        inView.translationX = w * dir
        outView?.translationX = 0f
        swapAnimator?.cancel()
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TAB_SWAP_MS
            interpolator = TAB_INTERPOLATOR
            addUpdateListener { a ->
                val p = a.animatedValue as Float
                inView.translationX = w * dir * (1f - p)
                outView?.translationX = -w * dir * p
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    inView.translationX = 0f
                    outView?.translationX = 0f
                    finishSlidingOut(outgoing)
                }
            })
        }
        swapAnimator = anim
        anim.start()
    }

    /** 动画结束：把「滑出中」的旧页 detach 并把位移复位。 */
    private fun finishSlidingOut(f: Fragment) {
        if (slidingOut === f) slidingOut = null
        resetSwapState()
        f.view?.let {
            it.animate().cancel()
            it.translationX = 0f
            it.alpha = 1f
        }
        if (f.isAdded && !f.isDetached) {
            // 在动画结束回调里用 commitNow 会打断当前帧，这里用异步 commit
            supportFragmentManager.beginTransaction().detach(f).commit()
        }
    }

    /** 切页动画中途又切页：立刻把上一个「滑出中」的页面收尾。 */
    private fun settleSlidingOut() {
        val f = slidingOut ?: return
        slidingOut = null
        resetSwapState()
        // 新页可能停在半路，把它的位移/裁剪复位
        ALL_TAGS.forEach { tag ->
            supportFragmentManager.findFragmentByTag(tag)?.view?.let {
                if (it.translationX != 0f) it.translationX = 0f
            }
        }
        f.view?.let {
            it.animate().cancel()
            it.translationX = 0f
            it.alpha = 1f
        }
        if (f.isAdded && !f.isDetached) {
            supportFragmentManager.beginTransaction().detach(f).commitNow()
        }
    }

    /** 取消横移动画（幂等）。 */
    private fun resetSwapState() {
        cancelSwapWatchdog()
        swapAnimator?.cancel()
        swapAnimator = null
    }

    private fun currentAttachedFragment(fm: androidx.fragment.app.FragmentManager): Fragment? =
        ALL_TAGS.firstNotNullOfOrNull { tag ->
            fm.findFragmentByTag(tag)?.takeIf { it.isAdded && !it.isDetached }
        }

    private fun newFragment(tab: Int): Fragment = when (tab) {
        ShellState.TAB_WORK -> WorkFragment()
        ShellState.TAB_MOMENTS -> MomentsFragment()
        ShellState.TAB_MINE -> MineFragment()
        else -> ScheduleFragment()
    }

    private fun cache(f: Fragment, tag: String) {
        when (tag) {
            TAG_WORK -> workFragment = f
            TAG_MOMENTS -> momentsFragment = f
            TAG_MINE -> mineFragment = f
            else -> scheduleFragment = f
        }
    }

    private fun tagFor(tab: Int): String = when (tab) {
        ShellState.TAB_WORK -> TAG_WORK
        ShellState.TAB_MOMENTS -> TAG_MOMENTS
        ShellState.TAB_MINE -> TAG_MINE
        else -> TAG_SCHEDULE
    }

    /**
     * 切 Tab 时换标题。
     *
     * 2026-10-26 第十一轮：切 Tab 与「进/出二级页」走**同一条**顶栏动画
     * （用户反馈切 Tab 时顶栏不自然）。顶栏怎么动不再由调用方决定，
     * 见 [com.lanhang.miuix.ui.shell.BarAnimation]。
     */
    private fun applyTitle(tab: Int) {
        showToolbarMain(titleForTab(tab))
    }

    /**
     * 主 Tab 态：标题 + 无返回箭头。
     *
     * 顶栏动画由 [com.lanhang.miuix.ui.shell.BarAnimation] 统一处理，这里不传任何动效参数。
     */
    fun showToolbarMain(
        title: String,
        actionIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
        onAction: (() -> Unit)? = null,
    ) {
        shell.title = title
        shell.backAction = null
        shell.homeAction = null
        shell.actionText = null
        shell.actionIcon = actionIcon
        shell.onAction = if (actionIcon == null) null else onAction
    }

    /**
     * 二级页态：返回箭头 + 子页标题 + 可选右上角文字动作
     * （如「发表动态」页的「发表」，对应 Miuix sub page top app bar 的 actions 槽）。
     *
     * [onHome] 非空时，返回箭头**左侧**再出现一个房子图标（通讯录用）：返回退一级目录，
     * 房子一步回本 Tab 根页 —— 用户 2026-10-26 要求。
     *
     * 顶栏动画不传参：推入和返回都是同一段收放，见 [com.lanhang.miuix.ui.shell.BarAnimation]。
     */
    fun showToolbarSub(
        title: String,
        onBack: () -> Unit,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
        onHome: (() -> Unit)? = null,
    ) {
        shell.title = title
        shell.backAction = onBack
        shell.homeAction = onHome
        shell.actionText = actionText
        shell.actionIcon = null
        shell.onAction = if (actionText == null) null else onAction
    }

    /** 设置页「课表工具 → 调试」入口：手动握手并拉一次课表原始数据 */
    fun debugSchedule() {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val ok = MiniappApi.handshake(Session.deviceId(this@MainActivity))
                    if (!ok) {
                        return@withContext getString(
                            R.string.debug_handshake_failed,
                            Session.ldapToken.take(20)
                        )
                    }
                    val raw = MiniappApi.getScheduleRaw(null)
                    getString(R.string.debug_handshake_ok, raw.length)
                }
                com.lanhang.miuix.ui.miuix.showMiuixInfo(
                    this@MainActivity,
                    getString(R.string.debug_schedule_title),
                    result,
                    getString(R.string.common_ok),
                )
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.debug_failed, e.message ?: e.toString()),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** 退出登录（原顶栏「更多」菜单里的入口，现由设置页调用） */
    /** 通讯录 → 「查看个人主页」：切到工作圈并直接打开这个人的主页 */
    fun openMomentsProfile(userId: String) {
        if (userId.isBlank()) return
        switchTab(ShellState.TAB_MOMENTS)
        (momentsFragment as? MomentsFragment)?.openProfileForUser(userId)
    }

    /** 消息 → 某条互动：切到工作圈列表 */
    fun openMomentsTab() {
        switchTab(ShellState.TAB_MOMENTS)
    }

    /** 消息 → 某条互动的帖子原文：切到工作圈并定位到那条动态 */
    fun openMomentsFeed(feedId: String) {
        if (feedId.isBlank()) return
        switchTab(ShellState.TAB_MOMENTS)
        (momentsFragment as? MomentsFragment)?.openFeed(feedId)
    }

    /** 个人主页 →「发私信」：切到「我的」并直接打开和这个人的聊天页（[title] 空时由页面自己查名字） */
    fun openDirectChat(userId: String, title: String = "") {
        if (userId.isBlank()) return
        switchTab(ShellState.TAB_MINE)
        (mineFragment as? MineFragment)?.openImChat(userId, title)
    }

    fun logout() {
        Session.clear()
        com.lanhang.miuix.net.ApiClient.clearCookies()
        com.lanhang.miuix.net.MomentsApi.logout()
        cacheDir.deleteRecursively()
        com.lanhang.miuix.widget.ScheduleWidgetUpdater.updateAll(applicationContext)
        startActivity(android.content.Intent(this, LoginActivity::class.java))
        finish()
    }

    companion object {
        /**
         * Tab 横移时长：参照 KernelSU-Style-UI-Kit 的 `100 × 距离 + 100`（相邻 Tab = 200ms），
         * 取 220ms 稍留余量给首帧组合。**不要再回到 320/560ms**——那是用户反馈「闪动/拖沓」的区间。
         */
        /** Tab 之间切页的时长：与顶栏 / 页面转场同一条 tween（第十一轮统一） */
    val TAB_SWAP_MS: Long = com.lanhang.miuix.ui.miuix.NavMotion.DURATION_MS.toLong()
        /** Compose 的 `EaseInOut` = cubic-bezier(0.42, 0, 0.58, 1)，与参考项目用的同一条曲线 */
        val TAB_INTERPOLATOR: android.view.animation.Interpolator =
            android.view.animation.PathInterpolator(0.42f, 0f, 0.58f, 1f)
        private const val TAG_SCHEDULE = "schedule"
        private const val TAG_WORK = "grades"
        private const val TAG_MOMENTS = "moments"
        private const val TAG_MINE = "mine"
        private const val KEY_TAB = "shell_tab"

        private val ALL_TAGS = arrayOf(TAG_SCHEDULE, TAG_WORK, TAG_MOMENTS, TAG_MINE)

        const val EXTRA_OPEN_SCHEDULE = "com.lanhang.miuix.extra.OPEN_SCHEDULE"
    }
}
