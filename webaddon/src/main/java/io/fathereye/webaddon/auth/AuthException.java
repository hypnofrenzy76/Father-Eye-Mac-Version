package io.fathereye.webaddon.auth;

/**
 * Sentinel exception thrown by route handlers when an auth precondition
 * fails. The {@code WebServer} maps this to a JSON response with the
 * configured HTTP status. Distinct from generic {@link IllegalArgumentException}
 * so a 401/403/429 doesn't get classified as a bad-request.
 */
public final class AuthException extends RuntimeException {
    private final int statusCode;
    public AuthException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }
    public int statusCode() { return statusCode; }
}
