import { Gate } from '@/Gate';
import { WidgetsPage } from '@/pages/WidgetsPage';

export function App() {
  return (
    <main>
      <header>
        <h1>storefront</h1>
        <p>A React + TypeScript front end, talking to the widgets API.</p>
      </header>
      <Gate>
        <WidgetsPage />
      </Gate>
    </main>
  );
}
