package `in`.diganta.aurabutler.domain

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import `in`.diganta.aurabutler.background.ReminderWorker
import `in`.diganta.aurabutler.device.DeviceHealthManager
import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ButlerAction
import `in`.diganta.aurabutler.model.ReminderItem
import `in`.diganta.aurabutler.media.SongFinder
import java.time.Instant
import java.util.Locale

sealed interface ExecutionResult {
    data class Success(
        val detail: String,
        val reminder: ReminderItem? = null
    ) : ExecutionResult

    data class Failure(val reason: String) : ExecutionResult
}

class ActionExecutor(private val context: Context) {
    private val appContext = context.applicationContext

    fun execute(action: ButlerAction): ExecutionResult {
        val safety = ActionSafetyPolicy.check(action)
        if (!safety.allowed) return ExecutionResult.Failure(safety.reason)

        Log.i(
            TAG,
            "Executing kind=${action.kind.name}, title=${action.title.take(80)}"
        )

        return try {
            when (action.kind) {
                ActionKind.OPEN_URL,
                ActionKind.PLAY_MEDIA,
                ActionKind.WHATSAPP_MESSAGE,
                ActionKind.FOOD_SEARCH,
                ActionKind.TRAVEL_SEARCH,
                ActionKind.SHOPPING_SEARCH -> {
                    launch(viewIntent(action.payload.getValue("url")))
                    ExecutionResult.Success("Opened ${safety.targetLabel}.")
                }

                ActionKind.OPEN_APP -> {
                    val requested = action.payload.getValue("app")
                    val resolved = resolveApp(requested)
                        ?: return ExecutionResult.Failure(
                            "I could not find an installed app named $requested."
                        )
                    launch(resolved.intent)
                    ExecutionResult.Success("Opened ${resolved.displayName}.")
                }

                ActionKind.OPEN_SYSTEM_SETTING -> {
                    launch(systemSettingIntent(action.payload.getValue("setting")))
                    ExecutionResult.Success("Opened the requested Android setting.")
                }

                ActionKind.OPEN_APP_STORAGE -> {
                    val requested = action.payload.getValue("app")
                    val resolved = resolveApp(requested)
                        ?: return ExecutionResult.Failure(
                            "I could not find an installed app named $requested."
                        )
                    val packageName = resolved.packageName
                        ?: return ExecutionResult.Failure(
                            "$requested is not installed as a local app."
                        )
                    launch(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                    )
                    ExecutionResult.Success(
                        "Opened ${resolved.displayName} app information. Choose Storage and cache, then Clear cache."
                    )
                }

                ActionKind.DIAL_PHONE -> {
                    val phone = action.payload.getValue("phone")
                    launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")))
                    ExecutionResult.Success("Opened the dialer. AURA did not place the call.")
                }

                ActionKind.MAP_ROUTE -> {
                    val destination = action.payload["address"] ?: action.payload.getValue("query")
                    launch(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("geo:0,0?q=${Uri.encode(destination)}")
                        )
                    )
                    ExecutionResult.Success("Opened maps for $destination.")
                }

                ActionKind.CALENDAR_EVENT -> {
                    val start = Instant.parse(action.payload.getValue("startIso")).toEpochMilli()
                    val end = action.payload["endIso"]
                        ?.takeIf(String::isNotBlank)
                        ?.let { Instant.parse(it).toEpochMilli() }
                        ?: start + 60 * 60 * 1000
                    val intent = Intent(Intent.ACTION_INSERT)
                        .setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, action.payload.getValue("title"))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                        .putExtra(
                            CalendarContract.Events.DESCRIPTION,
                            action.payload["body"].orEmpty()
                        )
                    launch(intent)
                    ExecutionResult.Success("Opened the calendar editor. Save remains under your control.")
                }

                ActionKind.SET_ALARM -> {
                    val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, action.payload.getValue("hour").toInt())
                        .putExtra(AlarmClock.EXTRA_MINUTES, action.payload.getValue("minute").toInt())
                        .putExtra(
                            AlarmClock.EXTRA_MESSAGE,
                            action.payload["title"] ?: "AURA alarm"
                        )
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    launch(intent)
                    ExecutionResult.Success("Opened Android's alarm screen.")
                }

                ActionKind.SET_TIMER -> {
                    val seconds = action.payload.getValue("seconds").toInt()
                    val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        .putExtra(
                            AlarmClock.EXTRA_MESSAGE,
                            action.payload["title"] ?: "AURA timer"
                        )
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    launch(intent)
                    ExecutionResult.Success("Opened Android's timer screen.")
                }

