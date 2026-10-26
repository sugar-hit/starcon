'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import {
  CircleHelp,
  Fingerprint,
  GitBranch,
  Loader2,
  Network,
  RefreshCw,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react';
import {
  REGISTRABLE_DIMENSIONS,
  SOURCES,
  acknowledgeAlert,
  actionLabel,
  alertStatusLabel,
  alertStatusTone,
  canActOnAlert,
  canActOnRemediation,
  clusterDimensionText,
  createIdentifier,
  deleteIdentifier,
  dimensionLabel,
  dismissAlert,
  emptyState,
  fetchAlert,
  fetchAlerts,
  fetchCapabilities,
  fetchGraph,
  fetchIdentifiers,
  fetchPolicy,
  fetchRemediations,
  fetchShops,
  factorText,
  graphSummary,
  legalEntityText,
  linkShop,
  registeredOnlyCapabilities,
  remediationStatusLabel,
  remediationStatusTone,
  resolveAlert,
  runScan,
  savePolicy,
  severityLabel,
  severityTone,
  unavailableCapabilities,
  unlinkShop,
  updateRemediationStatus,
  type LinkageRiskAlert,
  type LinkageRiskCapabilities,
  type LinkageRiskGraph,
  type LinkageRiskIdentifier,
  type LinkageRiskIdentifierPage,
  type LinkageRiskPolicy,
  type LinkageRiskPolicyInput,
  type LinkageRiskRemediation,
  type LinkageRiskScanResult,
  type LinkageRiskShop,
} from './linkage-risk-model';

/**
 * 16.5.2.2 关联风险 工作区。
 *
 * 蓝图：「识别账号关联风险：同一支付方式/收款账户/设备/IP/地址/法人关联的店铺，
 * 输出关联图谱与风险提示，提示避免触发平台关联处罚。输入：店铺与支付/设备/地址/法人关系数据；
 * 平台政策要求。输出：关联图谱 + 风险提示 + 整改建议。关系：上游 = 2 店铺、12.7.1 资金账户；
 * 下游 = 1 工作台预警。**关联封号是跨境多店经营的头号风险**：官方 API 授权是规避关联的关键，
 * 本系统只做官方 API，绝不提供任何模拟登录/指纹浏览器能力。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **「读不到」显示成「读不到」**，不显示成「没有关联」—— 后端 `known=false` 就是没判定，
 *    页面显示成「还没登记策略」并写清原因，绝不用空图谱冒充一切正常。
 * 2. **量不到的维度显式标「读不到」+ 归属模块**（支付方式 / 收款账户归 12.7.1.1，未交付）。
 * 3. **前端不重算后端的判定**：严重度 / 风险等级 / 关联组全部原样显示后端算好的值。
 * 4. **未交付的下游不编造**：1 工作台预警未交付，页面**没有**「推送到工作台」的按钮。
 * 5. **不提供任何绕关联的入口**：本页只有「登记已观测到的共享事实」。
 */
