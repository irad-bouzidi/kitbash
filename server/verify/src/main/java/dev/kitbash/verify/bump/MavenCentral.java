package dev.kitbash.verify.bump;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Asks Maven Central what versions an artifact has. */
public class MavenCentral implements Releases {

    /**
     * {@code maven-metadata.xml} rather than the search API.
     *
     * <p>It is served from the same CDN as the artifacts, has no rate limit worth worrying about,
     * and lists every version including the ones the search index has not caught up with. Parsed
     * with a regex, which is defensible for exactly this: the file is a flat list of
     * {@code <version>} elements, and pulling an XML parser in to read one tag would be the more
     * surprising choice.
     */
    private static final Pattern VERSION = Pattern.compile("<version>([^<]+)</version>");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public List<String> of(TrackedVersion tracked) throws IOException, InterruptedException {
        String path = tracked.group().replace('.', '/') + "/" + tracked.name() + "/maven-metadata.xml";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://repo1.maven.org/maven2/" + path))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "kitbash-dependency-freshness")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            // A coordinate that does not exist is a manifest bug, not a transient failure, and the
            // caller reports it as one rather than treating "no versions" as "already current".
            throw new IOException("No such artifact on Maven Central: " + tracked.artifact());
        }
        if (response.statusCode() != 200) {
            throw new IOException("Maven Central answered " + response.statusCode() + " for " + tracked.artifact());
        }

        List<String> versions = new ArrayList<>();
        Matcher matcher = VERSION.matcher(response.body());
        while (matcher.find()) {
            versions.add(matcher.group(1));
        }
        return List.copyOf(versions);
    }
}
