package android.content
import java.io.File
import java.io.InputStream
class AssetManager { fun open(n: String): InputStream = throw java.io.IOException(n) }
interface Cursor : java.io.Closeable { fun moveToNext(): Boolean; fun getString(i: Int): String?; fun getLong(i: Int): Long }
class ContentResolver {
    fun query(u: android.net.Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    fun delete(u: android.net.Uri, s: String?, a: Array<String>?): Int = 0
}
object ContentUris { fun withAppendedId(u: android.net.Uri, id: Long): android.net.Uri = u }
abstract class Context {
    abstract val filesDir: File
    abstract val assets: AssetManager
    abstract val contentResolver: ContentResolver
    abstract fun getExternalFilesDir(t: String?): File?
}
