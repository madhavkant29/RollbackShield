'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import {
  authConfigured,
  completeSignInIfCallback,
  isSignedIn,
  signIn,
  signOut,
} from '@/lib/auth';

const NAV_ITEMS = [
  { href: '/', label: 'Overview' },
  { href: '/services', label: 'Services' },
  { href: '/releases', label: 'Releases' },
  { href: '/contracts', label: 'Contracts' },
  { href: '/audit', label: 'Audit' },
  { href: '/settings', label: 'Settings' },
];

export function Sidebar() {
  const [open, setOpen] = useState(false);
  const [signedIn, setSignedIn] = useState(false);

  useEffect(() => {
    // Handles the redirect back from Cognito, then reflects sign-in state.
    void completeSignInIfCallback().finally(() => setSignedIn(isSignedIn()));
  }, []);

  return (
    <>
      {/* Mobile/tablet top bar: replaces the rail below md breakpoint */}
      <div className="flex h-14 items-center justify-between border-b border-border bg-panel px-4 md:hidden">
        <span className="font-mono text-[13px] tracking-tight text-ink-primary">
          rollbackshield
        </span>
        <button
          onClick={() => setOpen((v) => !v)}
          aria-expanded={open}
          aria-controls="mobile-nav"
          className="rounded-sm border border-border px-2 py-1 text-xs text-ink-secondary"
        >
          {open ? 'Close' : 'Menu'}
        </button>
      </div>
      {open && (
        <nav id="mobile-nav" className="border-b border-border bg-panel md:hidden">
          <ul className="flex flex-col gap-0.5 p-2">
            {NAV_ITEMS.map((item) => (
              <li key={item.href}>
                <Link
                  href={item.href}
                  onClick={() => setOpen(false)}
                  className="block rounded-sm px-3 py-2 text-sm text-ink-secondary hover:bg-panel-alt hover:text-ink-primary"
                >
                  {item.label}
                </Link>
              </li>
            ))}
          </ul>
        </nav>
      )}

      {/* Desktop rail: md and up */}
      <nav className="hidden h-screen w-56 shrink-0 flex-col border-r border-border bg-panel md:flex">
        <div className="flex h-14 items-center border-b border-border px-4">
          <span className="font-mono text-[13px] tracking-tight text-ink-primary">
            rollbackshield
          </span>
        </div>
        <ul className="flex flex-1 flex-col gap-0.5 p-2">
          {NAV_ITEMS.map((item) => (
            <li key={item.href}>
              <Link
                href={item.href}
                className="block rounded-sm px-3 py-1.5 text-sm text-ink-secondary hover:bg-panel-alt hover:text-ink-primary"
              >
                {item.label}
              </Link>
            </li>
          ))}
        </ul>
        <div className="flex items-center justify-between border-t border-border p-3 text-xs text-ink-tertiary">
          {authConfigured ? (
            <>
              <span>{signedIn ? 'signed in' : 'signed out'}</span>
              <button
                onClick={signedIn ? signOut : signIn}
                className="text-accent hover:underline"
              >
                {signedIn ? 'Sign out' : 'Sign in'}
              </button>
            </>
          ) : (
            <span>v0.1 · local</span>
          )}
        </div>
      </nav>
    </>
  );
}
