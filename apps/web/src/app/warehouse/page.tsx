import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function WarehousePage() {
  const spec = getDomainSpec('warehouse');
  return (
    <AppShell title="仓储中心" description="仓库档案、仓位、库内作业。">
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}