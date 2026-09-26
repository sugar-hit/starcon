import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function PurchasePage() {
  const spec = getDomainSpec('purchase');
  return (
    <AppShell title="采购中心" description="采购需求、采购单、1688 档口直连。">
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}