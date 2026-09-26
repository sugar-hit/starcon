import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function SupplyPage() {
  const spec = getDomainSpec('supply');
  return (
    <AppShell title="供应中心" description="供应商档案、评分、分级管理。">
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}