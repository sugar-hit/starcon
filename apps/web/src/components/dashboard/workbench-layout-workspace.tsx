'use client';

import { useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState, PageHeader, SectionCard, Segmented } from '@starcon/ui';
import { ArrowDown, ArrowUp, GripVertical, LayoutGrid, RotateCcw } from 'lucide-react';
import {
  WORKBENCH_CARDS,
  WORKBENCH_TABS,
  type WorkbenchCard,
  type WorkbenchTabId,
} from './workbench-card-registry';
import { visibleWorkbenchCardsInTab } from './workbench-layout-model';
import { useWorkbenchLayout } from './use-workbench-layout';

/**
 * 1.4.1.1 工作台布局（卷 01 · L3 1.4 界面偏好）。
 *
 * <p>蓝图原文：「**职责**：用户拖拽调整指标卡顺序、隐藏不需要的卡片、选择默认落地 Tab。
 * **输入**：拖拽/显隐操作；系统可选卡片清单。
 * **输出**：个性化后的工作台布局；可重置为默认布局。
 * **关系**：上游 = 前端卡片注册表；下游 = 1.1 全部卡片渲染。
 * **布局是纯个人偏好，不进业务库。**」
 *
 * <p>本组件是**布局的编辑面 + 布局的渲染面**：
 *
 * <ul>
 *   <li>上半部「我的工作台」= 按当前布局渲染出来的卡片区（分区由「默认落地 Tab」决定）。</li>
 *   <li>下半部「布局设置」= 排序 / 显隐 / 默认落地 Tab / 恢复默认。</li>
 * </ul>
 *
 * <p><b>排序为什么同时给拖拽和上移下移两个动作</b>：HTML5 拖拽对键盘用户和读屏用户
 * 不可用（本仓既有的拖拽实现 —— 部门树、工作区 Tab —— 也都是纯鼠标路径）。
 * 所以每一行都额外提供「上移 / 下移」按钮：键盘用户用 Tab 走到按钮、回车即可，
 * 读屏用户听到的是「把『异常订单队列』上移」而不是一句没有上下文的「按钮」。
 *
 * <p><b>数据全部来自真实后端</b>：布局存在 identity 的 {@code user_preference}
 * （page_key = {@code dashboard.layout}）里，读不到时按系统默认布局继续渲染，**不白屏**。
 */
