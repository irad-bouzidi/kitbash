package dev.kitbash.api.contributed;

import java.util.List;

/**
 * A submission that breaks §6.1 or §6.2 of the threat model (§14, kitbash-47).
 *
 * <p>Carries <b>every</b> reason rather than the first. A contributor who fixes one coordinate and
 * resubmits to be told about the next has been given a guessing game, and a reviewer reading a
 * half-refused submission cannot tell how much is wrong with it.
 */
public class SubmissionRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String recipeId;
    private final transient List<String> refusals;

    public SubmissionRefusedException(String recipeId, List<String> refusals) {
        super("Recipe " + recipeId + " was not accepted: " + String.join("; ", refusals));
        this.recipeId = recipeId;
        this.refusals = List.copyOf(refusals);
    }

    public String recipeId() {
        return recipeId;
    }

    public List<String> refusals() {
        return refusals;
    }
}
