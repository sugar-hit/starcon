import { DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function ProfitPage() {
  const spec = getDomainSpec('profit');
  return <>{spec && <DomainPlaceholder spec={spec} />}</>;
}