import type { Metadata } from 'next';
import { Sidebar } from '@/components/Sidebar';
import './globals.css';

export const metadata: Metadata = {
  title: 'RollbackShield',
  description: 'Deployment reversibility control plane',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <div className="flex flex-col md:flex-row">
          <Sidebar />
          <main className="min-h-screen flex-1">{children}</main>
        </div>
      </body>
    </html>
  );
}
