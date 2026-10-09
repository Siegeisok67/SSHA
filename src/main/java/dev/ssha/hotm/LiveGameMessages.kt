package dev.ssha.hotm

object LiveGameMessages {
    private var receiver: ((String) -> Unit)? = null
    internal fun listen(receiver: (String) -> Unit) { this.receiver = receiver }
    @JvmStatic fun receive(message: String) { receiver?.invoke(message) }
}
