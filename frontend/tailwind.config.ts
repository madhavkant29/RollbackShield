import type { Config } from 'tailwindcss';

/**
 * RollbackShield design tokens. Deliberately not the default Tailwind
 * indigo/violet palette, not a warm-cream/terracotta pairing, not a pure
 * near-black -- a cool charcoal-slate base with a muted teal working
 * accent and three semantic status colors (reversible/at-risk/blocked),
 * matching the "operational control room" brief: technical, calm, dense.
 */
const config: Config = {
  content: ['./app/**/*.{ts,tsx}', './components/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        base: '#14161C',        // page background
        panel: '#1B1E26',       // table/panel surface
        'panel-alt': '#20242E', // hover / zebra row
        border: '#2B2F3A',
        'border-strong': '#3A3F4D',
        ink: {
          primary: '#E7E8EC',
          secondary: '#9297A6',
          tertiary: '#5F6470',
        },
        accent: {
          DEFAULT: '#5FA8A3',
          dim: '#3E6E6B',
        },
        status: {
          pass: '#4FAE7C',
          warn: '#D2A24C',
          fail: '#D65F5F',
        },
      },
      fontFamily: {
        // IBM Plex Sans / IBM Plex Mono via next/font/google is the intended
        // pairing (distinct from the default Inter/system look, still
        // technical/legible) -- swapped for a system stack here only
        // because this was verified in a sandbox with no access to
        // fonts.googleapis.com. Restore next/font/google in app/layout.tsx
        // once building somewhere with normal internet access; see
        // docs/development/LOCAL_DEVELOPMENT.md.
        sans: ['ui-sans-serif', 'system-ui', '-apple-system', 'Segoe UI', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Consolas', 'monospace'],
      },
      borderRadius: {
        sm: '3px',
        DEFAULT: '4px',
      },
    },
  },
  plugins: [],
};
export default config;
