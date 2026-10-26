package com.lanhang.miuix.work

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.lanhang.miuix.ui.miuix.LanhangMiuixTheme
import com.lanhang.miuix.ui.miuix.isNightMode

/**
 * 「工作」Tab 宿主（2026-10-26 第六轮：工作与成绩互换，工作升为底栏一级 Tab）。
 *
 * 页面本体还是 [WorkPage]（办事大厅目录 + 常用功能），这里只负责把 Compose
 * 内容挂到 Fragment 生命周期上；标题由外壳 `MainActivity.titleForTab` 给「工作」。
 */
class WorkFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            LanhangMiuixTheme(darkTheme = LocalContext.current.isNightMode()) {
                WorkPage(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
