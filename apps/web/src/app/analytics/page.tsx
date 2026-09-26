import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function AnalyticsPage() {
  const spec = getDomainSpec('analytics');
  return (
    <AppShell title="数据中心" description="数据看板、报表、订阅与导出。">
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}