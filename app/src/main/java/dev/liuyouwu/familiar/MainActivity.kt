package dev.liuyouwu.familiar

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.liuyouwu.familiar.data.repository.SettingsRepository
import dev.liuyouwu.familiar.data.repository.ThemeMode
import dev.liuyouwu.familiar.ui.navigation.AppNavigation
import dev.liuyouwu.familiar.ui.theme.FamiliarTheme
import dev.liuyouwu.familiar.viewmodel.SettingsViewModel
import dev.liuyouwu.familiar.viewmodel.UpdateViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // 界面字体：true = 系统字体（状态收集自 DataStore，改动即时重组生效）
    private var useSystemFontState by androidx.compose.runtime.mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 桌宠总开关（唯一开关）：开（且已授权悬浮窗）拉起常驻服务，关就停——
        // 进程死后 STICKY 重启也能自愈。应用内外都是这一只悬浮窗，没有前台/后台切换。
        val settingsRepo = SettingsRepository(applicationContext)
        lifecycleScope.launch {
            settingsRepo.petEnabled.collect { on ->
                if (!on) {
                    dev.liuyouwu.familiar.pet.PetOverlayService.stop(this@MainActivity)
                } else if (Settings.canDrawOverlays(this@MainActivity)) {
                    dev.liuyouwu.familiar.pet.PetOverlayService.start(this@MainActivity)
                }
            }
        }
        lifecycleScope.launch {
            settingsRepo.useSystemFont.collect { useSystemFontState = it }
        }
        setContent {
            val viewModel: SettingsViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val updateViewModel: UpdateViewModel = viewModel()

            // 启动清理残留安装包 + 按节流策略自动检查更新（发现新版弹窗，无更新静默）
            LaunchedEffect(Unit) {
                updateViewModel.cleanupOnLaunch()
                updateViewModel.maybeAutoCheck()
            }

            FamiliarAppTheme(themeMode = themeMode, useSystemFont = useSystemFontState) {
                AppNavigation()
                updateViewModel.DialogHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从「安装未知应用」授权页返回时复查权限，已授权则续装
        ViewModelProvider(this)[UpdateViewModel::class.java].onHostResumed()
    }
}

@Composable
fun FamiliarAppTheme(
    themeMode: ThemeMode,
    useSystemFont: Boolean,
    content: @Composable () -> Unit
) {
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDarkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    FamiliarTheme(useSystemFont = useSystemFont, darkTheme = darkTheme) {
        content()
    }
}
