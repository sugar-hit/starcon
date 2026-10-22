import { chromium } from '@playwright/test';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { readdirSync, existsSync, mkdirSync } from 'node:fs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..', '..');
const url = process.env.PROGRESS_SITE_URL || 'http://127.0.0.1:8899/index.html';
const out = join(__dirname, '.shots');

/**
 * 优先用 playwright 自带的 chromium；装不上或版本对不上时，
 * 回落到本机 ms-playwright 缓存里已装的任意一个 headless shell。
 * 不写死版本号——版本号随 playwright 升级变，写死必烂。
 */
function resolveChromium() {
  // playwright 自带的路径在浏览器没下齐时**不会抛错**，只返回一个不存在的路径
  try {
    const p = chromium.executablePath();
    if (p && existsSync(p)) return p;
  } catch {
    /* 落到下面的扫描 */
  }
  const cache = join(process.env.HOME || '', 'Library', 'Caches', 'ms-playwright');
  if (!existsSync(cache)) return undefined;
  for (const dir of readdirSync(cache)) {
    if (!dir.startsWith('chromium_headless_shell')) continue;
    for (const rel of [
      ['chrome-headless-shell-mac-arm64', 'chrome-headless-shell'],
      ['chrome-headless-shell-mac-x64', 'chrome-headless-shell'],
      ['chrome-headless-shell-linux', 'chrome-headless-shell'],
    ]) {
      const p = join(cache, dir, ...rel);
      if (existsSync(p)) return p;
    }
  }
  return undefined;
}

const executablePath = resolveChromium();
const browser = await chromium.launch(executablePath ? { executablePath } : {});
const page = await browser.newPage({ viewport: { width: 1440, height: 950 } });

const errors = [];
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
page.on('console', (m) => {
  if (m.type() === 'error') errors.push('console: ' + m.text());
});

mkdirSync(out, { recursive: true });
await page.goto(url, { waitUntil: 'networkidle' });
await page.waitForSelector('.scard');

await page.screenshot({ path: join(out, '01-hero.png') });

await page.locator('#phase').scrollIntoViewIfNeeded();
await page.waitForTimeout(300);
await page.screenshot({ path: join(out, '02-phase.png') });

await page.locator('#systems').scrollIntoViewIfNeeded();
await page.waitForTimeout(300);
await page.screenshot({ path: join(out, '03-systems.png') });

// 打开一个已交付模块多的系统
await page.locator('.scard').nth(2).click();
await page.waitForSelector('#drawer:not([hidden])');
await page.waitForTimeout(400);
await page.screenshot({ path: join(out, '04-drawer.png') });

// 展开一个已交付模块看证据
const first = page.locator('.drawer__body .mod__btn').first();
await first.click();
await page.waitForTimeout(250);
await page.locator('.drawer__body .mod').first().scrollIntoViewIfNeeded();
await page.waitForTimeout(200);
await page.screenshot({ path: join(out, '05-module-evidence.png') });

// 工程现状
await page.keyboard.press('Escape');
await page.waitForTimeout(300);
await page.locator('#engineering').scrollIntoViewIfNeeded();
await page.waitForTimeout(300);
await page.screenshot({ path: join(out, '06-engineering.png') });

// 筛选联动
await page.locator('#systems').scrollIntoViewIfNeeded();
await page.locator('[data-status="已交付"]').click();
await page.waitForTimeout(300);
const note = await page.locator('#filter-note').innerText();
await page.screenshot({ path: join(out, '07-filter.png') });

// 移动端
const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } });
await mobile.goto(url, { waitUntil: 'networkidle' });
await mobile.waitForSelector('.scard');
await mobile.screenshot({ path: join(out, '08-mobile.png') });

console.log('filter note:', note.replace(/\s+/g, ' '));
console.log('errors:', errors.length ? errors : 'none');

await browser.close();
