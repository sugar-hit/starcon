'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard } from '@starcon/ui';
import { CircleAlert, Link2, Loader2, MessageSquareQuote, RefreshCw, Sparkles } from 'lucide-react';
import {
  detailLinkLabel,
  factLocationLabel,
  factRefLabel,
  formatMetric,
  formatRatio,
  isInternalRoute,
  matchModeLabel,
  matchModeTone,
  toAnswerView,
  type AssistantAnswer,
  type AssistantCapabilities,
  type AssistantIntentView,
  type AssistantSessionView,
} from './assistant-model';
import { api } from '../../lib/api';

/**
 * 18.4.1.1「对话助手」工作区（前端入口 `/platform/assistant`）。
 *
 * 蓝图：「**职责**：自然语言助手：用中文提问业务问题，返回答案并附数据来源链接；
 * 支持追问与上下文延续。**输入**：用户问题；当前上下文（租户、数据范围、当前页面）。
 * **输出**：答案 + 数据明细链接 + 建议的下一步操作。
 * **答案必须附明细链接**——不给依据的 AI 数字不能用于决策。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **没有明细链接的答案不显示成答案**：后端 `answered=false` ⇒ 显示「没有得出结论 + 为什么」，
 *    并把这一轮标成「自由问句 / 未接通」，**不显示任何像样的数字**。
 * 2. **受控意图目录是「能问什么」的唯一真相**：目录里的每条意图都带示例问题、
 *    数据源端点与口径；点示例问题即可提问（**不是**把问题写死在页面里）。
 * 3. **数值全程十进制文本**：后端给的是 `NUMERIC` 字符串，前端不转 `number`。
 * 4. **口径与时间范围常驻**：每条答案下面都带着口径与窗口。
 * 5. **建议的下一步只显示后端给的**：前端不自己编路由（编出来的路由点了 404）。
 * 6. **加载 / 空 / 错误三态齐全**：问句为空、请求失败、上游读不到（503）各有各的说法。
 */

const base = 'platform/assistant';

type Tab = 'ask' | 'intents' | 'capabilities';

const TABS: Array<{ value: Tab; label: string }> = [
  { value: 'ask', label: '提问' },
  { value: 'intents', label: '受控意图目录' },
  { value: 'capabilities', label: '能力声明' },
];

const BADGE_TONE = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
} as const;

