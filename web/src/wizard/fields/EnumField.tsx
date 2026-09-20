import type { CatalogOption } from '@/lib/api';

interface Props {
  option: CatalogOption;
  id: string;
  value: string;
  disabled: boolean;
  disabledReason?: string;
  invalid: boolean;
  onChange: (value: string) => void;
}

/**
 * One of several mutually exclusive recipes, or none.
 *
 * The empty choice is offered unless the catalog says the slot is required: leaving a half of the
 * stack out is a configuration, not an omission (§18).
 */
export function EnumField({
  option,
  id,
  value,
  disabled,
  disabledReason,
  invalid,
  onChange,
}: Props) {
  return (
    <select
      id={id}
      value={value}
      disabled={disabled}
      title={disabled ? disabledReason : undefined}
      aria-invalid={invalid || undefined}
      aria-describedby={`${id}-help`}
      onChange={(event) => onChange(event.target.value)}
      className="h-9 rounded-md border border-input bg-transparent px-3 text-sm disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive"
    >
      {!option.required && <option value="">— none —</option>}
      {(option.choices ?? []).map((choice) => (
        <option key={choice.value} value={choice.value ?? ''}>
          {choice.label}
          {choice.frameworkVersion ? ` (${choice.frameworkVersion})` : ''}
        </option>
      ))}
    </select>
  );
}
