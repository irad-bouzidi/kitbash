import { render, screen, waitFor } from '@testing-library/react-native';

import { WidgetsScreen } from './WidgetsScreen';

/**
 * What a mobile cell can honestly assert.
 *
 * §45 is explicit that a bundle build and a component test is a reasonable cell, and that claiming
 * a green badge means "this app runs on a device" would be a lie the matrix cannot back. So this
 * renders the screen against a stubbed `fetch` and checks the two states a user actually meets:
 * the list, and the failure when the API is not there.
 *
 * It does not prove the app launches, that a gesture works, or that anything is laid out
 * correctly. Those need a device or an emulator, and the matrix has neither.
 */
describe('WidgetsScreen', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('lists what the API returns', async () => {
    stubFetch([{ id: 1, name: 'flux capacitor', quantity: 3, createdAt: '2026-01-01T00:00:00Z' }]);

    render(<WidgetsScreen />);

    await waitFor(() => expect(screen.getByText(/flux capacitor/)).toBeTruthy());
  });

  it('says the list is empty rather than showing nothing', async () => {
    stubFetch([]);

    render(<WidgetsScreen />);

    // An empty screen and a broken screen look identical, which is the whole reason for this.
    await waitFor(() => expect(screen.getByText('No widgets yet.')).toBeTruthy());
  });

  it('reports an unreachable API instead of an empty list', async () => {
    jest.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Network request failed'));

    render(<WidgetsScreen />);

    // The failure a developer meets first: the app on a phone, the API on a laptop, and no route
    // between them. A screen that showed "No widgets yet." here would send them looking at the
    // database.
    await waitFor(() => expect(screen.getByText(/not reachable/)).toBeTruthy());
  });
});

function stubFetch(body: unknown): void {
  jest.spyOn(globalThis, 'fetch').mockResolvedValue({
    ok: true,
    status: 200,
    statusText: 'OK',
    json: () => Promise.resolve(body),
  } as Response);
}
