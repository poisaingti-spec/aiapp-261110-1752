package android.provider
object MediaStore {
    object Downloads { @JvmField val EXTERNAL_CONTENT_URI = android.net.Uri() }
    object MediaColumns { const val _ID = "_id"; const val DISPLAY_NAME = "_display_name"; const val RELATIVE_PATH = "relative_path" }
}
