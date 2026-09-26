import { DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function LogisticsPage() {
  const spec = getDomainSpec('logistics');
  return <>{spec && <DomainPlaceholder spec={spec} />}</>;
}