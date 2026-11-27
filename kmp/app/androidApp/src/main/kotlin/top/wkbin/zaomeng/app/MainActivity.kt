package top.wkbin.zaomeng.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.lifecycleScope
import io.github.suqi8.coui.kmp.window.WindowUserStatementDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import org.koin.compose.koinInject
import top.wkbin.zaomeng.app.shared.App
import top.wkbin.zaomeng.data.preferences.AppPreferences
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ContentDisclaimerPreferences

/**
 * Android 入口：渲染共享 UI，并驱动平台能力（内容声明、通知深链）。
 *
 * 个人自用版已经移除应用内更新：APK 由自己签名构建，作者 Release 的安装包
 * 与本地签名不一致，无法覆盖安装。
 */
class MainActivity : ComponentActivity() {
    private var pendingChaptersRunId by mutableStateOf<String?>(null)
    private var contentDisclaimerAccepted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 首帧主题需要同步值；读取加上限时兜底，避免 DataStore 首次读盘卡住主线程。
        val initialPreferences = runCatching {
            runBlocking {
                withTimeoutOrNull(400) {
                    GlobalContext.get().get<AppPreferencesRepository>().currentPreferences()
                }
            }
        }.getOrNull() ?: AppPreferences()
        enableEdgeToEdge()
        pendingChaptersRunId = intent.getStringExtra("open_run_id")
        setContent {
            val disclaimerPreferences: ContentDisclaimerPreferences = koinInject()
            LaunchedEffect(Unit) {
                contentDisclaimerAccepted = disclaimerPreferences.isAccepted()
            }
            App(
                launchChaptersRunId = pendingChaptersRunId,
                onChaptersLaunchConsumed = { pendingChaptersRunId = null },
                initialThemeMode = initialPreferences.themeMode,
                initialThemeSeedColorArgb = initialPreferences.themeSeedColorArgb,
                initialDynamicColorEnabled = initialPreferences.dynamicColorEnabled,
                initialUiScale = initialPreferences.uiScale,
                initialBuiltInBackHandlingEnabled = initialPreferences.builtInBackHandlingEnabled,
                onBuiltInBackHandlingEnabledChange = { enabled ->
                    lifecycleScope.launch {
                        GlobalContext.get().get<AppPreferencesRepository>()
                            .setBuiltInBackHandlingEnabled(enabled)
                    }
                },
                overlay = {
                    if (!contentDisclaimerAccepted) {
                        ContentDisclaimerDialog(
                            onAccept = {
                                contentDisclaimerAccepted = true
                                lifecycleScope.launch { disclaimerPreferences.accept() }
                            },
                            onDecline = { finishAndRemoveTask() },
                        )
                    }
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingChaptersRunId = intent.getStringExtra("open_run_id")
        setIntent(intent)
    }
}

@Composable
private fun ContentDisclaimerDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    WindowUserStatementDialog(
        show = true,
        title = stringResource(R.string.content_disclaimer_title),
        appName = stringResource(R.string.app_name),
        confirmText = stringResource(R.string.content_disclaimer_accept),
        onConfirm = onAccept,
        exitText = stringResource(R.string.content_disclaimer_decline),
        onExit = onDecline,
        statement = AnnotatedString(stringResource(R.string.content_disclaimer_message)),
    )
}
