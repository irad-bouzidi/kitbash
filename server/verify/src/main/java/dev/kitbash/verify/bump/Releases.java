package dev.kitbash.verify.bump;

import java.io.IOException;
import java.util.List;

/**
 * Where the published versions of an artifact come from.
 *
 * <p>An interface with one implementation, which is usually a smell. Here it is the seam that lets
 * the bump's own tests run without a network: the interesting logic is "which of these is newer and
 * is it a release", and a test that has to reach Maven Central to exercise it is a test that fails
 * on a train.
 */
public interface Releases {

    List<String> of(TrackedVersion tracked) throws IOException, InterruptedException;
}
