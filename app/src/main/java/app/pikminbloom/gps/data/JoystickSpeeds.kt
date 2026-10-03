package app.pikminbloom.gps.data

/** Null is the configured walking speed; each activation explicitly selects one of these. */
object JoystickSpeeds {
    val choices: List<TravelMode?> = listOf(null) + TravelMode.entries.filter { it != TravelMode.WALK }
}
