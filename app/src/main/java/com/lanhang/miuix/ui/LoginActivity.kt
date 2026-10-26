package com.lanhang.miuix.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lanhang.miuix.R
import com.lanhang.miuix.databinding.ActivityLoginBinding
import com.lanhang.miuix.net.ApiClient
import com.lanhang.miuix.net.ApiException
import com.lanhang.miuix.net.MiniappApi
import com.lanhang.miuix.net.RceApi
import com.lanhang.miuix.schedule.ScheduleRepository
import com.lanhang.miuix.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import com.lanhang.miuix.util.LocaleHelper

class LoginActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        // 语言切换见 LocaleHelper：用 createConfigurationContext 包 base context，
        // 切语言时由设置页显式 recreate()，不走系统的 per-app locale（会黑屏）
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }


    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Session.isLoggedIn) {
            goMain()
            return
        }

        binding.loginButton.setOnClickListener { doLogin() }
    }

    private fun doLogin() {
        val username = binding.usernameInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()
        if (username.isEmpty() || password.isEmpty()) {
            toast(getString(R.string.login_input_required))
            return
        }
        binding.loginButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    val login = RceApi.login(username, password, Session.deviceId(this@LoginActivity))
                    Session.username = username
                    Session.userId = login.staffInfo?.userId ?: ""
                    Session.name = login.staffInfo?.name ?: ""
                    Session.companyId = login.staffInfo?.company_id ?: ""
                    Session.token = login.token ?: ""
                    // 登录成功后尝试拿 LDAP token（OA 免登用）
                    runCatching { RceApi.getLdapToken() }.getOrNull()
                        ?.let { Session.ldapToken = it }
                    login
                }
                if (Session.token.isEmpty()) {
                    toast(getString(R.string.login_no_token))
                } else {
                    toast(getString(R.string.login_welcome, Session.name.ifEmpty { username }))
                    // 登录后自动导入课表
                    withContext(Dispatchers.IO) {
                        runCatching {
                            MiniappApi.handshake(Session.deviceId(this@LoginActivity))
                            ScheduleRepository.refresh(null)
                        }
                    }
                    goMain()
                }
            } catch (e: ApiException) {
                toast(getString(R.string.login_failed, e.code, e.message.orEmpty()))
            } catch (e: Exception) {
                toast(getString(R.string.login_network_error, e.message.orEmpty()))
            } finally {
                binding.loginButton.isEnabled = true
            }
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
