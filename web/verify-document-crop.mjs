import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,writeFile,readFile} from 'node:fs/promises';
const server=spawn('python3',['-m','http.server','4198','--bind','127.0.0.1','--directory','web/dist'],{stdio:'ignore'});
let browser;const results=[],errors=[];
try{
 for(let i=0;i<50;i++){try{if((await fetch('http://127.0.0.1:4198')).ok)break;}catch{}await new Promise(r=>setTimeout(r,100));}
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||'/home/dr/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome',args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:430,height:900}});page.on('pageerror',e=>errors.push(e.message));
 const source='data:image/png;base64,'+(await readFile('verification/document-crop/algorithm/tilted-with-clutter.png')).toString('base64');
 const cropped='data:image/png;base64,'+(await readFile('verification/document-crop/algorithm/cropped.png')).toString('base64');
 await page.addInitScript(({source,cropped})=>{
  window.calls=[];window.photoCount=0;
  function photo(){return{id:'crop-'+(++window.photoCount),title:'拍摄照片',mode:'pending-ocr',blocks:[],assets:{},pages:[],input:'source.png',original:source,documentCrop:{state:'pending',sourceUrl:source,width:800,height:800,points:[[.18,.08],[.78,.13],[.85,.89],[.12,.84]],detected:true,message:'已检测文档边界，请核对四角后预览'},recognition:{state:'waiting',message:'请先确认文档裁剪，再开始识别'}};}
  window.AndroidHost={request(raw){const {id,method,args}=JSON.parse(raw);window.calls.push({method,args});let value=true,error;
   if(method==='bootstrap')value={settings:{chunk:8},history:[],testBuild:true};
   if(method==='capture')window.saved=photo();
   if(['capture','open','save'].includes(method))value=window.saved;
   if(method==='history')value=window.saved?[{...window.saved,count:0}]:[];
   if(method==='recognitionStatus')value=window.saved.recognition;
   if(method==='beginCrop'){window.saved.documentCrop={...window.saved.documentCrop,state:'pending',points:[[0,0],[1,0],[1,1],[0,1]],detected:false,message:'未检测到清晰文档边界，请拖动四角手动选框'};value=window.saved;}
   if(method==='previewCrop'){window.saved.documentCrop.points=args.points;value={token:'preview-'+window.calls.length,src:cropped,width:584,height:609};window.preview=value;}
   if(method==='confirmCrop'){if(args.token!==window.preview?.token)error='无效预览';else{window.saved.documentCrop.state='confirmed';window.saved.original=window.preview.src;window.saved.input='confirmed.png';window.saved.recognition={state:'recognizing',docId:window.saved.id,jobId:'crop-job',sequence:1,message:'已确认，正在识别裁剪图片'};value=window.saved;}}
   if(method==='recognize'&&window.saved.documentCrop.state!=='confirmed')error='尚未确认裁剪';
   setTimeout(()=>window.nativeReply({id,value:structuredClone(value),error}),1);
  }};
 },{source,cropped});
 await page.goto('http://127.0.0.1:4198');await page.getByRole('button',{name:'拍照',exact:true}).click();
 const editor=page.getByRole('dialog',{name:'调整文档裁剪'});await editor.waitFor();
 await mkdir('verification/document-crop',{recursive:true});await page.screenshot({path:'verification/document-crop/crop-portrait.png'});
 const svg=editor.locator('svg');await page.waitForFunction(()=>document.querySelector('.crop-canvas image')?.getAttribute('href')?.startsWith('data:'));
 assert.equal(await page.evaluate(()=>window.calls.filter(c=>['recognize','confirmCrop'].includes(c.method)).length),0);
 await editor.getByRole('button',{name:'调整左上角'}).focus();await page.keyboard.press('ArrowRight');
 // 使用键盘和真实指针验证同一组件；SVG 坐标随横竖屏缩放，不能按屏幕像素直接当原图坐标。
 const handle=await editor.getByRole('button',{name:'调整左上角'}).boundingBox();assert(handle);
 await page.mouse.move(handle.x+handle.width/2,handle.y+handle.height/2);await page.mouse.down();await page.mouse.move(handle.x+handle.width/2+20,handle.y+handle.height/2+20,{steps:4});await page.mouse.up();
 await editor.getByRole('button',{name:'预览裁剪',exact:true}).click();await editor.getByAltText('裁剪后的文档预览').waitFor();
 const moved=await page.evaluate(()=>window.calls.filter(c=>c.method==='previewCrop').at(-1).args.points);
 assert(moved[0][0]>.19&&moved[0][1]>.08);
 assert(await editor.getByRole('button',{name:'确认并识别',exact:true}).isEnabled());
 assert.equal(await page.evaluate(()=>window.calls.filter(c=>c.method==='confirmCrop').length),0);
 results.push('拍照后先调整和预览；确认前未启动识别；四角键盘/指针调整坐标正确');
 await editor.getByRole('button',{name:'返回调整',exact:true}).click();
 const polygon=svg.locator('polygon');const shape=await polygon.boundingBox();
 await page.mouse.move(shape.x+shape.width/2,shape.y+shape.height/2);await page.mouse.down();await page.mouse.move(shape.x+shape.width/2+10,shape.y+shape.height/2+10);await page.mouse.up();
 await editor.getByRole('button',{name:'预览裁剪',exact:true}).click();await editor.getByAltText('裁剪后的文档预览').waitFor();
 const translated=await page.evaluate(()=>window.calls.filter(c=>c.method==='previewCrop').at(-1).args.points);
 for(let i=1;i<4;i++)assert(Math.abs((translated[i][0]-moved[i][0])-(translated[0][0]-moved[0][0]))<1e-6);
 await editor.getByRole('button',{name:'取消裁剪',exact:true}).click();await editor.waitFor({state:'hidden'});
 assert(await page.getByRole('button',{name:'开始识别',exact:true}).isDisabled());
 await page.getByRole('button',{name:'返回首页',exact:true}).click();await page.getByRole('button',{name:'最近记录',exact:true}).click();await page.getByRole('button',{name:/拍摄照片/}).first().click();await editor.waitFor();
 assert.equal(await page.evaluate(()=>window.calls.filter(c=>c.method==='confirmCrop').length),0);
 results.push('整框移动保持形状；取消不识别；未确认照片可从历史继续裁剪');
 await editor.getByRole('button',{name:'重新检测',exact:true}).click();await editor.getByText('未检测到清晰文档边界，请拖动四角手动选框').waitFor();
 await editor.getByRole('button',{name:'使用整张照片',exact:true}).click();
 await page.setViewportSize({width:900,height:430});await editor.getByRole('button',{name:'预览裁剪',exact:true}).click();await editor.getByAltText('裁剪后的文档预览').waitFor();
 await page.screenshot({path:'verification/document-crop/crop-landscape-preview.png'});
 assert.deepEqual(await page.evaluate(()=>window.calls.filter(c=>c.method==='previewCrop').at(-1).args.points),[[0,0],[1,0],[1,1],[0,1]]);
 await editor.getByRole('button',{name:'确认并识别',exact:true}).click();await editor.waitFor({state:'hidden'});await page.getByText('已确认，正在识别裁剪图片',{exact:true}).waitFor();
 assert.equal(await page.evaluate(()=>window.calls.filter(c=>c.method==='confirmCrop').length),1);
 assert.equal(await page.evaluate(()=>window.calls.filter(c=>c.method==='confirmCrop').at(-1).args.token),await page.evaluate(()=>window.preview.token));
 results.push('检测失败回退全图；横屏预览与确认可用；确认使用最新预览令牌');
 await page.getByRole('button',{name:'返回首页',exact:true}).click();await page.getByRole('button',{name:'拍照',exact:true}).click();await editor.waitFor();await editor.getByRole('button',{name:'重新拍照',exact:true}).click();await editor.waitFor({state:'hidden'});
 assert(await page.getByRole('button',{name:'拍照',exact:true}).isVisible());assert.equal(await page.evaluate(()=>window.calls.filter(c=>c.method==='confirmCrop').length),1);
 results.push('重新拍照返回相机且不误确认或启动识别');
 assert.deepEqual(errors,[]);await mkdir('verification/document-crop',{recursive:true});await writeFile('verification/document-crop/ui.json',JSON.stringify({environment:'Chromium 模拟 Android 桥；使用生产算法生成的裁剪样本，非设备桥实测',results,errors},null,2));console.log('PASS',results);
}finally{await browser?.close();server.kill();}
