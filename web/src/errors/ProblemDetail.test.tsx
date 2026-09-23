import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ProblemDetail } from '@/errors/ProblemDetail';
import { fieldOf } from '@/errors/problem';
import { ApiError, type ProblemDetail as Problem } from '@/lib/api';

/**
 * §39: *the UI renders the envelope verbatim — no re-wording, no swallowed detail.*
 *
 * <p>One case per error code, because the requirement is that **no variant falls through
 * unnoticed**. The rendering is deliberately the same for all of them — every code carries the same
 * envelope, and what differs is the text the server sent, which is the whole point of keeping the
 * vocabulary in `core`. So these are not twelve different components; they are twelve proofs that
 * the one component says what the server said.
 *
 * <p>The option ids are invented, like every other wizard test's: `noHardcodedCatalog` refuses a
 * real one anywhere under `web/src`, and a fixture is where the first one always creeps in.
 *
 * <p>The envelopes below are shaped after `docs/errors.md`, which is copied from the factories in
 * `GenerationError`. If a hint changes there and nothing changes here, that is fine — this asserts
 * that whatever arrives is *shown*, not what it says.
 */

/** Every code the server can send, with a realistic envelope for each. */
const ENVELOPES: Array<[string, Problem]> = [
  [
    'UNKNOWN_RECIPE',
    {
      error: 'UNKNOWN_RECIPE',
      stage: 'parse',
      recipe: 'contraption-alph',
      detail: "No recipe with id 'contraption-alph' exists in this catalog.",
      hint: 'Did you mean one of: contraption-alpha?',
    },
  ],
  [
    'CAPABILITY_UNSATISFIED',
    {
      error: 'CAPABILITY_UNSATISFIED',
      stage: 'resolve',
      recipe: 'flourish-maker',
      detail: "flourish-maker requires 'wotsit' and nothing in this selection provides it.",
      hint: "Choose a value for 'contraption'.",
      field: 'contraption',
    },
  ],
  [
    'CONFLICT',
    {
      error: 'CONFLICT',
      stage: 'resolve',
      detail: 'contraption-alpha and contraption-beta cannot be selected together.',
      hint: "Change 'contraption' to pick one of them.",
      field: 'contraption',
    },
  ],
  [
    'CYCLE',
    {
      error: 'CYCLE',
      stage: 'resolve',
      detail: 'These recipes depend on each other in a cycle: a -> b -> a.',
      hint: 'Break the cycle by removing one of the requires declarations in those manifests.',
    },
  ],
  [
    'PATCH_TARGET_MISSING',
    {
      error: 'PATCH_TARGET_MISSING',
      stage: 'patch',
      recipe: 'feature-auth-jwt',
      file: 'application.yml',
      detail: 'Patch target was not produced by any selected recipe.',
      hint: "feature-auth-jwt patches 'application.yml', so a recipe that produces that file has to be selected as well.",
    },
  ],
  [
    'PATCH_COLLISION',
    {
      error: 'PATCH_COLLISION',
      stage: 'patch',
      recipe: 'feature-auth-jwt',
      file: 'application.yml',
      detail: "'spring.security' is already set in application.yml by base.",
      hint: 'Two recipes cannot own the same key: move one behind an option, or have the owning recipe expose a marker to insert at.',
    },
  ],
  [
    'INVALID_IDENTIFIER',
    {
      error: 'INVALID_IDENTIFIER',
      stage: 'parse',
      detail: "'packageName' is not valid: 'new' is a Java keyword",
      hint: "Rename the segment: 'com.newthing' works.",
      field: 'packageName',
      rule: 'dot-separated lowercase segments',
    },
  ],
  [
    'PATH_ESCAPE',
    {
      error: 'PATH_ESCAPE',
      stage: 'plan',
      recipe: 'base',
      detail: "Refusing to write '../../etc/passwd': resolves outside the project root",
      hint: 'Fix the templated path in base; it has to resolve inside the project root.',
    },
  ],
  [
    'LIMIT_EXCEEDED',
    {
      error: 'LIMIT_EXCEEDED',
      stage: 'plan',
      detail: 'This selection exceeds the file count limit of 5000 (observed 5001).',
      hint: 'Deselect an option that contributes files, or raise the cap deliberately in core.',
    },
  ],
  [
    'RENDER_FAILED',
    {
      error: 'RENDER_FAILED',
      stage: 'render',
      recipe: 'base',
      file: 'files/README.md.peb',
      detail: "Template failed to render at line 12: unknown variable 'projetName'",
      hint: 'Fix the template in base, or declare the variable it reads under variables.required.',
    },
  ],
  [
    'NOT_FOUND',
    {
      error: 'NOT_FOUND',
      detail: 'No preset with that id.',
      hint: 'List /api/v1/presets to see the ones you can open.',
      resource: 'preset',
    },
  ],
  [
    'UNEXPECTED',
    {
      error: 'UNEXPECTED',
      detail: 'Something failed inside the generator rather than in the request.',
      hint: 'Nothing about the selection needs changing. Quote reference 4f2a1c8e in a bug report.',
      reference: '4f2a1c8e',
    },
  ],
];

describe('ProblemDetail', () => {
  it.each(ENVELOPES)('renders %s verbatim: the message and, always, the hint', (_code, problem) => {
    render(<ProblemDetail error={new ApiError(problem, 400)} />);

    expect(screen.getByTestId('problem-message')).toHaveTextContent(problem.detail ?? '');
    // The half §14 cares most about. Two of the seven hand-written renderers this replaced
    // dropped it, which is how an error becomes a dead end.
    expect(screen.getByTestId('problem-hint')).toHaveTextContent(problem.hint ?? '');
  });

  it.each(ENVELOPES)('shows where %s came from, when the server said', (_code, problem) => {
    render(<ProblemDetail error={new ApiError(problem, 400)} />);

    const facts = screen.queryByTestId('problem-facts');
    const expected = [problem.recipe, problem.file, problem.stage, problem.reference].filter(
      Boolean,
    );

    if (expected.length === 0) {
      expect(facts).toBeNull();
      return;
    }
    // "generation failed" is useless; "failed while patching application.yml for feature-auth-jwt"
    // is actionable, and it is the whole reason these are on the envelope.
    for (const fact of expected) {
      expect(facts).toHaveTextContent(String(fact));
    }
  });

  it('renders nothing at all for something that is not an envelope', () => {
    // A network failure has no envelope. Inventing one would be the generic fallback §39 forbids,
    // so callers write a sentence that fits where they are instead.
    const { container } = render(<ProblemDetail error={new TypeError('fetch failed')} />);

    expect(container).toBeEmptyDOMElement();
  });

  it('never invents a message when the server sent only a title', () => {
    render(<ProblemDetail error={new ApiError({ title: 'Slow down' }, 429)} />);

    expect(screen.getByTestId('problem-message')).toHaveTextContent('Slow down');
    expect(screen.queryByTestId('problem-hint')).toBeNull();
  });

  it('names the control an error belongs on, so §9 can render it there', () => {
    // The server says which field; nothing here knows what a field means. That is the rule §9
    // exists to protect, and a badge component that guessed would break it.
    expect(fieldOf(new ApiError({ error: 'CONFLICT', field: 'contraption' }, 400))).toBe(
      'contraption',
    );
    expect(fieldOf(new ApiError({ error: 'CYCLE' }, 500))).toBeUndefined();
    expect(fieldOf(new TypeError('offline'))).toBeUndefined();
  });
});
