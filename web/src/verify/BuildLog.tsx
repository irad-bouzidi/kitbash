import { useMemo, useState } from 'react';
import { Button } from '@/components/ui/button';

/** How much of a log is on screen before the reader asks for more. */
const PAGE = 400;

/**
 * A build log, from the end.
 *
 * Two decisions, both from §38's note that a failing Gradle log is long and rendering it wholesale
 * will jank the wizard.
 *
 * It renders a **window**, not the file. Forty thousand lines is forty thousand DOM nodes, and the
 * tab stops responding while React builds them — for text nobody reads from the top.
 *
 * And the window starts at the **end**. A build log's answer is its last page: the error, the
 * failing task, the summary. Starting at the beginning would make every reader scroll past the
 * dependency resolution to reach it.
 */
export function BuildLog({ text }: { text: string }) {
  const lines = useMemo(() => text.split('\n'), [text]);
  const [shown, setShown] = useState(PAGE);
  const from = Math.max(0, lines.length - shown);
  const hidden = from;

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-2">
      {hidden > 0 && (
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <span>
            {hidden} earlier line{hidden === 1 ? '' : 's'} not shown
          </span>
          <Button variant="outline" size="sm" onClick={() => setShown((current) => current + PAGE)}>
            Show more
          </Button>
          <Button variant="outline" size="sm" onClick={() => setShown(lines.length)}>
            Show all
          </Button>
        </div>
      )}
      <pre
        data-testid="build-log"
        className="min-h-0 flex-1 overflow-auto rounded bg-muted p-3 font-mono text-xs leading-relaxed"
      >
        {lines.slice(from).join('\n')}
      </pre>
    </div>
  );
}
