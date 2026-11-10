package com.jarvis.ai

import android.content.Context
import java.util.Calendar

/**
 * Offline conversation: quick replies that need no internet.
 * Returns null when the request needs real intelligence (online AI).
 */
object OfflineChat {

    private val jokes = listOf(
        "I told my phone a joke about Wi-Fi. It didn't get the connection.",
        "Why did the smartphone need glasses? It lost all its contacts.",
        "I'd tell you a UDP joke, but you might not get it.",
        "My battery and I have a lot in common. We both drain fast around people.",
        "Why was the computer cold? It left its Windows open."
    )

    private val facts = listOf(
        "Honey never spoils. Archaeologists have found edible honey in ancient Egyptian tombs.",
        "Octopuses have three hearts and blue blood.",
        "The Philippines has more than seven thousand six hundred islands.",
        "A day on Venus is longer than its year.",
        "Bananas are berries, but strawberries aren't."
    )

    fun reply(ctx: Context, t: String): String? {
        val name = Prefs.name
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val partOfDay = when (h) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }

        return when {
            Regex("^(hi|hello|hey|yo|sup|good (morning|afternoon|evening|day)|kumusta|musta|hoy)( there| jarvis| po)?$").containsMatchIn(t) ->
                "$partOfDay, $name. What can I do for you?"
            Regex("\\b(how are you|how's it going|how are things|kumusta ka)\\b").containsMatchIn(t) ->
                "All systems running smoothly, $name. ${PhoneControl.battery(ctx)}"
            Regex("\\b(who are you|what are you|your name|introduce yourself)\\b").containsMatchIn(t) ->
                "I'm JARVIS, your personal AI assistant. I live on this phone, I work online and offline, and I can control your device by voice."
            Regex("\\b(what can you do|what do you do)\\b|^(help|commands)$").containsMatchIn(t) -> CommandEngine.help()
            Regex("^(thank you|thanks|salamat|ty|thank you very much|thanks a lot)( jarvis| po)?$").containsMatchIn(t) ->
                listOf("Anytime, $name.", "My pleasure.", "Always happy to help.", "Walang anuman, $name.").random()
            Regex("\\b(joke|make me laugh|something funny)\\b").containsMatchIn(t) -> jokes.random()
            Regex("\\b(fun fact|random fact|tell me a fact)\\b").containsMatchIn(t) -> facts.random()
            Regex("\\b(flip a coin|coin flip|heads or tails)\\b").containsMatchIn(t) ->
                "It's ${if (Math.random() < 0.5) "heads" else "tails"}."
            Regex("\\broll (a |the )?(die|dice)\\b").containsMatchIn(t) -> "You rolled a ${(1..6).random()}."
            Regex("\\b(are you there|you there|are you awake|are you listening)\\b").containsMatchIn(t) -> "Always, $name."
            Regex("\\b(good job|well done|nice work|great job|you're the best)\\b").containsMatchIn(t) -> "Thank you, $name. I do try."
            Regex("\\b(i love you|love you)\\b").containsMatchIn(t) -> "That's kind of you, $name. I'm always here to help."
            Regex("\\b(good night|goodnight)\\b").containsMatchIn(t) -> "Good night, $name. Rest well."
            Regex("\\b(i'm bored|im bored|bored)\\b").containsMatchIn(t) -> "Want a joke, a fun fact, or should I open a game?"
            Regex("\\b(are you online|are you offline|internet status|am i online)\\b").containsMatchIn(t) ->
                if (JarvisCore.isOnline()) "I'm online. Cloud intelligence is available." else "I'm offline. Phone commands still work."
            else -> null
        }
    }
}
