package dev.liuyouwu.familiar

import android.app.Application
import dev.liuyouwu.familiar.data.local.PreV16Backup

class FamiliarApp : Application() {
    companion object {
        lateinit var instance: FamiliarApp
            private set
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
    }
}
