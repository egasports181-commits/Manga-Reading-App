package com.example.extension

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File

sealed class ExtensionInstallEvent {
    data class UserConfirmationRequired(
        val pkgId: String,
        val confirmationIntent: Intent
    ) : ExtensionInstallEvent()

    data class PermissionRequired(
        val pkgId: String,
        val settingsIntent: Intent,
        val message: String
    ) : ExtensionInstallEvent()

    data class Succeeded(
        val pkgId: String,
        val message: String
    ) : ExtensionInstallEvent()

    data class Failed(
        val pkgId: String,
        val reason: String
    ) : ExtensionInstallEvent()

    data class PackageChanged(
        val pkgId: String,
        val action: String
    ) : ExtensionInstallEvent()
}

/**
 * Handles OS-consented Android package installation (`PackageInstaller` session with
 * `STATUS_PENDING_USER_ACTION` system confirmation), Unknown Sources permission checks,
 * FileProvider fallback, OS package add/replace/remove monitoring, and OS uninstall prompts.
 * Never performs silent background APK installs.
 */
class ExtensionInstaller(context: Context) {

    private val appContext = context.applicationContext
    private val _events = MutableSharedFlow<ExtensionInstallEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ExtensionInstallEvent> = _events.asSharedFlow()

    @Volatile
    private var receiversRegistered = false

    private val installResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_INSTALL_COMMIT) return
            val pkgId = intent.getStringExtra(EXTRA_EXTENSION_PKG).orEmpty()
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val statusMsg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirmIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirmIntent != null) {
                        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        _events.tryEmit(ExtensionInstallEvent.UserConfirmationRequired(pkgId, confirmIntent))
                    } else {
                        _events.tryEmit(
                            ExtensionInstallEvent.Failed(pkgId, "OS installer confirmation intent was missing.")
                        )
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    _events.tryEmit(
                        ExtensionInstallEvent.Succeeded(pkgId, "Extension '$pkgId' installed by Android OS.")
                    )
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    _events.tryEmit(
                        ExtensionInstallEvent.Failed(pkgId, "Installation cancelled by user.")
                    )
                }
                else -> {
                    val reason = statusMsg.ifBlank { "PackageInstaller failed with status code $status" }
                    _events.tryEmit(ExtensionInstallEvent.Failed(pkgId, reason))
                }
            }
        }
    }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val action = intent.action ?: return
            val pkg = intent.data?.schemeSpecificPart.orEmpty()
            if (pkg.isNotBlank()) {
                _events.tryEmit(ExtensionInstallEvent.PackageChanged(pkg, action))
            }
        }
    }

    fun registerReceivers() {
        if (receiversRegistered) return
        synchronized(this) {
            if (receiversRegistered) return
            runCatching {
                ContextCompat.registerReceiver(
                    appContext,
                    installResultReceiver,
                    IntentFilter(ACTION_INSTALL_COMMIT),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                val pkgFilter = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED)
                    addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addDataScheme("package")
                }
                ContextCompat.registerReceiver(
                    appContext,
                    packageChangeReceiver,
                    pkgFilter,
                    ContextCompat.RECEIVER_EXPORTED
                )
                receiversRegistered = true
            }
        }
    }

    fun unregisterReceivers() {
        if (!receiversRegistered) return
        synchronized(this) {
            if (!receiversRegistered) return
            runCatching { appContext.unregisterReceiver(installResultReceiver) }
            runCatching { appContext.unregisterReceiver(packageChangeReceiver) }
            receiversRegistered = false
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { appContext.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else {
            true
        }
    }

    fun buildManageUnknownSourcesIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Initiates a user-consented Android PackageInstaller session for a verified, trusted APK.
     * Android always shows its system installer confirmation dialog before committing.
     */
    suspend fun requestInstallApk(
        pkgId: String,
        apkFile: File,
        isTrusted: Boolean,
        audit: ApkInspectionResult?
    ): ExtensionInstallEvent = withContext(Dispatchers.IO) {
        registerReceivers()

        if (!apkFile.exists() || apkFile.length() == 0L) {
            val event = ExtensionInstallEvent.Failed(pkgId, "Verified APK file is missing on disk.")
            _events.tryEmit(event)
            return@withContext event
        }
        if (audit != null && (!audit.isValidZipArchive || !audit.hasAndroidManifestEntry || audit.isUnsignedApk || audit.isTamperedApk)) {
            val reason = audit.structuralIssues.firstOrNull()
                ?: audit.signatureVerificationDetail.ifBlank { "APK failed integrity or signature verification." }
            val event = ExtensionInstallEvent.Failed(pkgId, reason)
            _events.tryEmit(event)
            return@withContext event
        }
        if (!isTrusted) {
            val event = ExtensionInstallEvent.Failed(
                pkgId,
                "Extension signer certificate is not trusted yet. Approve signer trust first."
            )
            _events.tryEmit(event)
            return@withContext event
        }

        if (!canRequestPackageInstalls()) {
            val permEvent = ExtensionInstallEvent.PermissionRequired(
                pkgId = pkgId,
                settingsIntent = buildManageUnknownSourcesIntent(),
                message = "Allow 'Install unknown apps' permission for Kiyomi in Android Settings to install extensions."
            )
            _events.tryEmit(permEvent)
            return@withContext permEvent
        }

        return@withContext runCatching {
            val installer = appContext.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(pkgId)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("extension.apk", 0L, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callbackIntent = Intent(ACTION_INSTALL_COMMIT)
                    .setPackage(appContext.packageName)
                    .putExtra(EXTRA_EXTENSION_PKG, pkgId)
                val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    callbackIntent,
                    piFlags
                )
                session.commit(pendingIntent.intentSender)
            }
            val pending = ExtensionInstallEvent.Succeeded(
                pkgId = pkgId,
                message = "Launched Android system installer for '$pkgId'. Confirm installation in the OS prompt."
            )
            pending
        }.getOrElse { err ->
            // Fallback to FileProvider ACTION_VIEW if PackageInstaller session fails on OEM ROMs
            val fallback = buildFileProviderInstallIntent(apkFile)
            if (fallback != null) {
                val event = ExtensionInstallEvent.UserConfirmationRequired(pkgId, fallback)
                _events.tryEmit(event)
                event
            } else {
                val fail = ExtensionInstallEvent.Failed(
                    pkgId,
                    "Failed to launch Android installer: ${err.message ?: err.javaClass.simpleName}"
                )
                _events.tryEmit(fail)
                fail
            }
        }
    }

    fun buildFileProviderInstallIntent(apkFile: File): Intent? {
        if (!apkFile.exists()) return null
        return runCatching {
            val authority = "${appContext.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(appContext, authority, apkFile)
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }.getOrNull()
    }

    fun buildUninstallIntent(pkgId: String): Intent {
        return Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkgId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        const val ACTION_INSTALL_COMMIT = "com.example.kiyomi.ACTION_EXTENSION_INSTALL_COMMIT"
        const val EXTRA_EXTENSION_PKG = "extra_extension_pkg"
    }
}
