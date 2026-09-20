import { Label } from '@/components/ui/label';
import type { CatalogOption, Diagnostic, ValidationResponse } from '@/lib/api';
import { BooleanField } from '@/wizard/fields/BooleanField';
import { EnumField } from '@/wizard/fields/EnumField';
import { MultiSelectField } from '@/wizard/fields/MultiSelectField';
import { StringField } from '@/wizard/fields/StringField';
import { diagnosticsFor } from '@/wizard/useValidation';
import { useSelectionStore, type SelectionValue } from '@/wizard/useSelection';

interface Props {
  option: CatalogOption;
  resolution: ValidationResponse | null;
  pattern?: string;
  /** From the runtime Zod schema: a value that does not match the rule the catalog ships. */
  fieldError?: string;
}

/**
 * Option **type** to component. The only switch in the application.
 *
 * It switches on `type` and never on `id`, which is the whole of §9's rule and the reason adding a
 * recipe needs no frontend commit. The temptation to special-case one option — "just for the
 * package name field" — is the exact failure this design exists to prevent: if an option needs
 * particular behaviour, that behaviour belongs to its type, declared in the catalog.
 */
export function FieldRenderer({ option, resolution, pattern, fieldError }: Props) {
  // The generated schema types every field as optional, because OpenAPI does. An option with no
  // id could not be rendered at all, so it is normalised once here rather than guarded at every
  // use — and the metadata tests assert the server never sends one.
  const id = option.id ?? '';
  const value = useSelectionStore((state) => state.values[id]);
  const set = useSelectionStore((state) => state.set);

  const diagnostics = diagnosticsFor(resolution, id);
  const conflict = diagnostics.find((diagnostic) => diagnostic.code !== 'WARNING');
  const invalid = conflict !== undefined || fieldError !== undefined;
  const disabledReason = unavailableReason(option, resolution);
  const disabled = disabledReason !== undefined;

  const onChange = (next: SelectionValue) => set(id, next);

  return (
    <div className="flex flex-col gap-1.5">
      {option.type !== 'boolean' && <Label htmlFor={id}>{option.label}</Label>}

      {renderControl()}

      <p id={`${id}-help`} className="text-xs text-muted-foreground">
        {disabled ? disabledReason : option.help}
      </p>

      {/* Inline on the field, never a banner (§9): a conflict belongs to the control that
          caused it, and a list at the top of the page makes the user hunt for which one. */}
      {fieldError && (
        <p role="alert" className="text-xs text-destructive">
          {fieldError}
        </p>
      )}

      {diagnostics.map((diagnostic) => (
        <DiagnosticLine key={diagnostic.message} diagnostic={diagnostic} />
      ))}
    </div>
  );

  function renderControl() {
    switch (option.type) {
      case 'enum':
        return (
          <EnumField
            option={option}
            id={id}
            value={String(value ?? '')}
            disabled={disabled}
            disabledReason={disabledReason}
            invalid={invalid}
            onChange={onChange}
          />
        );
      case 'boolean':
        return (
          <BooleanField
            option={option}
            id={id}
            value={value === true}
            disabled={disabled}
            disabledReason={disabledReason}
            onChange={onChange}
          />
        );
      case 'multi-select':
        return (
          <MultiSelectField
            option={option}
            value={String(value ?? '')}
            disabled={disabled}
            disabledReason={disabledReason}
            onChange={onChange}
          />
        );
      case 'string':
      default:
        return (
          <StringField
            option={option}
            id={id}
            value={String(value ?? '')}
            disabled={disabled}
            disabledReason={disabledReason}
            invalid={invalid}
            pattern={pattern}
            onChange={onChange}
          />
        );
    }
  }
}

function DiagnosticLine({ diagnostic }: { diagnostic: Diagnostic }) {
  const warning = diagnostic.code === 'WARNING';
  return (
    <p
      role={warning ? 'status' : 'alert'}
      className={warning ? 'text-xs text-muted-foreground' : 'text-xs text-destructive'}
    >
      {diagnostic.message} {diagnostic.hint}
    </p>
  );
}

/**
 * Why an option does not currently apply, or undefined when it does.
 *
 * §9 wants a blocked option to stay **visible and disabled** with the reason on hover, because
 * hiding it makes the catalog feel arbitrary — a user who cannot see the option cannot tell
 * whether it exists.
 */
function unavailableReason(
  option: CatalogOption,
  resolution: ValidationResponse | null,
): string | undefined {
  const owner = option.availableWhen;
  if (!owner) return undefined;

  const selected = (resolution?.recipes ?? []).some((recipe) => recipe.id === owner);
  if (selected) return undefined;

  const label = (resolution?.recipes ?? []).find((recipe) => recipe.id === owner)?.label ?? owner;
  return `Applies when ${label} is selected.`;
}
