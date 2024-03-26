package com.apkupdater.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.apkupdater.data.ui.AppInstalled
import com.apkupdater.data.ui.AppUpdate
import com.apkupdater.data.ui.Link
import com.apkupdater.data.ui.PlaySource
import com.apkupdater.data.ui.getPackageNames
import com.apkupdater.data.ui.getVersion
import com.apkupdater.data.ui.getVersionCode
import com.apkupdater.prefs.Prefs
import com.apkupdater.util.play.NativeDeviceInfoProvider
import com.apkupdater.util.play.PlayHttpClient
import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.File
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.aurora.gplayapi.helpers.SearchHelper
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach


@OptIn(FlowPreview::class)
class PlayRepository(
    private val context: Context,
    private val gson: Gson,
    private val prefs: Prefs
) {
    companion object {
        const val AUTH_URL = "https://auroraoss.com/api/auth"
    }

    init {
        // TODO: Needs testing.
        PlayHttpClient.responseCode
            .filter { it == 401 }
            .debounce(60 * 5 * 1_000)
            .onEach { runCatching { refreshAuth() }.getOrNull() }
            .launchIn(CoroutineScope(Dispatchers.IO))
    }

    private fun refreshAuth(): AuthData {
        val properties = NativeDeviceInfoProvider(context).getNativeDeviceProperties()
        val playResponse = PlayHttpClient.postAuth(AUTH_URL, gson.toJson(properties).toByteArray())
        if (playResponse.isSuccessful) {
            val authData = gson.fromJson(String(playResponse.responseBytes), AuthData::class.java)
            prefs.playAuthData.put(authData)
            return authData
        }
        throw IllegalStateException("Auth not successful.")
    }

    private fun auth(): AuthData {
        val savedData = prefs.playAuthData.get()
        if (savedData.email.isEmpty()) {
            return refreshAuth()
        }
        return savedData
    }

    suspend fun search(text: String) = flow {
        if (text.contains(" ") || !text.contains(".")) {
            // Normal Search
            val authData = auth()
            val updates = SearchHelper(authData)
                .using(PlayHttpClient)
                .searchResults(text)
                .appList
                .take(5)
                .map { it.toAppUpdate(::getInstallFiles) }
            emit(Result.success(updates))
        } else {
            // Package Name Search
            val authData = auth()
            val update = AppDetailsHelper(authData)
                .using(PlayHttpClient)
                .getAppByPackageName(text)
                .toAppUpdate(::getInstallFiles)
            emit(Result.success(listOf(update)))
        }
    }.catch {
        emit(Result.failure(it))
        Log.e("PlayRepository", "Error searching for $text.", it)
    }

    suspend fun updates(apps: List<AppInstalled>) = flow {
        val authData = auth()
        val details = AppDetailsHelper(authData)
            .using(PlayHttpClient)
            .getAppByPackageName(apps.getPackageNames())
        val updates = details
            .filter { it.versionCode > apps.getVersionCode(it.packageName) }
            .map {
                it.toAppUpdate(
                    ::getInstallFiles,
                    apps.getVersion(it.packageName),
                    apps.getVersionCode(it.packageName)
                )
            }
        emit(updates)
    }.catch {
        emit(emptyList())
        Log.e("PlayRepository", "Error looking for updates.", it)
    }

    private fun getInstallFiles(app: App) = PurchaseHelper(auth())
        .using(PlayHttpClient)
        .purchase(app.packageName, app.versionCode, app.offerType)
        .filter { it.type == File.FileType.BASE || it.type == File.FileType.SPLIT }
        .map { it.url }

}

fun App.toAppUpdate(
    getInstallFiles: (App) -> List<String>,
    oldVersion: String = "",
    oldVersionCode: Long = 0L
) = AppUpdate(
    displayName,
    packageName,
    versionName,
    oldVersion,
    versionCode.toLong(),
    oldVersionCode,
    PlaySource,
    Uri.parse(iconArtwork.url),
    Link.Play { getInstallFiles(this) },
    whatsNew = changes
)
