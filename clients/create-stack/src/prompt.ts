import { createInterface, type Interface } from 'node:readline/promises';

/**
 * Asking questions in a terminal.
 *
 * Hand-rolled over `node:readline` rather than a prompt library, for the reason the CLI's argument
 * parser gives: there are three control types and the dependency would be larger than the code it
 * replaces. If this ever needs arrow-key navigation or search, that calculation changes.
 *
 * Every prompt shows its default and accepts an empty line to take it, so the whole flow can be
 * completed by holding return — which is the fastest path to a working project and the one most
 * people want.
 */
export class Prompter {
  private readonly readline: Interface;
  private readonly output: NodeJS.WritableStream;

  constructor(
    input: NodeJS.ReadableStream = process.stdin,
    output: NodeJS.WritableStream = process.stdout,
  ) {
    this.readline = createInterface({ input, output });
    // Held separately, and every line below goes through it.
    //
    // `readline.write()` writes to the **input** stream — it simulates typing. Using it for output
    // produced a run where no prompt was ever printed and the answers were consumed as if somebody
    // had typed the questions. Nothing failed; the interview simply ended early with defaults,
    // which is the kind of bug only running the thing finds.
    this.output = output;
  }

  close(): void {
    this.readline.close();
  }

  /** One of a closed set. Numbered, because typing `3` beats typing `backend-spring-kotlin`. */
  async choose(
    label: string,
    help: string | undefined,
    choices: { value: string; label: string; note?: string }[],
    fallback: string | undefined,
  ): Promise<string | undefined> {
    if (choices.length === 0) return undefined;

    this.heading(label, help);
    const none = fallback === undefined;
    choices.forEach((choice, index) => {
      const marker = choice.value === fallback ? '*' : ' ';
      const note = choice.note ? `  (${choice.note})` : '';
      this.write(`  ${marker}${index + 1}. ${choice.label}${note}\n`);
    });
    if (none) this.write('   0. none\n');

    for (;;) {
      const answer = (await this.readline.question(this.suffix(fallback ?? 'none'))).trim();
      if (answer === '') return fallback;
      if (answer === '0' && none) return undefined;
      const index = Number.parseInt(answer, 10);
      if (Number.isInteger(index) && index >= 1 && index <= choices.length) {
        return choices[index - 1]!.value;
      }
      // Never silently take the default on a typo: a mistyped answer that is accepted is a project
      // somebody did not ask for, discovered after the download.
      this.write(`  Enter a number between ${none ? 0 : 1} and ${choices.length}.\n`);
    }
  }

  async confirm(label: string, help: string | undefined, fallback: boolean): Promise<boolean> {
    this.heading(label, help);
    for (;;) {
      const answer = (await this.readline.question(this.suffix(fallback ? 'yes' : 'no')))
        .trim()
        .toLowerCase();
      if (answer === '') return fallback;
      if (['y', 'yes'].includes(answer)) return true;
      if (['n', 'no'].includes(answer)) return false;
      this.write('  Answer yes or no.\n');
    }
  }

  /**
   * Free text, checked against the catalog's own pattern.
   *
   * The rule comes from the metadata document, so a variable added tomorrow arrives validated. The
   * server checks it again regardless (§13) — this is here so the answer is refused where it was
   * typed rather than after six more questions.
   */
  async text(
    label: string,
    help: string | undefined,
    pattern: string | undefined,
    fallback: string,
  ): Promise<string> {
    this.heading(label, help);
    const rule = pattern ? new RegExp(pattern) : undefined;
    for (;;) {
      const answer = (await this.readline.question(this.suffix(fallback))).trim();
      const value = answer === '' ? fallback : answer;
      if (!rule || rule.test(value)) return value;
      this.write(`  Does not match ${pattern}\n`);
    }
  }

  /** Skipped, and why — the terminal's version of §9's visible-and-disabled control. */
  skipped(label: string, reason: string): void {
    this.write(`\n  ${label}: skipped, ${reason}\n`);
  }

  private write(text: string): void {
    this.output.write(text);
  }

  private heading(label: string, help: string | undefined): void {
    this.write(`\n${label}\n`);
    if (help) this.write(`  ${help}\n`);
  }

  private suffix(fallback: string): string {
    return `  [${fallback}] `;
  }
}
