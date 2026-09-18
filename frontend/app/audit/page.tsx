export default function AuditPage() {
  return (
    <div className="mx-auto max-w-3xl px-4 py-6 md:px-8 md:py-8">
      <h1 className="mb-2 text-2xl text-ink-primary">Audit</h1>
      <p className="text-sm text-ink-secondary">
        Audit history is scoped per release. Open a release&rsquo;s control room under{' '}
        <a href="/releases" className="text-accent underline">Releases</a> to view its trail.
      </p>
    </div>
  );
}
