/*
 * PHASE-0 FORM — replaced by the metadata-driven wizard in kitbash-16.
 *
 * Named so its disposability is obvious. Nothing here should grow into a shared
 * abstraction: the wizard renders itself from /api/v1/metadata and will contain no
 * knowledge of what a Spring Boot project is. This file exists to prove the
 * toolchain and the download path while there is one page to verify against.
 */
import { useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

const GENERATE_URL = '/api/v1/generate';

type Field = 'projectName' | 'groupId' | 'packageName' | 'javaVersion';

const RULES: Record<Field, { label: string; hint: string; pattern: RegExp; message: string }> = {
  projectName: {
    label: 'Project name',
    hint: 'Lowercase letters, digits and hyphens. Becomes the directory and the zip name.',
    pattern: /^[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?$/,
    message:
      'Lowercase letters, digits and hyphens only, starting and ending with a letter or digit.',
  },
  groupId: {
    label: 'Group ID',
    hint: 'Maven coordinate, reverse domain.',
    pattern: /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$/,
    message: 'Dot-separated lowercase segments, each starting with a letter.',
  },
  packageName: {
    label: 'Package name',
    hint: 'Root Java package of the generated sources.',
    pattern: /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$/,
    message: 'Dot-separated lowercase segments, each starting with a letter.',
  },
  javaVersion: {
    label: 'Java version',
    hint: 'The toolchain the generated build pins.',
    pattern: /^(17|21|25)$/,
    message: 'Supported versions are 17, 21 and 25.',
  },
};

const INITIAL: Record<Field, string> = {
  projectName: 'customer-management',
  groupId: 'com.example',
  packageName: 'com.example.customer',
  javaVersion: '21',
};

export function Phase0Form() {
  const [values, setValues] = useState(INITIAL);
  const [touched, setTouched] = useState<Partial<Record<Field, boolean>>>({});
  const downloadForm = useRef<HTMLFormElement>(null);

  const errors = (Object.keys(RULES) as Field[]).reduce<Partial<Record<Field, string>>>(
    (found, field) => {
      if (!RULES[field].pattern.test(values[field])) found[field] = RULES[field].message;
      return found;
    },
    {},
  );
  const valid = Object.keys(errors).length === 0;

  // The §7 envelope, already the shape the resolver will consume.
  const selection = JSON.stringify({
    schemaVersion: 1,
    projectName: values.projectName,
    options: {},
    variables: {
      groupId: values.groupId,
      packageName: values.packageName,
      javaVersion: values.javaVersion,
    },
  });

  function submit(event: React.FormEvent) {
    event.preventDefault();
    setTouched({ projectName: true, groupId: true, packageName: true, javaVersion: true });
    if (!valid) return;
    // A real form submission, so the browser downloads natively: its own progress
    // indicator, its own resume, no blob held in memory (§9). fetch + createObjectURL
    // would put a multi-megabyte string in the tab and show the user nothing.
    downloadForm.current?.submit();
  }

  return (
    <Card className="w-full max-w-xl">
      <CardHeader>
        <CardTitle>Generate a project</CardTitle>
        <CardDescription>
          Java 21, Spring Boot, Gradle, Postgres. One stack, hardcoded — the catalog arrives in
          phase&nbsp;1.
        </CardDescription>
      </CardHeader>

      <CardContent>
        <form onSubmit={submit} noValidate className="flex flex-col gap-5">
          {(Object.keys(RULES) as Field[]).map((field) => {
            const invalid = touched[field] && errors[field];
            return (
              <div key={field} className="flex flex-col gap-1.5">
                <Label htmlFor={field}>{RULES[field].label}</Label>
                <Input
                  id={field}
                  name={field}
                  value={values[field]}
                  aria-invalid={invalid ? true : undefined}
                  aria-describedby={`${field}-hint`}
                  spellCheck={false}
                  autoComplete="off"
                  onBlur={() => setTouched((was) => ({ ...was, [field]: true }))}
                  onChange={(event) =>
                    setValues((was) => ({ ...was, [field]: event.target.value }))
                  }
                />
                <p
                  id={`${field}-hint`}
                  className={invalid ? 'text-xs text-destructive' : 'text-xs text-muted-foreground'}
                >
                  {invalid ? errors[field] : RULES[field].hint}
                </p>
              </div>
            );
          })}

          <Button type="submit" size="lg" className="mt-1">
            Generate
          </Button>
        </form>

        {/*
          The actual download. Kept out of the visible form so the fields above stay
          a plain controlled React form, while the request that leaves the browser is
          an ordinary navigation the browser knows how to download.
        */}
        <form
          ref={downloadForm}
          method="post"
          action={GENERATE_URL}
          className="hidden"
          aria-hidden="true"
          data-testid="download-form"
        >
          <input type="hidden" name="selection" value={selection} readOnly />
        </form>
      </CardContent>
    </Card>
  );
}
