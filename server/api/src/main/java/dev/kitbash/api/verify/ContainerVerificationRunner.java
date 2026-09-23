package dev.kitbash.api.verify;

import com.fasterxml.jackson.databind.JsonNode;
import dev.kitbash.verify.Cell;
import dev.kitbash.verify.CellResult;
import dev.kitbash.verify.CellRunner;
import dev.kitbash.verify.Containers;
import dev.kitbash.verify.Repository;
import dev.kitbash.verify.RequestedCell;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The API's one line into the matrix runner (§12).
 *
 * <p>Deliberately thin. Everything about how a project is built — the images, the CPU and memory
 * caps, the read-only mount, the non-root user, the hard timeout — belongs to {@code verify} and is
 * the same here as in the nightly, because a combination somebody asked about must not be verified
 * more gently than one the catalog happened to enumerate. What this adds is a selection that came
 * from a request instead of a file, and a deadline that belongs to a caller rather than to a shard.
 *
 * <p>Nothing here runs a build on the API host. The runner shells out to {@code docker run}; the
 * process this code is in never sees the generated project's build.
 */
@Component
@Profile("persistence")
public class ContainerVerificationRunner implements VerificationRunner {

    private final Repository repository;
    private final Containers containers;

    public ContainerVerificationRunner() {
        this(Repository.locate(), Containers.standard());
    }

    public ContainerVerificationRunner(Repository repository, Containers containers) {
        this.repository = repository;
        this.containers = containers;
    }

    @Override
    public Outcome run(String runId, JsonNode envelope, Instant deadline) {
        Cell cell = RequestedCell.from(repository, runId, envelope);
        CellResult result = new CellRunner(repository, containers).run(cell, deadline);
        return new Outcome(result.passed(), read(result), result.failedStep());
    }

    /**
     * The log as text, because that is what the caller is served.
     *
     * <p>A log that cannot be read is not a failed run — the verdict is already known — so this
     * says so in the place the log would have been rather than throwing the verdict away with it.
     */
    private static String read(CellResult result) {
        try {
            return Files.readString(result.log());
        } catch (IOException unreadable) {
            return "The run finished but its log could not be read: " + unreadable.getMessage();
        }
    }
}
