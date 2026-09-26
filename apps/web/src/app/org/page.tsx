import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function OrgPage() {
  const spec = getDomainSpec('org');
  return (
    <AppShell title="组织 / 权限" description="组织架构、员工、权限、平台账号授权。">
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}