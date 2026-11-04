#!/usr/bin/env node
/**
 * 生成「域内已交付模块目录」。
 *
 * 做什么：
 *   1. 扫 apps/web/src/app 下所有带 page.tsx 的目录 —— 这是**唯一**的
 *      「哪些页面真的存在」的真相源。
 *   2. 从每个页面自己写的文档注释里抽出**模块号 + 模块名**（形如 `10.1.1.1 承运商列表`）。
 *   3. 按域（路由第一段）归组，写出 apps/web/src/lib/domain-modules.generated.ts。
 *
 * 为什么要有这个脚本：
 *   域首页曾经挂着一个「域占位页」—— 只有一张静态功能清单，没有业务数据，
 *   而域下面真实的模块页面（物流 27 个、CRM 23 个、仓储 15 个……）就躺在那儿。
 *   域首页要改成真实工作区，它列什么就得是**真的**。
 *
 *   手写清单必然漂移：今天写 27 个，下周加了页面就忘了补。
 *   而模块名与模块号是**页面自己文档注释里写着的**（人工维护的那一份），
 *   所以这里只做搬运，不做编造 —— 抽不到模块号的页面一律跳过，
 *   宁可少列，也不编一个名字出来。
 *
 * 用法：
 *   node tools/codegen/build-domain-modules.mjs          # 生成文件
 *   node tools/codegen/build-domain-modules.mjs --check   # 只校验，不写文件
 *
 * 与 contracts 的分工：
 *   catalog-contracts.py 从后端 OpenAPI 生成**接口**契约；
 *   本脚本从前端真实页面生成**页面目录**。两者互不覆盖。
 */

import { readFileSync, writeFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative, sep } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..', '..');
const APP_DIR = join(ROOT, 'apps', 'web', 'src', 'app');
const BLUEPRINT_DIR = join(ROOT, 'docs', 'product', 'capability-blueprint');
const OUT = join(ROOT, 'apps', 'web', 'src', 'lib', 'domain-modules.generated.ts');

/**
 * 不进目录的路由。
 *
 * - `[...slug]` 是兜底页（路径拼错才落到那儿），不是模块；
 * - `login` / `styleguide` 是系统页，不是业务模块；
 * - `api` 是 Next.js 的 API 路由目录，里面没有业务页面。
 */
const SKIP_SEGMENTS = new Set(['[...slug]', 'login', 'styleguide', 'api']);

/**
 * 抽模块号 + 模块名。
 *
 * 认的是页面自己文档注释的**第一行**，格式形如：
 *   `10.1.1.1 承运商列表。`
 *   `15.6.2.1 生命周期与流失预警（蓝图 15 卷 L3 锚点 15.6.2 下的生命周期子域）。`
 *   `5.1.1 价目表（含 5.1.1.1 价目表列表与 5.1.1.2 价目项批量维护）。`
 *   `14.1.1.2 活动详情 的路由 `/ads/campaigns/[id]`。`
 *   `14.4.1.2 优惠券与秒杀 —— 优惠券。`
 *
 * 名字只取到**第一个**截止符之前 —— 截止符有：句号、左括号、破折号、
 * 「的路由」（页面作者常把路由写在名字后面），以及换行。
 * 避免把「（蓝图 …）」「 —— 优惠券」这类补充说明吃进名字里。
 */
const DOC_RE = /(\d+(?:\.\d+){2,3})\s+([^（(\n。]{2,40})/;

/** 名字的截止符（按出现顺序取最先的那个）。 */
const NAME_STOP = [' —— ', ' 的路由', '。', '（', '(', '（', '\n'];

/** 从源码里抽出文档注释中的模块号与模块名；抽不到就返回 null（绝不编）。 */
export function parseModuleIdentity(source) {
  const head = source.slice(0, 2000);
  const m = DOC_RE.exec(head);
  if (!m) return null;
  const moduleId = m[1];
  let name = m[2];
  for (const stop of NAME_STOP) {
    const at = name.indexOf(stop);
    if (at >= 0) name = name.slice(0, at);
  }
  name = name.trim();
  if (!moduleId || !name) return null;
  return { moduleId, name };
}

/** 递归列出 apps/web/src/app 下所有含 page.tsx 的目录（相对路由）。 */
function listPageRoutes(dir, acc = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (!statSync(full).isDirectory()) continue;
    if (SKIP_SEGMENTS.has(entry)) continue;
    if (existsSync(join(full, 'page.tsx'))) {
      acc.push('/' + relative(APP_DIR, full).split(sep).join('/'));
    }
    listPageRoutes(full, acc);
  }
  return acc;
}

