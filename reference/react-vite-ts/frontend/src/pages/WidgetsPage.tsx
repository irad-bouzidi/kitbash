import { useCallback, useEffect, useState } from 'react';
import { ApiError, createWidget, deleteWidget, listWidgets, type Widget } from '@/api/widgets';

/**
 * The vertical slice: list, create and delete one entity against the real API.
 *
 * One page is enough to prove the half of the stack that matters — a request, a response, an
 * error path and a loading state. A second page would repeat all four.
 */
export function WidgetsPage() {
  const [widgets, setWidgets] = useState<Widget[]>([]);
  const [name, setName] = useState('');
  const [quantity, setQuantity] = useState('1');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setWidgets(await listWidgets());
      setError(null);
    } catch (failure) {
      setError(describe(failure));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    try {
      await createWidget({ name, quantity: Number(quantity) });
      setName('');
      setQuantity('1');
      await refresh();
    } catch (failure) {
      setError(describe(failure));
    } finally {
      setSaving(false);
    }
  }

  async function remove(id: number) {
    try {
      await deleteWidget(id);
      await refresh();
    } catch (failure) {
      setError(describe(failure));
    }
  }

  return (
    <section>
      <h2>Widgets</h2>

      <form onSubmit={(event) => void submit(event)}>
        <label>
          Name
          <input
            value={name}
            onChange={(event) => setName(event.target.value)}
            required
            maxLength={120}
          />
        </label>
        <label>
          Quantity
          <input
            type="number"
            min={0}
            value={quantity}
            onChange={(event) => setQuantity(event.target.value)}
            required
          />
        </label>
        <button type="submit" disabled={saving || name.trim() === ''}>
          {saving ? 'Adding…' : 'Add widget'}
        </button>
      </form>

      {error && <p role="alert">{error}</p>}

      {loading ? (
        <p>Loading…</p>
      ) : widgets.length === 0 ? (
        <p>No widgets yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Quantity</th>
              <th scope="col">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {widgets.map((widget) => (
              <tr key={widget.id}>
                <td>{widget.name}</td>
                <td>{widget.quantity}</td>
                <td>
                  <button type="button" onClick={() => void remove(widget.id)}>
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

/** The server's own words where there are any: it explains the failure better than we can. */
function describe(failure: unknown): string {
  if (failure instanceof ApiError) return failure.message;
  return 'The API is not reachable. Is it running?';
}
