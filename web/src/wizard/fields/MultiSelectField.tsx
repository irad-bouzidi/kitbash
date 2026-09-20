import type { CatalogOption } from '@/lib/api';

interface Props {
  option: CatalogOption;
  value: string;
  disabled: boolean;
  disabledReason?: string;
  onChange: (value: string) => void;
}

/**
 * Several of a set at once.
 *
 * No slot uses this yet — the catalog's slots are enum or boolean — and it exists because
 * `FieldRenderer` switches on the closed set of option types, and a type with no component is a
 * blank space in the wizard the day a manifest first uses it.
 */
export function MultiSelectField({ option, value, disabled, disabledReason, onChange }: Props) {
  const selected = value ? value.split(',') : [];

  function toggle(choice: string, on: boolean) {
    const next = on ? [...selected, choice] : selected.filter((entry) => entry !== choice);
    onChange([...new Set(next)].sort().join(','));
  }

  return (
    <div className="flex flex-col gap-1.5" title={disabled ? disabledReason : undefined}>
      {(option.choices ?? []).map((choice) => (
        <label key={choice.value} className="inline-flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={selected.includes(choice.value ?? '')}
            disabled={disabled}
            onChange={(event) => toggle(choice.value ?? '', event.target.checked)}
            className="size-4 accent-primary disabled:cursor-not-allowed disabled:opacity-50"
          />
          <span className={disabled ? 'opacity-50' : undefined}>{choice.label}</span>
        </label>
      ))}
    </div>
  );
}
