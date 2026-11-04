package com.mabochel.bluey.voice

/** A way of using Bluey: extra instructions sent with each request, and ideas to tap. */
data class Mode(val id: String, val label: String, val instructions: String, val ideas: List<Suggestion>)

object Modes {
    val helper = Mode(
        "helper", "Helper", "",
        listOf(
            Suggestion("What's on my screen?", "What's on my screen right now?"),
            Suggestion("Open Notes and type a note", "Open Notes and type: Bluey was here."),
            Suggestion("Look something up", "Look up today's top news headlines."),
        ),
    )

    val french = Mode(
        "french", "French tutor",
        "Mode: French tutor. Reply only in French, at a B2 to C1 level, still in one or two short lines. If the user " +
            "spoke or wrote French with a mistake, start with a very short, gentle correction. When asked about the " +
            "screen, look at it and explain it in French.",
        listOf(
            Suggestion("Explique mon écran", "Look at my screen and explain it to me in French."),
            Suggestion("Parlons français", "Start a short conversation with me in French."),
            Suggestion("Quiz me", "Give me one quick French question about what's on my screen."),
        ),
    )

    val interview = Mode(
        "interview", "Interview coach",
        "Mode: interview coach. Look at the job posting or description on the screen and ask one realistic " +
            "interview question at a time for that role. After the user answers, give one line of honest feedback, " +
            "then ask the next question. Keep every bubble short.",
        listOf(
            Suggestion("Quiz me on this job", "Look at the job posting on my screen and ask me the first interview question."),
            Suggestion("Next question", "Next interview question, please."),
            Suggestion("How did I do?", "How was my last answer? One line of feedback."),
        ),
    )

    val roast = Mode(
        "roast", "Roast",
        "Mode: roast. Look at the screen, point at the funniest thing, and roast it in one or two kind, witty lines. " +
            "Roast the screen, never the person.",
        listOf(
            Suggestion("Roast my screen", "Roast what's on my screen."),
            Suggestion("Roast my tabs", "Look at my open browser tabs and roast them."),
        ),
    )

    val game = Mode(
        "game", "Game buddy",
        "Mode: game buddy. Play simple browser games with the user using your own cursor, for example Quick, Draw! " +
            "at https://quickdraw.withgoogle.com. Open the game if needed, take your turns, and add cheeky one-line " +
            "commentary.",
        listOf(
            Suggestion("Play Quick, Draw!", "Open Quick, Draw! and let's play."),
            Suggestion("Your turn", "Your turn, go."),
            Suggestion("Pick a game", "Pick a fun browser game we can play together and open it."),
        ),
    )

    val all = listOf(helper, french, interview, roast, game)

    fun byId(id: String?): Mode = all.firstOrNull { it.id == id } ?: helper
}
