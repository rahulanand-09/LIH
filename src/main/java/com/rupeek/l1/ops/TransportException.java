package com.rupeek.l1.ops;

/**
 * The call did not complete. The engine classifies it with the operation's idempotency:
 * not sent is always {@code TECHNICAL}; sent without a reply is {@code TECHNICAL} only if idempotent,
 * otherwise {@code UNKNOWN}.
 *
 * @param sent    true if the request may have reached the other side
 * @param timeout true if the failure was a timeout
 */
public class TransportException extends Exception {

    private final boolean sent;
    private final boolean timeout;

    public TransportException(boolean sent, boolean timeout, String message, Throwable cause) {
        super(message, cause);
        this.sent = sent;
        this.timeout = timeout;
    }

    public boolean sent() {
        return sent;
    }

    public boolean timeout() {
        return timeout;
    }
}
