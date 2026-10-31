package dev.liuyouwu.familiar.pet

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 皮肤位图供给（P5 spec §5）：Compose 侧统一取图入口。
 * 回落链在 decodeFor 内闭合：get(skinId) ?: get("blue")；null（两源皆失）→ 返回 null，调用方走占位。
 */
@Composable
fun rememberSkinBitmap(skinId: String, kind: Kind): ImageBitmap? {
    var bmp by remember(skinId, kind) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(skinId, kind) {
        bmp = withContext(Dispatchers.IO) { decodeFor(skinId, kind)?.asImageBitmap() }
    }
    return bmp
}

private fun decodeFor(skinId: String, kind: Kind): android.graphics.Bitmap? {
    val skin = PetSkinStore.get(skinId) ?: PetSkinStore.get("blue") ?: return null
    val bmp = decode(skin, kind)
    // spec §6：非 blue 皮肤解码失败（解析期 exists 已过、之后文件才损坏）→ blue 同 kind 再试一次；
    // blue 本身失败（含 skinId 解析失败已落 blue）才返 null，调用方走占位
    if (bmp != null || skin.id == "blue") return bmp
    return decode(PetSkinStore.get("blue") ?: return null, kind)
}

private fun decode(skin: PetSkin, kind: Kind): android.graphics.Bitmap? {
    val file = imageFile(skin, kind) ?: return null
    return PetSkinStore.frameBitmap(skin, file)
}

/** 首页陪伴卡头像（原 PetHub.FattyFishAvatar 皮肤化，spec P2-3：null 已在链内回落 blue，仍 null 不渲染） */
@Composable
fun FattyFishAvatar(skinId: String, modifier: Modifier = Modifier) {
    val bmp = rememberSkinBitmap(skinId, Kind.AVATAR)
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
    }
}