                ActionKind.DRAFT_MESSAGE -> {
                    val phone = action.payload.getValue("phone")
                    val intent = Intent(
                        Intent.ACTION_SENDTO,
                        Uri.parse("smsto:${Uri.encode(phone)}")
                    ).putExtra("sms_body", action.payload.getValue("body"))
                    launch(intent)
                    ExecutionResult.Success("Opened the message composer. AURA did not send it.")
                }

                ActionKind.COMPOSE_EMAIL -> {
                    val recipient = action.payload["recipient"].orEmpty()
                    val mailUri = Uri.parse(
                        if (recipient.isBlank()) "mailto:" else "mailto:${Uri.encode(recipient)}"
                    )
                    val intent = Intent(Intent.ACTION_SENDTO, mailUri)
                        .putExtra(Intent.EXTRA_SUBJECT, action.payload["subject"].orEmpty())
                        .putExtra(Intent.EXTRA_TEXT, action.payload["body"].orEmpty())
                    launch(intent)
                    ExecutionResult.Success("Opened the email composer. AURA did not send it.")
                }

                ActionKind.SHARE_TEXT -> {
                    val intent = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, action.payload.getValue("body"))
                    launch(Intent.createChooser(intent, "Share with"))
                    ExecutionResult.Success("Opened Android's share sheet.")
                }

                ActionKind.SYSTEM_VOLUME -> {
                    ExecutionResult.Success(changeVolume(action.payload))
                }

                ActionKind.SYSTEM_BRIGHTNESS -> {
                    val level = action.payload.getValue("level").toInt()
                    if (!Settings.System.canWrite(appContext)) {
                        launch(
                            Intent(
                                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:${appContext.packageName}")
                            )
                        )
                        ExecutionResult.Success(
                            "Opened permission settings. Allow AURA to modify system settings, then repeat the brightness command."
                        )
                    } else {
                        Settings.System.putInt(
                            appContext.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        )
                        Settings.System.putInt(
                            appContext.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            (level * 255 / 100).coerceIn(1, 255)
                        )
                        ExecutionResult.Success("Set screen brightness to $level%.")
                    }
                }

                ActionKind.FLASHLIGHT -> {
                    val enabled = action.payload.getValue("enabled").toBooleanStrict()
                    val camera = appContext.getSystemService(CameraManager::class.java)
                    val cameraId = camera.cameraIdList.firstOrNull { id ->
                        camera.getCameraCharacteristics(id)
                            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    } ?: return ExecutionResult.Failure(
                        "This phone did not report an available camera flash."
                    )
                    camera.setTorchMode(cameraId, enabled)
                    ExecutionResult.Success(
                        if (enabled) "Flashlight turned on." else "Flashlight turned off."
                    )
                }

                ActionKind.OPEN_CAMERA -> {
                    launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                    ExecutionResult.Success("Opened the camera.")
                }

                ActionKind.IDENTIFY_SONG -> {
                    val detail = SongFinder.launch(appContext)
                    if (detail.startsWith("No compatible")) {
                        ExecutionResult.Failure(detail)
                    } else {
                        ExecutionResult.Success(detail)
                    }
                }

                ActionKind.CLEAR_AURA_CACHE -> {
                    val bytes = clearAuraCache()
                    ExecutionResult.Success(
                        "Cleaned AURA temporary cache and freed ${formatBytes(bytes)}. " +
                            "Private settings, memories and API keys were preserved."
                    )
                }

                ActionKind.COOLING_ASSIST -> {
                    val detail = DeviceHealthManager(appContext).applyCoolingAssist()
                    runCatching {
                        launch(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                    }
                    ExecutionResult.Success(detail)
                }

                ActionKind.ANALYZE_SCREEN,
                ActionKind.CAPTURE_SCREEN,
                ActionKind.ANALYZE_IMAGE -> {
                    ExecutionResult.Failure(
                        "Open AURA and approve this private analysis action on screen."
                    )
                }

                ActionKind.REMINDER -> {
                    val reminder = ReminderItem(
                        title = action.payload.getValue("title"),
                        triggerAtEpochMs = Instant.parse(
                            action.payload.getValue("triggerAtIso")
                        ).toEpochMilli()
                    )
                    ReminderWorker.schedule(appContext, reminder)
                    ExecutionResult.Success("Reminder scheduled on this phone.", reminder)
                }
            }
        } catch (error: ActivityNotFoundException) {
            Log.e(TAG, "No activity found for ${action.kind}", error)
            ExecutionResult.Failure("No compatible app is installed for this action.")
        } catch (error: SecurityException) {
            Log.e(TAG, "Android blocked ${action.kind}", error)
            ExecutionResult.Failure(
                "Android blocked this app launch. Open AURA once and allow the requested permission."
            )
        } catch (error: Exception) {
            Log.e(TAG, "Action failed: ${action.kind}", error)
            ExecutionResult.Failure(
                "Android could not open this action: ${error.message.orEmpty().take(100)}"
                    .trimEnd(':', ' ')
            )
        }
    }

    private fun viewIntent(url: String): Intent {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        if (
            (host == "youtube.com" || host.endsWith(".youtube.com") ||
                host == "youtu.be") &&
            appContext.packageManager.getLaunchIntentForPackage(
                "com.google.android.youtube"
            ) != null
        ) {
            intent.setPackage("com.google.android.youtube")
        }
        return intent
    }

    private fun launch(intent: Intent) {
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        val resolved = intent.resolveActivity(appContext.packageManager)
        Log.i(
            TAG,
            "Launch attempt action=${intent.action}, package=${intent.`package`}, " +
                "component=${intent.component}, resolved=$resolved"
        )
        appContext.startActivity(intent)
    }

    private fun changeVolume(payload: Map<String, String>): String {
        val audio = appContext.getSystemService(AudioManager::class.java)
        val action = payload.getValue("action")
        val streamName = payload["stream"].orEmpty().ifBlank { "media" }
        val stream = when (streamName) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call" -> AudioManager.STREAM_VOICE_CALL
            "system" -> AudioManager.STREAM_SYSTEM
            else -> AudioManager.STREAM_MUSIC
        }
        val flags = AudioManager.FLAG_SHOW_UI
        when (action) {
            "up" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags)
            "down" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags)
            "mute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
            "unmute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
            "max" -> audio.setStreamVolume(
                stream,
                audio.getStreamMaxVolume(stream),
                flags
            )
            "min" -> audio.setStreamVolume(
                stream,
                streamMinimum(audio, stream),
                flags
            )
            "set" -> {
                val percent = payload.getValue("amount").toInt().coerceIn(0, 100)
                val minimum = streamMinimum(audio, stream)
                val maximum = audio.getStreamMaxVolume(stream)
                val index = minimum + ((maximum - minimum) * percent / 100.0).toInt()
                audio.setStreamVolume(stream, index.coerceIn(minimum, maximum), flags)
            }
        }
        val current = audio.getStreamVolume(stream)
        val minimum = streamMinimum(audio, stream)
        val maximum = audio.getStreamMaxVolume(stream)
        val percent = if (maximum > minimum) {
            ((current - minimum) * 100.0 / (maximum - minimum)).toInt().coerceIn(0, 100)
        } else {
            0
        }
        return "Set $streamName volume to approximately $percent%."
    }

    private fun streamMinimum(audio: AudioManager, stream: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audio.getStreamMinVolume(stream)
        } else {
            0
        }

    private fun clearAuraCache(): Long {
        var removedBytes = 0L
        listOfNotNull(appContext.cacheDir, appContext.externalCacheDir).forEach { root ->
            root.listFiles()?.forEach { file ->
                removedBytes += fileSize(file)
                runCatching { file.deleteRecursively() }
            }
        }
        return removedBytes
    }

    private fun fileSize(file: java.io.File): Long = when {
        file.isFile -> file.length()
        file.isDirectory -> file.listFiles()?.sumOf(::fileSize) ?: 0L
        else -> 0L
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576L -> "${"%.2f".format(bytes / 1_048_576.0)} MB"
        bytes >= 1_024L -> "${"%.1f".format(bytes / 1_024.0)} KB"
        else -> "$bytes bytes"
    }

    private fun systemSettingIntent(setting: String): Intent = when (setting) {
        "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
        "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
        "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
        "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
        "notification_access" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
        else -> Intent(Settings.ACTION_SETTINGS)
    }

    private fun resolveApp(rawName: String): ResolvedApp? {
        val normalized = normalizeAppName(rawName)

        when (normalized) {
            "settings", "phone settings", "android settings" -> {
                return ResolvedApp(Intent(Settings.ACTION_SETTINGS), "Settings")
            }
            "camera" -> {
                return ResolvedApp(
                    Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
                    "Camera"
                )
            }
            "phone", "dialer" -> {
                return ResolvedApp(Intent(Intent.ACTION_DIAL), "Phone")
            }
        }

        val known = KNOWN_APPS[normalized]
        if (known != null) {
            known.packages.firstNotNullOfOrNull { packageName ->
                appContext.packageManager.getLaunchIntentForPackage(packageName)
                    ?.let { intent -> packageName to intent }
            }?.let { (packageName, intent) ->
                Log.i(TAG, "Resolved '$rawName' to known package $packageName")
                return ResolvedApp(intent, known.displayName, packageName)
            }

            known.fallbackUrl?.let { url ->
                Log.i(TAG, "Known app '${known.displayName}' is absent; using web fallback")
                return ResolvedApp(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                    "${known.displayName} in the browser",
                    null
                )
            }
        }

        resolveCategoryIntent(normalized)?.let { return it }

        val launcherQuery = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        val selected = appContext.packageManager
            .queryIntentActivities(launcherQuery, 0)
            .mapNotNull { info ->
                val label = info.loadLabel(appContext.packageManager)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (label.isBlank()) null else AppMatch(
                    info = info,
                    displayName = label,
                    normalizedLabel = normalizeAppName(label),
                    score = appMatchScore(normalized, normalizeAppName(label))
                )
            }
            .filter { it.score < NO_MATCH_SCORE }
            .minWithOrNull(
                compareBy<AppMatch> { it.score }
                    .thenBy { it.displayName.length }
            )
            ?: return null

        val packageName = selected.info.activityInfo.packageName
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(launcherQuery).setClassName(
                packageName,
                selected.info.activityInfo.name
            )

        Log.i(
            TAG,
            "Resolved '$rawName' by launcher label '${selected.displayName}' " +
                "to $packageName"
        )
        return ResolvedApp(launchIntent, selected.displayName, packageName)
    }

    private fun resolveCategoryIntent(normalized: String): ResolvedApp? {
        val category = when (normalized) {
            "browser", "internet", "web browser" -> Intent.CATEGORY_APP_BROWSER
            "calculator", "calc" -> Intent.CATEGORY_APP_CALCULATOR
            "calendar" -> Intent.CATEGORY_APP_CALENDAR
            "contacts", "contact" -> Intent.CATEGORY_APP_CONTACTS
            "email", "mail" -> Intent.CATEGORY_APP_EMAIL
            "gallery", "photo gallery" -> Intent.CATEGORY_APP_GALLERY
            "maps", "map", "navigation" -> Intent.CATEGORY_APP_MAPS
            "messages", "messaging", "sms" -> Intent.CATEGORY_APP_MESSAGING
            "music", "music player" -> Intent.CATEGORY_APP_MUSIC
            else -> null
        } ?: return null

        val displayName = normalized.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }
        val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)
        val packageName = intent.resolveActivity(appContext.packageManager)?.packageName
        return ResolvedApp(intent, displayName, packageName)
    }

    private fun normalizeAppName(value: String): String {
        return value
            .lowercase(Locale.ROOT)
            .replace("&", " and ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .replace(Regex("""\b(?:the|please|application|app)\b"""), " ")
            .replace(Regex("""\byou\s+tube\b"""), "youtube")
            .replace(Regex("""\bgoogle\s+mail\b"""), "gmail")
            .replace(Regex("""\bgoogle\s+map\b"""), "google maps")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun appMatchScore(requested: String, candidate: String): Int = when {
        candidate == requested -> 0
        candidate.replace(" ", "") == requested.replace(" ", "") -> 1
        candidate.startsWith(requested) -> 2
        requested.startsWith(candidate) -> 3
        candidate.contains(requested) -> 4
        requested.contains(candidate) && candidate.length >= 4 -> 5
        else -> NO_MATCH_SCORE
    }

    private data class ResolvedApp(
        val intent: Intent,
        val displayName: String,
        val packageName: String? = null
    )

    private data class AppMatch(
        val info: ResolveInfo,
        val displayName: String,
        val normalizedLabel: String,
        val score: Int
    )

    private data class KnownApp(
        val displayName: String,
        val packages: List<String>,
        val fallbackUrl: String? = null
    )

    private companion object {
        const val TAG = "AuraActionExecutor"
        const val NO_MATCH_SCORE = 100

        val KNOWN_APPS = mapOf(
            "youtube" to KnownApp(
                "YouTube",
                listOf("com.google.android.youtube"),
                "https://www.youtube.com"
            ),
            "yt" to KnownApp(
                "YouTube",
                listOf("com.google.android.youtube"),
                "https://www.youtube.com"
            ),
            "youtube music" to KnownApp(
                "YouTube Music",
                listOf("com.google.android.apps.youtube.music"),
                "https://music.youtube.com"
            ),
            "yt music" to KnownApp(
                "YouTube Music",
                listOf("com.google.android.apps.youtube.music"),
                "https://music.youtube.com"
            ),
            "chrome" to KnownApp(
                "Chrome",
                listOf("com.android.chrome"),
                "https://www.google.com"
            ),
            "google" to KnownApp(
                "Google",
                listOf("com.google.android.googlequicksearchbox"),
                "https://www.google.com"
            ),
            "gmail" to KnownApp(
                "Gmail",
                listOf("com.google.android.gm"),
                "https://mail.google.com"
            ),
            "google maps" to KnownApp(
                "Google Maps",
                listOf("com.google.android.apps.maps"),
                "https://maps.google.com"
            ),
            "google photos" to KnownApp(
                "Google Photos",
                listOf("com.google.android.apps.photos"),
                "https://photos.google.com"
            ),
            "photos" to KnownApp(
                "Google Photos",
                listOf("com.google.android.apps.photos"),
                "https://photos.google.com"
            ),
            "drive" to KnownApp(
                "Google Drive",
                listOf("com.google.android.apps.docs"),
                "https://drive.google.com"
            ),
            "google drive" to KnownApp(
                "Google Drive",
                listOf("com.google.android.apps.docs"),
                "https://drive.google.com"
            ),
            "play store" to KnownApp(
                "Google Play Store",
                listOf("com.android.vending")
            ),
            "google play store" to KnownApp(
                "Google Play Store",
                listOf("com.android.vending")
            ),
            "whatsapp" to KnownApp(
                "WhatsApp",
                listOf("com.whatsapp", "com.whatsapp.w4b"),
                "https://web.whatsapp.com"
            ),
            "whatsapp business" to KnownApp(
                "WhatsApp Business",
                listOf("com.whatsapp.w4b")
            ),
            "instagram" to KnownApp(
                "Instagram",
                listOf("com.instagram.android"),
                "https://www.instagram.com"
            ),
            "facebook" to KnownApp(
                "Facebook",
                listOf("com.facebook.katana"),
                "https://www.facebook.com"
            ),
            "messenger" to KnownApp(
                "Messenger",
                listOf("com.facebook.orca"),
                "https://www.messenger.com"
            ),
            "telegram" to KnownApp(
                "Telegram",
                listOf("org.telegram.messenger", "org.thunderdog.challegram"),
                "https://web.telegram.org"
            ),
            "spotify" to KnownApp(
                "Spotify",
                listOf("com.spotify.music"),
                "https://open.spotify.com"
            ),
            "netflix" to KnownApp(
                "Netflix",
                listOf("com.netflix.mediaclient"),
                "https://www.netflix.com"
            ),
            "prime video" to KnownApp(
                "Prime Video",
                listOf("com.amazon.avod.thirdpartyclient"),
                "https://www.primevideo.com"
            ),
            "amazon prime" to KnownApp(
                "Prime Video",
                listOf("com.amazon.avod.thirdpartyclient"),
                "https://www.primevideo.com"
            ),
            "hotstar" to KnownApp(
                "JioHotstar",
                listOf("in.startv.hotstar"),
                "https://www.hotstar.com"
            ),
            "jio hotstar" to KnownApp(
                "JioHotstar",
                listOf("in.startv.hotstar"),
                "https://www.hotstar.com"
            ),
            "sony liv" to KnownApp(
                "Sony LIV",
                listOf("com.sonyliv"),
                "https://www.sonyliv.com"
            ),
            "zee5" to KnownApp(
                "ZEE5",
                listOf("com.graymatrix.did"),
                "https://www.zee5.com"
            ),
            "truecaller" to KnownApp(
                "Truecaller",
                listOf("com.truecaller")
            ),
            "phonepe" to KnownApp(
                "PhonePe",
                listOf("com.phonepe.app")
            ),
            "google pay" to KnownApp(
                "Google Pay",
                listOf("com.google.android.apps.nbu.paisa.user")
            ),
            "gpay" to KnownApp(
                "Google Pay",
                listOf("com.google.android.apps.nbu.paisa.user")
            ),
            "paytm" to KnownApp(
                "Paytm",
                listOf("net.one97.paytm")
            )
        )
    }
}
