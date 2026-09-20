package dev.kitbash.api.share;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.kitbash.api.generate.GenerateRequest;
import dev.kitbash.api.security.Caller;
import dev.kitbash.api.security.RateLimiter;
import dev.kitbash.api.store.ShareLink;
import dev.kitbash.api.store.ShareLinkRepository;
import dev.kitbash.core.error.GenerationError;
import dev.kitbash.core.pipeline.GenerationPipeline;
import dev.kitbash.core.selection.SelectionEnvelope;
import dev.kitbash.core.selection.SelectionMigrations;
import dev.kitbash.core.selection.SelectionValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Short links, for when the URL is too long to paste (§8, §9, §13).
 *
 * <p>The ordering §8 sets matters and is easy to invert by accident: <b>the URL is the mechanism
 * and the token is the fallback</b>, not the other way round. A link that depends on a row stops
 * working when the row expires, and the commonest sharing case is pasting a configuration into a
 * chat message somebody opens five minutes later — which the URL form serves without this endpoint
 * existing at all.
 *
 * <p>So this is deliberately small. It stores a selection, hands back a token, and gives it back.
 * There is no gallery, no discovery and no permanent link: a share link is a convenience, not a
 * published artifact.
 */
@RestController
@RequestMapping("/api/v1/share")
@Profile("persistence")
public class ShareController {

    /**
     * §10's uniform thirty days, for the same reason: one number across rows, artifacts and links
     * is a story somebody can recall — anything older than a month is gone.
     */
    static final Duration LIFETIME = Duration.ofDays(30);

    private final ShareLinkRepository links;
    private final GenerationPipeline pipeline;
    private final RateLimiter limiter;
    private final ObjectMapper json;

    public ShareController(
            ShareLinkRepository links, GenerationPipeline pipeline, RateLimiter limiter, ObjectMapper json) {
        this.links = links;
        this.pipeline = pipeline;
        this.limiter = limiter;
        this.json = json;
    }

    /**
     * Stores a selection and returns a token.
     *
     * <p>Rate limited beside {@code /generate} (§13), on its own budget: minting a token is cheap,
     * but an unbounded token factory is a spam tool, and the two costs are different enough that
     * one budget for both would either throttle generation or fail to throttle this.
     *
     * <p>The selection is parsed before it is stored. A link that resolves to something the
     * generator would refuse is a link that fails in somebody else's hands, and the person who
     * created it is the one who can fix it.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShareResponse create(@RequestBody GenerateRequest request, HttpServletRequest httpRequest) {
        limiter.require(RateLimiter.Bucket.SHARE, Caller.key(httpRequest));

        SelectionEnvelope envelope = request.toEnvelope();
        pipeline.parse(envelope);

        Instant now = Instant.now();
        ShareLink link = links.insert(new ShareLink(ShareToken.next(), serialize(envelope), now, now.plus(LIFETIME)));

        return new ShareResponse(link.token(), link.expiresAt());
    }

    /**
     * The selection behind a token.
     *
     * <p>Open to any authenticated user rather than to its creator: a link nobody else can open is
     * not a share link. What it is not is public — §13 closes every endpoint, and a token is a
     * capability within the team rather than on the internet.
     *
     * <p>The stored envelope is migrated on the way out ({@code kitbash-6}), which is exactly what
     * that framework was written early for: a link made before a schema bump still opens.
     */
    @GetMapping("/{token}")
    public SharedSelection read(@PathVariable String token) {
        if (!ShareToken.looksLikeAToken(token)) {
            // Rejected before the database is asked: a lookup by an arbitrary path parameter is a
            // query somebody else chose the shape of.
            throw notFound();
        }

        ShareLink link = links.find(token).orElseThrow(ShareController::notFound);
        if (link.expiresAt() != null && link.expiresAt().isBefore(Instant.now())) {
            // Expired rather than missing, and said so: the URL form of this link still works, and
            // somebody who knows that can recover the selection from whoever sent it.
            throw GenerationError.invalidIdentifier(
                            "token",
                            "'" + token + "'",
                            "has expired",
                            "Share links last " + LIFETIME.toDays() + " days. The URL form of a selection "
                                    + "never expires, because it carries the selection itself — ask for that "
                                    + "instead.")
                    .asException();
        }

        GenerateRequest stored = deserialize(link.selection());
        // Migrated and re-parsed on the way out, so an old link opens as the current schema.
        return new SharedSelection(token, SelectionMigrations.migrate(stored.toEnvelope()), link.expiresAt());
    }

    private static SelectionValidationException notFound() {
        return new SelectionValidationException("token", "No share link with that token.");
    }

    private String serialize(SelectionEnvelope envelope) {
        try {
            return json.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise a selection for a share link", e);
        }
    }

    private GenerateRequest deserialize(String stored) {
        try {
            return json.readValue(stored, GenerateRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A share link holds a selection that will not parse", e);
        }
    }

    /** What minting a link gives back. */
    public record ShareResponse(String token, Instant expiresAt) {}

    /** What a token resolves to. */
    public record SharedSelection(String token, SelectionEnvelope selection, Instant expiresAt) {}
}
