export default function ContractsPage() {
  return (
    <div className="mx-auto max-w-3xl px-4 py-6 md:px-8 md:py-8">
      <h1 className="mb-2 text-2xl text-ink-primary">Contracts</h1>
      <p className="text-sm text-ink-secondary">
        Rollback contracts are created and activated from a release&rsquo;s control room, not
        managed standalone. Open a release under{' '}
        <a href="/releases" className="text-accent underline">Releases</a> to create one.
      </p>
    </div>
  );
}
