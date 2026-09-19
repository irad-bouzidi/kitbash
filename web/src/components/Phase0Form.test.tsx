import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Phase0Form } from '@/components/Phase0Form';

/** The hidden form that carries the envelope to the server. */
function downloadForm(): HTMLFormElement {
  const form = screen.getByTestId('download-form');
  if (!(form instanceof HTMLFormElement)) throw new Error('the download element is not a form');
  return form;
}

function envelope() {
  const field = downloadForm().querySelector<HTMLInputElement>('input[name="selection"]');
  expect(field).not.toBeNull();
  return JSON.parse(field!.value) as {
    schemaVersion: number;
    projectName: string;
    variables: Record<string, string>;
  };
}

describe('Phase0Form', () => {
  it('carries the §7 envelope, and keeps it in step with what was typed', async () => {
    const user = userEvent.setup();
    render(<Phase0Form />);

    expect(envelope()).toEqual({
      schemaVersion: 1,
      projectName: 'customer-management',
      options: {},
      variables: { groupId: 'com.example', packageName: 'com.example.customer', javaVersion: '21' },
    });

    const projectName = screen.getByLabelText('Project name');
    await user.clear(projectName);
    await user.type(projectName, 'billing-service');

    expect(envelope().projectName).toBe('billing-service');
  });

  it('downloads by submitting a form, never by fetching a blob', async () => {
    const user = userEvent.setup();
    // jsdom does not implement form submission; spying on it is also how this test
    // asserts the download path is a navigation rather than a fetch.
    const submit = vi.fn();
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    render(<Phase0Form />);
    downloadForm().submit = submit;

    await user.click(screen.getByRole('button', { name: 'Generate' }));

    expect(submit).toHaveBeenCalledOnce();
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(downloadForm().method).toBe('post');
    expect(downloadForm().getAttribute('action')).toBe('/api/v1/generate');
  });

  it('refuses to submit a project name the server would reject', async () => {
    const user = userEvent.setup();
    const submit = vi.fn();
    render(<Phase0Form />);
    downloadForm().submit = submit;

    const projectName = screen.getByLabelText('Project name');
    await user.clear(projectName);
    await user.type(projectName, 'Not A Project');
    await user.click(screen.getByRole('button', { name: 'Generate' }));

    expect(submit).not.toHaveBeenCalled();
    expect(projectName).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByText(/Lowercase letters, digits and hyphens only/)).toBeInTheDocument();
  });

  it('refuses an unsupported Java version', async () => {
    const user = userEvent.setup();
    const submit = vi.fn();
    render(<Phase0Form />);
    downloadForm().submit = submit;

    const javaVersion = screen.getByLabelText('Java version');
    await user.clear(javaVersion);
    await user.type(javaVersion, '8');
    await user.click(screen.getByRole('button', { name: 'Generate' }));

    expect(submit).not.toHaveBeenCalled();
    expect(screen.getByText('Supported versions are 17, 21 and 25.')).toBeInTheDocument();
  });
});
