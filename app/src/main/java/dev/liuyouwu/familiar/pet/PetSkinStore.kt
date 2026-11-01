package dev.liuyouwu.familiar.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import java.io.File

/**
 * 皮肤仓库（P5 spec §4）：双源现扫现解析（assets + filesDir/pets，filesDir 同 id 覆盖 assets），
 * 位图 LruCache（键 "id/file"，上限 40，解码在锁外——synchronized 只护 map 读写）。
 * 解析零缓存契约：新放皮肤 list() 立即可见、被删皮肤 get() 立即 null、filesDir 覆盖立即生效。
 * init(appContext) 挂 FamiliarApp.onCreate（PetBondStore 同款先例）；未 init 时 get/list 返回空、
 * frameBitmap null——调用方回落链必须对 null 安全（蓝卡兜底全程闭合，禁 !!）。
 */
object PetSkinStore {
    private const val TAG = "PetSkin"
    private const val ASSETS_ROOT = "pets"
    private val ID_RE = Regex("[a-z0-9_-]+")

    @Volatile private var assetManager: android.content.res.AssetManager? = null
    @Volatile private var filesRoot: File? = null

    // 按字节计容（堆 1/8）：512px 帧 ARGB_8888 一张 1MB，按条数 40 上限时 32 帧动作播一圈
    // 即把缓存挤穿 → 每帧主线程现解码（5-15ms/张）= 实测"一卡一卡"的直接来源（2026-11-01 真机）
    private val bitmapCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = maxOf(1, value.byteCount / 1024)
    }

    fun init(appContext: Context) {
        assetManager = appContext.assets
        filesRoot = File(appContext.filesDir, ASSETS_ROOT)
    }

    /** 双源现扫：filesDir 优先（覆盖 assets），id 正则过滤杂项（AssetManager.list 文件/子目录混列） */
    fun list(): List<PetSkin> {
        val order = scan()
        return order.keys.mapNotNull { id -> get(id) }
    }

    /** 现扫解析：id 在源里 → 解析返回（拒载 → null）；不在 → null。任何时刻不缓存解析产物 */
    fun get(id: String): PetSkin? {
        if (!ID_RE.matches(id)) return null
        filesDirFor(id)?.let { return loadFromFiles(id, it) }
        val am = assetManager ?: return null
        val names = try { am.list("$ASSETS_ROOT/$id") } catch (_: Exception) { null } ?: return null
        if (names.isEmpty()) return null
        return loadFromAssets(id, am, names)
    }

    /** filesDir 皮肤目录有效性：目录存在**且含 pet.json**——残目录不算皮肤，
     *  否则残 filesDir/blue 会挡死内置蓝（回落链两路皆 null 且重启不愈，审查 P2-9） */
    private fun filesDirFor(id: String): File? =
        filesRoot?.let { root -> File(root, id).takeIf { d -> d.isDirectory && File(d, "pet.json").isFile } }

    /** 位图解码缓存：命中直返；未命中锁外解码（LruCache 自身线程安全，解码不持任何锁） */
    fun frameBitmap(skin: PetSkin, file: String): Bitmap? {
        if (!PetSkinJson.isBareFileName(file)) return null   // 纵深防御：解析层已滤，此处防直接调用
        val key = "${skin.id}/$file"
        bitmapCache.get(key)?.let { return it }
        val decoded = decode(skin.id, file) ?: return null
        bitmapCache.put(key, decoded)
        return decoded
    }

    /** 切肤预热：调用方包 IO 协程（spec §5"collect 里发切肤预热协程"）；同步实现，解码全部帧+头图 */
    fun warmUp(skin: PetSkin) {
        skin.clips.values.flatMap { it.frames }.forEach { frameBitmap(skin, it.first) }
        Kind.values().forEach { kind ->
            imageFile(skin, kind)?.let { frameBitmap(skin, it) }
        }
    }

    private fun decode(id: String, file: String): Bitmap? = try {
        val dir = filesDirFor(id)
        if (dir != null) {
            BitmapFactory.decodeFile(File(dir, file).absolutePath)
        } else {
            assetManager?.open("$ASSETS_ROOT/$id/$file")?.use { BitmapFactory.decodeStream(it) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "帧图解码失败 $id/$file：${e.message}")
        null
    }

    private fun scan(): LinkedHashMap<String, Unit> {
        val out = LinkedHashMap<String, Unit>()
        // filesDir 条目须含 pet.json 才算皮肤（与 filesDirFor 同口径，残目录不遮蔽 assets 同 id）
        filesRoot?.listFiles()
            ?.filter { it.isDirectory && ID_RE.matches(it.name) && File(it, "pet.json").isFile }
            ?.forEach { out.putIfAbsent(it.name, Unit) }
        val assetIds = try { assetManager?.list(ASSETS_ROOT) } catch (_: Exception) { null }.orEmpty()
        assetIds.filter { ID_RE.matches(it) }.forEach { out.putIfAbsent(it, Unit) }
        return out
    }

    private fun loadFromFiles(id: String, dir: File): PetSkin? {
        val json = try { File(dir, "pet.json").takeIf { it.isFile }?.readText() } catch (_: Exception) { null }
        if (json == null) { Log.w(TAG, "PetSkin[$id] filesDir 源缺 pet.json，拒载"); return null }
        return PetSkinJson.parse(id, json, { f -> File(dir, f).isFile }) { Log.w(TAG, it) }
    }

    private fun loadFromAssets(id: String, am: android.content.res.AssetManager, names: Array<String>): PetSkin? {
        val namesSet = names.toSet()
        val json = try { am.open("$ASSETS_ROOT/$id/pet.json").use { it.readBytes().decodeToString() } } catch (_: Exception) { null }
        if (json == null) { Log.w(TAG, "PetSkin[$id] assets 源缺 pet.json，拒载"); return null }
        return PetSkinJson.parse(id, json, { f -> f in namesSet }) { Log.w(TAG, it) }
    }
}
