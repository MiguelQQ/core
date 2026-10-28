package com.maxrave.media3.service.callback

private val googleSize = Regex("=w\\d+-h\\d+")
private val bareSize = Regex("([wh])(?:60|120)(?=-)")
private val avatarSize = Regex("=s(?:60|88|120|176|180)(?=-|$)")

/**
 * YouTube list rows ship 60px and 120px art. Android Auto grid tiles need the
 * large URI or the host draws a blurry icon. Resource and file URIs are left
 * alone: none of these patterns match them.
 */
internal fun String.toLargeArtworkUrl(): String {
    val sized = googleSize.replace(this, "=w544-h544")
    val bare = bareSize.replace(sized, "$1544")
    val avatar = avatarSize.replace(bare, "=s544")
    val sharp = avatar.replace("sddefault", "maxresdefault")
    return if (sharp.contains("i.ytimg.com") && sharp.contains("/default.jpg")) {
        sharp.replace("/default.jpg", "/hqdefault.jpg")
    } else {
        sharp
    }
}
