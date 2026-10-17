import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,writeFile} from 'node:fs/promises';
const port=Number(process.env.OCR_TEST_PORT||4199),origin=`http://127.0.0.1:${port}`;
const server=spawn(process.env.PYTHON||(process.platform==='win32'?'python':'python3'),['-m','http.server',String(port),'--bind','127.0.0.1','--directory','web/dist'],{stdio:'ignore'});
let browser;const errors=[],results=[];
try {
 for(let i=0;i<50;i++){try{if((await fetch(origin)).ok)break;}catch{}await new Promise(r=>setTimeout(r,100));}
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||undefined,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:375,height:812}});page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  window.calls=[];window.job=0;window.unavailable='';window.modelsReady=true;window.delayStart=false;
  window.saved={id:'invoice',title:'发票区域校对',mode:'real-ocr',progress:2,status:'complete',blocks:[{id:'b1',type:'table',markdown:'旧表格',sourceStatus:'partial',resource:'assets/b1.png'},{id:'b2',type:'text',markdown:'其他区域',sourceStatus:'ok'}],edits:{b2:'已有人工校对'},assets:{},pages:[],recognition:{docId:'invoice',jobId:'page',state:'succeeded',sequence:1,liveOutput:true}};
  window.AndroidHost={request(raw){const {id,method,args}=JSON.parse(raw);window.calls.push({method,args});let value=true;
   if(method==='bootstrap')value={settings:{chunk:64},history:[]};
   if(method==='capture'||method==='open')value=window.saved;
   if(method==='history')value=[window.saved];
   if(method==='save'){const {blockEdit,...fields}=args;Object.assign(window.saved,fields);if(blockEdit)window.saved.edits[blockEdit.id]=blockEdit.text;value=window.saved;}
   if(method==='recognitionStatus')value=window.saved.recognition;
   if(method==='regionRecognitionOptions')value={models:[{id:'ovis',name:'Ovis',ready:window.modelsReady},{id:'glm',name:'GLM',ready:window.modelsReady}],defaultModel:'ovis',unavailable:window.unavailable,state:window.region};
   if(method==='recognizeRegion'){
    window.region={kind:'region',docId:args.id,blockId:args.blockId,modelChoice:args.model,jobId:`region-${++window.job}`,sequence:1,state:'recognizing',message:'正在识别此区域'};value=window.region;
    if(window.delayStart){window.replyStart=()=>window.nativeReply({id,value});return;}
   }
   if(method==='regionRecognitionStatus')value=window.region;
   if(method==='cancelRecognition')value=window.region={...window.region,state:'cancelled',sequence:99,message:'已取消区域识别，原内容已保留',result:undefined};
   setTimeout(()=>window.nativeReply({id,value:structuredClone(value)}),0);
  }};
  window.complete=(partial=false)=>{window.region={...window.region,sequence:5,state:partial?'partial':'succeeded',message:'新结果已替换旧输出',resultApplied:true,result:{markdown:'<table><tr><td>正确金额</td><td>100</td></tr></table>',sourceStatus:partial?'partial':'ok',stopReason:partial?'repetition':'normal'}};const block=window.saved.blocks.find(b=>b.id===window.region.blockId);Object.assign(block,{markdown:window.region.result.markdown,format:'markdown',sourceStatus:window.region.result.sourceStatus,regionRevision:window.region.jobId});window.saved.edits[block.id]=block.markdown;window.saved.regionRecognition=structuredClone(window.region);window.nativeEvent('recognition',window.region);};
 });
 await page.goto(origin);await page.getByRole('button',{name:'拍照',exact:true}).click();
 const open=async(index=0)=>{await page.locator('.edit-region').nth(index).click();await page.locator('.region-retry>summary').click();};
 const close=()=>page.getByRole('button',{name:'关闭',exact:true}).click();
 const editor=page.getByRole('textbox',{name:'校对 Markdown'}), start=page.getByRole('button',{name:'开始区域识别',exact:true});
 await open();assert.equal(await page.getByLabel('区域识别模型').inputValue(),'glm');
 await editor.fill('人工草稿');await start.click();await page.getByText('正在识别此区域',{exact:true}).waitFor();
 assert.deepEqual(await page.evaluate(()=>window.calls.find(c=>c.method==='recognizeRegion').args),{id:'invoice',blockId:'b1',model:'glm'});
 await page.evaluate(()=>window.complete(true));await page.waitForFunction(()=>document.querySelector('textarea[aria-label="校对 Markdown"]').value.includes('正确金额'));
 assert(!(await editor.inputValue()).includes('人工草稿'));assert(!(await page.locator('.reader').innerText()).includes('旧表格'));
 assert.equal(await page.locator('.reader table').count(),1);
 await page.getByText('检测到重复输出，已提前停止。请对照原图核对。',{exact:true}).waitFor();
 assert.equal(await page.locator('.region-retry-candidate table').count(),1);
 await mkdir('verification/region-replacement',{recursive:true});
 await page.screenshot({path:'verification/region-replacement/portrait.png'});
 await page.setViewportSize({width:1000,height:650});await page.screenshot({path:'verification/region-replacement/landscape.png'});
 assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
 assert.match(await editor.inputValue(),/正确金额/);
 assert.deepEqual(await page.evaluate(()=>window.saved.edits),{b1:'<table><tr><td>正确金额</td><td>100</td></tr></table>',b2:'已有人工校对'});
 assert.match(await page.evaluate(()=>window.saved.blocks[0].markdown),/正确金额/);
 await editor.fill('完成后人工修改');await page.waitForTimeout(1100);assert.equal(await editor.inputValue(),'完成后人工修改');
 await page.getByRole('button',{name:'保存修改',exact:true}).click();
 assert.equal(await page.evaluate(()=>window.saved.edits.b1),'完成后人工修改');
 results.push('默认选择另一模型；完成后自动替换正文、草稿和存储；不保留旧错误输出；其他区域不变；重复通知不覆盖之后的人工修改');
 await open();await start.click();await page.getByRole('button',{name:'取消区域识别',exact:true}).click();await page.getByText('已取消区域识别，原内容已保留',{exact:true}).waitFor();
 assert.equal(await page.locator('.region-retry-candidate').count(),0);assert.equal(await editor.inputValue(),'完成后人工修改');await close();
 await page.evaluate(()=>window.delayStart=true);await open();await start.click();await close();await open(1);
 await page.evaluate(()=>{window.replyStart();window.complete();});
 assert.equal(await editor.inputValue(),'已有人工校对');assert.equal(await page.locator('.region-retry-candidate').count(),0);
 await close();await open();assert.match(await editor.inputValue(),/正确金额/);await close();
 await page.getByRole('button',{name:'返回首页',exact:true}).click();await page.getByRole('button',{name:'最近记录',exact:true}).click();await page.locator('.history-row>button').first().click();assert(!(await page.locator('.reader').innerText()).includes('旧表格'));
 results.push('取消保留原文；关闭后完成仍替换对应区域；迟到回复不污染其他校对框；重开历史不恢复旧输出');
 await page.evaluate(()=>{window.region=undefined;window.unavailable='此区域没有保存识别裁图，无法单独重新识别';});await open();
 await page.getByText('此区域没有保存识别裁图，无法单独重新识别',{exact:true}).waitFor();assert.equal(await start.count(),0);await close();
 await page.evaluate(()=>{window.unavailable='';window.modelsReady=false;});await open();assert(await start.isDisabled());
 results.push('缺失裁图或未下载模型时禁用识别并说明原因');
 assert.deepEqual(errors,[]);await writeFile('verification/region-replacement/ui.json',JSON.stringify({environment:'Chromium 模拟 Android 桥，非真机推理',results,errors},null,2));console.log('PASS',results);
}finally{await browser?.close();server.kill();}
