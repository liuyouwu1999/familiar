plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    // ktlint（审计 E5）：仅 app 模块应用，见 app/build.gradle.kts
    alias(libs.plugins.ktlint) apply false
}
