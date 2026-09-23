import { useQuery } from '@tanstack/react-query';
import { fetchVerification, type VerificationBadge, type VerificationDocument } from '@/lib/api';

/**
 * What the nightly matrix found, for the wizard to badge with (§9, §12).
 *
 * Fetched separately from the catalog because it changes on a different clock: the metadata
 * document is immutable per catalog digest, while a nightly lands hours after a deploy. Its entity
 * tag covers the catalog *and* the run, so the browser revalidates cheaply and can never show
 * badges from a catalog it is not rendering.
 *
 * A failure here is not a failure of the wizard. Badges are information about choices, not the
 * choices themselves, so the query does not retry hard and the page renders without them.
 */
export function useVerification() {
  return useQuery<VerificationDocument>({
    queryKey: ['verification'],
    queryFn: fetchVerification,
    staleTime: 5 * 60 * 1000,
    retry: false,
  });
}

/** One selected option, in the form the badge keys use. */
export interface SelectedValue {
  optionId: string;
  value: string;
}

/**
 * The badge for a pairing, whichever order it is asked in.
 *
 * Keys are built the same way on both sides and sorted, because a pairing has no direction — and
 * doing it in one function here is what keeps the component from knowing the format at all.
 */
export function pairKey(left: SelectedValue, right: SelectedValue): string {
  const a = `${left.optionId}=${left.value}`;
  const b = `${right.optionId}=${right.value}`;
  return a <= b ? `${a} & ${b}` : `${b} & ${a}`;
}

/**
 * Every red pairing that involves this option's current value.
 *
 * Generic over the option, deliberately: §9's rule is that nothing in React knows what an option
 * means, and a badge that hardcoded "backend × build tool" would reintroduce exactly the catalog
 * knowledge the metadata document exists to keep out.
 */
export function redPairingsFor(
  document: VerificationDocument | undefined,
  option: SelectedValue,
  others: SelectedValue[],
): VerificationBadge[] {
  if (!document?.pairs) return [];
  const byKey = new Map((document.pairs ?? []).map((badge) => [badge.key ?? '', badge]));

  return others
    .filter((other) => other.optionId !== option.optionId)
    .map((other) => byKey.get(pairKey(option, other)))
    .filter((badge): badge is VerificationBadge => badge?.status === 'failed');
}

/**
 * What the matrix says about one chosen value on its own.
 *
 * Used for the "verified on <date>" line rather than for a warning: §38 asks that a green badge
 * carry evidence, and a green with no date is a claim.
 */
export function verdictFor(
  document: VerificationDocument | undefined,
  option: SelectedValue,
): VerificationBadge | undefined {
  return (document?.choices ?? []).find(
    (badge) => badge.key === `${option.optionId}=${option.value}`,
  );
}
