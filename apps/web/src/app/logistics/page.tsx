import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function LogisticsPage() {
  const spec = getDomainSpec('logistics');
  return (
    <AppShell title="物流中心" description="物流商对接、路由推荐、轨迹跟踪。">
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}