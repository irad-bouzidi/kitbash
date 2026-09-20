import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import {
  ApiError,
  fetchPreviewFile,
  fetchPreviewTree,
  type GenerateRequest,
  type PreviewTree,
} from '@/lib/api';
import { highlight } from '@/preview/highlight';

/**
 * Look inside before downloading (§9, §26).
 *
 * The value this earns is trust: a generator you cannot look inside is one you try once. So what
 * it shows is the <b>real rendered output</b> — the Java somebody is about to be handed, not the
 * template it came from.
 *
 * A file is fetched when it is clicked, never before. The tree is paths and sizes, which is what
 * keeps the first response small enough to render instantly however large the project is.
 */
export function PreviewDialog({
  envelope,
  onClose,
}: {
  envelope: GenerateRequest;
  onClose: () => void;
}) {
  const [selected, setSelected] = useState<string | null>(null);

  const tree = useQuery({
    queryKey: ['preview', envelope],
    queryFn: () => fetchPreviewTree(envelope),
  });

  const file = useQuery({
    queryKey: ['preview', envelope, selected],
    queryFn: () => fetchPreviewFile(envelope, selected ?? ''),
    // The whole point of a lazy tree: nothing is fetched until somebody asks for it.
    enabled: selected !== null,
  });

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Preview"
      data-testid="preview-dialog"
    >
      <div className="flex h-[80vh] w-full max-w-6xl flex-col gap-4 rounded-lg border bg-background p-6">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h2 className="text-lg font-semibold">Preview</h2>
            <p className="text-sm text-muted-foreground">
              {tree.data
                ? `${tree.data.fileCount} files · ${Math.round((tree.data.totalBytes ?? 0) / 1024)} KB`
                : 'Rendering…'}
            </p>
          </div>
          <Button variant="outline" onClick={onClose}>
            Close
          </Button>
        </div>

        {tree.isError && (
          <p role="alert" className="text-sm text-destructive">
            {tree.error instanceof ApiError
              ? (tree.error.problem.detail ?? tree.error.message)
              : 'The preview could not be rendered.'}
            {tree.error instanceof ApiError && tree.error.problem.hint && (
              <span className="block text-muted-foreground">{tree.error.problem.hint}</span>
            )}
          </p>
        )}

        <div className="grid min-h-0 flex-1 grid-cols-[18rem_minmax(0,1fr)] gap-4">
          <FileTree tree={tree.data} selected={selected} onSelect={setSelected} />

          <div className="min-h-0 overflow-auto rounded-md border bg-muted/30">
            {selected === null ? (
              <p className="p-4 text-sm text-muted-foreground">
                Choose a file to see what it will actually contain.
              </p>
            ) : file.isPending ? (
              <p className="p-4 text-sm text-muted-foreground">Loading {selected}…</p>
            ) : file.isError || !file.data ? (
              <p role="alert" className="p-4 text-sm text-destructive">
                {file.error instanceof ApiError
                  ? (file.error.problem.detail ?? file.error.message)
                  : 'That file could not be read.'}
              </p>
            ) : file.data.binary ? (
              // §26: binary files are reported as binary with their size, never as text.
              <p className="p-4 text-sm text-muted-foreground">
                {selected} is binary · {file.data.bytes} bytes
              </p>
            ) : (
              <pre className="p-4 text-xs leading-relaxed" data-testid="preview-content">
                <code
                  // The content is the server's own rendered output, highlighted client-side; the
                  // highlighter escapes what it cannot classify, and falls back to plain text.
                  dangerouslySetInnerHTML={{
                    __html: highlight(file.data.content ?? '', selected),
                  }}
                />
              </pre>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function FileTree({
  tree,
  selected,
  onSelect,
}: {
  tree: PreviewTree | undefined;
  selected: string | null;
  onSelect: (path: string) => void;
}) {
  if (!tree) {
    return <p className="text-sm text-muted-foreground">Rendering the tree…</p>;
  }

  return (
    <ul className="min-h-0 overflow-auto rounded-md border p-2 text-sm" data-testid="preview-tree">
      {(tree.files ?? []).map((entry) => (
        <li key={entry.path}>
          <button
            type="button"
            onClick={() => onSelect(entry.path ?? '')}
            className={`flex w-full items-center justify-between gap-2 rounded px-2 py-1 text-left hover:bg-muted ${
              selected === entry.path ? 'bg-muted font-medium' : ''
            }`}
          >
            <span className="truncate" title={entry.path}>
              {entry.path}
            </span>
            <span className="shrink-0 text-xs text-muted-foreground">
              {entry.binary ? 'bin' : `${entry.bytes}`}
            </span>
          </button>
        </li>
      ))}
    </ul>
  );
}
