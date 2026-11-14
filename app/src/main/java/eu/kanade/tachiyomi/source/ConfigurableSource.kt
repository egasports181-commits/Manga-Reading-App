package eu.kanade.tachiyomi.source

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

interface ConfigurableSource : Source {
    fun setupPreferenceScreen(screen: PreferenceScreen)
}

fun ConfigurableSource.getSourcePreferences(): SharedPreferences {
    val ctx = Injekt.get<Application>()
    return ctx.getSharedPreferences(sourcePreferenceFileName(id), Context.MODE_PRIVATE)
}

fun sourcePreferenceFileName(sourceId: Long): String = "source_$sourceId"
