package gentoo.myrelib.util

/** Attempt all cleanup steps, retaining the first throwable and suppressing later failures. */
internal fun cleanupAll(vararg steps: () -> Unit) {
    var first: Throwable? = null
    for(step in steps) {
        try { step() }
        catch(error: Throwable) {
            if(first == null) first = error
            else if(first !== error) first.addSuppressed(error)
        }
    }
    first?.let { throw it }
}
