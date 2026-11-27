package com.arin.selffix

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * The same + attach menu for every tab: camera, photos AND videos (as many as the phone's picker allows), files of any
 * type and any size (big files are kept on the phone and read from there), an app installed on the phone (its APK,
 * with split parts), or an APK file. Everything picked arrives as file addresses in [onUris].
 */
@Composable
fun AttachButton(onUris: (List<Uri>) -> Unit, enabled: Boolean = true, onProblem: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var pickApp by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onUris(it) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { if (it.isNotEmpty()) onUris(it) }
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> onUris(listOf(u)) } }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = cameraUri
        if (saved && uri != null) onUris(listOf(uri))
        cameraUri = null
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { saved ->
        val uri = cameraUri
        if (saved && uri != null) onUris(listOf(uri))
        cameraUri = null
    }
    fun newCameraFile(ext: String): Uri? = runCatching {
        val dir = java.io.File(context.cacheDir, "exports").apply { mkdirs() }
        val file = java.io.File(dir, "capture-${System.currentTimeMillis()}.$ext")
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }.getOrNull()

    if (pickApp) {
        com.arin.chat.InstalledAppPicker(
            onPick = { app ->
                pickApp = false
                scope.launch {
                    runCatching { com.arin.data.InstalledApps.copyApks(context, app) }
                        .onSuccess { files -> if (files.isNotEmpty()) onUris(files.map { Uri.fromFile(it) }) else onProblem("Couldn't copy ${app.label}'s APK.") }
                        .onFailure { onProblem("Couldn't copy ${app.label}'s APK: ${it.message}") }
                }
            },
            onClose = { pickApp = false },
        )
    }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) { Icon(Icons.Filled.Add, contentDescription = "Attach") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Camera (photo)") }, onClick = {
                open = false
                newCameraFile("jpg")?.let { cameraUri = it; takePhoto.launch(it) } ?: onProblem("Couldn't open the camera.")
            })
            DropdownMenuItem(text = { Text("Camera (video)") }, onClick = {
                open = false
                newCameraFile("mp4")?.let { cameraUri = it; takeVideo.launch(it) } ?: onProblem("Couldn't open the camera.")
            })
            DropdownMenuItem(text = { Text("Photos & videos") }, onClick = {
                open = false
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            })
            DropdownMenuItem(text = { Text("Files (any type, any size)") }, onClick = { open = false; pickFiles.launch(arrayOf("*/*")) })
            DropdownMenuItem(text = { Text("Installed app (APK)") }, onClick = { open = false; pickApp = true })
            DropdownMenuItem(text = { Text("APK file") }, onClick = {
                open = false
                pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*"))
            })
        }
    }
}
