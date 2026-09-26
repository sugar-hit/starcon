import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function CrmPage() {
  const spec = getDomainSpec('crm');
  return (
    <AppShell title="CRM / 客服" description="客户档案、工单、平台站内信、评价管理。">
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}