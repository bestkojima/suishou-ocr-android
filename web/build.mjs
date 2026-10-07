import{build}from'esbuild';
import{mkdir,writeFile,cp,readFile}from'node:fs/promises';
const r=await build({entryPoints:['web/app.jsx'],bundle:true,minify:true,write:false,outdir:'web/dist',platform:'browser',target:['chrome100'],define:{'process.env.NODE_ENV':'"production"'},loader:{'.woff':'dataurl','.woff2':'dataurl','.ttf':'dataurl'},legalComments:'eof'});
await mkdir('web/dist',{recursive:true});let js='',css='';for(const f of r.outputFiles){if(f.path.endsWith('.js'))js=f.text;if(f.path.endsWith('.css'))css=f.text;}
await writeFile('web/dist/app.js',js);await writeFile('web/dist/app.css',css);
await writeFile('web/dist/index.html',`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta http-equiv="Content-Security-Policy" content="default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https://appassets.androidplatform.net; font-src 'self' data:; connect-src 'self' data: blob:; object-src 'none';"><title>随手识别 · 离线文档识别</title><link rel="stylesheet" href="app.css"></head><body><div id="root"></div><script src="app.js"></script></body></html>`);
await mkdir('app/src/main/assets',{recursive:true});await cp('web/dist','app/src/main/assets/web',{recursive:true});await cp('prototypes/ocr-streaming/fixtures','app/src/main/assets/fixtures',{recursive:true});
const pkg=JSON.parse(await readFile('package.json','utf8'));await writeFile('web/dist/build-info.json',JSON.stringify({dependencies:pkg.dependencies,builtAt:new Date().toISOString()},null,2));
console.log('Built browser preview and Android offline assets');
