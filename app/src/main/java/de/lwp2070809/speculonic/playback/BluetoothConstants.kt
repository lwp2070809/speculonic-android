package de.lwp2070809.speculonic.playback

object BluetoothConstants {
    val CAR_KEYWORDS = listOf(
        "tesla", "model 3", "model y", "model s", "model x", "tesla model", "bmw", "mini cooper", "mini connected", "idrive", "mercedes", "mercedes-benz", "mbux", "audi", "mmi", "vw", "volkswagen", "car-net", "skoda", "seat media", "seat connect", "porsche", "pcm", "ford", "ford sync", "lincoln", "gm mylink", "gm infotainment", "chevrolet", "chevy", "buick", "cadillac", "gmc", "mylink", "intellilink", "chrysler", "dodge", "jeep", "ram truck", "ram uconnect", "uconnect", "toyota", "lexus", "entune", "subaru", "starlink", "honda", "acura", "handsfreelink", "nissan", "infiniti", "mazda", "mazda connect", "mazda-connect", "mitsubishi", "suzuki", "isuzu", "kia", "hyundai", "genesis", "bluelink", "renault", "peugeot", "citroen", "opel", "vauxhall", "volvo", "sensus", "polestar", "jaguar", "land rover", "range rover", "incontrol", "ferrari", "lamborghini", "mclaren", "bentley", "rolls-royce", "maserati", "alfa romeo", "fiat", "abarth", "iveco", "scania", "kenworth", "peterbilt", "freightliner", "android auto", "carplay", "mirrorlink", "baidu carlife", "hicar", "huawei hicar", "carlife", "apollo", "denso", "bosch", "harman", "continental", "pioneer", "kenwood", "jvc", "alpine", "clarion", "sony xav", "atoto", "joying", "eonon", "seicane", "roadnav", "phoenix auto", "ownice", "dasaita", "ts10", "ts18", "mekede", "navifly", "car multimedia", "car audio", "car kit", "handsfree", "hands-free", "vehicle bluetooth", "vehicle audio", "automotive", "head unit", "infotainment", "multimedia", "bt car", "auto bt", "car bt", "my car", "smart car", "uconnect phone", "phone audio", "mqb", "mqb infotainment", "cic", "nbt", "comand", "discover pro", "r-link", "easy link", "sync 2", "sync 3", "sync 4", "drive-e", "blue&me", "mirror screen", "openauto", "aaos", "android automotive", "apple carplay"
    )

    private val SHORT_KEYWORD_REGEXES by lazy {
        CAR_KEYWORDS.filter { it.length <= 4 }.map { Regex("\\b${Regex.escape(it)}\\b") }
    }
    private val LONG_KEYWORDS by lazy {
        CAR_KEYWORDS.filter { it.length > 4 }
    }

    fun matchesCarKeyword(lowerName: String): Boolean {
        if (LONG_KEYWORDS.any { lowerName.contains(it) }) return true
        return SHORT_KEYWORD_REGEXES.any { it.containsMatchIn(lowerName) }
    }
}
