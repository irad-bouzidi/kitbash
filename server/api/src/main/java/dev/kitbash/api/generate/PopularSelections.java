package dev.kitbash.api.generate;

import dev.kitbash.api.store.GenerationRepository;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Which selections people actually generate (§10, §27, §40).
 *
 * <p>This exists for {@code kitbash-40}, which feeds it into the nightly matrix: the combinations
 * worth verifying every night are the ones people use, and the only honest source for that is what
 * they generated. Guessing produces a matrix that tests the stacks somebody imagined.
 *
 * <h2>It used to be a metric, and §41 says it should not have been</h2>
 *
 * <p>{@code kitbash-27} counted generations in a Micrometer series tagged by selection hash,
 * bounded at two hundred series so the cardinality was a number rather than a risk. §41 does not
 * admit a bounded exception: <i>anything tagged by selection hash is not a metric, it is a log
 * line.</i> A tag's values come from user input there, and a series outlives every request that
 * created it.
 *
 * <p>Nothing was lost. The question — which stacks are popular — is answered from the
 * {@code generation} table, where a selection hash is a column rather than a dimension, and
 * {@code generation_selection_hash_idx} exists for exactly this query. The counters were this
 * process's and started at zero every deploy, which was never a sound basis for deciding what to
 * build tonight.
 */
@Component
public class PopularSelections {

    private final ObjectProvider<GenerationRepository> history;

    public PopularSelections(ObjectProvider<GenerationRepository> history) {
        this.history = history;
    }

    /**
     * The most-generated selections, most first.
     *
     * <p>Empty without a database, which is not a failure: a deployment with no persistence has no
     * history to feed the nightly, and the enumerated matrix is the whole matrix there.
     */
    public List<GenerationRepository.PopularSelection> top(int limit) {
        GenerationRepository repository = history.getIfAvailable();
        return repository == null ? List.of() : repository.mostGenerated(limit);
    }
}