export function LinkageRiskWorkspace() {
  const [caps, setCaps] = useState<LinkageRiskCapabilities | null>(null);
  const [policy, setPolicy] = useState<LinkageRiskPolicy | null>(null);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<LinkageRiskPolicyInput | null>(null);

  const [shops, setShops] = useState<LinkageRiskShop[]>([]);
  const [shopTotal, setShopTotal] = useState(0);
  const [shopKeyword, setShopKeyword] = useState('');

  const [identifiers, setIdentifiers] = useState<LinkageRiskIdentifierPage | null>(null);
  const [newDimension, setNewDimension] = useState('DEVICE');
  const [newKey, setNewKey] = useState('');
  const [newLabel, setNewLabel] = useState('');
  const [linkTarget, setLinkTarget] = useState<LinkageRiskIdentifier | null>(null);
  const [linkShopId, setLinkShopId] = useState('');
  const [linkSource, setLinkSource] = useState('MANUAL');

  const [graph, setGraph] = useState<LinkageRiskGraph | null>(null);
  const [scan, setScan] = useState<LinkageRiskScanResult | null>(null);

  const [alerts, setAlerts] = useState<LinkageRiskAlert[]>([]);
  const [alertTotal, setAlertTotal] = useState(0);
  const [selectedAlert, setSelectedAlert] = useState<LinkageRiskAlert | null>(null);
  const [alertReason, setAlertReason] = useState('');

  const [remediations, setRemediations] = useState<LinkageRiskRemediation[]>([]);
  const [remediationTotal, setRemediationTotal] = useState(0);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const loadShops = useCallback(async (keyword: string) => {
    const result = await fetchShops(keyword, 0, 50);
    setShops(result.items);
    setShopTotal(result.total);
  }, []);

  const loadIdentifiers = useCallback(async () => {
    const result = await fetchIdentifiers('', '', 0, 50);
    setIdentifiers(result);
  }, []);

  const loadAlerts = useCallback(async () => {
    const result = await fetchAlerts('', '', 0, 50);
    setAlerts(result.items);
    setAlertTotal(result.total);
  }, []);

  const loadRemediations = useCallback(async () => {
    const result = await fetchRemediations('', 0, 50);
    setRemediations(result.items);
    setRemediationTotal(result.total);
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, policyResult] = await Promise.all([fetchCapabilities(), fetchPolicy()]);
      setCaps(capsResult);
      setPolicy(policyResult);
      await Promise.all([loadShops(shopKeyword), loadIdentifiers(), loadAlerts(), loadRemediations()]);
      setGraph(await fetchGraph());
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [loadAlerts, loadIdentifiers, loadRemediations, loadShops, shopKeyword]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const unavailable = useMemo(() => unavailableCapabilities(caps), [caps]);
  const registeredOnly = useMemo(() => registeredOnlyCapabilities(caps), [caps]);

  const startEdit = useCallback(() => {
    if (!policy) return;
    const dimensions = policy.dimensions.length
      ? policy.dimensions.map((d) => ({
          dimension: d.dimension,
          sensitivity: d.sensitivity,
          minShopCount: d.minShopCount,
          policyBasis: d.policyBasis,
          enabled: d.enabled,
        }))
      : [
          {
            dimension: 'LEGAL_ENTITY',
            sensitivity: 'MEDIUM',
            minShopCount: 2,
            policyBasis: '',
            enabled: true,
          },
        ];
    setDraft({
      policyName: policy.policyName || '关联风险策略',
      status: policy.status || 'DRAFT',
      minSharedShopCount: policy.minSharedShopCount || 2,
      evaluationScope: policy.evaluationScope || 'TENANT',
      dimensions,
    });
    setEditing(true);
  }, [policy]);

  const submitPolicy = useCallback(async () => {
    if (!draft) return;
    setBusy(true);
    setError(null);
    try {
      const saved = await savePolicy(draft);
      setPolicy(saved);
      setEditing(false);
      setNotice('策略已保存。阈值与各维度的政策依据现在由你的口径决定。');
      setGraph(await fetchGraph());
    } catch (e) {
      setError(e instanceof Error ? e.message : '策略保存失败');
    } finally {
      setBusy(false);
    }
  }, [draft]);

  const submitIdentifier = useCallback(async () => {
    if (!newKey.trim()) {
      setError('请填写共享因子的标识值');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await createIdentifier({
        dimension: newDimension,
        identifierKey: newKey.trim(),
        displayLabel: newLabel.trim(),
        note: '',
        firstObservedAt: null,
      });
      setNewKey('');
      setNewLabel('');
      setNotice('共享因子已登记。接着把共享它的店铺挂上去。');
      await loadIdentifiers();
    } catch (e) {
      setError(e instanceof Error ? e.message : '登记失败');
    } finally {
      setBusy(false);
    }
  }, [loadIdentifiers, newDimension, newKey, newLabel]);

  const submitLink = useCallback(async () => {
    if (!linkTarget || !linkShopId.trim()) {
      setError('请选择要挂上去的店铺');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await linkShop(linkTarget.identifierId, {
        shopId: linkShopId.trim(),
        source: linkSource,
        note: '',
        observedAt: null,
      });
      setLinkShopId('');
      setNotice('已登记一条共享事实。跑一次扫描即可算出关联组与风险提示。');
      await Promise.all([loadIdentifiers(), loadShops(shopKeyword)]);
      setGraph(await fetchGraph());
    } catch (e) {
      setError(e instanceof Error ? e.message : '登记共享事实失败');
    } finally {
      setBusy(false);
    }
  }, [linkShopId, linkSource, linkTarget, loadIdentifiers, loadShops, shopKeyword]);

  const removeLink = useCallback(
    async (identifier: LinkageRiskIdentifier, shopId: string) => {
      setBusy(true);
      setError(null);
      try {
        await unlinkShop(identifier.identifierId, shopId);
        await Promise.all([loadIdentifiers(), loadShops(shopKeyword)]);
        setGraph(await fetchGraph());
      } catch (e) {
        setError(e instanceof Error ? e.message : '解除共享事实失败');
      } finally {
        setBusy(false);
      }
    },
    [loadIdentifiers, loadShops, shopKeyword],
  );

  const removeIdentifier = useCallback(
    async (identifier: LinkageRiskIdentifier) => {
      setBusy(true);
      setError(null);
      try {
        await deleteIdentifier(identifier.identifierId);
        await loadIdentifiers();
        setGraph(await fetchGraph());
      } catch (e) {
        setError(e instanceof Error ? e.message : '删除失败');
      } finally {
        setBusy(false);
      }
    },
    [loadIdentifiers],
  );

  const doScan = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const result = await runScan();
      setScan(result);
      await Promise.all([loadAlerts(), loadRemediations()]);
      setGraph(await fetchGraph());
      setNotice(result.note);
    } catch (e) {
      setError(e instanceof Error ? e.message : '扫描失败');
    } finally {
      setBusy(false);
    }
  }, [loadAlerts, loadRemediations]);

  const disposeAlert = useCallback(
    async (action: 'acknowledge' | 'resolve' | 'dismiss') => {
      if (!selectedAlert) return;
      if (!alertReason.trim()) {
        setError('处置理由必填 —— 事后争议时它是唯一的证据');
        return;
      }
      setBusy(true);
      setError(null);
      try {
        const alertId = selectedAlert.alertId;
        const saved =
          action === 'acknowledge'
            ? await acknowledgeAlert(alertId, alertReason.trim())
            : action === 'resolve'
              ? await resolveAlert(alertId, alertReason.trim())
              : await dismissAlert(alertId, alertReason.trim());
        setSelectedAlert(saved);
        setAlertReason('');
        setNotice('处置已记录（每一次处置都留痕）。');
        await loadAlerts();
      } catch (e) {
        setError(e instanceof Error ? e.message : '处置失败');
      } finally {
        setBusy(false);
      }
    },
    [alertReason, loadAlerts, selectedAlert],
  );

  const decideRemediation = useCallback(
    async (remediation: LinkageRiskRemediation, status: string) => {
      setBusy(true);
      setError(null);
      try {
        await updateRemediationStatus(remediation.remediationId, status, '');
        await loadRemediations();
      } catch (e) {
        setError(e instanceof Error ? e.message : '整改建议处置失败');
      } finally {
        setBusy(false);
      }
    },
    [loadRemediations],
  );

  const openAlert = useCallback(async (alertId: string) => {
    setError(null);
    try {
      setSelectedAlert(await fetchAlert(alertId));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载提示详情失败');
    }
  }, []);

  if (loading) {
    return (
      <div className="flex items-center gap-2 text-copy-13 text-muted-foreground">
        <Loader2 className="h-4 w-4 animate-spin" />
        正在加载关联风险数据…
      </div>
    );
  }

  if (error && !caps && !policy) {
    return (
      <EmptyState
        icon={<TriangleAlert className="h-6 w-6" />}
        title={emptyState('ERROR').title}
        description={error}
        action={
          <Button size="sm" onClick={() => void refresh()}>
            重试
          </Button>
        }
      />
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="关联风险"
        description="同一支付方式 / 收款账户 / 设备 / IP / 地址 / 法人关联的店铺，输出关联图谱与风险提示。"
        actions={
          <>
            <Button size="sm" variant="outline" onClick={() => void refresh()} disabled={busy}>
              <RefreshCw className="h-4 w-4" />
              刷新
            </Button>
            <Button size="sm" onClick={() => void doScan()} disabled={busy || !policy?.configured}>
              <ShieldAlert className="h-4 w-4" />
              {busy ? '扫描中…' : '跑一次扫描'}
            </Button>
          </>
        }
      />

      {error && (
        <div className="text-copy-13 text-destructive flex items-center gap-2">
          <TriangleAlert className="h-4 w-4" />
          {error}
        </div>
      )}
      {notice && (
        <div className="text-copy-13 text-muted-foreground flex items-start gap-2">
          <CircleHelp className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{notice}</span>
        </div>
      )}

      {/* ---------------- 能力声明 ---------------- */}
      {caps && (
        <SectionCard
          title="能力声明"
          description="逐维度如实标注：做得到吗 / 事实能自动发现吗 / 缺的那部分归哪个模块。"
        >
          <div className="space-y-3">
            <div className="flex flex-wrap gap-2">
              {caps.capabilities.map((c) => (
                <Badge
                  key={c.code}
                  tone={!c.available ? 'warning' : c.autoDiscoverable ? 'success' : 'info'}
                >
                  {c.label}：
                  {!c.available
                    ? `读不到（归 ${c.ownerModule}）`
                    : c.autoDiscoverable
                      ? '可读 · 自动发现'
                      : '可登记 · 不自动发现'}
                </Badge>
              ))}
            </div>
            {unavailable.length > 0 && (
              <div className="text-copy-13 text-muted-foreground space-y-1">
                <p className="text-foreground">读不到的维度（**不是「无风险」**）：</p>
                {unavailable.map((c) => (
                  <p key={c.code}>
                    · {c.label} —— 归 {c.ownerModule || '（无归属模块）'}：{c.impact}
                  </p>
                ))}
              </div>
            )}
            {registeredOnly.length > 0 && (
              <div className="text-copy-13 text-muted-foreground space-y-1">
                <p className="text-foreground">可登记但不自动发现的维度（事实靠你登记）：</p>
                {registeredOnly.map((c) => (
                  <p key={c.code}>
                    · {c.label} —— {c.impact}
                  </p>
                ))}
              </div>
            )}
            <details className="text-copy-12 text-muted-foreground">
              <summary className="cursor-pointer">口径说明（节点来源 / 维度 / 登记 / 图谱 / 提示 / 整改 / 边界）</summary>
              <div className="mt-2 space-y-2">
                <p>{caps.shopSourceNote}</p>
                <p>{caps.dimensionNote}</p>
                <p>{caps.registerNote}</p>
                <p>{caps.graphNote}</p>
                <p>{caps.alertNote}</p>
                <p>{caps.remediationNote}</p>
                <p>{caps.officialApiNote}</p>
                <p>{caps.downstreamNote}</p>
                <p>{caps.financeNote}</p>
              </div>
            </details>
          </div>
        </SectionCard>
      )}

      {/* ---------------- 策略 ---------------- */}
      <SectionCard
        title="关联风险策略"
        description="阈值不给默认值：几个店铺共享同一标识才算关联，必须由你显式选。"
        extra={
          !editing && (
            <Button size="sm" variant="outline" onClick={startEdit}>
              {policy?.configured ? '修改策略' : '登记策略'}
            </Button>
          )
        }
      >
        {!policy?.configured && !editing ? (
          <EmptyState
            icon={<ShieldAlert className="h-6 w-6" />}
            title={emptyState('NO_POLICY').title}
            description={emptyState('NO_POLICY').description}
            action={
              <Button size="sm" onClick={startEdit}>
                登记策略
              </Button>
            }
          />
        ) : editing && draft ? (
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-4">
              <label className="text-copy-13 text-foreground space-y-1">
                <span>策略名</span>
                <Input
                  value={draft.policyName}
                  onChange={(e) => setDraft({ ...draft, policyName: e.target.value })}
                />
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>状态</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                  value={draft.status}
                  onChange={(e) => setDraft({ ...draft, status: e.target.value })}
                >
                  <option value="DRAFT">草稿</option>
                  <option value="ACTIVE">生效</option>
                </select>
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>最少共享店铺数</span>
                <Input
                  type="number"
                  min="2"
                  max="50"
                  value={draft.minSharedShopCount}
                  onChange={(e) =>
                    setDraft({ ...draft, minSharedShopCount: Number(e.target.value) || 2 })
                  }
                />
              </label>
              <label className="text-copy-13 text-foreground space-y-1">
                <span>判定范围</span>
                <select
                  className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                  value={draft.evaluationScope}
                  onChange={(e) => setDraft({ ...draft, evaluationScope: e.target.value })}
                >
                  <option value="TENANT">全部店铺之间</option>
                  <option value="PLATFORM">仅同一平台之间</option>
                </select>
              </label>
            </div>
            <div className="space-y-2">
              <p className="text-copy-13 text-foreground">逐维度口径（平台政策要求）</p>
              {draft.dimensions.map((dimension, index) => (
                <div
                  key={`${dimension.dimension}-${index}`}
                  className="grid items-end gap-3 md:grid-cols-5"
                >
                  <label className="text-copy-13 text-foreground space-y-1">
                    <span>维度</span>
                    <select
                      className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                      value={dimension.dimension}
                      onChange={(e) => {
                        const next = [...draft.dimensions];
                        next[index] = { ...dimension, dimension: e.target.value };
                        setDraft({ ...draft, dimensions: next });
                      }}
                    >
                      {['LEGAL_ENTITY', ...REGISTRABLE_DIMENSIONS.map((d) => d.value)].map((value) => (
                        <option key={value} value={value}>
                          {dimensionLabel(value)}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label className="text-copy-13 text-foreground space-y-1">
                    <span>敏感度</span>
                    <select
                      className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                      value={dimension.sensitivity}
                      onChange={(e) => {
                        const next = [...draft.dimensions];
                        next[index] = { ...dimension, sensitivity: e.target.value };
                        setDraft({ ...draft, dimensions: next });
                      }}
                    >
                      <option value="HIGH">高</option>
                      <option value="MEDIUM">中</option>
                      <option value="LOW">低</option>
                    </select>
                  </label>
                  <label className="text-copy-13 text-foreground space-y-1">
                    <span>该维度最少共享店铺数</span>
                    <Input
                      type="number"
                      min="2"
                      max="50"
                      value={dimension.minShopCount}
                      onChange={(e) => {
                        const next = [...draft.dimensions];
                        next[index] = { ...dimension, minShopCount: Number(e.target.value) || 2 };
                        setDraft({ ...draft, dimensions: next });
                      }}
                    />
                  </label>
                  <label className="text-copy-13 text-foreground space-y-1">
                    <span>政策依据</span>
                    <Input
                      value={dimension.policyBasis}
                      placeholder="依据哪条平台政策 / 内部口径"
                      onChange={(e) => {
                        const next = [...draft.dimensions];
                        next[index] = { ...dimension, policyBasis: e.target.value };
                        setDraft({ ...draft, dimensions: next });
                      }}
                    />
                  </label>
                  <label className="text-copy-13 text-foreground flex items-center gap-2">
                    <input
                      type="checkbox"
                      checked={dimension.enabled}
                      onChange={(e) => {
                        const next = [...draft.dimensions];
                        next[index] = { ...dimension, enabled: e.target.checked };
                        setDraft({ ...draft, dimensions: next });
                      }}
                    />
                    <span>参与判定</span>
                  </label>
                </div>
              ))}
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    setDraft({
                      ...draft,
                      dimensions: [
                        ...draft.dimensions,
                        {
                          dimension: 'DEVICE',
                          sensitivity: 'HIGH',
                          minShopCount: 2,
                          policyBasis: '',
                          enabled: true,
                        },
                      ],
                    })
                  }
                >
                  添加维度
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => setDraft({ ...draft, dimensions: draft.dimensions.slice(0, -1) })}
                  disabled={draft.dimensions.length <= 1}
                >
                  移除最后一个维度
                </Button>
              </div>
            </div>
            <div className="flex gap-2">
              <Button size="sm" onClick={submitPolicy} disabled={busy}>
                {busy ? '保存中…' : '保存策略'}
              </Button>
              <Button size="sm" variant="outline" onClick={() => setEditing(false)}>
                取消
              </Button>
            </div>
          </div>
        ) : policy ? (
          <div className="space-y-2">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-copy-14 text-foreground font-medium">{policy.policyName}</span>
              <Badge tone={policy.status === 'ACTIVE' ? 'success' : 'neutral'}>
                {policy.status === 'ACTIVE' ? '生效' : '草稿'}
              </Badge>
              <Badge tone="info">
                最少 {policy.minSharedShopCount} 家店铺共享才算关联
              </Badge>
              <Badge tone="neutral">
                {policy.evaluationScope === 'TENANT' ? '全部店铺之间判' : '仅同平台之间判'}
              </Badge>
              <Badge tone="neutral">版本 {policy.version}</Badge>
            </div>
            <div className="text-copy-13 text-muted-foreground grid gap-1 md:grid-cols-2">
              {policy.dimensions.map((dimension) => (
                <p key={dimension.dimension}>
                  · {dimension.dimensionLabel}（敏感度 {dimension.sensitivity} / 阈值{' '}
                  {dimension.minShopCount} 家 / {dimension.enabled ? '参与判定' : '不参与判定'}）
                  {dimension.policyBasis ? ` —— 依据：${dimension.policyBasis}` : ''}
                </p>
              ))}
            </div>
          </div>
        ) : null}
      </SectionCard>

      {/* ---------------- 店铺（节点来源） ---------------- */}
      <SectionCard
        title="店铺（图谱节点来源）"
        description="节点就是本租户真实的店铺。法人名称读不到时如实显示「读不到」，不显示成空名字。"
      >
        <div className="space-y-3">
          <div className="flex gap-2">
            <Input
              placeholder="搜索店铺代码 / 名称 / 平台"
              value={shopKeyword}
              onChange={(e) => setShopKeyword(e.target.value)}
            />
            <Button size="sm" variant="outline" onClick={() => void loadShops(shopKeyword)}>
              搜索
            </Button>
          </div>
          {shopTotal === 0 ? (
            <EmptyState
              icon={<Network className="h-6 w-6" />}
              title={emptyState('NO_SHOPS').title}
              description={emptyState('NO_SHOPS').description}
            />
          ) : (
            <div className="text-copy-13 space-y-1">
              {shops.map((shop) => (
                <div
                  key={shop.shopId}
                  className="border-control-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2"
                >
                  <span className="text-foreground font-medium">{shop.code}</span>
                  <span className="text-muted-foreground">{shop.name}</span>
                  <Badge tone="neutral">{shop.platformCode}</Badge>
                  <Badge tone={shop.legalEntityNameAvailable ? 'info' : 'warning'}>
                    法人：{legalEntityText(shop)}
                  </Badge>
                  {shop.linkCount > 0 && (
                    <Badge tone="warning">
                      {shop.linkCount} 条关联 · {shop.dimensions.map(dimensionLabel).join('/')}
                    </Badge>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 关联事实登记 ---------------- */}
      <SectionCard
        title="共享事实登记"
        description="设备 / 网络 IP / 地址 / 其它共享因子：登记你已经观测到的共享事实。法人维度不登记（真相在店铺的法人绑定上）。"
      >
        <div className="space-y-3">
          <div className="grid items-end gap-3 md:grid-cols-4">
            <label className="text-copy-13 text-foreground space-y-1">
              <span>维度</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={newDimension}
                onChange={(e) => setNewDimension(e.target.value)}
              >
                {REGISTRABLE_DIMENSIONS.map((d) => (
                  <option key={d.value} value={d.value}>
                    {d.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>标识值</span>
              <Input
                value={newKey}
                placeholder="设备编号 / IP 或网段 / 地址标识"
                onChange={(e) => setNewKey(e.target.value)}
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>展示标签（可脱敏）</span>
              <Input
                value={newLabel}
                placeholder="留空则用标识值"
                onChange={(e) => setNewLabel(e.target.value)}
              />
            </label>
            <Button size="sm" onClick={submitIdentifier} disabled={busy}>
              登记共享因子
            </Button>
          </div>

          {!identifiers || identifiers.total === 0 ? (
            <EmptyState
              icon={<Fingerprint className="h-6 w-6" />}
              title={emptyState('NO_FACTS').title}
              description={emptyState('NO_FACTS').description}
            />
          ) : (
            <div className="space-y-2">
              {identifiers.items.map((identifier) => (
                <div
                  key={identifier.identifierId}
                  className="border-control-border space-y-2 rounded-md border px-3 py-2"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone="info">{identifier.dimensionLabel}</Badge>
                    <span className="text-foreground font-medium">{identifier.displayLabel}</span>
                    <span className="text-muted-foreground text-copy-12">{identifier.identifierKey}</span>
                    <Badge tone={identifier.status === 'ACTIVE' ? 'success' : 'neutral'}>
                      {identifier.status === 'ACTIVE' ? '生效中' : '已停用'}
                    </Badge>
                    <Badge tone={identifier.shopCount >= 2 ? 'warning' : 'neutral'}>
                      {identifier.shopCount} 家店铺共享
                    </Badge>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => setLinkTarget(identifier)}
                    >
                      挂店铺
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => void removeIdentifier(identifier)}
                      disabled={busy}
                    >
                      删除
                    </Button>
                  </div>
                  {identifier.shops.length > 0 && (
                    <div className="flex flex-wrap gap-2">
                      {identifier.shops.map((shop) => (
                        <span
                          key={shop.shopId}
                          className="border-control-border inline-flex items-center gap-1 rounded-md border px-2 py-1 text-copy-12"
                        >
                          {shop.shopCode}（{shop.platformCode}）
                          <button
                            type="button"
                            className="text-muted-foreground hover:text-foreground"
                            onClick={() => void removeLink(identifier, shop.shopId)}
                          >
                            解除
                          </button>
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}

          {linkTarget && (
            <div className="border-control-border space-y-2 rounded-md border px-3 py-2">
              <p className="text-copy-13 text-foreground">
                给「{linkTarget.displayLabel}」挂上共享它的店铺
              </p>
              <div className="grid items-end gap-3 md:grid-cols-3">
                <label className="text-copy-13 text-foreground space-y-1">
                  <span>店铺</span>
                  <select
                    className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                    value={linkShopId}
                    onChange={(e) => setLinkShopId(e.target.value)}
                  >
                    <option value="">请选择</option>
                    {shops.map((shop) => (
                      <option key={shop.shopId} value={shop.shopId}>
                        {shop.code} · {shop.name}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="text-copy-13 text-foreground space-y-1">
                  <span>事实来源</span>
                  <select
                    className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                    value={linkSource}
                    onChange={(e) => setLinkSource(e.target.value)}
                  >
                    {SOURCES.map((s) => (
                      <option key={s.value} value={s.value}>
                        {s.label}
                      </option>
                    ))}
                  </select>
                </label>
                <Button size="sm" onClick={submitLink} disabled={busy}>
                  登记共享事实
                </Button>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 关联图谱 ---------------- */}
      <SectionCard
        title="关联图谱"
        description="实算：节点来自店铺，边来自共享同一因子，关联组是这些边连成的连通分量。"
      >
        {!graph || !graph.known ? (
          <EmptyState
            icon={<GitBranch className="h-6 w-6" />}
            title={emptyState('NO_POLICY').title}
            description={graph?.note || emptyState('NO_POLICY').description}
          />
        ) : graph.shopCount === 0 ? (
          <EmptyState
            icon={<Network className="h-6 w-6" />}
            title={emptyState('NO_SHOPS').title}
            description={emptyState('NO_SHOPS').description}
          />
        ) : graph.clusters.length === 0 ? (
          <EmptyState
            icon={<GitBranch className="h-6 w-6" />}
            title={emptyState('NO_CLUSTER').title}
            description={emptyState('NO_CLUSTER').description}
          />
        ) : (
          <div className="space-y-3">
            <div className="grid gap-3 md:grid-cols-3">
              <StatCard label="店铺" value={String(graph.shopCount)} />
              <StatCard label="关联边" value={String(graph.linkCount)} />
              <StatCard label="关联组" value={String(graph.clusterCount)} />
            </div>
            {graph.note && <p className="text-copy-12 text-muted-foreground">{graph.note}</p>}
            <div className="space-y-2">
              {graph.clusters.map((cluster) => (
                <div
                  key={cluster.clusterKey}
                  className="border-control-border space-y-1 rounded-md border px-3 py-2"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={severityTone(cluster.severity)}>
                      严重度 {cluster.severityLabel}
                    </Badge>
                    <Badge tone={severityTone(cluster.riskLevel)}>
                      风险等级 {cluster.riskLevelLabel}
                    </Badge>
                    <Badge tone="info">{clusterDimensionText(cluster)}</Badge>
                    <Badge tone="neutral">{cluster.shopCount} 家店铺</Badge>
                    {cluster.crossPlatform && <Badge tone="warning">跨平台</Badge>}
                  </div>
                  <p className="text-copy-13 text-muted-foreground">{cluster.summary}</p>
                </div>
              ))}
            </div>
            {graph.links.length > 0 && (
              <details className="text-copy-12 text-muted-foreground">
                <summary className="cursor-pointer">关联边明细（{graph.links.length} 条）</summary>
                <div className="mt-2 space-y-1">
                  {graph.links.map((link) => (
                    <p key={`${link.dimension}-${link.factorKey}-${link.platformCode}`}>
                      · {factorText(link)}
                    </p>
                  ))}
                </div>
              </details>
            )}
          </div>
        )}
      </SectionCard>

      {/* ---------------- 风险提示 ---------------- */}
      <SectionCard
        title="风险提示"
        description="一组关联一条提示；处置走状态机，每一次处置都留一行（append-only）。"
        extra={
          <span className="text-copy-12 text-muted-foreground">
            {alertTotal} 条 · 待处置 {alerts.filter((a) => a.status === 'OPEN').length} 条
          </span>
        }
      >
        {alertTotal === 0 ? (
          <EmptyState
            icon={<ShieldAlert className="h-6 w-6" />}
            title="还没有风险提示"
            description="登记共享事实后跑一次扫描，系统会按当前事实实算关联组并产出提示。🔴 这不等于「没有关联风险」，只说明还没判定过。"
          />
        ) : (
          <div className="space-y-2">
            {alerts.map((alert) => (
              <div
                key={alert.alertId}
                className="border-control-border space-y-1 rounded-md border px-3 py-2"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={severityTone(alert.severity)}>{severityLabel(alert.severity)}</Badge>
                  <Badge tone={alertStatusTone(alert.status)}>{alertStatusLabel(alert.status)}</Badge>
                  <span className="text-foreground text-copy-13 font-medium">{alert.title}</span>
                  <Badge tone="neutral">{alert.shopCount} 家店铺</Badge>
                  <Button size="sm" variant="outline" onClick={() => void openAlert(alert.alertId)}>
                    详情
                  </Button>
                </div>
                <p className="text-copy-13 text-muted-foreground">{alert.summary}</p>
              </div>
            ))}
          </div>
        )}

        {selectedAlert && (
          <div className="border-control-border mt-3 space-y-2 rounded-md border px-3 py-2">
            <p className="text-copy-13 text-foreground font-medium">提示详情</p>
            <div className="text-copy-12 text-muted-foreground space-y-1">
              <p>· 店铺：{selectedAlert.shops.map((s) => s.shopCode).join('、')}</p>
              <p>· 维度：{selectedAlert.dimensions.map(dimensionLabel).join(' / ')}</p>
              {selectedAlert.acknowledgedAt && (
                <p>
                  · 已确认：{selectedAlert.acknowledgeReason}（{selectedAlert.acknowledgedAt}）
                </p>
              )}
              {selectedAlert.resolvedAt && (
                <p>· 已整改：{selectedAlert.resolutionNote}（{selectedAlert.resolvedAt}）</p>
              )}
              {selectedAlert.dismissedAt && (
                <p>· 已忽略：{selectedAlert.dismissReason}（{selectedAlert.dismissedAt}）</p>
              )}
            </div>
            {selectedAlert.remediations.length > 0 && (
              <div className="space-y-1">
                <p className="text-copy-13 text-foreground">整改建议</p>
                {selectedAlert.remediations.map((remediation) => (
                  <div key={remediation.remediationId} className="text-copy-12">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={remediationStatusTone(remediation.status)}>
                        {remediationStatusLabel(remediation.status)}
                      </Badge>
                      <span className="text-foreground">{remediation.title}</span>
                    </div>
                    <p className="text-muted-foreground">{remediation.detail}</p>
                  </div>
                ))}
              </div>
            )}
            {selectedAlert.actions.length > 0 && (
              <details className="text-copy-12 text-muted-foreground">
                <summary className="cursor-pointer">
                  处置留痕（{selectedAlert.actions.length} 条，不可改写）
                </summary>
                <div className="mt-1 space-y-1">
                  {selectedAlert.actions.map((action) => (
                    <p key={action.actionId}>
                      · {action.occurredAt} · {action.actorName} · {actionLabel(action.action)} ·{' '}
                      {action.fromStatus} → {action.toStatus} · {action.reason}
                    </p>
                  ))}
                </div>
              </details>
            )}
            {canActOnAlert(selectedAlert) && (
              <div className="space-y-2">
                <Input
                  placeholder="处置理由（必填）"
                  value={alertReason}
                  onChange={(e) => setAlertReason(e.target.value)}
                />
                <div className="flex gap-2">
                  <Button size="sm" onClick={() => void disposeAlert('acknowledge')} disabled={busy}>
                    确认
                  </Button>
                  <Button size="sm" onClick={() => void disposeAlert('resolve')} disabled={busy}>
                    已整改
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => void disposeAlert('dismiss')} disabled={busy}>
                    忽略
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => setSelectedAlert(null)}>
                    关闭
                  </Button>
                </div>
              </div>
            )}
          </div>
        )}
      </SectionCard>

      {/* ---------------- 整改建议 ---------------- */}
      <SectionCard
        title="整改建议"
        description="按维度生成；没有一条是「用模拟登录绕开关联」—— 本系统只做官方 API。"
        extra={<span className="text-copy-12 text-muted-foreground">{remediationTotal} 条</span>}
      >
        {remediationTotal === 0 ? (
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="还没有整改建议"
            description="整改建议在扫描产出风险提示时按维度生成。🔴 没跑过扫描就没有建议 —— 这不等于「不需要整改」。"
          />
        ) : (
          <div className="space-y-2">
            {remediations.map((remediation) => (
              <div
                key={remediation.remediationId}
                className="border-control-border space-y-1 rounded-md border px-3 py-2"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={remediationStatusTone(remediation.status)}>
                    {remediationStatusLabel(remediation.status)}
                  </Badge>
                  <span className="text-foreground text-copy-13 font-medium">{remediation.title}</span>
                  <Badge tone="neutral">{remediation.dimension}</Badge>
                </div>
                <p className="text-copy-13 text-muted-foreground">{remediation.detail}</p>
                {canActOnRemediation(remediation) && (
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => void decideRemediation(remediation, 'ACCEPTED')}
                      disabled={busy}
                    >
                      采纳
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => void decideRemediation(remediation, 'DONE')}
                      disabled={busy}
                    >
                      已完成
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => void decideRemediation(remediation, 'DISMISSED')}
                      disabled={busy}
                    >
                      忽略
                    </Button>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {scan && (
        <p className="text-copy-12 text-muted-foreground">
          上次扫描：{scan.shopsScanned} 家店铺 · {scan.linksFound} 条关联边 · {scan.clustersFound} 个关联组 ·
          新增提示 {scan.alertsCreated} 条 / 更新 {scan.alertsUpdated} 条 · 新增整改建议 {scan.remediationsCreated} 条
        </p>
      )}
    </div>
  );
}
