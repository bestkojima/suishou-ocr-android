import { chromium } from 'playwright';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
import assert from 'node:assert/strict';

const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/home/dr/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome', headless: true, args: ['--no-sandbox'] });
const report = { scope: 'Desktop Chromium offline browser simulation; not Android WebView or real inference', checks: [], errors: [], network: [], measurements: {} };
const page = await browser.newPage({ viewport: { width: 1180, height: 980 } });
page.on('pageerror', e => report.errors.push(e.message));
page.on('request', r => { if (/^https?:/.test(r.url())) report.network.push(r.url()); });
await mkdir('verification', { recursive: true });
async function check(name, fn) { await fn(); report.checks.push({ name, pass: true }); console.log('PASS', name); }
async function tickBlock() {
  const before = await page.evaluate(() => window.__ocrPrototype.state.index);
  await page.evaluate(() => window.__ocrPrototype.step());
  await page.waitForFunction(i => window.__ocrPrototype.state.index > i || window.__ocrPrototype.state.phase === 'complete', before);
}
async function finish() {
  while (await page.evaluate(() => window.__ocrPrototype.state.phase !== 'complete')) await tickBlock();
}
async function reset() {
  await page.getByRole('button', { name: '重置', exact: true }).click();
  await page.waitForFunction(() => window.__ocrPrototype.state.blocks.length === 0);
}
try {
  await page.goto(pathToFileURL(`${process.cwd()}/dist/ocr-streaming-prototype.html`).href);
  await page.getByRole('button', { name: '开始回放', exact: true }).waitFor();
  await page.waitForFunction(() => !document.querySelector('.app-actions .primary').disabled);
  await check('真实样本与输入原图加载', async () => {
    assert.equal(await page.evaluate(() => window.__ocrPrototype.source.blocks.length), 16);
    assert.equal(await page.locator('.source-pane img').evaluate(img => img.complete && img.naturalWidth > 0), true);
  });
  await check('定时增量输出及暂停后不再增长', async () => {
    await page.getByRole('button', { name: '开始回放', exact: true }).click();
    await page.waitForFunction(() => window.__ocrPrototype.state.blocks[0]?.count >= 16);
    await page.getByRole('button', { name: '暂停输出', exact: true }).click();
    const counts = await page.evaluate(() => window.__ocrPrototype.state.blocks.map(b => b.count));
    await page.waitForTimeout(250);
    assert.deepEqual(await page.evaluate(() => window.__ocrPrototype.state.blocks.map(b => b.count)), counts);
    assert.ok((await page.locator('.markdown-content').first().textContent()).length > 0);
  });
  await reset();
  await check('完成区域复用原 DOM；后续文本不挪动已完成区域', async () => {
    await tickBlock();
    await page.evaluate(() => { window.firstNode = document.querySelector('[data-block-id]'); window.firstY = window.firstNode.offsetTop; });
    await tickBlock(); await tickBlock();
    assert.equal(await page.evaluate(() => window.firstNode === document.querySelector('[data-block-id]')), true);
    assert.equal(await page.evaluate(() => window.firstY === window.firstNode.offsetTop), true);
  });
  await check('真实 HTML 表格由渲染器显示', async () => {
    assert.ok(await page.locator('.document-body table td').count() >= 20);
    assert.ok((await page.locator('.document-body table').textContent()).includes('时间'));
  });
  await page.getByRole('button', { name: '图片延迟 2.4 秒', exact: true }).click();
  await check('延迟图片预留尺寸，加载后后续区域位移不超过 1px', async () => {
    for (let n = 0; n < 6; n++) await tickBlock();
    const frame = page.locator('[data-image]').first();
    await frame.waitFor();
    assert.notEqual(await frame.getAttribute('data-image-state'), 'loaded');
    const before = await page.locator('[data-block-id="b0016"]').evaluate(el => el.offsetTop);
    const h1 = await frame.evaluate(el => el.getBoundingClientRect().height);
    assert.ok(h1 > 50);
    await page.waitForFunction(() => document.querySelector('[data-image]')?.dataset.imageState === 'loaded');
    const after = await page.locator('[data-block-id="b0016"]').evaluate(el => el.offsetTop);
    const h2 = await frame.evaluate(el => el.getBoundingClientRect().height);
    report.measurements.delayedImageFollowingBlockShiftPx = Math.abs(after - before);
    assert.ok(Math.abs(after - before) <= 1); assert.ok(Math.abs(h2 - h1) <= 1);
  });
  await check('点击真实图片放大并关闭', async () => {
    await page.locator('.image-expand').first().click();
    await page.locator('dialog[open]').waitFor();
    assert.equal(await page.locator('dialog img').evaluate(i => i.naturalWidth > 0), true);
    await page.getByRole('button', { name: '关闭', exact: true }).click();
  });
  await check('上滑后流式更新不强制拉回底部', async () => {
    await page.locator('.reader').hover();
    await page.mouse.wheel(0, -300);
    await page.waitForTimeout(150);
    assert.equal(await page.evaluate(() => window.__ocrPrototype.following), false);
    const top = await page.locator('.reader').evaluate(el => el.scrollTop);
    await tickBlock(); await tickBlock();
    const end = await page.locator('.reader').evaluate(el => el.scrollTop);
    report.measurements.readingScrollDriftPx = Math.abs(top - end);
    assert.ok(Math.abs(top - end) <= 1);
  });
  await check('旋转保留任务与阅读块', async () => {
    const current = await page.evaluate(() => {
      const p = document.querySelector('.reader'), top = p.getBoundingClientRect().top;
      return [...p.querySelectorAll('[data-block-id]')].find(e => e.getBoundingClientRect().bottom > top + 8).dataset.blockId;
    });
    const count = await page.evaluate(() => window.__ocrPrototype.state.blocks.length);
    await page.getByRole('button', { name: '横屏', exact: true }).click();
    await page.waitForTimeout(200);
    const rotated = await page.evaluate(() => {
      const p = document.querySelector('.reader'), top = p.getBoundingClientRect().top;
      return [...p.querySelectorAll('[data-block-id]')].find(e => e.getBoundingClientRect().bottom > top + 8).dataset.blockId;
    });
    assert.equal(rotated, current); assert.equal(await page.evaluate(() => window.__ocrPrototype.state.blocks.length), count);
    assert.equal(await page.locator('.source-pane').isVisible(), true);
  });
  await finish();
  await page.waitForFunction(() => [...document.querySelectorAll('[data-image]')].every(e => e.dataset.imageState === 'loaded'));
  await check('完整回放与 JSON 内容一致，四张真实插图均加载', async () => {
    assert.equal(await page.locator('[data-image-state="loaded"]').count(), 4);
    assert.equal(await page.evaluate(() => window.__ocrPrototype.state.blocks.every((b,i) => b.raw === window.__ocrPrototype.source.blocks[i].markdown)), true);
  });
  await page.locator('.reader').evaluate(el => el.scrollTop = 0);
  await page.locator('.device').screenshot({ path: 'verification/landscape-table.png' });
  await page.getByRole('button', { name: '图片失败与重试', exact: true }).click();
  await check('图片失败占位与重试不改变后续区域位置', async () => {
    for (let n=0;n<6;n++) await tickBlock();
    await page.locator('[data-image-state="error"]').first().waitFor();
    const before = await page.locator('[data-block-id="b0016"]').evaluate(el => el.offsetTop);
    await page.getByRole('button', { name: '重试图片', exact: true }).first().click();
    await page.locator('[data-image-state="loaded"]').first().waitFor();
    const after = await page.locator('[data-block-id="b0016"]').evaluate(el => el.offsetTop);
    report.measurements.retryFollowingBlockShiftPx = Math.abs(after-before);
    assert.ok(Math.abs(after-before)<=1);
  });
  await page.getByRole('button', { name: '正常加载', exact: true }).click();
  await page.getByLabel('真实样本').selectOption('odb-09');
  await finish();
  await check('真实 LaTeX 公式渲染，保留原始 partial 内容', async () => {
    assert.ok(await page.locator('.document-body .katex').count() >= 5);
    assert.equal(await page.evaluate(() => window.__ocrPrototype.state.blocks.every((b,i) => b.raw === window.__ocrPrototype.source.blocks[i].markdown)), true);
  });
  await page.locator('.reader').evaluate(el => el.scrollTop = el.scrollHeight);
  await page.locator('.device').screenshot({ path: 'verification/landscape-formula.png' });
  await check('390px 手机布局无页面横向溢出', async () => {
    await page.setViewportSize({width:390,height:900});
    await page.getByRole('button', {name:'竖屏',exact:true}).click();
    await page.waitForTimeout(100);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    assert.equal(await page.locator('.source-pane').isVisible(),false);
    await page.locator('.device').screenshot({path:'verification/portrait-formula.png'});
  });
  await check('图片/字体/脚本不依赖外部网络，无 JavaScript 错误', async () => {
    assert.deepEqual(report.network, []); assert.deepEqual(report.errors, []);
  });
} catch (error) {
  report.failure = error.stack; console.error(error);
  await page.screenshot({path:'verification/failure.png',fullPage:true});
  process.exitCode = 1;
} finally {
  report.passed = !report.failure;
  await writeFile('verification/report.json',JSON.stringify(report,null,2));
  await browser.close();
}
