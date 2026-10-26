'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Notice, PageHeader, SectionCard } from '@starcon/ui';
import { Globe2, Languages, RotateCcw, Save, Search } from 'lucide-react';
import type { AccountLocaleCapabilities } from '../../../../../packages/api-contracts/src/index';
import {
  DEFAULT_DISPLAY_CONVENTION,
  REFERENCE_TIMEZONES,
  buildTimezoneCandidates,
  fetchAccountLocaleCapabilities,
  fetchPlatformSiteTimezones,
  filterTimezoneCandidates,
  formatDateTime,
  formatOffset,
  toDisplayConvention,
  type DisplayConvention,
  type PlatformSiteTimezone,
} from '@/lib/account-locale-model';
import { useAccountLocale } from '@/lib/use-account-locale';

/**
 * 1.4.2.1 语言与时区 —— 「语言与时区」设置页。
 *
 * <p>蓝图：「界面语言（中文/英文等）与展示时区，影响时间显示与日期分组，**不改变存储时区**。
 * 输入：用户选择；平台站点时区清单。输出：界面语言包与时间显示口径。」
 *
 * <p>这一页回答三个问题：
 *
 * <ol>
 *   <li><b>时间按哪个时区显示</b> —— 选一个 IANA 时区，保存后全站按它显示时间；</li>
 *   <li><b>这个时区从哪来</b> —— 平台站点时区（channel 的站点参数）与全部 IANA 时区
 *       是<b>两份不同的清单</b>，页面上分开标注，不混为一谈；</li>
 *   <li><b>界面语言能不能切</b> —— 如实回答：<b>当前不能</b>（本仓没有 i18n 运行时与语言包），
 *       并写清缺什么、归谁。</li>
 * </ol>
 *
 * <p><b>不提供语言切换下拉</b>：没有语言包时下拉里的「English」点了什么都不会变 ——
 * 那是被明令禁止的假开关。所以语言这一维只展示现状与缺口。
 *
 * <p>三态分开：加载中 / 读不到（说清原因）/ 正常。
 */
