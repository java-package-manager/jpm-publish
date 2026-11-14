package dev.jpm.publish;

/** A user-facing failure: printed without a stack trace. */
final class PublishException extends RuntimeException {
    PublishException(String message) {
        super(message);
    }

    PublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
