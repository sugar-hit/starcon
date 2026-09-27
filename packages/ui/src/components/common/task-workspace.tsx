import Link from 'next/link';
import { ArrowRight, ListChecks } from 'lucide-react';
import { SectionCard } from './section-card';
import { TASK_PAGES, type TaskPage } from '../../lib/task-pages';

/** A truthful task workspace: explicit schema and workflow, no invented business records. */
export function TaskWorkspace({ page }: { page: TaskPage }) {
  const related = TASK_PAGES.filter(
    (item) => item.domain === page.domain && item.href !== page.href,
  );
  return (
    <div className="space-y-4">
      <div className="border-border bg-card flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3">
        <span className="text-copy-13 flex items-center gap-2">
          <ListChecks className="text-brand h-4 w-4" />
          {page.label}工作区
        </span>
        <span className="text-copy-12 text-muted-foreground">业务数据待接入</span>
      </div>
      <SectionCard title="处理流程" description={page.description}>
        <ol className="grid gap-3 sm:grid-cols-3">
          {page.workflow.map((step, index) => (
            <li
              key={step}
              className="bg-muted/50 text-copy-14 flex items-center gap-3 rounded-md p-3"
            >
              <span className="text-label-12 text-brand font-semibold">0{index + 1}</span>
              {step}
            </li>
          ))}
        </ol>
      </SectionCard>
      <SectionCard
        title={page.label}
        description="后续业务记录将按以下信息展示"
        bodyClassName="p-0"
      >
        <div
          role="region"
          aria-label={`${page.label}数据表`}
          tabIndex={0}
          className="overflow-x-auto"
        >
          <table className="text-copy-13 w-full text-left">
            <thead className="bg-muted/50">
              <tr>
                {page.fields.map((field) => (
                  <th key={field} className="whitespace-nowrap px-4 py-3 font-medium">
                    {field}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              <tr>
                <td colSpan={page.fields.length} className="px-4 py-12 text-center">
                  <p className="font-medium">尚未接入{page.label}数据</p>
                  <p className="text-copy-13 text-muted-foreground mt-2">
                    连接对应业务来源后，这里将显示可核对的记录与处理状态。
                  </p>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </SectionCard>
      <SectionCard title="相关任务">
        <div className="flex flex-wrap gap-2">
          {related.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="border-border text-copy-13 hover:bg-muted inline-flex items-center gap-2 rounded-md border px-3 py-2"
            >
              {item.label}
              <ArrowRight className="h-3.5 w-3.5" />
            </Link>
          ))}
        </div>
      </SectionCard>
    </div>
  );
}
