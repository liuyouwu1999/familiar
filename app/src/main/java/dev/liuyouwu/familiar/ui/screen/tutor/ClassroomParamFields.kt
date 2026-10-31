package dev.liuyouwu.familiar.ui.screen.tutor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.liuyouwu.familiar.ui.theme.PixelChip
import dev.liuyouwu.familiar.ui.theme.pixelEdgeFade

// 选项常量自 TutorPrompts:29-35 复制本地化（classroom 参数表单自持取值域；
// TutorPrompts 主体供旧链/creator 双写，P4 才删）。P5 T6 组件化：随
// [ClassroomParamFields] 迁入共享文件，开课表单与 SkillManagement 编辑弹窗共用。
/** 学习风格可选项 */
val CLASSROOM_STYLE_OPTIONS = listOf("讲解型（通俗演示为主）", "苏格拉底问答（引导我自己想通）", "实例驱动（大量例子）", "项目实战（边做边学）")

/** 水平可选项 */
val CLASSROOM_LEVEL_OPTIONS = listOf("零基础", "入门（知道基本概念）", "进阶（有实践经验）", "高级（系统深入学习）")

/** 语气可选项 */
val CLASSROOM_TONE_OPTIONS = listOf("鼓励式", "中立严谨", "幽默轻松", "严格高效")

/** 教学语言可选项（旧表单内联常量随迁） */
val CLASSROOM_LANGUAGE_OPTIONS = listOf("中文", "英文", "中英混合")

/**
 * 课堂参数六字段表单（P5 T6 组件化，自 ClassroomSetupScreen 表单体抽出）：
 * 主题输入 + 水平/风格/语气/语言四组单选 chip 行 + AI 同学二选开关。
 * ClassroomSetupScreen（开课）与 SkillManagementScreen 的 classroom 参数编辑弹窗
 * 共用，保证两处字段/取值域不漂移；六键产出口径见 ClassroomSetupScreen KDoc
 * （subject/level/style/tone/language/classmate，值域全字符串）。
 */
@Composable
fun ClassroomParamFields(
    subject: String,
    onSubjectChange: (String) -> Unit,
    level: String,
    onLevelChange: (String) -> Unit,
    style: String,
    onStyleChange: (String) -> Unit,
    tone: String,
    onToneChange: (String) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit,
    classmateOn: Boolean,
    onClassmateChange: (Boolean) -> Unit,
) {
    OutlinedTextField(
        value = subject,
        onValueChange = onSubjectChange,
        label = { Text("想学什么？（必填）") },
        placeholder = { Text("例如：Python 基础 / 高数极限 / 日语五十音") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )

    Spacer(modifier = Modifier.height(16.dp))
    Text("当前水平", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).pixelEdgeFade(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CLASSROOM_LEVEL_OPTIONS.forEach { opt ->
            PixelChip(
                text = opt.substringBefore("（"),
                selected = opt == level,
                onClick = { onLevelChange(opt) }
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text("学习风格", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).pixelEdgeFade(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CLASSROOM_STYLE_OPTIONS.forEach { opt ->
            PixelChip(
                text = opt.substringBefore("（"),
                selected = opt == style,
                onClick = { onStyleChange(opt) }
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text("导师语气", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).pixelEdgeFade(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CLASSROOM_TONE_OPTIONS.forEach { opt ->
            PixelChip(
                text = opt,
                selected = opt == tone,
                onClick = { onToneChange(opt) }
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text("教学语言", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).pixelEdgeFade(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CLASSROOM_LANGUAGE_OPTIONS.forEach { opt ->
            PixelChip(
                text = opt,
                selected = opt == language,
                onClick = { onLanguageChange(opt) }
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text("课堂讨论", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).pixelEdgeFade(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PixelChip(
            text = "有 AI 同学（更热闹）",
            selected = classmateOn,
            onClick = { onClassmateChange(true) }
        )
        PixelChip(
            text = "只要导师（更专注）",
            selected = !classmateOn,
            onClick = { onClassmateChange(false) }
        )
    }
}
