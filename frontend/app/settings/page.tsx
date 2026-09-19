'use client';

import { useEffect, useState } from 'react';
import { API_BASE } from '@/lib/api';
import { authConfigured, isSignedIn, signIn, signOut } from '@/lib/auth';

/**
 * Operational settings: the real values this deployment runs with. There
 * are no toggles here that the backend does not actually support.
 */
export default function SettingsPage() {
  const [health, setHealth] = useState<'UP' | 'DOWN' | 'UNREACHABLE' | null>(null);
  const [signedIn, setSignedIn] = useState(false);

  useEffect(() => {
    setSignedIn(isSignedIn());
    fetch(`${API_BASE}/actuator/health`, { cache: 'no-store' })
      .then((response) => response.json())
      .then((body: { status?: string }) => setHealth(body.status === 'UP' ? 'UP' : 'DOWN'))
      .catch(() => setHealth('UNREACHABLE'));
  }, []);

  return (
    <div className="mx-auto max-w-3xl px-4 py-6 md:px-8 md:py-8">
      <h1 className="mb-6 text-2xl text-ink-primary">Settings</h1>

      <table className="w-full text-left text-sm">
        <tbody>
          <tr className="border-b border-border">
            <td className="w-56 py-3 text-xs text-ink-tertiary">Control plane URL</td>
            <td className="py-3 font-mono text-xs text-ink-primary">{API_BASE}</td>
          </tr>
          <tr className="border-b border-border">
            <td className="py-3 text-xs text-ink-tertiary">Control plane health</td>
            <td className={`py-3 font-mono text-xs ${
              health === 'UP' ? 'text-status-pass'
                : health === null ? 'text-ink-tertiary' : 'text-status-fail'
            }`}>
              {health ?? 'checking…'}
            </td>
          </tr>
          <tr className="border-b border-border">
            <td className="py-3 text-xs text-ink-tertiary">Authentication</td>
            <td className="py-3 text-xs text-ink-primary">
              {authConfigured ? (
                <span className="flex items-center gap-3">
                  <span className="font-mono">
                    Cognito PKCE · {signedIn ? 'signed in' : 'signed out'}
                  </span>
                  <button
                    onClick={signedIn ? signOut : signIn}
                    className="text-accent hover:underline"
                  >
                    {signedIn ? 'Sign out' : 'Sign in'}
                  </button>
                </span>
              ) : (
                <span className="font-mono">
                  local profile · fixed dev principal (no Cognito configured)
                </span>
              )}
            </td>
          </tr>
          <tr className="border-b border-border">
            <td className="py-3 text-xs text-ink-tertiary">Local enforcement</td>
            <td className="py-3 text-xs text-ink-secondary">
              Mutations are evaluated in-process by the Java SDK against the cached contract
              policy; the control plane is never on that path.
            </td>
          </tr>
        </tbody>
      </table>

      <p className="mt-6 text-xs text-ink-tertiary">
        Credentials are stored as references only. Locally a reference names an environment
        variable; in AWS it names a Secrets Manager secret read by the task role. No secret is
        ever returned by the API.
      </p>
    </div>
  );
}
