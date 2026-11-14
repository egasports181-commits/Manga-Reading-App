package com.example.data.repository

import android.content.Context
import android.content.Intent
import com.example.data.local.dao.ExtensionDao
import com.example.data.local.entity.ExtensionInstallHistoryEntity
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.data.local.entity.ExtensionRepoEntity
import com.example.extension.ExtensionInstallEvent
import com.example.extension.ExtensionInstaller
import com.example.extension.ExtensionPackageManager
import com.example.extension.ExtensionRepositoryManager
import com.example.extension.ExtensionTrustManager
import com.example.extension.ExtensionUpdateManager
import com.example.extension.MihonExtensionLoader
import com.example.extension.MihonLoadedState
import com.example.extension.RepoBatchRefreshReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

class ExtensionRepository(
    private val extensionDao: ExtensionDao,
    private val httpClient: OkHttpClient,
    private val context: Context? = null,
    val trustManager: ExtensionTrustManager? = context?.let { ExtensionTrustManager(it) },
    val installer: ExtensionInstaller? = context?.let { ExtensionInstaller(it) },
    val mihonLoader: MihonExtensionLoader? = if (context != null && trustManager != null) {
        MihonExtensionLoader(context, httpClient, trustManager)
    } else null
) {
    val repositoryManager = ExtensionRepositoryManager(extensionDao, httpClient)
    val packageManager = ExtensionPackageManager(
        context = context,
        extensionDao = extensionDao,
        httpClient = httpClient,
        trustManager = trustManager,
        installer = installer,
        mihonLoader = mihonLoader
    )
    val updateManager = ExtensionUpdateManager(packageManager, extensionDao)

    val repos: Flow<List<ExtensionRepoEntity>> = extensionDao.observeRepos()
    val packages: Flow<List<ExtensionPackageEntity>> = extensionDao.observePackages()
    val installHistory: Flow<List<ExtensionInstallHistoryEntity>> = extensionDao.observeInstallHistory()
    val downloadProgress: StateFlow<Map<String, Float>> = packageManager.downloadProgress
    val mihonLoadedState: StateFlow<MihonLoadedState> = mihonLoader?.state ?: MutableStateFlow(MihonLoadedState())
    val installEvents: SharedFlow<ExtensionInstallEvent> = installer?.events ?: MutableSharedFlow()

    suspend fun seedDefaultRepositoriesIfMissing(forceRestore: Boolean = false): Int =
        repositoryManager.seedDefaultRepositoriesIfMissing(forceRestore)

    suspend fun addRepository(
        rawUrl: String,
        customName: String = "",
        expectedFingerprint: String = ""
    ): Result<ExtensionRepoEntity> =
        repositoryManager.addRepository(rawUrl, customName, expectedFingerprint)

    suspend fun refreshRepository(repo: ExtensionRepoEntity): Result<Int> =
        repositoryManager.refreshRepository(repo)

    suspend fun refreshAllEnabledRepos(onlyFailed: Boolean = false): RepoBatchRefreshReport =
        repositoryManager.refreshAllEnabled(onlyFailed)

    suspend fun setRepoEnabled(repo: ExtensionRepoEntity, enabled: Boolean) =
        repositoryManager.setRepoEnabled(repo, enabled)

    suspend fun setRepoTrusted(repo: ExtensionRepoEntity, trusted: Boolean) =
        repositoryManager.setRepoTrusted(repo, trusted)

    suspend fun removeRepository(repo: ExtensionRepoEntity) =
        repositoryManager.removeRepository(repo)

    suspend fun exportRepositoriesJson(): String =
        repositoryManager.exportRepositoriesJson()

    suspend fun importRepositoriesJson(rawJson: String): Result<Int> =
        repositoryManager.importRepositoriesJson(rawJson)

    suspend fun installExtension(pkg: ExtensionPackageEntity): Result<Unit> =
        packageManager.installKiyomiAdapter(pkg)

    suspend fun activateCompanionIpc(
        pkg: ExtensionPackageEntity,
        authority: String,
        targetPackageName: String = pkg.pkgId
    ): Result<Unit> =
        packageManager.activateCompanionIpc(pkg, authority, targetPackageName)

    suspend fun syncInstalledExtensions(): Int =
        packageManager.syncInstalledExtensions()

    suspend fun syncInstalledCompanionApps(): Int =
        packageManager.syncInstalledExtensions()

    suspend fun downloadAndVerifyMihonApk(pkg: ExtensionPackageEntity): Result<ExtensionPackageEntity> =
        packageManager.downloadAndVerifyMihonApk(pkg)

    suspend fun trustExtensionSigner(pkg: ExtensionPackageEntity): Result<ExtensionPackageEntity> =
        packageManager.trustExtensionSigner(pkg)

    suspend fun requestInstallMihonApk(pkg: ExtensionPackageEntity): Result<ExtensionInstallEvent> =
        packageManager.requestInstallMihonApk(pkg)

    fun buildOsUninstallIntent(pkgId: String): Intent? =
        packageManager.buildOsUninstallIntent(pkgId)

    suspend fun updateAllExtensions(packages: List<ExtensionPackageEntity>): Int =
        updateManager.updateAll(packages)

    suspend fun setExtensionEnabled(pkgId: String, enabled: Boolean) {
        extensionDao.setPackageEnabled(pkgId, enabled)
        syncInstalledExtensions()
    }

    suspend fun uninstallExtension(pkgId: String) {
        packageManager.uninstall(pkgId)
    }
}
