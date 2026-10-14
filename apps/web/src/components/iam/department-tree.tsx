'use client';
import { useState } from 'react';
import { Button } from '@starcon/ui';
import type { DepartmentView } from '../../../../../packages/api-contracts/src/index';
import { canDropDepartment } from './administration-model';

/** 提升为根部门的投放区，用一个不会与部门 id 混淆的哨兵值。 */
const ROOT_TARGET = '__root__';

/**
 * 17.2.1.1 部门树。
 *
 * 拖拽用原生 HTML5 drag & drop（apps/web 没有装树/拖拽库，不擅自引入）。
 * 非法目标（自己或自己的子孙）在拖动过程中就变暗并给出提示，放下时也有明确反馈，
 * 不静默失败——后端会用递归查询再判一次，这里只负责即时反馈。
 */
export function DepartmentTree({
  nodes,
  busy,
  canUpdate,
  canDelete,
  onMove,
  onEdit,
  onDelete,
}: {
  nodes: DepartmentView[];
  busy: boolean;
  canUpdate: boolean;
  canDelete: boolean;
  onMove: (id: string, parentId: string | null) => void;
  onEdit: (id: string) => void;
  onDelete: (id: string) => void;
}) {
  const [dragging, setDragging] = useState<string | null>(null);
  const [target, setTarget] = useState<string | null>(null);
  const [rejected, setRejected] = useState('');

  if (nodes.length === 0)
    return <p className="rounded-lg border p-6 text-sm">还没有部门，先新增一个根部门。</p>;

  function drop(targetId: string) {
    const dragged = dragging;
    setDragging(null);
    setTarget(null);
    setRejected('');
    if (!dragged || dragged === targetId) return;
    if (!canDropDepartment(nodes, dragged, targetId)) {
      setRejected('不能把部门移动到自身或它的下级部门下。');
      return;
    }
    onMove(dragged, targetId);
  }

  function branch(list: DepartmentView[], depth: number) {
    return list.map((node) => {
      const isDragging = dragging === node.id;
      const isTarget = target === node.id;
      const allowed = isTarget && dragging !== null && canDropDepartment(nodes, dragging, node.id);
      const blocked = isTarget && dragging !== null && !allowed;
      return (
        <li key={node.id} className="space-y-2">
          <div
            draggable={canUpdate && !busy && !isDragging}
            onDragStart={() => setDragging(node.id)}
            onDragEnd={() => {
              setDragging(null);
              setTarget(null);
            }}
            onDragOver={(event) => {
              if (!canUpdate || dragging === null) return;
              // 默认行为必须阻止，否则浏览器不会触发 drop
              event.preventDefault();
              event.dataTransfer.dropEffect = allowed ? 'move' : 'none';
              setTarget(node.id);
            }}
            onDragLeave={() => setTarget((current) => (current === node.id ? null : current))}
            onDrop={(event) => {
              event.preventDefault();
              drop(node.id);
            }}
            aria-label={`部门 ${node.name}，${node.memberCount} 名成员`}
            className={[
              'flex flex-wrap items-center justify-between gap-3 rounded-lg border p-3 transition',
              depth === 0 ? 'bg-card' : 'bg-muted/40',
              isDragging ? 'opacity-40' : 'opacity-100',
              isTarget
                ? allowed
                  ? 'border-brand bg-brand/5'
                  : 'border-destructive'
                : 'border-control-border',
              blocked ? 'cursor-not-allowed' : '',
            ].join(' ')}
            style={{ marginInlineStart: depth * 20 }}
          >
            <div className="min-w-0">
              <p className="break-words font-medium">
                {node.name}
                <span className="text-muted-foreground ml-2 text-xs">{node.code}</span>
              </p>
              <p className="text-muted-foreground mt-1 text-xs">
                {/* 成员分布：后端只统计直接挂靠本部门的人，不含子部门 */}
                {node.memberCount} 名成员
                {node.legalEntityName ? ` · 所属法人 ${node.legalEntityName}` : ' · 未设所属法人'}
                {node.leaderName ? ` · 负责人 ${node.leaderName}` : ' · 未指定负责人'}
                {node.children.length > 0 ? ` · ${node.children.length} 个子部门` : ''}
              </p>
              {blocked && (
                <p className="text-destructive mt-1 text-xs">
                  非法目标：不能移动到自己或自己的下级。
                </p>
              )}
            </div>
            <div className="flex flex-wrap gap-2">
              {canUpdate && (
                <Button size="sm" variant="outline" disabled={busy} onClick={() => onEdit(node.id)}>
                  编辑
                </Button>
              )}
              {canDelete && (
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={busy}
                  onClick={() => {
                    if (
                      window.confirm(
                        '确认删除此部门？有子部门或仍有成员挂靠时会被拒绝，需要先调整层级或成员归属。',
                      )
                    )
                      onDelete(node.id);
                  }}
                >
                  删除
                </Button>
              )}
            </div>
          </div>
          {node.children.length > 0 && (
            <ul className="border-control-border mt-2 space-y-2 border-s-2 ps-3">
              {branch(node.children, depth + 1)}
            </ul>
          )}
        </li>
      );
    });
  }

  return (
    <div className="space-y-3">
      {canUpdate && (
        <div
          onDragOver={(event) => {
            if (!canUpdate || dragging === null) return;
            event.preventDefault();
            event.dataTransfer.dropEffect = 'move';
            setTarget(ROOT_TARGET);
          }}
          onDragLeave={() => setTarget((current) => (current === ROOT_TARGET ? null : current))}
          onDrop={(event) => {
            event.preventDefault();
            const dragged = dragging;
            setDragging(null);
            setTarget(null);
            setRejected('');
            if (dragged) onMove(dragged, null);
          }}
          className={[
            'rounded-lg border border-dashed p-3 text-center text-xs transition',
            target === ROOT_TARGET ? 'border-brand bg-brand/5' : 'border-control-border',
            dragging === null ? 'text-muted-foreground' : 'text-foreground',
          ].join(' ')}
        >
          拖到这里可把部门提升为根部门（当前选中：{dragging ? '拖动中的部门' : '无'}）
        </div>
      )}
      {rejected && (
        <p role="alert" className="text-destructive text-sm">
          {rejected}
        </p>
      )}
      <ul className="space-y-2">{branch(nodes, 0)}</ul>
    </div>
  );
}
