import { useQuery } from '@tanstack/react-query';
import { Navigate, useParams } from 'react-router-dom';
import { ApiError, fetchSharedSelection } from '@/lib/api';
import { wizardLink } from '@/presets/selectionLink';

/**
 * Opens a short link: `/s/{token}` (§8).
 *
 * It resolves the token and then redirects into the wizard's own URL form, rather than loading the
 * selection some other way. That redirect is the point — one encoding, so a shared token and a
 * shared URL arrive at exactly the same place and re-validate through exactly the same path. Two
 * ways in would mean two things to keep in step when the envelope's schema version bumps.
 */
export function SharedLink() {
  const { token = '' } = useParams();
  const { data, isPending, isError, error } = useQuery({
    queryKey: ['share', token],
    queryFn: () => fetchSharedSelection(token),
    retry: false,
  });

  if (isPending) return <p className="text-sm text-muted-foreground">Opening the link…</p>;

  if (isError || !data?.selection) {
    return (
      <div className="flex w-full max-w-5xl flex-col gap-2">
        <p role="alert" className="text-sm text-destructive">
          {error instanceof ApiError
            ? (error.problem.detail ?? error.message)
            : 'That link cannot be opened.'}
          {error instanceof ApiError && error.problem.hint && (
            <span className="block text-muted-foreground">{error.problem.hint}</span>
          )}
        </p>
      </div>
    );
  }

  // `replace`, so Back goes where the user came from rather than back through the redirect.
  return <Navigate to={wizardLink(data.selection)} replace />;
}
