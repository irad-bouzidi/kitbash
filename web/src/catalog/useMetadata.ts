import { useQuery } from '@tanstack/react-query';
import { fetchMetadata, type MetadataDocument } from '@/lib/api';

/**
 * The catalog, fetched once and cached for the session.
 *
 * <p>Server data in TanStack Query and local selection state in Zustand is a deliberate split
 * (§5): one is a cache of something the server owns, the other is ephemeral and belongs to the
 * tab. Keeping them in one store means either refetching what has not changed or hand-writing
 * the invalidation that Query already does.
 *
 * The document is immutable per catalog digest and the server sends an ETag that *is* that
 * digest, so the browser's own cache handles revalidation and this never needs a refetch policy
 * of its own.
 */
export function useMetadata() {
  return useQuery<MetadataDocument>({
    queryKey: ['metadata'],
    queryFn: fetchMetadata,
    staleTime: Infinity,
    retry: 1,
  });
}
