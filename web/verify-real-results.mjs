import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,writeFile,readFile} from 'node:fs/promises';
import {unzipSync,strFromU8} from 'fflate';
const server=spawn('python3',['-m','http.server','4197','--bind','127.0.0.1','--directory','web/dist'],{stdio:'ignore'});
let browser;const results=[],errors=[];const out='verification/real-ocr';
try{
 for(let i=0;i<50;i++){try{if((await fetch('http://127.0.0.1:4197')).ok)break;}catch{}await new Promise(r=>setTimeout(r,100));}
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||'/home/dr/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome',args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:1000,height:950},acceptDownloads:true});page.on('pageerror',e=>errors.push(e.message));await page.goto('http://127.0.0.1:4197');
 await page.getByRole('button',{name:'设置',exact:true}).click();await page.locator('select').selectOption('64');await page.getByRole('dialog').getByRole('button',{name:'关闭',exact:true}).click();
 const importBundle=async name=>{const pick=page.waitForEvent('filechooser');await page.getByRole('button',{name:'导入文件',exact:true}).click();await(await pick).setFiles(`${out}/${name}.zip`);await page.getByRole('button',{name:'重新回放',exact:true}).waitFor({timeout:45000});};
 await importBundle('output');
 assert.equal(await page.locator('.region').count(),10);assert(await page.locator('.katex').count()>0);assert.equal(await page.locator('.markdown table').count(),1);assert.equal(await page.locator('.image-frame img').count(),1);
 await page.waitForFunction(()=>[...document.querySelectorAll('.image-frame img')].every(i=>i.complete&&i.naturalWidth>0));
 await page.locator('.reader').evaluate(e=>e.scrollTop=0);await page.screenshot({path:`${out}/preview-text.png`});await page.locator('.reader').evaluate(e=>e.scrollTop=e.scrollHeight);await page.screenshot({path:`${out}/preview-structured.png`});results.push('本轮真实中文、公式、表格、插图及原图正常展示');
 await page.locator('.edit-region').first().click();await page.getByRole('textbox',{name:'校对 Markdown'}).fill('## 校对后的新文档');await page.getByRole('button',{name:'保存修改',exact:true}).click();
 await page.getByRole('button',{name:'调整顺序',exact:true}).click();await page.getByRole('button',{name:'下移区域 1',exact:true}).first().click();await page.getByRole('button',{name:'保存顺序',exact:true}).click();
 await page.getByRole('button',{name:'导出 / 分享',exact:true}).click();const p=page.waitForEvent('download');await page.getByRole('button',{name:'完整文档 ZIP（含图片）',exact:true}).click();const download=await p;
 const bytes=await readFile(await download.path()),zip=unzipSync(new Uint8Array(bytes)),ir=JSON.parse(strFromU8(zip['document.json']));
 assert.equal(ir.schema_version,'1.10');assert.equal(ir.pages[0].reading_order[0],'b0002');assert(ir.pages[0].layout_blocks.length>0);assert(zip['original-document.json']);assert(strFromU8(zip['document.md']).includes('校对后的新文档'));
 await page.getByRole('button',{name:'返回首页',exact:true}).click();const pick=page.waitForEvent('filechooser');await page.getByRole('button',{name:'导入文件',exact:true}).click();await(await pick).setFiles({name:'真实输出往返.zip',mimeType:'application/zip',buffer:bytes});await page.getByRole('button',{name:'重新回放'}).waitFor({timeout:45000});await page.getByText('校对后的新文档',{exact:true}).waitFor();results.push('真实输出校对、排序、ZIP 原始元数据保留与再导入');
 await page.getByRole('button',{name:'返回首页',exact:true}).click();await importBundle('partial');assert(await page.locator('.warning').count()>0);await page.locator('.reader').evaluate(e=>e.scrollTop=e.scrollHeight);await page.screenshot({path:`${out}/preview-partial.png`});results.push('既有真实 partial 样本（provenance.json）与未完成区域提示');
 await page.setViewportSize({width:412,height:892});await page.screenshot({path:`${out}/preview-mobile.png`});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));assert.deepEqual(errors,[]);
 await writeFile(`${out}/browser.json`,JSON.stringify({environment:'Chromium；导入 Linux 真实输出后回放，不执行模型',results,errors},null,2));
 console.log(results);
}finally{await browser?.close();server.kill();}