export function AccountLocaleWorkspace() {
  const locale = useAccountLocale();
  const [capabilities, setCapabilities] = useState<AccountLocaleCapabilities | null>(null);
  const [capabilityError, setCapabilityError] = useState('');
  const [platformSites, setPlatformSites] = useState<PlatformSiteTimezone[] | null>(null);
  const [platformError, setPlatformError] = useState('');
  const [selected, setSelected] = useState('');
  const [query, setQuery] = useState('');
  const [now, setNow] = useState<Date>(() => new Date());

  // 用户改过下拉之后，就不再被「服务端读回来的值」覆盖（否则一保存就被冲掉）。
  const [dirty, setDirty] = useState(false);

  const loadCapabilities = useCallback(async () => {
    setCapabilityError('');
    try {
      setCapabilities(await fetchAccountLocaleCapabilities());
    } catch (cause) {
      setCapabilityError(
        cause instanceof Error ? cause.message : '能力声明暂时读不到，无法说明当前可用范围',
      );
    }
  }, []);

  const loadPlatformSites = useCallback(async () => {
    setPlatformError('');
    try {
      setPlatformSites(await fetchPlatformSiteTimezones());
    } catch (cause) {
      // 拿不到平台站点清单 ≠ 平台站点没有时区：这是两件事，界面上要分开说。
      setPlatformSites([]);
      setPlatformError(
        cause instanceof Error
          ? cause.message
          : '平台站点时区清单暂时读不到（channel 服务不可达或缺少 channel.registry.read 权限）',
      );
    }
  }, []);

  useEffect(() => {
    void loadCapabilities();
    void loadPlatformSites();
  }, [loadCapabilities, loadPlatformSites]);

  // 预览用的当前时刻（每秒钟走一格）。
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  // 服务端读回来的时区：没改过就跟着走。
  useEffect(() => {
    if (!dirty && locale.timezone) setSelected(locale.timezone);
  }, [locale.timezone, dirty]);

  const convention: DisplayConvention = capabilities
    ? toDisplayConvention(capabilities.displayConvention)
    : DEFAULT_DISPLAY_CONVENTION;

  const candidates = useMemo(
    () =>
      buildTimezoneCandidates({
        platformSites: platformSites ?? [],
        current: selected || locale.timezone,
        defaultTimezone: locale.defaultTimezone,
        instant: now,
      }),
    [platformSites, selected, locale.timezone, locale.defaultTimezone, now],
  );

  const visible = useMemo(
    () => filterTimezoneCandidates(candidates, query),
    [candidates, query],
  );

  const siteCandidates = candidates.filter((candidate) => candidate.source === 'platform-site');
  const dirtySelection = selected !== locale.timezone;

  async function save() {
    await locale.save({ timezone: selected });
    setDirty(false);
  }

  async function reset() {
    await locale.reset();
    setDirty(false);
    setSelected(locale.defaultTimezone);
  }

  return (
    <div className="space-y-5">
      <PageHeader
        title="语言与时区"
        description="决定时间按哪个时区显示；只影响展示，存储时间一律按 timestamptz 记录"
        actions={
          <div className="flex gap-2">
            <Button
              variant="outline"
              onClick={() => void locale.reload()}
              disabled={!locale.ready || locale.saving}
            >
              刷新
            </Button>
            <Button variant="secondary" onClick={() => void reset()} disabled={locale.saving}>
              <RotateCcw className="mr-2 h-4 w-4" aria-hidden />
              恢复默认
            </Button>
            <Button onClick={() => void save()} disabled={locale.saving || !selected}>
              <Save className="mr-2 h-4 w-4" aria-hidden />
              保存
            </Button>
          </div>
        }
      />

      {locale.error ? (
        <Notice tone="danger" title="读取失败">
          {locale.error}
        </Notice>
      ) : null}
      {locale.notice ? (
        <Notice tone="success" title="已处理">
          {locale.notice}
        </Notice>
      ) : null}
      {locale.fallbackReason ? (
        <Notice tone="warning" title="已回落到默认设置">
          {locale.fallbackReason}
        </Notice>
      ) : null}
      {capabilityError ? (
        <Notice tone="danger" title="能力声明读不到">
          {capabilityError}
        </Notice>
      ) : null}

      <SectionCard
        title="展示时区"
        description="按这个时区显示全站时间；存储时间不变，跨时区协作看到的仍是同一绝对时刻"
      >
        <div className="space-y-4">
          <div className="text-copy-13 flex flex-wrap items-center gap-2">
            <Globe2 className="text-muted-foreground h-4 w-4" aria-hidden />
            <span>
              当前生效：
              <span className="font-mono">{locale.timezone}</span>
              <span className="text-muted-foreground">
                （{formatOffset(now, locale.timezone) || '偏移未知'}）
              </span>
            </span>
            {locale.stored ? (
              <Badge tone="success">已保存</Badge>
            ) : (
              <Badge tone="neutral">未设置，用默认</Badge>
            )}
            {dirtySelection ? <Badge tone="warning">有未保存的改动</Badge> : null}
          </div>

          <div className="space-y-1.5">
            <label className="block text-sm font-medium" htmlFor="timezone-search">
              搜索时区
            </label>
            <input
              id="timezone-search"
              type="search"
              value={query}
              placeholder="输入城市或时区名，如 shanghai、new_york"
              onChange={(event) => setQuery(event.target.value)}
              className="border-control-border bg-card focus:ring-ring w-full min-w-0 rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2"
            />
          </div>

          <div className="space-y-1.5">
            <label className="block text-sm font-medium" htmlFor="timezone-select">
              选择展示时区
            </label>
            <select
              id="timezone-select"
              value={selected}
              onChange={(event) => {
                setSelected(event.target.value);
                setDirty(true);
              }}
              className="border-control-border bg-card focus:ring-ring w-full min-w-0 rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2"
            >
              {visible.map((candidate) => (
                <option key={candidate.id} value={candidate.id}>
                  {candidate.id}
                  {candidate.offset ? `（${candidate.offset}）` : ''}
                  {candidate.source === 'platform-site'
                    ? ` · 平台站点：${candidate.platformLabels.join('、')}`
                    : ''}
                </option>
              ))}
            </select>
            <p className="text-muted-foreground text-xs">
              共 {candidates.length} 个可选时区，当前过滤出 {visible.length} 个。
            </p>
          </div>

          <div className="space-y-1.5">
            <p className="block text-sm font-medium">平台站点时区</p>
            {platformError ? (
              <Notice tone="warning" title="平台站点时区清单暂时读不到">
                {platformError} —— 下面仍可选全部 IANA 时区，只是少了「平台站点」的标注。
              </Notice>
            ) : siteCandidates.length === 0 ? (
              <EmptyState
                title="没有读到平台站点时区"
                description="平台站点参数在 channel 服务（marketplace.default_timezone）。当前没有平台站点或清单读不到，不影响选择其它时区。"
              />
            ) : (
              <ul className="text-copy-13 space-y-1">
                {siteCandidates.map((candidate) => (
                  <li key={candidate.id} className="flex flex-wrap items-center gap-2">
                    <span className="font-mono">{candidate.id}</span>
                    <span className="text-muted-foreground">
                      {candidate.offset ? `（${candidate.offset}）` : ''}
                    </span>
                    <span className="text-muted-foreground">
                      {candidate.platformLabels.join('、')}
                    </span>
                  </li>
                ))}
              </ul>
            )}
            <p className="text-muted-foreground text-xs">
              平台站点时区来自 channel 服务的站点参数（只读）；其余候选来自浏览器的 IANA
              时区表 —— <b>后者不是</b>「平台站点时区清单」，只是全部可选时区。
            </p>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="界面语言">
        <div className="space-y-3">
          <div className="text-copy-13 flex flex-wrap items-center gap-2">
            <Languages className="text-muted-foreground h-4 w-4" aria-hidden />
            <span>
              当前界面语言：
              <span className="font-mono">{locale.locale}</span>
              <span className="text-muted-foreground">（简体中文）</span>
            </span>
            <Badge tone="neutral">只有中文可用</Badge>
          </div>
          <Notice tone="info" title="这里没有语言切换开关">
            本仓还没有 i18n 运行时与英文语言包，全站文案是硬编码中文。
            现在放一个「English」下拉，点了页面文案一点都不会变 —— 那是假开关，所以不做。
            <br />
            缺的是：① i18n 运行时（语言上下文 + 缺键回落）；② 语言包（zh-CN / en-US）；
            ③ 全站文案的 t() 化。归属：蓝图里没有这一格，属未立项能力（建议挂在 1.4.2
            账号偏好下或作为平台级基础能力单独立项）。
          </Notice>
        </div>
      </SectionCard>

      <SectionCard
        title="现在的几点"
        description="同一时刻在所选时区与几个参考时区下的样子；显示口径由服务端声明"
      >
        <div className="space-y-2">
          <p className="text-copy-13">
            <span className="font-mono">{selected || locale.timezone}</span>：{' '}
            <span className="font-medium">{formatDateTime(now, selected || locale.timezone, convention)}</span>
          </p>
          <ul className="text-copy-13 text-muted-foreground space-y-0.5">
            {REFERENCE_TIMEZONES.map((zone) => (
              <li key={zone}>
                <span className="font-mono">{zone}</span>：{formatDateTime(now, zone, convention)}
              </li>
            ))}
          </ul>
          <p className="text-muted-foreground text-xs">
            这里只改「怎么显示」，不改任何单据的业务日期口径 —— 订单属于哪一天由各单据自己定义。
          </p>
        </div>
      </SectionCard>

      <SectionCard title="这个模块做了什么、没做什么">
        {!capabilities ? (
          <p role="status" className="text-muted-foreground text-sm">
            正在读取能力声明…
          </p>
        ) : (
          <ul className="space-y-3">
            {capabilities.dimensions.map((dimension) => (
              <li key={dimension.key} className="space-y-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-mono text-sm">{dimension.key}</span>
                  {dimension.available ? (
                    <Badge tone="success">可用</Badge>
                  ) : (
                    <Badge tone="warning">当前不可用</Badge>
                  )}
                  {dimension.ownerModule ? (
                    <span className="text-muted-foreground text-xs">归属：{dimension.ownerModule}</span>
                  ) : null}
                </div>
                <p className="text-copy-13">{dimension.summary}</p>
                {dimension.missing.length > 0 ? (
                  <ul className="text-muted-foreground list-disc space-y-0.5 pl-5 text-xs">
                    {dimension.missing.map((item) => (
                      <li key={item}>缺：{item}</li>
                    ))}
                  </ul>
                ) : null}
              </li>
            ))}
          </ul>
        )}
        <ul className="text-muted-foreground mt-4 list-disc space-y-1 pl-5 text-sm">
          {capabilities?.boundaries.map((boundary) => (
            <li key={boundary}>{boundary}</li>
          ))}
        </ul>
      </SectionCard>
    </div>
  );
}
