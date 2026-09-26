import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function FinancePage() {
  const spec = getDomainSpec('finance');
  return (
    <AppShell title="财务中心" description="对账、结算、多币种核算、税务合规。">
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}