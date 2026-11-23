package `in`.diganta.aurabutler.domain

import `in`.diganta.aurabutler.model.ActionKind
import `in`.diganta.aurabutler.model.ActionRisk
import `in`.diganta.aurabutler.model.ButlerAction

/**
 * Actions in this list only open an app, screen, link, map, camera, or change a
 * reversible local control. They may run immediately after an explicit user
 * command. Anything that communicates, schedules, purchases, or writes
 * personal data still requires an approval card.
 */
object DirectActionPolicy {
    private val immediateKinds = setOf(
        ActionKind.OPEN_APP,
        ActionKind.OPEN_URL,
        ActionKind.PLAY_MEDIA,
        ActionKind.OPEN_SYSTEM_SETTING,
        ActionKind.OPEN_APP_STORAGE,
        ActionKind.MAP_ROUTE,
        ActionKind.OPEN_CAMERA,
        ActionKind.IDENTIFY_SONG,
        ActionKind.SYSTEM_VOLUME,
        ActionKind.FLASHLIGHT,
        ActionKind.FOOD_SEARCH,
        ActionKind.TRAVEL_SEARCH,
        ActionKind.SHOPPING_SEARCH
    )

    fun canExecuteImmediately(action: ButlerAction): Boolean =
        action.risk == ActionRisk.LOW &&
            action.kind in immediateKinds &&
            ActionSafetyPolicy.check(action).allowed
}
