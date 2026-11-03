/**
 * api-proxy 白名单的**真实条目**解析器（测试侧共用）。
 *
 * <p>为什么需要它：白名单只在浏览器端生效，`pnpm test` 直连服务层天然测不出漏登记 ——
 * 漏登记的路径在浏览器里静默 404，而 typecheck / lint / vitest / Playwright 全绿。
 * 所以判据是**真跑 proxy 断言 HTTP 状态码**，不是眼看正则。
 *
 * <p>但有一类断言**不能**用 HTTP 状态码表达：**「本车道的条目不吃兄弟车道的形状」**。
 * 兄弟车道并入之前，兄弟的形状在 proxy 上确实是 404（没有条目放行它）；
 * 兄弟车道并入之后，它被**兄弟自己的条目**放行 ⇒ 200。
 * 同一个「本车道不吃它」的语义，HTTP 状态码在并入前后**含义相反** ⇒
 * 拿状态码写这条断言，等于把断言绑死在一个分支拓扑上（并入即红）。
 *
 * <p>正确的表达方式是**在正则层断言**：本车道的 pattern 对兄弟的形状必须**不匹配**。
 * 这条性质与「兄弟车道是否已并入」无关 ⇒ 恒成立、恒有意义。
 *
 * <p>⚠️ **为什么用 TypeScript 编译器解析 AST**（而不是文本正则）：
 * api-proxy.ts 是并行车道共同的累积型文件，里面大量**注释**也写着
 * {@code prefix:} / {@code pattern:} 字样 —— 文本正则会把注释里的字面量
 * 也当成真条目，测出来的结论全是假的。解析 AST 才拿得到真的那一份。
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import ts from 'typescript';

export interface ProxyEntry {
  /** 条目的服务前缀（如 {@code platform/}）。 */
  prefix: string;
  /** 条目的真实正则（按 {@code resolve()} 的同构口径构造）。 */
  pattern: RegExp;
  /** 正则本体（不含首尾斜杠与 flags），用于按来源定位条目。 */
  source: string;
}

const API_PROXY = join(__dirname, 'api-proxy.ts');

/**
 * 从正则字面量里取出**模式本体**。
 *
 * <p>⚠️ TypeScript 的 {@code RegularExpressionLiteral.text} 给的是**带首尾斜杠**的原文
 * （{@code /^ai\/templates…$/}）—— 直接拿去 {@code new RegExp()} 会把那个开头的斜杠
 * 当成字面量去匹配，于是**每条路径都不匹配**，测出来的结论全是假的。
 * 这里按「最后一个**未被转义**的斜杠」切掉尾部（连同 flags），并去掉开头的斜杠。
 */
function regexSource(raw: string): string {
  const body = raw.startsWith('/') ? raw.slice(1) : raw;
  for (let i = body.length - 1; i >= 0; i--) {
    if (body[i] === '/' && body[i - 1] !== '\\') return body.slice(0, i);
  }
  return body;
}

/**
 * 从 api-proxy.ts 的**真实数组字面量**里取出全部条目。
 *
 * @param file 默认为 api-proxy.ts 自身所在目录下的那一份。
 */
export function proxyEntries(file: string = API_PROXY): ProxyEntry[] {
  const source = readFileSync(file, 'utf8');
  const parsed = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  let literal: ts.ArrayLiteralExpression | undefined;
  parsed.forEachChild((node) => {
    if (!ts.isVariableStatement(node)) return;
    for (const declaration of node.declarationList.declarations) {
      if (!ts.isIdentifier(declaration.name)) continue;
      if (declaration.name.text !== 'services') continue;
      if (declaration.initializer && ts.isArrayLiteralExpression(declaration.initializer)) {
        literal = declaration.initializer;
      }
    }
  });
  if (!literal) throw new Error('未能从 api-proxy.ts 里定位 services 变量的数组字面量');

  const out: ProxyEntry[] = [];
  for (const element of literal.elements) {
    if (!ts.isObjectLiteralExpression(element)) continue;
    let prefix = '';
    let pattern = '';
    for (const property of element.properties) {
      if (!ts.isPropertyAssignment(property) || !ts.isIdentifier(property.name)) continue;
      if (property.name.text === 'prefix' && ts.isStringLiteral(property.initializer)) {
        prefix = property.initializer.text;
      }
      if (property.name.text === 'pattern' && ts.isRegularExpressionLiteral(property.initializer)) {
        pattern = regexSource(property.initializer.text);
      }
    }
    if (!prefix || !pattern) continue;
    out.push({ prefix, pattern: new RegExp(pattern), source: pattern });
  }
  if (out.length === 0) throw new Error('未能从 api-proxy.ts 里解析出任何真实条目');
  return out;
}

/**
 * {@code resolve()} 的**同构口径**：先按 prefix 收窄服务，再测正则。
 *
 * <p>与 api-proxy.ts 里的 {@code resolve()} 完全一致 —— 口径不同，测出来的结论就是假的。
 */
export function matchingEntries(path: string, all: ProxyEntry[]): ProxyEntry[] {
  return all.filter(
    (entry) => path.startsWith(entry.prefix) && entry.pattern.test(path.slice(entry.prefix.length)),
  );
}

/**
 * 按正则**来源前缀**定位属于某车道的条目（如 {@code ^ai\/templates}）。
 *
 * <p>用于「本车道的那一条」这种按来源认领的场合 —— api-proxy.ts 里同一服务
 * （{@code platform/}）有很多条条目，光看 prefix 分不出谁是谁。
 */
export function entriesBySource(all: ProxyEntry[], sourcePrefix: string): ProxyEntry[] {
  return all.filter((entry) => entry.source.startsWith(sourcePrefix));
}
