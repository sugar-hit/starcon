export default function Loading() {
  return (
    <div className="animate-pulse space-y-5" role="status" aria-label="页面加载中">
      <span className="sr-only">页面加载中…</span>
      <div className="space-y-2">
        <div className="bg-muted h-7 w-40 rounded-md" />
        <div className="bg-muted h-4 w-72 max-w-full rounded-md" />
      </div>
      <div className="grid gap-4 sm:grid-cols-3">
        {[0, 1, 2].map((item) => (
          <div key={item} className="border-border bg-card space-y-3 rounded-lg border p-5">
            <div className="bg-muted h-4 w-20 rounded" />
            <div className="bg-muted h-7 w-28 rounded" />
          </div>
        ))}
      </div>
      <div className="border-border bg-card space-y-4 rounded-lg border p-5">
        <div className="bg-muted h-5 w-32 rounded" />
        {[0, 1, 2, 3].map((item) => (
          <div key={item} className="bg-muted h-10 w-full rounded-md" />
        ))}
      </div>
    </div>
  );
}
