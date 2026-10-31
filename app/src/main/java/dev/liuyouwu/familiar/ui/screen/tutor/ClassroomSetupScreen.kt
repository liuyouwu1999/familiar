package dev.liuyouwu.familiar.ui.screen.tutor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.liuyouwu.familiar.ui.theme.PaperLight
import dev.liuyouwu.familiar.ui.theme.PixelIconButton
import dev.liuyouwu.familiar.ui.theme.PixelTopBar
import dev.liuyouwu.familiar.ui.theme.WoodBorder

/**
 * 课堂私教开课表单（T11 自旧开课表单迁移，tutor_setup 同路由换 composable）：
 * 主题 + 水平 + 学习风格 + 语气 + 语言 + AI 同学，提交输出 skillParams 六键
 * （subject/level/style/tone/language/classmate，值域全字符串，classmate 存
 * "true"/"false"——与 classroom paramsSchema/SkillHost.classroomParams 同形），
 * 由 AppNavigation 直连会话服务（ChatRepository.createConversationWithSkill；原
 * creator 壳 createTutorConversation 已随 P5 T4b 删除）。
 * paramsSchema 另声明 searchBudget:int=2——表单不设该项：缺省值与 §6.2
 * 「classroom 每轮链 2」运行时默认一致，无需用户输入。
 * P5 T6：表单六字段组件化为 [ClassroomParamFields]（与 SkillManagementScreen 的
 * 现存会话参数编辑弹窗共用），取值域常量同迁该文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassroomSetupScreen(
    onCreate: (
        skillParams: Map<String, String>,
        title: String,
        onDone: (Boolean) -> Unit
    ) -> Unit,
    onBack: () -> Unit
) {
    var subject by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf(CLASSROOM_LEVEL_OPTIONS[1]) }
    var style by rememberSaveable { mutableStateOf(CLASSROOM_STYLE_OPTIONS[1]) }
    var tone by rememberSaveable { mutableStateOf(CLASSROOM_TONE_OPTIONS[0]) }
    var language by rememberSaveable { mutableStateOf("中文") }
    var classmateOn by rememberSaveable { mutableStateOf(true) }
    var creating by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            PixelTopBar(
                title = "新建学习课程",
                navigationIcon = {
                    PixelIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = onBack,
                        tint = PaperLight
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "导师引导你自己想出答案，不直接报答案：先摸底，分段讲练，小测过了才进下一节",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(16.dp))

            ClassroomParamFields(
                subject = subject,
                onSubjectChange = { subject = it },
                level = level,
                onLevelChange = { level = it },
                style = style,
                onStyleChange = { style = it },
                tone = tone,
                onToneChange = { tone = it },
                language = language,
                onLanguageChange = { language = it },
                classmateOn = classmateOn,
                onClassmateChange = { classmateOn = it }
            )

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                shape = RectangleShape,
                border = BorderStroke(1.dp, WoodBorder),
                onClick = {
                    if (creating) return@Button
                    creating = true
                    val subjectText = subject.trim()
                    // 六键直通 skillParams 列（值域全字符串，classmate 存
                    // "true"/"false"——与 classroom paramsSchema 同形；
                    // searchBudget 缺省=2 与运行时预算一致，表单不设）
                    val skillParams = mapOf(
                        "subject" to subjectText,
                        "level" to level,
                        "style" to style,
                        "tone" to tone,
                        "language" to language,
                        "classmate" to classmateOn.toString()
                    )
                    // 创建失败时复位允许重试；成功后导航离开页面自然销毁
                    onCreate(
                        skillParams,
                        "学习：$subjectText"
                    ) { ok ->
                        if (!ok) creating = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = subject.isNotBlank() && !creating
            ) {
                Text("开课")
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
