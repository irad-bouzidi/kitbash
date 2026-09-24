package dev.kitbash.api.contributed;

import dev.kitbash.api.store.ContributedRecipe;
import dev.kitbash.api.store.ContributedRecipeRepository;
import dev.kitbash.api.store.ContributedRecipeStatus;
import dev.kitbash.catalog.SubmittedManifest;
import dev.kitbash.catalog.contributed.ContributedRecipeRules;
import dev.kitbash.core.recipe.Recipe;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Submission, review, approval and revocation (§47, ADR 0004).
 *
 * <p>The order of the checks in {@link #submit} is the design. Rules first, hash second, insert
 * third: a recipe that breaks §6.1 or §6.2 never reaches the review queue, because a queue full of
 * things that cannot be approved is a queue people stop reading carefully — and the reviewer is the
 * only control the two Critical threats have.
 *
 * <p>Requires persistence, like the rest of §10's features. Without a database there is no queue,
 * no reviewer and no revocation, and a contributed recipe with none of those is just untrusted
 * code with extra steps.
 */
@Service
@Profile("persistence")
public class ContributedRecipeService {

    private static final Logger log = LoggerFactory.getLogger(ContributedRecipeService.class);

    private final ContributedRecipeRepository recipes;
    private final ContributedRecipeRules rules;
    private final LiveCatalog live;
    private final Clock clock;

    public ContributedRecipeService(
            ContributedRecipeRepository recipes, ContributedRecipeRules rules, LiveCatalog live, Clock clock) {
        this.recipes = recipes;
        this.rules = rules;
        this.live = live;
        this.clock = clock;
    }

    /**
     * Accepts a submission, or refuses it with every reason at once.
     *
     * @param content the {@code files/} tree as path to content
     */
    public ContributedRecipe submit(String manifest, Map<String, String> content, UUID submitter) {
        Recipe recipe = SubmittedManifest.parse(manifest, "a submitted recipe.yaml");

        List<String> refusals = rules.refusalsFor(recipe);
        if (!refusals.isEmpty()) {
            throw new SubmissionRefusedException(recipe.id().value(), refusals);
        }

        ContributedRecipe row = new ContributedRecipe(
                UUID.randomUUID(),
                recipe.id().value(),
                recipe.id().namespace().orElseThrow(),
                recipe.version().toString(),
                manifest,
                toJson(content),
                contentHash(manifest, content),
                ContributedRecipeStatus.SUBMITTED,
                submitter,
                Instant.now(clock),
                null,
                null,
                null,
                null,
                null,
                null);

        recipes.insert(row);
        // §10's privacy rule holds here too: the recipe id and the submission id, never the
        // submitter's name and never the manifest's contents.
        log.info("Contributed recipe submitted: recipe={} submission={}", row.recipeId(), row.id());
        return row;
    }

    public List<ContributedRecipe> awaitingReview() {
        return recipes.awaitingReview();
    }

    /** Records that the matrix passed, which §47 requires to happen before approval. */
    public void markVerified(UUID id, UUID runId) {
        if (!recipes.markVerified(id, runId)) {
            throw new ReviewConflictException(
                    "That submission is not waiting to be verified.",
                    "It has already been verified, approved or revoked. Reload the queue.");
        }
    }

    /**
     * Approval, by a named human.
     *
     * <p>The guard is in the update's where clause rather than here, so two reviewers opening the
     * same submission cannot both approve it and an unverified one cannot be approved whatever
     * order the requests arrive in. This method's job is to turn the failed update into a sentence.
     */
    public ContributedRecipe approve(UUID id, UUID reviewer) {
        ContributedRecipe submission = recipes.findById(id).orElseThrow();
        if (submission.submittedBy().equals(reviewer)) {
            // Separation of duties, and the one rule here that is about people rather than state.
            // The threat model's §5 admits this feature has no CODEOWNERS equivalent and that any
            // approver may approve any recipe; the least it can do is stop them approving their
            // own. Without it "approval is a human action recorded against a named reviewer"
            // reduces to a submitter writing their own name in the column.
            throw new ReviewConflictException(
                    "A submission cannot be approved by the person who made it.",
                    "Ask another reviewer. Approval is what stands between a contributed recipe and "
                            + "everybody else's generated projects, so it is somebody else's signature "
                            + "by design.");
        }
        if (!recipes.approve(id, reviewer, Instant.now(clock))) {
            throw new ReviewConflictException(
                    "That submission cannot be approved.",
                    "A submission has to pass the verification matrix before a reviewer can approve "
                            + "it, and one that is already approved or revoked cannot be approved "
                            + "again. Reload the queue to see where it is.");
        }
        ContributedRecipe approved = recipes.findById(id).orElseThrow();
        // The moment it becomes generable. Immediately rather than on the next boot, which is
        // kitbash-48's "without restarting the server".
        live.refresh();
        log.info("Contributed recipe approved: recipe={} submission={}", approved.recipeId(), id);
        return approved;
    }

    /**
     * Withdraws a recipe and flags every generation that used it (§47).
     *
     * <p>Flagging comes after the revocation, not before. If the flagging fails halfway the recipe
     * is still out of the catalog, which is the half that stops the bleeding; doing it the other
     * way round could leave a revoked-looking set of flags against a recipe still being generated
     * from.
     *
     * @return how many generations were flagged
     */
    public int revoke(UUID id, UUID revoker, String reason) {
        ContributedRecipe recipe = recipes.findById(id).orElseThrow();
        Instant now = Instant.now(clock);

        if (!recipes.revoke(id, revoker, reason, now)) {
            throw new ReviewConflictException(
                    "That submission is already revoked.",
                    "Nothing changed. The generations that used it were flagged when it was first " + "revoked.");
        }

        // Out of the catalog first, then the flagging. The order is the point: withdrawing is
        // what stops new generations using it, and if the flagging fails halfway the recipe is
        // still gone. The reverse would leave it generable while its victims were being listed.
        live.refresh();

        List<UUID> affected = recipes.generationsUsing(recipe.recipeId());
        recipes.flag(affected, recipe.recipeId(), reason, now);

        // Worth a log line at this level even though the others are info: a revocation is the
        // event an operator goes looking for afterwards, and the count is the part they need.
        log.warn(
                "Contributed recipe revoked: recipe={} submission={} generationsFlagged={}",
                recipe.recipeId(),
                id,
                affected.size());
        return affected.size();
    }

    /**
     * The same sorted {@code (path, bytes)} walk {@code CatalogLoader} uses for a git recipe.
     *
     * <p>Identical on purpose. A contributed recipe's identity must not depend on which half of
     * the catalog it lives in, or the digest could not honestly be the concatenation of the two —
     * and the manifest is hashed alongside the tree because for a shipped recipe {@code
     * recipe.yaml} is a file in the directory being walked.
     */
    static String contentHash(String manifest, Map<String, String> content) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            Map<String, String> sorted = new TreeMap<>(content);
            sorted.put("recipe.yaml", manifest);
            sorted.forEach((path, body) -> {
                sha256.update(path.getBytes(StandardCharsets.UTF_8));
                sha256.update((byte) 0);
                sha256.update(body.getBytes(StandardCharsets.UTF_8));
            });
            return "sha256:" + java.util.HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static String toJson(Map<String, String> content) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new TreeMap<>(content));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("A map of strings would not serialise", e);
        }
    }
}
