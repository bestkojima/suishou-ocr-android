import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,writeFile} from 'node:fs/promises';

const origin='http://127.0.0.1:4198';
const server=spawn('python3',['-m','http.server','4198','--bind','127.0.0.1','--directory','web/dist'],{stdio:'ignore'});
let browser;
const results=[],errors=[];
try {
 for(let i=0;i<50;i++){try{if((await fetch(origin)).ok)break;}catch{}await new Promise(r=>setTimeout(r,100));}
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||'/home/dr/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome',args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:412,height:892}});
 page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  const input=(id,title)=>({id,title,mode:'pending-ocr',input:'source.png',blocks:[],assets:{},pages:[],progress:0,status:'ready',recognition:{state:'waiting',message:'输入已保存，可开始识别'}});
  window.documents={first:input('first','第一个输入'),second:input('second','第二个输入')};
  window.calls=[];window.heldStart=null;
  window.AndroidHost={request(raw){
   const {id,method,args}=JSON.parse(raw);window.calls.push({method,args});let value=true;
   if(method==='bootstrap')value={settings:{chunk:128},history:Object.values(window.documents),testBuild:true};
   if(method==='history')value=Object.values(window.documents);
   if(method==='open')value=window.documents[args.id];
   if(method==='save')value=Object.assign(window.documents[args.id],args);
   if(method==='recognitionStatus')value=window.documents[args.id].recognition;
   if(method==='recognize'){
    const doc=window.documents[args.id];
    doc.recognition={docId:doc.id,jobId:'first-job',sequence:1,state:'preparing',message:'正在准备第一个输入'};
    window.heldStart={id,value:structuredClone(doc)};return;
   }
   if(method==='cancelRecognition'){window.heldCancel={id};return;}
   window.nativeReply({id,value:structuredClone(value)});
  }};
 });
 await page.goto(origin);
 const openHistory=async(title)=>{
  await page.getByRole('button',{name:'最近记录',exact:true}).click();
  await page.getByRole('button',{name:new RegExp(title)}).first().click();
  await page.locator('.result h1').filter({hasText:title}).waitFor();
 };
 const check=async(name,fn)=>{await fn();results.push(name);console.log('PASS',name);};
 await check('启动回复迟到时，切换文档后保持当前页面，原作业可从历史重开',async()=>{
  await openHistory('第一个输入');
  await page.getByRole('button',{name:'开始识别',exact:true}).click();
  await page.waitForFunction(()=>!!window.heldStart);
  await page.getByRole('button',{name:'返回首页',exact:true}).click();
  await openHistory('第二个输入');
  await page.evaluate(()=>window.nativeReply(window.heldStart));
  await page.waitForTimeout(150);
  assert.equal(await page.locator('.result h1').innerText(),'第二个输入');
  await page.getByRole('button',{name:'返回首页',exact:true}).click();
  await openHistory('第一个输入');
  await page.getByText('正在准备第一个输入',{exact:true}).waitFor();
  assert(await page.getByRole('button',{name:'取消识别',exact:true}).isVisible());
 });
 await check('启动等待期间禁用重复提交，返回首页后迟到回复不重新打开文档',async()=>{
  await page.getByRole('button',{name:'返回首页',exact:true}).click();
  await openHistory('第二个输入');
  await page.evaluate(()=>{window.heldStart=null;});
  await page.getByRole('button',{name:'开始识别',exact:true}).click();
  await page.waitForFunction(()=>!!window.heldStart);
  assert(await page.getByRole('button',{name:'正在启动',exact:true}).isDisabled());
  await page.getByRole('button',{name:'返回首页',exact:true}).click();
  await page.evaluate(()=>window.nativeReply(window.heldStart));
  await page.waitForTimeout(150);
  assert(await page.getByRole('button',{name:'拍照',exact:true}).isVisible());
 });
 await check('旧文档启动失败的迟到回复不会在新文档显示错误',async()=>{
  await page.evaluate(()=>{window.documents.first.recognition={docId:'first',jobId:'first-job',state:'failed',sequence:2,message:'输入已保留，可重试'};});
  await openHistory('第一个输入');
  await page.evaluate(()=>{window.heldStart=null;});
  await page.getByRole('button',{name:'重试识别',exact:true}).click();
  await page.waitForFunction(()=>!!window.heldStart);
  await page.getByRole('button',{name:'返回首页',exact:true}).click();
  await openHistory('第二个输入');
  await page.evaluate(()=>window.nativeReply({id:window.heldStart.id,error:'第一个输入启动失败'}));
  await page.waitForTimeout(150);
  assert.equal(await page.getByText('第一个输入启动失败',{exact:true}).count(),0);
  assert.equal(await page.locator('.result h1').innerText(),'第二个输入');
 });
 await check('切换文档后忽略旧取消请求的失败回复',async()=>{
  await page.getByRole('button',{name:'取消识别',exact:true}).click();
  await page.waitForFunction(()=>!!window.heldCancel);
  await page.getByRole('button',{name:'返回首页',exact:true}).click();
  await openHistory('第一个输入');
  await page.evaluate(()=>window.nativeReply({id:window.heldCancel.id,error:'第二个输入的作业已经结束'}));
  await page.waitForTimeout(150);
  assert.equal(await page.getByText('第二个输入的作业已经结束',{exact:true}).count(),0);
  assert.equal(await page.locator('.result h1').innerText(),'第一个输入');
 });
 await check('重开同一文档后，旧启动回复不能覆盖已恢复的新进度',async()=>{
  await page.evaluate(()=>{
   window.documents.first.recognition={docId:'first',jobId:'first-job',state:'failed',sequence:10,message:'失败后保留输入'};
   window.nativeEvent('recognition',window.documents.first.recognition);
  });
  await page.getByRole('button',{name:'重试识别',exact:true}).click();
  await page.getByRole('button',{name:'返回首页',exact:true}).click();
  await page.evaluate(()=>{window.documents.first.recognition={docId:'first',jobId:'first-job',state:'recognizing',sequence:11,message:'已经恢复最新识别进度'};});
  await openHistory('第一个输入');
  await page.getByText('已经恢复最新识别进度',{exact:true}).waitFor();
  await page.evaluate(()=>window.nativeReply(window.heldStart));
  await page.waitForTimeout(150);
  assert(await page.getByText('已经恢复最新识别进度',{exact:true}).isVisible());
 });
 await check('当前文档启动失败仍显示错误，保留输入并允许再次重试',async()=>{
  await page.evaluate(()=>{
   window.documents.first.recognition={docId:'first',jobId:'first-job',state:'failed',sequence:12,message:'失败后保留输入'};
   window.nativeEvent('recognition',window.documents.first.recognition);window.heldStart=null;
  });
  await page.getByRole('button',{name:'重试识别',exact:true}).click();
  await page.waitForFunction(()=>!!window.heldStart);
  await page.evaluate(()=>{
   window.documents.first.recognition={docId:'first',jobId:'first-job',state:'failed',sequence:12,message:'失败后保留输入'};
   window.nativeReply({id:window.heldStart.id,error:'当前输入启动失败，请重试'});
  });
  await page.getByText('当前输入启动失败，请重试',{exact:true}).waitFor();
  assert(await page.getByRole('button',{name:'重试识别',exact:true}).isEnabled());
  assert.equal(await page.locator('.result h1').innerText(),'第一个输入');
 });
 assert.deepEqual(errors,[]);
 await mkdir('verification/ticket4',{recursive:true});
 await writeFile('verification/ticket4/navigation.json',JSON.stringify({boundary:'AndroidHost 浏览器模拟，非设备推理',results,errors},null,2)+'\n');
} finally {await browser?.close();server.kill();}
