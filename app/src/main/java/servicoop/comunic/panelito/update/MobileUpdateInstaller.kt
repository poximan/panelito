package servicoop.comunic.panelito.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import servicoop.comunic.panelito.BuildConfig
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val UPDATE_HOST = "comunicaciones.servicoop.com.ar"
private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")

data class MobileUpdateRequest(
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val versionCode: Long,
)

suspend fun installMobileUpdate(context: Context, request: MobileUpdateRequest) {
    val apkFile = withContext(Dispatchers.IO) {
        require(SHA256_PATTERN.matches(request.sha256)) { "mobile_update_sha256_invalid" }
        require(request.sizeBytes > 0L) { "mobile_update_size_invalid" }
        require(request.versionCode > BuildConfig.VERSION_CODE.toLong()) { "mobile_update_version_not_newer" }
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.forEach { file -> check(file.delete()) { "mobile_update_cleanup_failed" } }
        val partial = File(directory, "panelito-update.apk.part")
        download(request, partial)
        verify(context, partial, request)
        val target = File(directory, "panelito-update.apk")
        check(partial.renameTo(target)) { "mobile_update_commit_failed" }
        target
    }
    val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", apkFile)
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, APK_MIME_TYPE)
        clipData = ClipData.newUri(context.contentResolver, apkFile.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

private fun download(request: MobileUpdateRequest, target: File) {
    val url = URL(request.apkUrl)
    require(url.protocol == "https" && url.host.equals(UPDATE_HOST, true) && (url.port == -1 || url.port == 443)) {
        "mobile_update_url_not_allowed"
    }
    val connection = url.openConnection() as? HttpsURLConnection
        ?: error("mobile_update_https_required")
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = false
        val status = connection.responseCode
        check(status in 200..299) { "mobile_update_download_failed:$status" }
        val declaredLength = connection.contentLengthLong
        check(declaredLength < 0L || declaredLength == request.sizeBytes) { "mobile_update_content_length_mismatch" }
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    received += count
                    check(received <= request.sizeBytes) { "mobile_update_size_exceeded" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        check(received == request.sizeBytes) { "mobile_update_size_mismatch" }
        val actualHash = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        check(MessageDigest.isEqual(actualHash.toByteArray(), request.sha256.toByteArray())) {
            "mobile_update_sha256_mismatch"
        }
    } finally {
        connection.disconnect()
    }
}

@Suppress("DEPRECATION")
private fun verify(context: Context, apkFile: File, request: MobileUpdateRequest) {
    val packageManager = context.packageManager
    val archive = packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        ?: error("mobile_update_apk_invalid")
    val installed = packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    check(archive.packageName == context.packageName) { "mobile_update_package_mismatch" }
    check(archive.longVersionCode == request.versionCode && archive.longVersionCode > installed.longVersionCode) {
        "mobile_update_version_code_mismatch"
    }
    check(signerDigests(archive).isNotEmpty() && signerDigests(archive) == signerDigests(installed)) {
        "mobile_update_signer_mismatch"
    }
}

private fun signerDigests(info: PackageInfo): Set<String> = info.signingInfo?.apkContentsSigners
    ?.map { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
    ?.toSet()
    .orEmpty()
