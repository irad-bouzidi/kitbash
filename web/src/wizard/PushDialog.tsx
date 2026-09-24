import { ProblemDetail } from '@/errors/ProblemDetail';
import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ApiError, pushToGitLab, type GenerateRequest } from '@/lib/api';

/**
 * Push this configuration into a GitLab group (§18, §46).
 *
 * §18 deferred a push target rather than dismissing it, on the grounds that *the zip path must be
 * excellent first*. It is, and this dialog is careful not to replace it: Generate stays the
 * primary action in the bottom bar and this sits beside it, because the zip works for somebody who
 * has no GitLab, no token and no intention of creating a repository from a web form.
 *
 * <p>Three fields, and the third is the one worth explaining. The token is typed here, sent with
 * the request and forgotten — never stored in this browser and never stored on the server. A
 * service that kept it would be a service that creates repositories in your namespace whenever it
 * likes, and no amount of care with the storage changes that. The scope to give it is in
 * docs/gitlab-push.md, and the link is in the dialog because the moment somebody is minting a
 * token is the only moment they will read it. Named rather than linked: this is self-hosted
 * software and a hardcoded link to somebody else's forge is a link that rots.
 */
export function PushDialog({
  envelope,
  onClose,
}: {
  envelope: GenerateRequest;
  onClose: () => void;
}) {
  const [group, setGroup] = useState('');
  // The project's name defaults to the project's name: they are the same thing to the user, and
  // making them type it twice invites the two to disagree.
  const [projectName, setProjectName] = useState(envelope.projectName ?? '');
  const [token, setToken] = useState('');

  const push = useMutation({
    mutationFn: () => pushToGitLab({ selection: envelope, group, projectName, token }),
    onSuccess: () => {
      // Cleared on the way out rather than held for a second push. The value is a live credential
      // and the dialog has no further use for it.
      setToken('');
    },
  });

  const failure = push.error instanceof ApiError ? push.error : null;
  // A 502 from this endpoint can mean the project exists and the push did not land (§46). That is
  // not a failure to retry blindly — the second attempt hits a name that is already taken — so the
  // envelope's `projectUrl` is surfaced as a link rather than buried in the detail line.
  const strandedProject = failure?.problem.projectUrl ?? null;
  const incomplete = !group.trim() || !projectName.trim() || !token.trim();

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Push to GitLab"
      data-testid="push-dialog"
    >
      <div className="flex w-full max-w-lg flex-col gap-4 rounded-lg border bg-background p-6">
        <div>
          <h2 className="text-lg font-semibold">Push to GitLab</h2>
          <p className="text-sm text-muted-foreground">
            Creates a new project in the group you name and pushes this build into it as the initial
            commit — the same commit the zip carries.
          </p>
        </div>

        {push.data ? (
          <Pushed result={push.data} onClose={onClose} />
        ) : (
          <form
            className="flex flex-col gap-4"
            onSubmit={(event) => {
              event.preventDefault();
              push.mutate();
            }}
          >
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="push-group">Group</Label>
              <Input
                id="push-group"
                value={group}
                placeholder="acme/platform"
                onChange={(event) => setGroup(event.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                The group&rsquo;s full path, as it appears in its URL. The project is created inside
                it.
              </p>
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="push-project">Project name</Label>
              <Input
                id="push-project"
                value={projectName}
                onChange={(event) => setProjectName(event.target.value)}
              />
            </div>

            <div className="flex flex-col gap-1.5">
              <Label htmlFor="push-token">Access token</Label>
              <Input
                id="push-token"
                type="password"
                autoComplete="off"
                value={token}
                onChange={(event) => setToken(event.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                Sent with this request and not stored, here or on the server. It needs the{' '}
                <code>api</code> scope and nothing more; <code>docs/gitlab-push.md</code> says what
                is done with it.
              </p>
            </div>

            {failure && (
              <ProblemDetail error={failure} className="text-sm">
                {strandedProject && (
                  <p className="mt-1">
                    The project was created:{' '}
                    <a
                      className="underline"
                      href={strandedProject}
                      target="_blank"
                      rel="noreferrer"
                    >
                      {failure.problem.path ?? strandedProject}
                    </a>
                    . It is empty — push into it yourself, or delete it before trying again.
                  </p>
                )}
              </ProblemDetail>
            )}

            <div className="flex justify-end gap-2">
              <Button type="button" variant="outline" onClick={onClose}>
                Cancel
              </Button>
              <Button type="submit" disabled={incomplete || push.isPending}>
                {push.isPending ? 'Pushing…' : 'Create and push'}
              </Button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}

/**
 * What landed, said in the terms §46 makes checkable.
 *
 * The commit id is shown because it is the claim: the commit now on the remote is byte-for-byte
 * the one the zip would have contained, and a short sha is how somebody confirms that against the
 * project they just opened.
 */
function Pushed({
  result,
  onClose,
}: {
  result: { projectUrl?: string; path?: string; commitId?: string };
  onClose: () => void;
}) {
  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm" data-testid="push-result">
        Pushed to{' '}
        <a
          className="underline"
          href={result.projectUrl}
          target="_blank"
          rel="noreferrer"
          data-testid="push-project-link"
        >
          {result.path ?? result.projectUrl}
        </a>
        .
      </p>
      {result.commitId && (
        <p className="text-xs text-muted-foreground">
          Initial commit <code>{result.commitId.slice(0, 12)}</code> — the same commit the zip
          carries for this selection.
        </p>
      )}
      <div className="flex justify-end">
        <Button variant="outline" onClick={onClose}>
          Done
        </Button>
      </div>
    </div>
  );
}
