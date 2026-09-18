export default function SettingsPage() {
  return (
    <div className="mx-auto max-w-3xl px-4 py-6 md:px-8 md:py-8">
      <h1 className="mb-2 text-2xl text-ink-primary">Settings</h1>
      <p className="text-sm text-ink-secondary">
        Nothing configurable yet in v0.1. The control-plane URL is set via{' '}
        <code className="font-mono text-xs">NEXT_PUBLIC_ROLLBACKSHIELD_API_URL</code>.
      </p>
    </div>
  );
}
