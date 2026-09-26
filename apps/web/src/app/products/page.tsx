import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function ProductsPage() {
  const spec = getDomainSpec('products');
  return (
    <AppShell title="商品中心" description="SKU 与变型主数据,所有业务的源头。">
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}