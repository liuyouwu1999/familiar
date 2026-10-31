package dev.liuyouwu.familiar

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.liuyouwu.familiar.data.local.PreV16Backup
import kotlinx.coroutines.flow.MutableStateFlow

class FamiliarApp : Application() {
    companion object {
        lateinit var instance: FamiliarApp
            private set

        /** 应用前后台（单 Activity，started 计数 0=后台）：鱼窗后台守卫的判定源。
         *  冷启动默认 true——首个 onStart 必然先于任何挂载判定到来 */
        val inForeground = MutableStateFlow(true)
    }
    override fun onCreate() {
        instance = this
        super.onCreate()
        // pdfbox-android 必须初始化，否则 PDF 文本提取对带字体映射的文档静默失败
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)
        // v16 迁移前旧库自动备份（DB 懒打开，此处必然先于首次 AppDatabase.getInstance）
        PreV16Backup.runOnce(this)
        // 相伴数据单例（B1 Task 7）：上下文就位 + 异步载入 pet_bond 镜像——
        // 三挂接点/回合打点桥直调 PetBondStore，免 DI 传递（四轮 F3）
        dev.liuyouwu.familiar.pet.PetBondStore.init(this)
        // 皮肤仓库（P5 Task 3）：assets + filesDir 双源扫描就绪；未消费前仅占位（Task 4 起接入 PetHub）
        dev.liuyouwu.familiar.pet.PetSkinStore.init(this)
        // 前后台计数（P6 任务2）：不引 lifecycle-process 依赖，ActivityLifecycleCallbacks 够用
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                started++
                inForeground.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) inForeground.value = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
