import { DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function FinancePage() {
  const spec = getDomainSpec('finance');
  return <>{spec && <DomainPlaceholder spec={spec} />}</>;
}
