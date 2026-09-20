import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ApiError, createPreset, type GenerateRequest } from '@/lib/api';

/**
 * "Save as preset", from the wizard's bottom bar (§3, §23).
 *
 * The name defaults from the project name but is not tied to it: §23 asks for exactly that, and
 * the reason is that a preset outlives any single project. "billing-service" is a project;
 * "our standard service" is a preset, and somebody who renames one should not be renaming the
 * other.
 *
 * Version policy is a choice with a default rather than a question. §7 makes tracking the default
 * because the common case is *give me our standard service again*, and a preset frozen on an old
 * framework version is a trap that goes off six months later.
 */
export function SavePresetDialog({
  envelope,
  onClose,
}: {
  envelope: GenerateRequest;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(envelope.projectName ?? '');
  const [description, setDescription] = useState('');
  const [visibility, setVisibility] = useState('private');
  const [pinned, setPinned] = useState(false);

  const save = useMutation({
    mutationFn: () =>
      createPreset({
        name,
        description,
        visibility,
        versionPolicy: pinned ? 'pinned' : 'track_latest',
        selection: envelope,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['presets'] });
      onClose();
    },
  });

  const failure = save.error instanceof ApiError ? save.error : null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Save as preset"
      data-testid="save-preset"
    >
      <div className="flex w-full max-w-md flex-col gap-4 rounded-lg border bg-background p-6">
        <div>
          <h2 className="text-lg font-semibold">Save as preset</h2>
          <p className="text-sm text-muted-foreground">
            Saves the selection, not the resolved versions — so this preset follows the catalog.
          </p>
        </div>

        {failure && (
          <p role="alert" className="text-sm text-destructive">
            {failure.problem.detail ?? failure.message}
            {failure.problem.hint && (
              <span className="block text-muted-foreground">{failure.problem.hint}</span>
            )}
          </p>
        )}

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="preset-name">Name</Label>
          <Input
            id="preset-name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="our standard service"
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="preset-description">Description</Label>
          <Input
            id="preset-description"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            placeholder="What this stack is for"
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="preset-visibility">Visibility</Label>
          <select
            id="preset-visibility"
            value={visibility}
            onChange={(event) => setVisibility(event.target.value)}
            className="h-9 rounded-md border border-input bg-transparent px-3 text-sm"
          >
            <option value="private">Private — only me</option>
            <option value="team">Team — anyone here</option>
            <option value="public">Public — needs a role</option>
          </select>
        </div>

        <label className="inline-flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={pinned}
            onChange={(event) => setPinned(event.target.checked)}
            className="size-4 accent-primary"
          />
          Pin today&rsquo;s versions
        </label>
        <p className="-mt-2 text-xs text-muted-foreground">
          Leave this off unless something requires the versions to stop moving. Every generation
          records exactly what it used either way.
        </p>

        <div className="flex items-center justify-end gap-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={!name.trim() || save.isPending} onClick={() => save.mutate()}>
            {save.isPending ? 'Saving…' : 'Save preset'}
          </Button>
        </div>
      </div>
    </div>
  );
}
