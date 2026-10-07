/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.signal

/**
 * The default word lists, kept in one place. Each can be replaced in configuration
 * (`streampack.moderation.profanity`, `.insults`, `.slurs`). They're short on purpose: a word on
 * its own says little, and the scoring cares about who it's aimed at. An entry ending in `*`
 * matches every word it starts; any other matches the word, or the word with an `s`.
 */
object ModerationWords {
    val PROFANITY: List<String> =
        listOf(
            "fuck*",
            "shit*",
            "bullshit",
            "crap*",
            "damn*",
            "goddamn*",
            "hell",
            "piss*",
            "bitch*",
            "bastard",
            "asshole*",
            "arsehole*",
            "dick",
            "dickhead",
            "prick",
            "cunt*",
            "twat",
            "wanker",
            "bollocks",
            "motherfuck*",
            "wtf",
            "stfu",
            "gtfo",
        )

    /** Words for people, not things: "this is garbage" is about code, so it isn't here. */
    val INSULTS: List<String> =
        listOf(
            "idiot*",
            "moron*",
            "stupid",
            "dumb",
            "dumbass*",
            "imbecile",
            "loser",
            "pathetic",
            "worthless",
            "clown",
            "jackass",
            "scum",
            "creep",
            "freak",
        )

    /**
     * Any one of these counts heavily on its own. Spelling games (`n1gger`) are folded first. Words
     * with an everyday meaning too are left out; the review sees the line either way.
     */
    val SLURS: List<String> =
        listOf(
            "nigger*",
            "nigga*",
            "faggot*",
            "fag",
            "kike",
            "spic",
            "gook",
            "wetback",
            "raghead",
            "towelhead",
            "tranny",
            "trannie*",
            "retard",
            "retarded",
        )
}
