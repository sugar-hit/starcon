'use client';

import { useCallback, useEffect, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  aggregationLabel,
  chartTypeLabel,
  dataUpdatedNote,
  dataUpdatedText,
  definitionFormula,
  dimensionColumns,
  emptyText,
  measureAvailabilityText,
  measureColumnLabel,
  measureValueLines,
  pageState,
  rowDimensionTexts,
  visibilityLabel,
} from './custom-report-model';
import type { CustomReportSharedResponse } from './custom-report-model';

/**
 * 13.6.1.1 自定义报表 · **分享链接视图**。
 *
 * <p>🔴 **分享链接不等于公开访问**：令牌绑定租户 + 绑定报表归属（个人 / 团队）。
 * 未授权访问与「报表不存在」返回**逐字相同**的 404 ⇒ 这个页面读不到时，
 * 访客**无法分辨**「报表不存在」与「你没权限」—— 存在性不泄露。
 *
 * <p>本页与工作区共用同一套展示口径（同一个 model 文件），
 * 所以「算不出来」「多币种不相加」这两条纪律在分享视图里同样成立。
 */
export function CustomReportSharedView({ token }: { token: string }) {
  const [shared, setShared] = useState<CustomReportSharedResponse | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      setShared(
        await api<CustomReportSharedResponse>(
          `reporting/custom-report/shared/${token}?page=0&size=50`,
        ),
      );
    } catch (e) {
      setShared(null);
      setError(e instanceof Error ? e.message : '读取失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    void load();
  }, [load]);

  const result = shared?.result ?? null;
  const state = pageState(result, error);

  if (loading) {
    return (
      <SectionCard>
        <EmptyState title="正在读取这份报表…" description="报表数据来自投影读模型。" />
      </SectionCard>
    );
  }

  if (state === 'error' || !shared) {
    return (
      <SectionCard>
        <EmptyState
          icon={<TriangleAlert className="h-6 w-6" />}
          title="这份报表现在打不开"
          description={
            error ||
            '链接不存在、已被撤销，或你没有查看权限 —— 这三种情况返回的是同一个提示，不泄露报表是否存在。'
          }
          action={
            <Button variant="outline" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      <SectionCard
        title={shared.report.displayName}
        description={`${shared.report.reportCode} · ${visibilityLabel(shared.report.visibility)} · ${chartTypeLabel(shared.report.chartType)}`}
      >
        {shared.report.description && (
          <p className="text-copy-13 text-muted-foreground mb-3">{shared.report.description}</p>
        )}
        <div className="text-copy-13 text-muted-foreground mb-3">
          <span className="text-foreground font-medium">{dataUpdatedText(result)}</span>
          <span className="ml-2">{dataUpdatedNote(result)}</span>
        </div>

        {state === 'empty' && (
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="这个窗口里没有数据"
            description={emptyText(result)}
          />
        )}

        {state === 'ready' && result && (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-muted-foreground border-border border-b">
                <tr>
                  {dimensionColumns(result).map((label) => (
                    <th key={label} className="py-2 pr-3 font-medium">
                      {label}
                    </th>
                  ))}
                  {result.measures.map((measure) => (
                    <th key={measure.metricCode} className="py-2 pr-3 font-medium">
                      {measureColumnLabel(measure)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {result.rows.map((row, index) => (
                  <tr key={`${index}-${row.dimensions.map((d) => d.value).join('|')}`}>
                    {rowDimensionTexts(row).map((text, position) => (
                      <td key={`d-${position}`} className="py-2 pr-3">
                        {text}
                      </td>
                    ))}
                    {row.measures.map((measure) => (
                      <td key={measure.metricCode} className="py-2 pr-3">
                        <div>
                          {measure.values.length === 0
                            ? '—'
                            : measureValueLines(measure).join(' / ')}
                        </div>
                        {measureAvailabilityText(measure) && (
                          <div className="text-muted-foreground text-copy-13">
                            {measureAvailabilityText(measure)}
                          </div>
                        )}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-copy-13 text-muted-foreground mt-2">
              共 {result.total} 个维度组合。
            </p>
          </div>
        )}
      </SectionCard>

      {shared.report.measures.length > 0 && (
        <SectionCard title="口径" description="取自服务端的口径表，原样显示。">
          <ul className="text-copy-13 space-y-1">
            {shared.report.measures.map((measure) => (
              <li key={measure.metricCode}>
                <span className="font-medium">{measure.displayName}</span>
                <span className="text-muted-foreground">（{measure.metricCode}）</span>
                <Badge tone="neutral" className="ml-2">
                  {aggregationLabel(measure.aggregation)}
                </Badge>
                {measure.aggregation === 'DERIVED_RATIO' ||
                measure.aggregation === 'DERIVED_DIFF' ? (
                  <span className="text-muted-foreground"> · {definitionFormula(measure)}</span>
                ) : null}
                <span className="text-muted-foreground block">{measure.description}</span>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}
    </div>
  );
}
