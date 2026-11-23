package `in`.diganta.aurabutler.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.app.SearchManager
import android.util.Log

object SongFinder {
    fun launch(context: Context): String {
        val appContext = context.applicationContext
        val intents = listOf(
            Intent(ACTION_GOOGLE_MUSIC_SEARCH)
                .setPackage("com.google.android.googlequicksearchbox"),
            Intent(Intent.ACTION_WEB_SEARCH)
                .putExtra(SearchManager.QUERY, "what song is playing")
                .setPackage("com.google.android.googlequicksearchbox"),
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/search?q=what+song+is+playing")
            )
        )

        intents.forEach { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(appContext.packageManager) != null) {
                return try {
                    appContext.startActivity(intent)
                    "Opened song identification. Let the phone listen to the music, then ask AURA to play the identified song."
                } catch (error: ActivityNotFoundException) {
                    Log.w(TAG, "Song search activity disappeared", error)
                    ""
                }
            }
        }
        return "No compatible song-identification activity is installed on this phone."
    }

    private const val ACTION_GOOGLE_MUSIC_SEARCH =
        "com.google.android.googlequicksearchbox.MUSIC_SEARCH"
    private const val TAG = "AuraSongFinder"
}
