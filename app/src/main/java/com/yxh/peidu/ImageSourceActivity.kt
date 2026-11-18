package com.yxh.peidu

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class ImageSourceActivity : Activity() {
    private var photo: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            photo = savedInstanceState.getString("photo")?.let(::File)
            return
        }

        try {
            val source = if (intent.getBooleanExtra(EXTRA_CAMERA, false)) {
                val file = File.createTempFile("photo-", ".jpg", imageCache())
                photo = file
                val uri = photoUri(file)
                Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .apply { clipData = ClipData.newRawUri("photo", uri) }
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } else {
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*")
            }
            startActivityForResult(source, PICK_IMAGE)
        } catch (e: ActivityNotFoundException) {
            fail("没有可用的相机或文件选择器")
        } catch (e: Exception) {
            fail("无法打开图片来源")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("photo", photo?.absolutePath)
    }

    @Deprecated("Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_IMAGE) return
        if (resultCode != RESULT_OK) {
            photo?.delete()
            finish()
            return
        }

        val captured = photo
        if (captured != null && captured.length() > 0) {
            deliver(photoUri(captured))
            return
        }

        val selected = data?.data
        if (selected == null) {
            fail("没有取得图片")
            return
        }

        // The picker grants access to this activity. Copy before finishing it.
        Thread {
            val cached = runCatching {
                val mime = contentResolver.getType(selected)
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
                val file = File.createTempFile("picked-", ".$ext", imageCache())
                try {
                    val input = contentResolver.openInputStream(selected) ?: error("Missing image")
                    input.use { source -> file.outputStream().use { source.copyTo(it) } }
                    check(file.length() > 0)
                    file
                } catch (e: Exception) {
                    file.delete()
                    throw e
                }
            }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    cached?.delete()
                } else if (cached == null) {
                    fail("无法读取选中的图片")
                } else {
                    photo?.delete()
                    deliver(photoUri(cached))
                }
            }
        }.start()
    }

    private fun deliver(uri: Uri) {
        startActivity(Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("image/*")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .apply { clipData = ClipData.newRawUri("image", uri) }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }

    private fun imageCache(): File = File(cacheDir, "camera").apply {
        mkdirs()
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun photoUri(file: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.image-source", file)

    private fun fail(message: String) {
        photo?.delete()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_CAMERA = "camera"
        private const val PICK_IMAGE = 1
    }
}
