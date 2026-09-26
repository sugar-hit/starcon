import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function ProfitPage() {
  const spec = getDomainSpec('profit');
  return (
    <AppShell title="利润中心" description="毛利、分润、成本归集。">
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}