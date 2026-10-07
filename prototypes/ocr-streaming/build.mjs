import { build } from 'esbuild';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
const result = await build({ entryPoints: ['app.jsx'], bundle: true, minify: true,
  write: false, outdir: 'dist', platform: 'browser', target: ['chrome110'],
  define: { 'process.env.NODE_ENV': '"production"' },
  loader: { '.woff': 'dataurl', '.woff2': 'dataurl', '.ttf': 'dataurl' },
  legalComments: 'eof' });
let script = '', styles = '';
for (const file of result.outputFiles) {
  if (file.path.endsWith('.js')) script += file.text;
  if (file.path.endsWith('.css')) styles += file.text;
}
const html = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none';"><title>真实 OCR JSON · 流式渲染验证</title><style>${styles}</style></head><body><div id="app"></div><script>${script.replace(/<\/script/gi, '<\\/script')}</script></body></html>`;
await mkdir('dist', { recursive: true });
await writeFile('dist/ocr-streaming-prototype.html', html);
await writeFile('dist/index.html', html);
const versions = JSON.parse(await readFile('package.json', 'utf8'));
await writeFile('dist/build-info.json', JSON.stringify({ builtAt: new Date().toISOString(), bytes: Buffer.byteLength(html), dependencies: versions.dependencies, offline: true }, null, 2));
console.log(`Built offline HTML: ${(Buffer.byteLength(html)/1024/1024).toFixed(2)} MiB (includes real images, renderer, math fonts)`);
