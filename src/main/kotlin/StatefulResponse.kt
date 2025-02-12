import dev.kord.core.behavior.interaction.response.DeferredMessageInteractionResponseBehavior
import dev.kord.core.behavior.interaction.response.edit
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.interaction.response.MessageInteractionResponse
import dev.kord.rest.builder.message.modify.InteractionResponseModifyBuilder

sealed class StatefulResponse {
    class Handle(private var inner: StatefulResponse) {
        suspend fun update(block: InteractionResponseModifyBuilder.() -> Unit) {
            inner = inner.update(block)
        }

        suspend fun delete() {
            val temp = inner
            inner = Deleted
            temp.delete()
        }

        fun consume(): Handle {
            val temp = inner
            inner = Deleted
            return Handle(temp)
        }
    }

    companion object {
        fun from(deferred: DeferredMessageInteractionResponseBehavior): Handle = Handle(Deferred(deferred))
    }

    private data class Deferred(val inner: DeferredMessageInteractionResponseBehavior) : StatefulResponse() {
        override suspend fun update(block: InteractionResponseModifyBuilder.() -> Unit): StatefulResponse {
            return Existing(inner.respond(block))
        }

        override suspend fun delete(): StatefulResponse {
            inner.delete()
            return Deleted
        }
    }

    private data class Existing(val inner: MessageInteractionResponse) : StatefulResponse() {
        override suspend fun update(block: InteractionResponseModifyBuilder.() -> Unit): StatefulResponse {
            return Existing(inner.edit(block))
        }

        override suspend fun delete(): StatefulResponse {
            inner.delete()
            return Deleted
        }
    }

    private data object Deleted : StatefulResponse() {
        override suspend fun update(block: InteractionResponseModifyBuilder.() -> Unit): StatefulResponse = this

        override suspend fun delete(): StatefulResponse = this
    }

    protected abstract suspend fun update(block: InteractionResponseModifyBuilder.() -> Unit): StatefulResponse

    protected abstract suspend fun delete(): StatefulResponse
}