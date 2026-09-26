import { AppShell, DomainPlaceholder, getDomainSpec } from '@starcon/ui';

export default function CopilotPage() {
  const spec = getDomainSpec('copilot');
  return (
    <AppShell title="AI Copilot" description="AI Agent、自动化工作流、智能问答。">
      {spec && <DomainPlaceholder spec={spec} />}
    </AppShell>
  );
}