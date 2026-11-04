package com.mabochel.bluey.voice

/**
 * Keeps the voice session small. Every action result carries a full screen read and look_at_screen adds a
 * screenshot; left in the conversation they're re-sent on every reply, which caused rate-limit failures and most of
 * the cost. After each tool round this says which older items to delete: every screenshot, and every big output
 * together with the function call it answers (so no call is left without its output). The newest round stays.
 */
class ContextTrimmer(private val keepRounds: Int = 1, private val bigOutput: Int = 1500) {
    sealed class Item {
        abstract val id: String
        data class Output(override val id: String, val size: Int, val callItemId: String?) : Item()
        data class Screenshot(override val id: String) : Item()
    }

    private val rounds = ArrayDeque<List<Item>>()

    /** Records a finished tool round; returns the ids of items to delete now. */
    fun roundFinished(items: List<Item>): List<String> {
        rounds.addLast(items)
        val delete = mutableListOf<String>()
        while (rounds.size > keepRounds) {
            for (item in rounds.removeFirst()) when (item) {
                is Item.Screenshot -> delete += item.id
                is Item.Output -> if (item.size > bigOutput) {
                    delete += item.id
                    item.callItemId?.let { delete += it }
                }
            }
        }
        return delete
    }

    /** A new session has none of the old items. */
    fun reset() = rounds.clear()
}