export function WorkbenchLayoutWorkspace() {
  const layout = useWorkbenchLayout();
  const [activeTab, setActiveTab] = useState<WorkbenchTabId | null>(null);
  const [dragging, setDragging] = useState<string | null>(null);
  const [dropTarget, setDropTarget] = useState<string | null>(null);

  // 第一次读回布局时落在「默认落地 Tab」上；之后用户点分区只是临时切换。
  useEffect(() => {
    if (!layout.ready) return;
    setActiveTab((current) => current ?? layout.state.defaultTab);
  }, [layout.ready, layout.state.defaultTab]);

  const tab: WorkbenchTabId = activeTab ?? layout.state.defaultTab;
  const visibleCards = useMemo(
    () => visibleWorkbenchCardsInTab(layout.state, tab),
    [layout.state, tab],
  );
  const orderedCards = useMemo(
    () =>
      layout.state.order
        .map((key) => WORKBENCH_CARDS.find((card) => card.key === key))
        .filter((card): card is WorkbenchCard => card !== undefined),
    [layout.state.order],
  );

  if (!layout.ready) {
    return <p className="text-copy-13 text-muted-foreground">正在读取你的工作台布局…</p>;
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="工作台布局"
        description="拖拽调整指标卡顺序、隐藏不需要的卡片、选择默认落地 Tab。布局只对你自己生效，换设备也会跟着你。"
        actions={
          <div className="flex items-center gap-2">
            {layout.saving ? (
              <Badge tone="info">正在保存…</Badge>
            ) : layout.stored ? (
              <Badge tone="success">已保存</Badge>
            ) : (
              <Badge tone="neutral">当前使用系统默认布局</Badge>
            )}
            <Button
              variant="outline"
              onClick={layout.reset}
              disabled={layout.saving}
              aria-label="恢复为系统默认布局"
            >
              <RotateCcw className="h-4 w-4" />
              <span className="ml-1">恢复默认</span>
            </Button>
          </div>
        }
      />

      {layout.error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {layout.error}
        </div>
      )}
      {layout.notice && (
        <p role="status" className="text-copy-12 text-muted-foreground">
          {layout.notice}
        </p>
      )}

      {/* ---------------- 我的工作台（按布局渲染） ---------------- */}
      <SectionCard
        title="我的工作台"
        description="这是按你的布局渲染出来的卡片区。点分区可以临时切换；「默认落地 Tab」决定下次进来先落在哪一段。"
        extra={
          <Segmented<WorkbenchTabId>
            value={tab}
            onChange={(value) => setActiveTab(value)}
            options={WORKBENCH_TABS.map((item) => ({ label: item.label, value: item.id }))}
            aria-label="工作台分区"
          />
        }
      >
        {visibleCards.length === 0 ? (
          <EmptyState
            icon={<LayoutGrid className="h-6 w-6" />}
            title={`「${WORKBENCH_TABS.find((item) => item.id === tab)?.label ?? ''}」这一段没有卡片`}
            description="在下面的「布局设置」里把这张卡显示出来，它就会出现在这里。"
          />
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {visibleCards.map((card) => (
              <WorkbenchCardTile key={card.key} card={card} />
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---------------- 布局设置 ---------------- */}
      <SectionCard
        title="布局设置"
        description="「系统可选卡片清单」是代码里的声明（改了它对所有人生效）；你在这里做的顺序与显隐是个人偏好，只存在你自己的账号里。"
      >
        <div className="space-y-5">
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-copy-13 font-medium">默认落地 Tab</span>
            <Segmented<WorkbenchTabId>
              value={layout.state.defaultTab}
              onChange={(value) => {
                layout.setDefaultTab(value);
                setActiveTab(value);
              }}
              options={WORKBENCH_TABS.map((item) => ({ label: item.label, value: item.id }))}
              aria-label="默认落地分区"
            />
          </div>

          <div className="space-y-2">
            <p className="text-copy-13 font-medium">卡片顺序与显隐</p>
            <p className="text-copy-12 text-muted-foreground">
              拖动左侧手柄可以换位；不方便拖拽时，用每一行的「上移 / 下移」按钮同样能排序。
            </p>
            <ul className="space-y-2">
              {orderedCards.map((card, index) => {
                const visible = !layout.state.hidden.includes(card.key);
                return (
                  <li
                    key={card.key}
                    draggable
                    onDragStart={() => setDragging(card.key)}
                    onDragEnd={() => {
                      setDragging(null);
                      setDropTarget(null);
                    }}
                    onDragOver={(event) => {
                      if (dragging === null) return;
                      // 默认行为必须阻止，否则浏览器不会触发 drop
                      event.preventDefault();
                      event.dataTransfer.dropEffect = 'move';
                      setDropTarget(card.key);
                    }}
                    onDragLeave={() =>
                      setDropTarget((current) => (current === card.key ? null : current))
                    }
                    onDrop={(event) => {
                      event.preventDefault();
                      const from = dragging;
                      setDragging(null);
                      setDropTarget(null);
                      if (from) layout.reorderCard(from, card.key);
                    }}
                    aria-label={`${card.label}，第 ${index + 1} 位`}
                    className={[
                      'flex flex-wrap items-center justify-between gap-3 rounded-lg border p-3 transition',
                      dragging === card.key ? 'opacity-40' : 'opacity-100',
                      dropTarget === card.key && dragging !== card.key
                        ? 'border-brand bg-brand/5'
                        : 'border-control-border',
                    ].join(' ')}
                  >
                    <div className="flex min-w-0 items-center gap-3">
                      <GripVertical
                        aria-hidden="true"
                        className="text-muted-foreground h-4 w-4 shrink-0 cursor-grab"
                      />
                      <div className="min-w-0">
                        <p className="text-copy-13 font-medium">
                          {card.label}
                          <span className="text-muted-foreground ml-2 text-xs">{card.module}</span>
                        </p>
                        <p className="text-copy-12 text-muted-foreground mt-0.5">{card.description}</p>
                      </div>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                      <label className="text-copy-12 flex items-center gap-1.5">
                        <input
                          type="checkbox"
                          checked={visible}
                          onChange={(event) => layout.setCardVisible(card.key, event.target.checked)}
                          aria-label={`在「${card.label}」卡片区显示这张卡`}
                        />
                        显示
                      </label>
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={index === 0}
                        onClick={() => layout.moveCard(card.key, -1)}
                        aria-label={`把「${card.label}」上移`}
                      >
                        <ArrowUp className="h-4 w-4" />
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={index === orderedCards.length - 1}
                        onClick={() => layout.moveCard(card.key, 1)}
                        aria-label={`把「${card.label}」下移`}
                      >
                        <ArrowDown className="h-4 w-4" />
                      </Button>
                    </div>
                  </li>
                );
              })}
            </ul>
          </div>
        </div>
      </SectionCard>
    </div>
  );
}

/** 工作台卡片区里的一张卡（点进去是归属系统里那个已交付的真实工作区）。 */
function WorkbenchCardTile({ card }: { card: WorkbenchCard }) {
  const Icon = card.icon;
  return (
    <Link
      href={card.href}
      className="border-border bg-card hover:border-control-border group rounded-lg border p-4 transition-colors"
    >
      <div className="text-copy-12 text-muted-foreground flex items-center justify-between gap-2">
        <span className="flex items-center gap-1.5">
          <Icon className="h-3.5 w-3.5" />
          {card.module}
        </span>
      </div>
      <p className="text-copy-16 mt-2 font-medium">{card.label}</p>
      <p className="text-copy-13 text-muted-foreground mt-1">{card.description}</p>
    </Link>
  );
}
