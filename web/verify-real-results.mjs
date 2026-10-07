import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,writeFile,readFile} from 'node:fs/promises';
import {unzipSync,zipSync,strFromU8,strToU8} from 'fflate';
const server=spawn('python3',['-m','http.server','4197','--bind','127.0.0.1','--directory','web/dist'],{stdio:'ignore'});
let browser;const results=[],errors=[];const out='verification/real-ocr',evidence=process.env.OCR_EVIDENCE_DIR||out;
try{
 await mkdir(evidence,{recursive:true});
 for(let i=0;i<50;i++){try{if((await fetch('http://127.0.0.1:4197')).ok)break;}catch{}await new Promise(r=>setTimeout(r,100));}
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||'/home/dr/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome',args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:1000,height:950},acceptDownloads:true});page.on('pageerror',e=>errors.push(e.message));await page.goto('http://127.0.0.1:4197');
 await page.getByRole('button',{name:'设置',exact:true}).click();await page.locator('select').selectOption('64');await page.getByRole('dialog').getByRole('button',{name:'关闭',exact:true}).click();
 const importBundle=async name=>{const pick=page.waitForEvent('filechooser');await page.getByRole('button',{name:'导入文件',exact:true}).click();await(await pick).setFiles(`${out}/${name}.zip`);await page.getByRole('button',{name:'重新回放',exact:true}).waitFor({timeout:45000});};
 await importBundle('output');
 assert.equal(await page.locator('.region').count(),10);assert(await page.locator('.katex').count()>0);assert.equal(await page.locator('.markdown table').count(),1);assert.equal(await page.locator('.image-frame img').count(),1);
 await page.waitForFunction(()=>[...document.querySelectorAll('.image-frame img')].every(i=>i.complete&&i.naturalWidth>0));
 await page.locator('.reader').evaluate(e=>e.scrollTop=0);await page.screenshot({path:`${evidence}/preview-text.png`});await page.locator('.reader').evaluate(e=>e.scrollTop=e.scrollHeight);await page.screenshot({path:`${evidence}/preview-structured.png`});results.push('本轮真实中文、公式、表格、插图及原图正常展示');
 await page.locator('.edit-region').first().click();await page.getByRole('textbox',{name:'校对 Markdown'}).fill('## 校对后的新文档');await page.getByRole('button',{name:'保存修改',exact:true}).click();
 await page.getByRole('button',{name:'调整顺序',exact:true}).click();await page.getByRole('button',{name:'下移区域 1',exact:true}).first().click();await page.getByRole('button',{name:'保存顺序',exact:true}).click();
 await page.getByRole('button',{name:'导出 / 分享',exact:true}).click();const p=page.waitForEvent('download');await page.getByRole('button',{name:'完整文档 ZIP（含图片）',exact:true}).click();const download=await p;
 const bytes=await readFile(await download.path()),zip=unzipSync(new Uint8Array(bytes)),ir=JSON.parse(strFromU8(zip['document.json']));
 assert.equal(ir.schema_version,'1.10');assert.equal(ir.pages[0].reading_order[0],'b0002');assert(ir.pages[0].layout_blocks.length>0);assert(zip['original-document.json']);assert(strFromU8(zip['document.md']).includes('校对后的新文档'));
 await page.getByRole('button',{name:'返回首页',exact:true}).click();const pick=page.waitForEvent('filechooser');await page.getByRole('button',{name:'导入文件',exact:true}).click();await(await pick).setFiles({name:'真实输出往返.zip',mimeType:'application/zip',buffer:bytes});await page.getByRole('button',{name:'重新回放'}).waitFor({timeout:45000});await page.getByText('校对后的新文档',{exact:true}).waitFor();results.push('真实输出校对、排序、ZIP 原始元数据保留与再导入');
 await page.getByRole('button',{name:'导出 / 分享',exact:true}).click();const again=page.waitForEvent('download');await page.getByRole('button',{name:'完整文档 ZIP（含图片）',exact:true}).click();const secondZip=unzipSync(new Uint8Array(await readFile(await(await again).path())));
 const original=JSON.parse(await readFile(`${out}/output/document.json`,'utf8'));
 assert.deepEqual(JSON.parse(strFromU8(secondZip['original-document.json'])),original);
 assert.equal(JSON.parse(strFromU8(secondZip['document.json'])).pages[0].reading_order[0],'b0002');
 assert(strFromU8(secondZip['document.md']).includes('校对后的新文档'));results.push('再次导出保留首次原始 DocumentIR、当前顺序和校对');
 const inputZip=unzipSync(new Uint8Array(await readFile(`${out}/output.zip`)));
 for(const [path,bytes]of Object.entries(inputZip))if(path.startsWith('assets/')||path==='run-manifest.json'||path==='source.png'){
  assert.deepEqual(zip[path],bytes,`首次导出遗漏或修改 ${path}`);
  assert.deepEqual(secondZip[path],bytes,`再次导出遗漏或修改 ${path}`);
 }
 results.push('完整保存原生二进制资源、图片、原图和 run-manifest');
 await page.getByRole('button',{name:'返回首页',exact:true}).click();await importBundle('partial');assert(await page.locator('.warning').count()>0);assert((await page.locator('.document-notice').innerText()).includes('部分'));await page.locator('.reader').evaluate(e=>e.scrollTop=e.scrollHeight);await page.screenshot({path:`${evidence}/preview-partial.png`});results.push('既有真实 partial 样本（provenance.json）与未完成区域提示');
 await page.setViewportSize({width:412,height:892});await page.screenshot({path:`${evidence}/preview-mobile.png`});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));assert.deepEqual(errors,[]);
 await page.getByRole('button',{name:'返回首页',exact:true}).click();await importBundle('blank');
 assert((await page.locator('.empty').innerText()).includes('空白页'),'实际 empty_page 输出应明确为空白页');assert.equal(await page.locator('.document-notice').count(),0);results.push('实际 empty_page 输出明确显示为空白，不误报部分成功或持续准备');
 // 使用真实内容改写合法资源路径，检验 schema 变体；这不是新的模型推理。
 const variant=structuredClone(original),picture=variant.pages[0].blocks.find(b=>b.type==='image'),oldPath=picture.content.resource,newPath='images/figure.png';
 picture.content.resource=newPath;variant.resources.find(r=>r.path===oldPath).path=newPath;
 const variantFiles={...inputZip,'document.json':strToU8(JSON.stringify(variant)),[newPath]:inputZip[oldPath],'assets/':new Uint8Array(),'images/':new Uint8Array()};delete variantFiles[oldPath];
 await page.getByRole('button',{name:'返回首页',exact:true}).click();const variantPick=page.waitForEvent('filechooser');await page.getByRole('button',{name:'导入文件',exact:true}).click();await(await variantPick).setFiles({name:'真实内容资源路径变体.zip',mimeType:'application/zip',buffer:Buffer.from(zipSync(variantFiles))});await page.getByRole('button',{name:'重新回放'}).waitFor({timeout:45000});
 const image=page.locator(`.image-frame[data-resource="${newPath}"] img`);assert.equal(await image.count(),1,'合法的 assets 外图片资源应展示');
 await page.waitForFunction(path=>{const img=document.querySelector(`.image-frame[data-resource="${path}"] img`);return img?.complete&&img.naturalWidth>0;},newPath);
 await page.getByRole('button',{name:'返回首页',exact:true}).click();await page.reload();await page.getByRole('button',{name:'最近记录',exact:true}).click();await page.getByRole('button',{name:/真实内容资源路径变体/}).first().click();await image.waitFor();assert.equal(await image.count(),1);
 results.push('合法的 assets 外图片路径及刷新后历史重开保持可用');
 await page.getByRole('button',{name:'导出 / 分享',exact:true}).click();const variantDownload=page.waitForEvent('download');await page.getByRole('button',{name:'完整文档 ZIP（含图片）',exact:true}).click();const variantExport=unzipSync(new Uint8Array(await readFile(await(await variantDownload).path())));
 assert(!Object.keys(variantExport).some(path=>path.endsWith('/')),'目录项不能作为零字节资源文件导出');assert.deepEqual(variantExport[newPath],inputZip[oldPath]);
 const nativeState=JSON.parse(strFromU8(secondZip['app-state.json']));nativeState.pages[0].source='source.png';
 await page.getByRole('button',{name:'返回首页',exact:true}).click();const sourcePick=page.waitForEvent('filechooser');await page.getByRole('button',{name:'导入文件',exact:true}).click();await(await sourcePick).setFiles({name:'原图关联往返.zip',mimeType:'application/zip',buffer:Buffer.from(zipSync({...secondZip,'app-state.json':strToU8(JSON.stringify(nativeState))}))});await page.getByRole('button',{name:'重新回放'}).waitFor({timeout:45000});
 await page.getByRole('button',{name:'导出 / 分享',exact:true}).click();const sourceDownload=page.waitForEvent('download');await page.getByRole('button',{name:'完整文档 ZIP（含图片）',exact:true}).click();const sourceZip=unzipSync(new Uint8Array(await readFile(await(await sourceDownload).path()))),portable=JSON.parse(strFromU8(sourceZip['app-state.json']));
 assert.equal(portable.pages[0].source,'source.png');assert(sourceZip[portable.pages[0].source]);results.push('原生应用状态导入浏览器后再导出，页面原图仍使用有效本地路径');assert.deepEqual(errors,[]);
 await writeFile(`${evidence}/browser.json`,JSON.stringify({environment:'Chromium；导入 Linux 真实输出后回放，不执行模型',results,errors},null,2));
 console.log(results);
}finally{await browser?.close();server.kill();}
