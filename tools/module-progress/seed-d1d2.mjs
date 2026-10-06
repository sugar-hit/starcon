#!/usr/bin/env node
/**
 * D1–D2 期基线播种（一次性）
 *
 * 这份基线不是拍脑袋填的，每一条「已交付」都对应四件可查的证据：
 *   迁移文件 + 服务端点 + 真实页面（非 DomainPlaceholder）+ 测试。
 * 判定偏保守：只有四件齐了才标「已交付」；**证据不足一律留「未开发」**，
 * 让后续开发任务去现场核实并补齐，而不是靠乐观估计把未做完的算成已完成。
 *
 * 已知阻塞项单独记录在 BLOCKERS 里，它们不是「还没做」，是「照现在的设计做不了」。
 *
 * 用法：node tools/module-progress/seed-d1d2.mjs
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..');
const LEDGER = join(ROOT, 'docs', 'product', 'capability-blueprint', 'module-progress.md');

/**
 * 阻塞项：不是「未开工」，是「按当前数据模型做不出来」。
 * 开发任务接手这些模块时，必须先修模型，不能在错误的表上继续加字段。
 */
const BLOCKERS = {
  '3.4.1.1':
    '阻塞：product_asset 是 `LIKE product_research INCLUDING ALL` 的同构复制（catalog V1:77-80），装不了文件键/图片处理元数据。须先拆表。',
  '3.7.1.1':
    '阻塞：product_compliance 同为 `LIKE product_research INCLUDING ALL` 复制，装不了 HS 编码与认证要素。须先拆表。',
};

/** [模块 ID, 证据] —— 均为 D1–D2 期真实交付，四件齐备 */
const DELIVERED = [
  // ── 02 渠道与店铺（channel V1+V2、/channels 真实工作区、38 tests）──────────
  ['2.1.1.1', 'channel V1 platform 码表 + GET /channel/platforms + /channels 平台目录段'],
  ['2.1.1.2', 'platform.capabilities 跨系统能力矩阵 + 端点返回 + /channels 展示'],
  ['2.1.1.3', 'marketplace 码表 + shop_marketplace 站点启停 + /channels 站点段'],
  ['2.1.2.1', 'platform_enum_mapping + GET /channel/platforms/{code}/enums'],
  ['2.2.1.1', 'channel_connection + GET /channel/connections + /channels 连接段'],
  ['2.2.1.2', 'POST /channel/connections 授权端点 + /channels 授权向导'],
  ['2.2.1.3', 'credential_ref + PUT /connections/{id}/credential；wire model 无明文令牌类型'],
  ['2.2.2.1', 'GET /connections/{id}/health 健康诊断 + /channels 诊断段'],
  ['2.2.2.2', 'GET /connections/reminders 到期提醒 + /channels 提醒展示'],
  ['2.3.1.1', 'shop 表 + GET /channel/shops + /channels 店铺列表段'],
  ['2.3.1.2', 'GET/PUT /channel/shops/{id} + /channels 店铺详情'],
  [
    '2.3.2.1',
    'shop.operating_mode CHECK(SELF_FULFILLED/FULL_CONSIGNMENT/SEMI_CONSIGNMENT/…) + 页面展示',
  ],
  ['2.3.2.2', 'shop.owner_id/department_id + channel_scope RLS(scopeMode) + 转让走显式端点'],
  ['2.4.1.1', 'channel V2 sync_cursor + GET /sync-cursors + /channels 同步运行段'],
  ['2.4.1.2', 'sync_run + GET /sync-runs 运行日志 + 失败原因分布'],
  ['2.4.1.3', '游标水位 + pause/resume/rewind 端点；游标仅 SUCCEEDED 推进'],
  ['2.4.2.1', 'sync_failure + GET /sync-failures + /sync-failures/summary'],
  ['2.4.2.2', 'POST /sync-failures/{id}/replay 人工重放（归属最初运行）'],
  ['2.5.1.1', 'platform_operation UNIQUE(tenant,idempotency_key) + GET /platform-operations'],
  ['2.5.1.2', 'POST /platform-operations/{id}/verify 未知结果核验（CONFIRMED_* 三选一）'],

  // ── 03 商品主数据（catalog V1、/products 真实工作区、11 tests）────────────
  ['3.1.1.1', 'spu 表 + GET /catalog/products + /products 商品工作区'],
  ['3.1.1.2', 'GET /catalog/products/{id} + PUT 更新 + 商品详情段'],
  [
    '3.2.2.1',
    'POST/PUT/DELETE /products/{id}/attributes + 属性录入与校验（OPTION/DECIMAL/BOOLEAN/DATE）',
  ],
  ['3.3.1.1', 'GET/POST/PUT /catalog/records/category 分类树 + 分类管理段'],

  // ── 17 组织权限（identity V1–V5、/org/* 真实工作区、13 tests）────────────
  ['17.1.1.2', 'child_tenants 表 + /children CRUD + /org/children 层级工作区'],
  ['17.1.2.1', 'identity V5 legal_entity + CRUD + 删除保护触发器 + /org/legal-entities'],
  ['17.2.1.1', 'department 表 + /departments CRUD + /org/departments 部门树'],
  ['17.2.2.1', 'member 表 + /members CRUD + /org/members 成员工作区'],
  ['17.3.1.1', 'role 表 + /roles CRUD + /org/roles 角色管理'],
  ['17.3.1.2', 'identity V1/V4 page_permission + GET /identity/permissions + 权限矩阵页'],
  ['17.3.2.2', 'catalog V3 record_data_scopes + RLS scopeMode 数据范围隔离'],
  ['17.3.3.1', 'RLS FORCE + isolation；三条断言实跑（互不可见/伪造头无效/归还不串租户）'],
  ['17.5.3.1', 'identity_audit 表 + 索引 + GRANT INSERT + 关键动作审计'],
];

const text = readFileSync(LEDGER, 'utf8');

const lines = text.split('\n');
let touched = 0;
let blocked = 0;

const next = lines
  .map((line) => {
    const m = line.match(
      /^\|\s*(\d+\.\d+\.\d+\.\d+)\s*\|([^|]*)\|([^|]*)\|([^|]*)\|\s*([^|]*?)\s*\|\s*([^|]*?)\s*\|$/,
    );
    if (!m) return line;
    const [, id, name, route, phase, status, evidence] = m;
    if (status !== '未开发') return line; // 不覆盖已有的非默认状态

    const blocker = BLOCKERS[id];
    if (blocker) {
      touched += 1;
      blocked += 1;
      return `| ${id.trim()} | ${name.trim()} | ${route.trim()} | ${phase.trim()} | 未开发 | ${blocker} |`;
    }

    const hit = DELIVERED.find(([mid]) => mid === id.trim());
    if (hit) {
      touched += 1;
      return `| ${id.trim()} | ${name.trim()} | ${route.trim()} | ${phase.trim()} | 已交付 | ${hit[1]} |`;
    }
    return line;
  })
  .join('\n');

writeFileSync(LEDGER, next);

const done = DELIVERED.length;
const total = (next.match(/^\|\s*\d+\.\d+\.\d+\.\d+\s*\|/gm) ?? []).length;
console.log(
  `✓ 播种 D1–D2 基线\n` +
    `  已交付 ${done} · 阻塞项 ${blocked} · 未开发 ${total - done - blocked} · 合计 ${total}`,
);
