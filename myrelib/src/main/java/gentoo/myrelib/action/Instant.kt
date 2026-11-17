package gentoo.myrelib.action

fun interface Instant : ActionInstance, Runnable {
    /**
     * the functional interface method
     */
    override fun run()

    /**
     * only runs once
     */
    override fun doStart() {
        run()
    }

    override fun tickOrStop(): ActionInstance.Status {
        return ActionInstance.Status.STOPPED
    }

    override fun doStop(interrupted: Boolean) {
    }
}
