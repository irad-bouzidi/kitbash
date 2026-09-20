import type { CatalogOption } from '@/lib/api';

interface Props {
  option: CatalogOption;
  id: string;
  value: boolean;
  disabled: boolean;
  disabledReason?: string;
  onChange: (value: boolean) => void;
}

/** A toggle: a slot with one recipe in it, or a recipe's own on/off option. */
export function BooleanField({ option, id, value, disabled, disabledReason, onChange }: Props) {
  return (
    <label
      className="inline-flex items-center gap-2 text-sm"
      title={disabled ? disabledReason : undefined}
    >
      <input
        id={id}
        type="checkbox"
        checked={value}
        disabled={disabled}
        aria-describedby={`${id}-help`}
        onChange={(event) => onChange(event.target.checked)}
        className="size-4 accent-primary disabled:cursor-not-allowed disabled:opacity-50"
      />
      <span className={disabled ? 'opacity-50' : undefined}>{option.label}</span>
    </label>
  );
}