/**
 * 蓝图功能树里的一行：
 *   `├── 10.1 物流渠道  → /logistics/channels`
 *   `│   ├── 10.1.1 承运商管理`
 *   `│   │   ├── 10.1.1.1 承运商列表`
 *
 * 抽出：层级（L2 = 2 段、L3 = 3 段）、模块号、模块名、蓝图声明的路由。
 */
const TREE_RE =
  /(?:^|\n)[│\s]*(?:├──|└──)\s+(\d+(?:\.\d+){1,3})\s+([^（(\n。→]+?)(?:\s*→\s*(\S+))?[ \t]*(?=\n|$)/g;

/**
 * 解析蓝图功能树，得到 L3 / L2 的模块号与名字。
 *
 * 这些名字是**人工维护的蓝图原文**，不是这里生成的 —— 域首页要按能力域分组，
 * 分组名就得用蓝图里那一份，而不是按路由首段瞎分。
 */
export function parseBlueprintTree() {
  /** @type {Map<string, {id:string,name:string,sectionId:string}>} L3 → { id, name, sectionId } */
  const groups = new Map();
  /** @type {Map<string, {id:string,name:string}>} L2 → { id, name } */
  const sections = new Map();

  const files = readdirSync(BLUEPRINT_DIR)
    .filter((f) => /^\d\d-.*\.md$/.test(f))
    .sort();
  for (const file of files) {
    const raw = readFileSync(join(BLUEPRINT_DIR, file), 'utf8');
    for (const m of raw.matchAll(TREE_RE)) {
      const id = m[1];
      const name = m[2].trim();
      const depth = id.split('.').length;
      if (depth === 2) {
        if (!sections.has(id)) sections.set(id, { id, name });
      } else if (depth === 3) {
        if (!groups.has(id)) {
          groups.set(id, { id, name, sectionId: id.split('.').slice(0, 2).join('.') });
        }
      }
    }
  }
  return { groups, sections };
}

/**
 * 收集全部「域 → 已交付模块」清单。
 *
 * @returns {{ domains: Record<string, DomainModuleRow[]>, skipped: string[] }}
 */
export function collectDomainModules() {
  const routes = listPageRoutes(APP_DIR).sort();
  /** @type {Record<string, any[]>} */
  const domains = {};
  const skipped = [];
  const { groups, sections } = parseBlueprintTree();

  for (const route of routes) {
    const segments = route.split('/').filter(Boolean);
    const domain = segments[0];
    if (!domain) continue;
    const source = readFileSync(join(APP_DIR, route.replace(/^\//, ''), 'page.tsx'), 'utf8');
    const identity = parseModuleIdentity(source);
    if (!identity) {
      // 抽不到模块号的页面**不列**：列一个编出来的名字比不列更糟。
      skipped.push(route);
      continue;
    }
    const groupId = identity.moduleId.split('.').slice(0, 3).join('.');
    const sectionId = identity.moduleId.split('.').slice(0, 2).join('.');
    (domains[domain] ??= []).push({
      moduleId: identity.moduleId,
      name: identity.name,
      href: route,
      groupId,
      groupName: groups.get(groupId)?.name ?? groupId,
      sectionId,
      sectionName: sections.get(sectionId)?.name ?? sectionId,
    });
  }

  for (const list of Object.values(domains)) {
    list.sort((a, b) => a.moduleId.localeCompare(b.moduleId, 'en'));
  }

  // 同一个模块号在同一域下有多个页面时（模块被拆成两个路由），
  // 用**相对域根的路由**把两条区分开 —— 否则两张卡片长得一模一样，只差点进去的路由不同。
  // 注意：这里**不改 name** —— name 必须逐字等于页面自己文档注释里写的那个名字，
  // 否则「模块名来自页面文档」这条不变量就没法验了。
  for (const [domain, list] of Object.entries(domains)) {
    const byModule = new Map();
    for (const item of list) {
      const list2 = byModule.get(item.moduleId) ?? [];
      list2.push(item);
      byModule.set(item.moduleId, list2);
    }
    for (const group of byModule.values()) {
      if (group.length < 2) continue;
      for (const item of group) {
        const rest = item.href.slice(domain.length + 1);
        item.variant = rest || item.href;
      }
    }
  }

  return { domains, skipped };
}

/** 渲染成 TS 源文件。 */
export function renderModuleFile(domains) {
  const entries = Object.keys(domains)
    .sort()
    .map((domain) => {
      const rows = domains[domain]
        .map(
          (m) =>
            `    { moduleId: ${JSON.stringify(m.moduleId)}, name: ${JSON.stringify(m.name)}, href: ${JSON.stringify(m.href)},` +
            ` sectionId: ${JSON.stringify(m.sectionId)}, sectionName: ${JSON.stringify(m.sectionName)},` +
            ` groupId: ${JSON.stringify(m.groupId)}, groupName: ${JSON.stringify(m.groupName)}` +
            (m.variant ? `, variant: ${JSON.stringify(m.variant)}` : '') +
            ' },',
        )
        .join('\n');
      return `  ${JSON.stringify(domain)}: [\n${rows}\n  ],`;
    })
    .join('\n');

  return `/**
 * 本文件由 \`node tools/codegen/build-domain-modules.mjs\` 生成，请勿手工编辑。
 *
 * <p>内容是**域首页的模块目录**：
 *
 * <ul>
 *   <li>每个条目对应 {@code apps/web/src/app 下真实存在的页面}（路由 = href）；
 *   <li>{@code moduleId} 与 {@code name} 取自该页面**自己写的文档注释**，
 *       不是这里编出来的；
 *   <li>{@code sectionName} / {@code groupName} 取自功能蓝图的 L2 / L3 原文，
 *       同样不是这里编的；
 *   <li>抽不到模块号的页面**不会**出现在清单里 —— 宁可少列，也不编名字。
 * </ul>
 *
 * <p>重新生成：{@code node tools/codegen/build-domain-modules.mjs}
 * 校验是否同步：{@code node tools/codegen/build-domain-modules.mjs --check}
 */

/** 一个已交付模块：模块号、模块名、真实路由，以及它所属的能力域。 */
export interface DomainModule {
  /** 功能蓝图的 L3 / L4 模块号（如 \`10.1.1.1\`）。 */
  moduleId: string;
  /** 模块名（如「承运商列表」）。 */
  name: string;
  /** 真实路由（如 \`/logistics/carriers\`）。 */
  href: string;
  /** 所属 L2（如 \`10.1\`）。 */
  sectionId: string;
  /** 所属 L2 名称（如「物流渠道」）。 */
  sectionName: string;
  /** 所属 L3（如 \`10.1.1\`）。 */
  groupId: string;
  /** 所属 L3 名称（如「承运商管理」）。 */
  groupName: string;
  /**
   * 同一个模块号有多个页面时的区分标记（相对域根的路由）。
   *
   * <p>例如 7.8.2.2 同时挂在 {@code /inventory/stockout-alerts} 与
   * {@code /inventory/allocation/stockout-alerts} 两个路由上 ——
   * 那两条要能分得清，否则目录里会出现两张一模一样的卡片。
   */
  variant?: string;
}

/** 域 → 该域下真实存在的模块清单。 */
export const DOMAIN_MODULES: Record<string, DomainModule[]> = {
${entries}
};
`;
}

/**
 * 用仓库的 prettier 规则格式化生成物。
 *
 * <p>为什么必须在生成器里格式化：`pnpm format:check` 会检查**所有**文件，
 * 生成物也在其中；而同步守卫测试又要求「仓库里的文件」与「生成器的输出」逐字相同。
 * 两个要求叠在一起 ⇒ 生成器吐出来的就必须是 prettier 之后的样子，
 * 否则「跑一次生成器」立刻把格式检查打红。
 */
export async function formatOutput(content) {
  const prettier = await import('prettier');
  const config = await prettier.resolveConfig(OUT);
  return prettier.format(content, { ...(config ?? {}), filepath: OUT });
}

function main() {
  const check = process.argv.includes('--check');
  const { domains, skipped } = collectDomainModules();
  const total = Object.values(domains).reduce((sum, list) => sum + list.length, 0);

  return formatOutput(renderModuleFile(domains)).then((content) => {
    if (check) {
      if (!existsSync(OUT)) {
        console.error('生成物不存在，请运行：node tools/codegen/build-domain-modules.mjs');
        process.exit(1);
      }
      const current = readFileSync(OUT, 'utf8');
      if (current !== content) {
        console.error(
          '生成物与真实页面不一致，请运行：node tools/codegen/build-domain-modules.mjs',
        );
        process.exit(1);
      }
      console.log(`域模块目录已同步：${Object.keys(domains).length} 个域 / ${total} 个模块`);
      return;
    }

    writeFileSync(OUT, content);
    console.log(
      `已生成 ${relative(ROOT, OUT)}：${Object.keys(domains).length} 个域 / ${total} 个模块` +
        (skipped.length ? `（跳过 ${skipped.length} 个抽不到模块号的页面）` : ''),
    );
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main();
}
