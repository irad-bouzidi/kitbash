package dev.kitbash.api.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is asking — the token's subject, and the address it came from.
 *
 * <p>§18 is why this is so small: there is no user table and no profile, because there is one
 * internal team behind an identity provider that already knows all of that. {@code owner_id} is
 * the subject and nothing more.
 */
public final class Caller {

    private Caller() {}

    /**
     * The rate-limit key: the subject where there is one, the remote address otherwise.
     *
     * <p>§13 says user then IP, in that order, and the order matters. Keying on IP first would put
     * everyone behind one office NAT into a single bucket, which is a limit on the company rather
     * than on a caller.
     */
    public static String key(HttpServletRequest request) {
        return subject().map(subject -> "sub:" + subject).orElseGet(() -> "ip:" + address(request));
    }

    /** The token's subject, when the request carries one. */
    public static Optional<String> subject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof Jwt jwt && jwt.getSubject() != null) {
            return Optional.of(jwt.getSubject());
        }
        return Optional.ofNullable(authentication.getName());
    }

    /**
     * The subject as the {@code owner_id} column wants it.
     *
     * <p>§10 types that column as a uuid. A provider whose subjects are already uuids maps
     * straight through; one whose subjects are opaque strings gets a stable uuid derived from the
     * subject, so the same person is the same owner across sessions without the schema having to
     * care what shape the provider's identifiers are.
     */
    public static Optional<UUID> ownerId() {
        return subject().map(Caller::asUuid);
    }

    static UUID asUuid(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException notAUuid) {
            return UUID.nameUUIDFromBytes(subject.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /**
     * The address, trusting no header.
     *
     * <p>{@code X-Forwarded-For} is caller-supplied and trivially spoofed, so it is not read here.
     * A deployment behind a proxy should configure Spring's {@code ForwardedHeaderFilter}, which is
     * the one place that knows whether the proxy in front is trustworthy.
     */
    private static String address(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null ? "unknown" : address;
    }
}
