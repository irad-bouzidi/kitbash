package dev.kitbash.api.push;

import dev.kitbash.api.generate.GenerateRequest;
import dev.kitbash.api.security.Caller;
import dev.kitbash.core.pipeline.GeneratedProject;
import dev.kitbash.core.pipeline.GenerationPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/push} — the generated project, into a GitLab group (§18, §46).
 *
 * <p>§18 deferred this rather than dismissing it: <i>the zip path must be excellent first, and the
 * API surface changes when a push target exists.</i> Both halves held. The zip path is excellent,
 * and this reuses all of it — the same pipeline, the same deterministic tree, the same initial
 * commit, the same generation record.
 *
 * <p>The zip stays the default. This is a second way to take delivery of one thing, not a second
 * thing.
 */
@RestController
@RequestMapping("/api/v1")
public class PushController {

    private static final Logger log = LoggerFactory.getLogger(PushController.class);

    private final GenerationPipeline pipeline;
    private final GitLabClient gitlab;
    private final GitPusher pusher;
    private final PushRecorder history;
    private final String gitlabUrl;

    public PushController(
            GenerationPipeline pipeline,
            GitLabClient gitlab,
            GitPusher pusher,
            PushRecorder history,
            @Value("${kitbash.push.gitlab-url:https://gitlab.com}") String gitlabUrl) {
        this.pipeline = pipeline;
        this.gitlab = gitlab;
        this.pusher = pusher;
        this.history = history;
        this.gitlabUrl = gitlabUrl;
    }

    /**
     * What a caller sends: the same selection the wizard would generate, plus where to put it.
     *
     * @param token the caller's GitLab token. Sent per request rather than stored: this server
     *     creates repositories on somebody's behalf, and a credential it keeps is a credential it
     *     has to protect, rotate and explain. §46 asks for the scope to be named, and the smallest
     *     way to name it is not to hold it.
     */
    public record PushRequest(GenerateRequest selection, String group, String projectName, String token) {}

    /**
     * What comes back.
     *
     * @param commitId the commit now on the remote — the same one the zip carries, which is the
     *     claim §46 asks to be checkable
     * @param partial true when the project was created and the push did not land. §46 requires this
     *     to be reported as exactly that, with the project named, rather than rolled back silently
     *     or reported as a generic failure.
     */
    public record PushResponse(
            String projectUrl, String path, String commitId, String selectionHash, boolean partial, String detail) {}

    @PostMapping("/push")
    public ResponseEntity<PushResponse> push(@RequestBody PushRequest request) {
        // Generated first, and once. §46: push from the pipeline's output, not by re-running
        // generation for the push path — two code paths producing "the same" commit is how they
        // stop being the same.
        GeneratedProject project = pipeline.generate(request.selection().toEnvelope());

        long groupId = gitlab.groupId(gitlabUrl, request.token(), request.group());
        GitLabClient.Project created = gitlab.createProject(gitlabUrl, request.token(), groupId, request.projectName());

        try {
            GitPusher.Pushed pushed = pusher.push(project, remoteFor(created, request.token()));
            // §46: history records the pushed URL alongside the usual lock and digest. Recorded
            // after the push rather than before, so a row never claims a project that is empty.
            history.pushed(project, Caller.ownerId().orElse(null), created.webUrl(), pushed.commitId());
            log.info(
                    "Pushed selection={} to project={} commit={}",
                    project.selectionHash(),
                    created.id(),
                    pushed.commitId());
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new PushResponse(
                            created.webUrl(), created.path(), pushed.commitId(), project.selectionHash(), false, null));
        } catch (PushFailedException failed) {
            // Created, not pushed. Reported as that, with the project named: a rollback would
            // delete something the user can now see in GitLab, and a generic failure would leave
            // them with an empty project and no idea where it came from.
            log.warn(
                    "Created project={} but the push failed: {}",
                    created.id(),
                    failed.detail() == null ? failed.getMessage() : failed.detail());
            throw new PartialPushException(created.webUrl(), created.path(), failed.getMessage());
        }
    }

    /**
     * The remote, with the caller's token in it.
     *
     * <p>Built here and passed straight to git, never logged and never returned. GitLab accepts a
     * token as the password with any username; {@code oauth2} is the one it documents.
     */
    private static String remoteFor(GitLabClient.Project project, String token) {
        return project.httpUrl().replaceFirst("^https://", "https://oauth2:" + token + "@");
    }
}
