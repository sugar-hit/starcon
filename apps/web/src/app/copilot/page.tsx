import { DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function CopilotPage() {
  const spec = getDomainSpec('copilot');
  return <>{spec && <DomainPlaceholder spec={spec} />}</>;
}