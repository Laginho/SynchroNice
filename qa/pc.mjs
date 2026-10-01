import { readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';

let browser;
try {
  let input = '';
  for await (const chunk of process.stdin) input += chunk;
  const request = JSON.parse(input.replace(/^\uFEFF/, ''));
  if (request.action === 'list-files') {
    let entries;
    try { entries = await readdir(request.folder, { withFileTypes: true }); }
    catch (error) { if (error.code !== 'ENOENT') throw error; entries = []; }
    console.log(JSON.stringify(entries.filter(entry => entry.isFile()).map(entry => join(request.folder, entry.name))));
  } else if (request.action === 'presence') {
    const { token } = JSON.parse(await readFile(request.config, 'utf8'));
    if (!token) throw new Error('config.json sem token');
    const response = await fetch('http://127.0.0.1:47820/listen', {
      method: 'HEAD', headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(5000),
    });
    if (![200, 204].includes(response.status)) throw new Error(`HEAD /listen: ${response.status}`);
    console.log(JSON.stringify({ status: response.status, online: response.status === 200 }));
  } else {
    const { chromium } = await import('playwright-core');
    browser = await chromium.connectOverCDP(`http://127.0.0.1:${request.port}`, { timeout: 10000 });
    const page = browser.contexts().flatMap(context => context.pages())
      .find(page => /^(https?:\/\/(tauri\.localhost|localhost)|tauri:)/.test(page.url()));
    if (!page) throw new Error('infraestrutura: janela Tauri nao encontrada no CDP');
    page.setDefaultTimeout(5000);
    let result = null;
    switch (request.action) {
      case 'click': await page.locator(request.selector).click(); break;
      case 'type': await page.locator(request.selector).fill(request.text); break;
      case 'key':
        if (request.selector) await page.locator(request.selector).press(request.key);
        else await page.keyboard.press(request.key);
        break;
      case 'paste-image':
      case 'paste-files':
        await page.bringToFront();
        await page.locator(request.selector || '#composer-input').focus();
        await page.keyboard.press('Control+v');
        break;
      case 'text': result = await page.locator(request.selector).allTextContents(); break;
      case 'screenshot': await page.screenshot({ path: request.path }); result = request.path; break;
      case 'invoke':
        result = await page.evaluate(({ command, args }) => window.__TAURI__.core.invoke(command, args), request);
        break;
      default: throw new Error(`Acao desconhecida: ${request.action}`);
    }
    console.log(JSON.stringify(result));
  }
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
} finally {
  // For a CDP connection this disconnects Playwright, leaving pc.exe alive.
  if (browser) await browser.close();
}
