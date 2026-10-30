package io.github.finbridge.runtime;

/** Signals a bounded plugin lifecycle callback failure or a saturated callback executor. */
public final class PluginLifecycleCallbackException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final boolean timedOut;
    private final String diagnosticCode;

    PluginLifecycleCallbackException(String message, Throwable cause, boolean timedOut, String diagnosticCode) {
        super(message, cause);
        this.timedOut = timedOut;
        this.diagnosticCode = diagnosticCode;
    }

    /** Returns whether the callback exceeded its configured deadline. */
    public boolean timedOut() {
        return timedOut;
    }

    /** Returns the stable lifecycle diagnostic for this callback failure. */
    public String diagnosticCode() {
        return diagnosticCode;
    }
}
