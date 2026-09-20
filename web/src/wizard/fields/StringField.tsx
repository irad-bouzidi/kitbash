import { Input } from '@/components/ui/input';
import type { CatalogOption } from '@/lib/api';

interface Props {
  option: CatalogOption;
  id: string;
  value: string;
  disabled: boolean;
  disabledReason?: string;
  invalid: boolean;
  pattern?: string;
  onChange: (value: string) => void;
}

/** Free text, validated against the rule the catalog ships — and again by the server (§13). */
export function StringField({
  id,
  value,
  disabled,
  disabledReason,
  invalid,
  pattern,
  onChange,
}: Props) {
  return (
    <Input
      id={id}
      value={value}
      disabled={disabled}
      title={disabled ? disabledReason : undefined}
      aria-invalid={invalid || undefined}
      aria-describedby={`${id}-help`}
      pattern={pattern}
      spellCheck={false}
      autoComplete="off"
      onChange={(event) => onChange(event.target.value)}
    />
  );
}
