import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function ListingsPage() {
  const spec = getDomainSpec('listings');
  return (
    <AppShell title="刊登中心" description="多平台商品刊登、内容编辑、健康监控。">
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}