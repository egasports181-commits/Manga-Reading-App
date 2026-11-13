package com.example.data.repository

import android.content.Context
import com.example.data.local.dao.ExtensionDao
import com.example.data.local.entity.ExtensionInstallHistoryEntity
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.data.local.entity.ExtensionRepoEntity
import com.example.extension.ExtensionPackageManager
import com.example.extension.ExtensionRepositoryManager
import com.example.extension.ExtensionUpdateManager
import com.example.extension.RepoBatchRefreshReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

class ExtensionRepository(
    private val extensionDao: ExtensionDao,
    private val httpClient: OkHttpClient,
    private val context: Context? = null
) {
    val repositoryManager = ExtensionRepositoryManager(extensionDao, httpClient)
    val packageManager = ExtensionPackageManager(context, extensionDao, httpClient)
    val updateManager = ExtensionUpdateManager(packageManager, extensionDao)

    val repos: Flow<List<ExtensionRepoEntity>> = extensionDao.observeRepos()
    val packages: Flow<List<ExtensionPackageEntity>> = extensionDao.observePackages()
    val installHistory: Flow<List<ExtensionInstallHistoryEntity>> = extensionDao.observeInstallHistory()
    val downloadProgress: StateFlow<Map<String, Float>> = packageManager.downloadProgress

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
        packageManager.installKomaAdapter(pkg)

    suspend fun downloadAndVerifyMihonApk(pkg: ExtensionPackageEntity): Result<ExtensionPackageEntity> =
        packageManager.downloadAndVerifyMihonApk(pkg)

    suspend fun updateAllExtensions(packages: List<ExtensionPackageEntity>): Int =
        updateManager.updateAll(packages)

    suspend fun setExtensionEnabled(pkgId: String, enabled: Boolean) {
        extensionDao.setPackageEnabled(pkgId, enabled)
    }

    suspend fun uninstallExtension(pkgId: String) {
        packageManager.uninstall(pkgId)
    }
}
