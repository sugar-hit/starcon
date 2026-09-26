import { DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function ProductsPage() {
  const spec = getDomainSpec('products');
  return <>{spec && <DomainPlaceholder spec={spec} />}</>;
}