import { PassThrough } from 'node:stream';
import { describe, expect, it } from 'vitest';
import { Prompter } from '../src/prompt.js';

/**
 * The prompts, against streams this test owns.
 *
 * <p>Driven this way rather than by piping into the real CLI, because a pipe closes when its input
 * ends and `readline` cancels whatever question is outstanding — so a piped run ends early for a
 * reason that has nothing to do with the code. A PassThrough stays open, which is what a terminal
 * does.
 *
 * <p>The first assertion exists because of a bug it would have caught immediately.
 * `readline.write()` writes to the **input** stream — it simulates typing — so using it for output
 * printed no prompts at all and fed the questions back as answers. Nothing threw; the interview
 * just ended early having taken every default.
 */
function harness() {
  const input = new PassThrough();
  const output = new PassThrough();
  const written: string[] = [];
  output.on('data', (chunk: Buffer) => written.push(chunk.toString()));
  return { input, output, written, text: () => written.join('') };
}

describe('Prompter', () => {
  it('writes the question to the output stream, not back into the input', async () => {
    const { input, output, text } = harness();
    const prompter = new Prompter(input, output);

    const answer = prompter.text('Project name', 'What it is called.', undefined, 'demo');
    await new Promise((wake) => setTimeout(wake, 10));
    input.write('\n');

    expect(await answer).toBe('demo');
    expect(text()).toContain('Project name');
    expect(text()).toContain('What it is called.');
    prompter.close();
  });

  it('takes the default on an empty line, so the whole flow is holding return', async () => {
    const { input, output } = harness();
    const prompter = new Prompter(input, output);

    const answer = prompter.choose(
      'Backend',
      undefined,
      [
        { value: 'alpha', label: 'Alpha' },
        { value: 'beta', label: 'Beta' },
      ],
      'beta',
    );
    await new Promise((wake) => setTimeout(wake, 10));
    input.write('\n');

    expect(await answer).toBe('beta');
    prompter.close();
  });

  it('asks again on a number that is not offered, rather than taking the default', async () => {
    const { input, output, text } = harness();
    const prompter = new Prompter(input, output);

    const answer = prompter.choose(
      'Backend',
      undefined,
      [{ value: 'alpha', label: 'Alpha' }],
      'alpha',
    );
    await new Promise((wake) => setTimeout(wake, 10));
    input.write('7\n');
    await new Promise((wake) => setTimeout(wake, 10));
    input.write('1\n');

    // A mistyped answer silently accepted is a project somebody did not ask for, found after the
    // download.
    expect(await answer).toBe('alpha');
    expect(text()).toContain('Enter a number between');
    prompter.close();
  });

  it('offers "none" only where the option is optional', async () => {
    const { input, output, text } = harness();
    const prompter = new Prompter(input, output);

    const answer = prompter.choose(
      'Frontend',
      undefined,
      [{ value: 'spa', label: 'SPA' }],
      undefined,
    );
    await new Promise((wake) => setTimeout(wake, 10));
    input.write('0\n');

    expect(await answer).toBeUndefined();
    expect(text()).toContain('0. none');
    prompter.close();
  });

  it("refuses text the catalog's own pattern rejects, where it was typed", async () => {
    const { input, output, text } = harness();
    const prompter = new Prompter(input, output);

    const answer = prompter.text('Project name', undefined, '^[a-z-]+$', 'demo');
    await new Promise((wake) => setTimeout(wake, 10));
    input.write('Not A Name\n');
    await new Promise((wake) => setTimeout(wake, 10));
    input.write('a-name\n');

    // The server checks it again regardless (§13). This is so the answer is refused six questions
    // earlier than it otherwise would be.
    expect(await answer).toBe('a-name');
    expect(text()).toContain('Does not match');
    prompter.close();
  });

  it('says why an option was skipped, because silence makes a catalog look arbitrary', () => {
    const { input, output, text } = harness();
    const prompter = new Prompter(input, output);

    prompter.skipped('Architecture', 'applies when contraption-alpha is selected');

    expect(text()).toContain('Architecture: skipped, applies when contraption-alpha is selected');
    prompter.close();
  });
});