export function AssistantWorkspace() {
  const [tab, setTab] = useState<Tab>('ask');
  const [question, setQuestion] = useState('');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [answer, setAnswer] = useState<AssistantAnswer | null>(null);
  const [history, setHistory] = useState<AssistantSessionView | null>(null);
  const [intents, setIntents] = useState<AssistantIntentView[]>([]);
  const [capabilities, setCapabilities] = useState<AssistantCapabilities | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadingCatalog, setLoadingCatalog] = useState(true);
  const [error, setError] = useState('');

  // ---- 目录与能力声明（页面一进来就拉；**空态要能说清「没有意图」而不是白屏**）
  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api<AssistantIntentView[]>(`${base}/intents`),
      api<AssistantCapabilities>(`${base}/capabilities`),
    ])
      .then(([intentRows, capabilityView]) => {
        if (cancelled) return;
        setIntents(intentRows);
        setCapabilities(capabilityView);
      })
      .catch((cause: unknown) => {
        if (cancelled) return;
        setError(cause instanceof Error ? cause.message : '意图目录加载失败');
      })
      .finally(() => {
        if (!cancelled) setLoadingCatalog(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // ---- 提问（带会话 id ⇒ 追问沿用上一轮的意图与口径）
  const ask = useCallback(
    async (text: string) => {
      const trimmed = text.trim();
      if (!trimmed) {
        setError('问题不能为空');
        return;
      }
      setLoading(true);
      setError('');
      try {
        const result = await api<AssistantAnswer>(`${base}/ask`, 'POST', {
          question: trimmed,
          ...(sessionId ? { sessionId } : {}),
        });
        setAnswer(result);
        setSessionId(result.sessionId);
        setHistory(
          await api<AssistantSessionView>(`${base}/sessions/${result.sessionId}`).catch(() => null),
        );
      } catch (cause: unknown) {
        setError(
          cause instanceof Error
            ? cause.message
            : '提问失败；若是「读不到」，那是上游事实没取到，不是「没有数据」',
        );
      } finally {
        setLoading(false);
      }
    },
    [sessionId],
  );

  const view = useMemo(() => toAnswerView(answer), [answer]);

  return (
    <div className="space-y-4">
      <PageHeader
        title="对话助手"
        description="用中文提问业务问题；答案一定带可点的明细链接与口径说明。自由问句目前如实答「AI 通道未接通」。"
        actions={
          <div className="flex items-center gap-2">
            {TABS.map((item) => (
              <Button
                key={item.value}
                size="sm"
                variant={tab === item.value ? 'default' : 'secondary'}
                onClick={() => setTab(item.value)}
              >
                {item.label}
              </Button>
            ))}
          </div>
        }
      />

      {tab === 'ask' && (
        <>
          <SectionCard
            title="提问"
            description="命中受控意图目录的问题会走真实只读查询；没命中的自由问句会如实答「AI 通道未接通」，不返回编造的答案。"
            extra={
              loading ? (
                <span className="text-copy-13 text-muted-foreground flex items-center gap-1">
                  <Loader2 className="h-3.5 w-3.5 animate-spin" /> 正在查
                </span>
              ) : null
            }
          >
            <form
              className="flex flex-col gap-3"
              onSubmit={(event) => {
                event.preventDefault();
                void ask(question);
              }}
            >
              <Input
                value={question}
                placeholder="例如：上周哪个店利润最差 / 哪些 SKU 该补货了 / 那第二名呢"
                onChange={(event) => setQuestion(event.target.value)}
                disabled={loading}
              />
              <div className="flex flex-wrap items-center gap-2">
                <Button type="submit" disabled={loading || !question.trim()}>
                  {loading ? '正在查…' : '提问'}
                </Button>
                {sessionId ? (
                  <span className="text-copy-13 text-muted-foreground">
                    追问会沿用上一轮的意图与口径（会话 {sessionId.slice(0, 8)}…）
                  </span>
                ) : null}
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    setQuestion('');
                    setAnswer(null);
                    setSessionId(null);
                    setHistory(null);
                    setError('');
                  }}
                >
                  <RefreshCw className="mr-1 h-3.5 w-3.5" /> 新会话
                </Button>
              </div>
            </form>

            {error ? (
              <div className="border-destructive/40 bg-destructive-soft mt-4 flex items-start gap-2 rounded-md border px-3 py-2">
                <CircleAlert className="text-destructive mt-0.5 h-4 w-4 shrink-0" />
                <div className="text-copy-13 text-foreground">
                  <div className="font-medium">这一轮没跑通</div>
                  <div className="text-muted-foreground">{error}</div>
                </div>
              </div>
            ) : null}
          </SectionCard>

          {answer ? (
            <SectionCard
              title="答案"
              extra={<Badge tone={BADGE_TONE[view.tone]}>{matchModeLabel(answer.matchMode)}</Badge>}
              description={
                answer.intentName
                  ? `意图：${answer.intentName}（${answer.intentCode}）`
                  : '未命中受控意图目录'
              }
            >
              {view.answered ? (
                <>
                  <p className="text-copy-14 text-foreground">{view.headline}</p>
                  {view.caliber ? (
                    <p className="text-copy-13 text-muted-foreground mt-2">{view.caliber}</p>
                  ) : null}
                  <div className="mt-4 space-y-2">
                    <div className="text-copy-13 text-foreground font-medium">
                      明细依据（{view.evidence.length} 条）
                    </div>
                    {view.evidence.map((item, index) => (
                      <div
                        key={`${item.factRef}-${index}`}
                        className="border-border rounded-md border px-3 py-2"
                      >
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="text-copy-14 text-foreground font-medium">
                            {item.factLabel}
                          </span>
                          <span className="text-copy-13 text-foreground">
                            {item.metricCode.includes('RATIO') || item.metricCode.includes('RATE')
                              ? formatRatio(item.metricValue)
                              : formatMetric(item.metricValue, item.metricUnit)}
                          </span>
                        </div>
                        <div className="text-copy-12 text-muted-foreground mt-1">
                          {factLocationLabel(item)}
                          {factRefLabel(item) ? ` · ${factRefLabel(item)}` : ''}
                        </div>
                        {item.caliberNote ? (
                          <div className="text-copy-12 text-muted-foreground mt-1">
                            {item.caliberNote}
                          </div>
                        ) : null}
                        {isInternalRoute(item.detailRoute) ? (
                          <a
                            className="text-copy-13 text-brand mt-2 inline-flex items-center gap-1 underline"
                            href={item.detailRoute}
                          >
                            <Link2 className="h-3.5 w-3.5" />
                            {detailLinkLabel(item)}
                          </a>
                        ) : null}
                      </div>
                    ))}
                  </div>
                  {view.nextActions.length ? (
                    <div className="mt-4">
                      <div className="text-copy-13 text-foreground font-medium">建议的下一步</div>
                      <div className="mt-2 flex flex-wrap gap-2">
                        {view.nextActions.map((action) => (
                          <a
                            key={action.route}
                            className="border-border text-copy-13 hover:bg-muted rounded-md border px-3 py-1.5"
                            href={action.route}
                          >
                            {action.label}
                            <span className="text-muted-foreground ml-1">{action.moduleId}</span>
                          </a>
                        ))}
                      </div>
                    </div>
                  ) : null}
                </>
              ) : (
                <div className="flex items-start gap-2">
                  <CircleAlert className="text-warning mt-0.5 h-4 w-4 shrink-0" />
                  <div>
                    <p className="text-copy-14 text-foreground font-medium">没有得出结论</p>
                    <p className="text-copy-13 text-muted-foreground mt-1">{view.headline}</p>
                    {view.caliber ? (
                      <p className="text-copy-13 text-muted-foreground mt-1">{view.caliber}</p>
                    ) : null}
                    {answer.ownerModule ? (
                      <p className="text-copy-13 text-muted-foreground mt-1">
                        归口模块：{answer.ownerModule}
                      </p>
                    ) : null}
                  </div>
                </div>
              )}
            </SectionCard>
          ) : null}

          {history && history.turns.length > 1 ? (
            <SectionCard title="本会话的问答" description="每一轮都带着它的口径与明细链接。">
              <div className="space-y-3">
                {history.turns.map((turn) => (
                  <div key={turn.turnId} className="border-border rounded-md border px-3 py-2">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="text-copy-14 text-foreground">
                        #{turn.turnNo} {turn.question}
                      </span>
                      <Badge tone={BADGE_TONE[matchModeTone(turn.matchMode)]}>
                        {matchModeLabel(turn.matchMode)}
                      </Badge>
                    </div>
                    <div className="text-copy-13 text-muted-foreground mt-1">{turn.answer}</div>
                    <div className="text-copy-12 text-muted-foreground mt-1">
                      窗口 {turn.periodFrom} ~ {turn.periodTo}
                    </div>
                    {turn.evidence.length ? (
                      <div className="mt-2 space-y-1">
                        {turn.evidence.map((item, index) => (
                          <a
                            key={`${turn.turnId}-${index}`}
                            className="text-copy-13 text-brand inline-flex items-center gap-1 underline"
                            href={item.detailRoute}
                          >
                            <Link2 className="h-3.5 w-3.5" />
                            {item.factLabel} · {formatMetric(item.metricValue, item.metricUnit)}
                          </a>
                        ))}
                      </div>
                    ) : null}
                  </div>
                ))}
              </div>
            </SectionCard>
          ) : null}
        </>
      )}

      {tab === 'intents' && (
        <SectionCard
          title="受控意图目录"
          description="助手只认这几条问题形态；每个意图对应一个上游只读端点，答案里会带它的口径与明细链接。"
          extra={
            loadingCatalog ? (
              <span className="text-copy-13 text-muted-foreground">正在加载…</span>
            ) : null
          }
        >
          {loadingCatalog ? null : intents.length === 0 ? (
            <EmptyState
              icon={<Sparkles className="h-6 w-6" />}
              title="还没有受控意图"
              description="意图目录是空的 —— 助手现在什么都问不了。"
            />
          ) : (
            <div className="space-y-3">
              {intents.map((intent) => (
                <div key={intent.intentCode} className="border-border rounded-md border px-3 py-3">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="text-copy-14 text-foreground font-medium">
                      {intent.intentName}
                    </span>
                    <Badge tone="neutral">{intent.intentCode}</Badge>
                  </div>
                  <div className="text-copy-13 text-muted-foreground mt-1">
                    数据源：{intent.sourceService} · {intent.sourceEndpoint}
                  </div>
                  <div className="text-copy-13 text-muted-foreground mt-1">
                    {intent.sourceCaliber}
                  </div>
                  <div className="mt-2 flex flex-wrap gap-2">
                    {intent.questionExamples.map((example) => (
                      <Button
                        key={example}
                        size="sm"
                        variant="secondary"
                        onClick={() => {
                          setTab('ask');
                          setQuestion(example);
                          void ask(example);
                        }}
                      >
                        <MessageSquareQuote className="mr-1 h-3.5 w-3.5" />
                        {example}
                      </Button>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      )}

      {tab === 'capabilities' && (
        <SectionCard title="能力声明" description="哪些真接通、哪些还没交付、归谁。">
          {capabilities === null ? (
            <EmptyState title="能力声明还没加载出来" description="请稍后重试。" />
          ) : (
            <div className="space-y-4">
              <div className="space-y-2">
                {capabilities.capabilities.map((capability) => (
                  <div key={capability.name} className="border-border rounded-md border px-3 py-2">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="text-copy-14 text-foreground font-medium">
                        {capability.moduleId} {capability.name}
                      </span>
                      <Badge tone={capability.available ? 'success' : 'warning'}>
                        {capability.available ? '已接通' : '未接通'}
                      </Badge>
                    </div>
                    <div className="text-copy-13 text-muted-foreground mt-1">
                      {capability.evidence}
                    </div>
                    {capability.note ? (
                      <div className="text-copy-13 text-muted-foreground mt-1">
                        {capability.note}
                      </div>
                    ) : null}
                    {capability.ownerModule ? (
                      <div className="text-copy-12 text-muted-foreground mt-1">
                        归口模块：{capability.ownerModule}
                      </div>
                    ) : null}
                  </div>
                ))}
              </div>
              {capabilities.gaps.length ? (
                <div>
                  <div className="text-copy-13 text-foreground font-medium">下游与兄弟能力缺口</div>
                  <div className="mt-2 space-y-2">
                    {capabilities.gaps.map((gap) => (
                      <div key={gap.moduleId} className="border-border rounded-md border px-3 py-2">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <span className="text-copy-13 text-foreground">
                            {gap.moduleId} {gap.name}
                          </span>
                          <Badge tone="warning">{gap.available ? '已接通' : '未接通'}</Badge>
                        </div>
                        <div className="text-copy-13 text-muted-foreground mt-1">{gap.note}</div>
                      </div>
                    ))}
                  </div>
                </div>
              ) : null}
            </div>
          )}
        </SectionCard>
      )}
    </div>
  );
}
