package de.kango99w.orbitadmin

/** One exact signal is accepted from the trusted first-party main frame. */
object MailNoticePolicy {
    private const val SIGNAL = "new_mail"
    private const val MIN_INTERVAL_MS = 15_000L
    fun accepts(scheme: String?, host: String?, port: Int, mainFrame: Boolean, signal: String?): Boolean =
        mainFrame && scheme.equals("https", true) &&
            host.equals("inbox.kango99w.de", true) &&
            (port == -1 || port == 443) && signal == SIGNAL

    fun canDeliver(stamp: Long, previousStamp: Long): Boolean =
        stamp >= 0 && (previousStamp == 0L || (stamp >= previousStamp && stamp - previousStamp >= MIN_INTERVAL_MS))
}
