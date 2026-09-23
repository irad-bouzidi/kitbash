import { type ProblemDetail as Problem } from '@/lib/api';
import { asProblem } from '@/errors/problem';

/**
 * The §14 envelope, rendered verbatim.
 *
 * §39 is explicit: *the UI renders the envelope verbatim — no re-wording, no swallowed detail, no
 * generic "something went wrong" fallback.* Before this component there were seven copies of
 * `detail ?? message`, and two of them dropped the hint — which is the half §14 cares most about,
 * since the hint is the only part that names what to do next.
 *
 * It switches on nothing. Every code renders through the same shape because every code carries the
 * same envelope; what differs between them is the *text the server sent*, which is the point of
 * putting the vocabulary in `core`. The one thing this adds is the structured facts — stage,
 * recipe, file — shown quietly beneath, because "generation failed" is useless and "failed while
 * patching application.yml for feature-auth-jwt" is actionable.
 */
export function ProblemDetail({
  error,
  className,
  children,
}: {
  error: unknown;
  className?: string;
  /**
   * One fact peculiar to this caller's situation, rendered inside the alert.
   *
   * <p>The queue-depth line is the case it exists for: it belongs to the same alert as the
   * message it qualifies, and a sibling outside the region is announced separately by a screen
   * reader and found separately by a test.
   */
  children?: React.ReactNode;
}) {
  const problem = asProblem(error);
  if (!problem) return null;

  return (
    <div role="alert" className={className ?? 'text-sm'} data-testid="problem-detail">
      <p className="text-destructive" data-testid="problem-message">
        {problem.detail ?? problem.title ?? 'The request failed.'}
      </p>

      {/* The next action. Never dropped: an error without it is a dead end. */}
      {problem.hint && (
        <p className="text-muted-foreground" data-testid="problem-hint">
          {problem.hint}
        </p>
      )}

      {children}

      <Facts problem={problem} />
    </div>
  );
}

/**
 * Where it happened, when the server said.
 *
 * Small and grey on purpose: a user acting on the hint does not need them, and somebody writing a
 * bug report needs nothing else. `reference` is the correlation id an unmapped failure carries —
 * quoting it is the entire next action for that one.
 */
function Facts({ problem }: { problem: Problem }) {
  const facts = [
    problem.recipe && `recipe ${problem.recipe}`,
    problem.file && `file ${problem.file}`,
    problem.stage && `${problem.stage} stage`,
    problem.reference && `reference ${problem.reference}`,
  ].filter((fact): fact is string => typeof fact === 'string' && fact.length > 0);

  if (facts.length === 0) return null;

  return (
    <p className="text-xs text-muted-foreground" data-testid="problem-facts">
      {facts.join(' · ')}
    </p>
  );
}
