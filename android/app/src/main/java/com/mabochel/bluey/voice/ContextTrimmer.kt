package com.mabochel.bluey.voice

/**
 * Keeps the voice session small. Action results carry a full screen read and look_at_screen adds a screenshot;
 * left in the conversation they're re-sent on every reply, which caused rate-limit failures and most of the cost.
 *
 * Only the newest screen is kept. When a tool round brings a new screen, the previous screen-carrying round is
 * trimmed: its screenshots are deleted, and each screen-carrying output is replaced by a one-line stub right after
 * its call (so the model still knows what it did, and no call is left without an output). Rounds without a screen
 * (pointing, typing, focus, research) never move the window, so "look, point, then answer" keeps its screen.
 */
class ContextTrimmer(private val keepRounds: Int = 1) {
    sealed class Item {
        abstract val id: String

        /** A tool output; [screen] when it carries a screen read, [stub] the line that replaces it later. */
        data class Output(
            override val id: String,
            val callItemId: String?,
            val callId: String,
            val screen: Boolean,
            val stub: String,
        ) : Item()

        data class Screenshot(override val id: String) : Item()
    }

    sealed class Action {
        data class Delete(val id: String) : Action()
        data class Stub(val afterItemId: String, val callId: String, val text: String) : Action()
    }

    private val rounds = ArrayDeque<List<Item>>()

    /** Records a finished tool round; returns what to delete and which stubs to add, in order. */
    fun roundFinished(items: List<Item>): List<Action> {
        val bringsScreen = items.any { it is Item.Screenshot || (it is Item.Output && it.screen) }
        if (!bringsScreen) return emptyList()
        rounds.addLast(items.toList())
        val actions = mutableListOf<Action>()
        while (rounds.size > keepRounds) {
            for (item in rounds.removeFirst()) when (item) {
                is Item.Screenshot -> actions += Action.Delete(item.id)
                // Without its call's id the stub can't go in the right place, so the output stays.
                is Item.Output -> if (item.screen && item.callItemId != null) {
                    actions += Action.Delete(item.id)
                    actions += Action.Stub(item.callItemId, item.callId, item.stub)
                }
            }
        }
        return actions
    }

    /** A new session has none of the old items. */
    fun reset() = rounds.clear()
}
