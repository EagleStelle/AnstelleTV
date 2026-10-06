package tv.anstelle.core.data.channels

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import tv.anstelle.core.common.AppLog
import javax.inject.Inject
import javax.inject.Singleton

/** The channel templates that ship with the app. */
fun interface TemplateSource {
    fun templates(): List<ChannelTemplate>
}

/** Reads every `*.json` file in `assets/templates`. Broken files are logged and skipped. */
@Singleton
class AssetTemplateSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : TemplateSource {
    override fun templates(): List<ChannelTemplate> {
        val assets = context.assets
        val files = assets.list(FOLDER).orEmpty()
            .filter { it.endsWith(".json") }
            .map { name -> name to assets.open("$FOLDER/$name").bufferedReader().use { it.readText() } }
        return ChannelTemplates.parseAll(files) { AppLog.w(TAG, "Skipped template: $it") }
    }

    private companion object {
        const val TAG = "Templates"
        const val FOLDER = "templates"
    }
}
