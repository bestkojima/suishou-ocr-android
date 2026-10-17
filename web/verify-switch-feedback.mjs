import {chromium} from 'playwright';
import {spawn} from 'node:child_process';
import {mkdir,writeFile} from 'node:fs/promises';
import assert from 'node:assert/strict';
const port=Number(process.env.OCR_TEST_PORT||4197),origin=`http://127.0.0.1:${port}`;
const server=spawn(process.env.PYTHON||(process.platform==='win32'?'python':'python3'),['-m','http.server',String(port),'--bind','127.0.0.1','--directory','web/dist'],{stdio:'ignore',windowsHide:true});
let browser;const errors=[];
try {
 for(let i=0;i<50;i++){try{if((await fetch(origin)).ok)break;}catch{}await new Promise(r=>setTimeout(r,100));}
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||undefined});
 const page=await browser.newPage({viewport:{width:390,height:844}});page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  window.calls=[];window.configured=false;window.feedbackFailure=false;
  window.saved={id:'sample',title:'真实识别样本',mode:'pending-ocr',input:'source.png',progress:0,blocks:[],assets:{},pages:[],recognition:{docId:'sample',jobId:'cancelled-job',sequence:1,state:'recognizing',stage:'region_started',liveOutput:true,stream:{regions:[{requestId:'r1',type:'text',raw:'尚未完成的输出',done:false}]}}};
  window.AndroidHost={request(raw){const {id,method,args}=JSON.parse(raw);window.calls.push({method,args});let value=true,error;
   if(method==='bootstrap')value={settings:{chunk:64},history:[]};
   if(method==='capture'||method==='open')value=window.saved;
   if(method==='recognitionStatus')value=window.saved.recognition;
   if(method==='history')value=[window.saved];
   if(method==='cancelRecognition')value=window.saved.recognition={...window.saved.recognition,state:'cancelled',sequence:2,message:'已取消，输入已保留'}; // 故意保留旧 stream，界面也必须以终态为准。
   if(method==='feedbackOptions')value={image:'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7',json:'document.json',configured:window.configured,backupConfigured:window.configured};
   if(method==='prepareFeedback')value={feedbackId:'frozen-1',submitted:false};
   if(method==='submitFeedback'){if(window.feedbackFailure)error='网络不可用，反馈包仍保存在本机';else value={feedbackId:args.feedbackId,submitted:true};}
   setTimeout(()=>window.nativeReply({id,value:structuredClone(value),error}),0);
  }};
 });
 await page.goto(origin);await page.getByRole('button',{name:'拍照',exact:true}).click();
 await page.getByText('尚未完成的输出',{exact:true}).waitFor();
 await page.getByRole('button',{name:'取消识别',exact:true}).click();await page.getByText('已取消，输入已保留',{exact:true}).waitFor();
 assert(!(await page.locator('.reader').innerText()).includes('尚未完成的输出'));
 assert(!(await page.locator('.result').innerText()).includes('正在生成'));
 assert.equal(await page.getByRole('button',{name:'继续输出',exact:true}).count(),0);
 assert.equal(await page.getByRole('button',{name:'反馈识别问题',exact:true}).count(),0);
 await page.getByRole('button',{name:'返回首页',exact:true}).click();
 await page.evaluate(()=>Object.assign(window.saved,{mode:'real-ocr',progress:1,blocks:[{id:'b',type:'text',markdown:'识别结果',sourceStatus:'ok'}],recognition:{docId:'sample',jobId:'done-job',sequence:3,state:'succeeded',resultSaved:true}}));
 await page.getByRole('button',{name:'拍照',exact:true}).click();await page.getByRole('button',{name:'反馈识别问题',exact:true}).click();
 await page.getByText(/反馈接收地址尚未配置/).waitFor();assert.equal(await page.getByRole('button',{name:'提交识别反馈',exact:true}).count(),0);
 assert(await page.getByRole('button',{name:'保存反馈包',exact:true}).isDisabled());
 await page.getByLabel('识别问题描述').fill('金额应该是 128.50');await page.getByLabel('识别问题分类').selectOption('text');
 await page.getByRole('button',{name:'保存反馈包',exact:true}).click();await page.getByText('反馈包已保存，尚未提交。',{exact:true}).waitFor();
 await page.getByRole('button',{name:'分享到飞书等应用',exact:true}).click();await page.getByText(/已打开系统分享/).waitFor();
 assert.equal(await page.evaluate(()=>window.calls.filter(c=>c.method==='prepareFeedback').length),1);
 const prepared=await page.evaluate(()=>window.calls.find(c=>c.method==='prepareFeedback').args);assert.deepEqual(prepared,{id:'sample',jobId:'done-job',category:'text',description:'金额应该是 128.50'});
 await page.getByRole('button',{name:'关闭',exact:true}).click();await page.evaluate(()=>{window.configured=true;window.feedbackFailure=true;});await page.getByRole('button',{name:'反馈识别问题',exact:true}).click();
 assert.equal(await page.getByLabel('识别问题描述').inputValue(),'金额应该是 128.50');
 await page.getByRole('button',{name:'提交识别反馈',exact:true}).click();await page.getByRole('alert').filter({hasText:'网络不可用'}).waitFor();
 assert.equal(await page.getByText('此份反馈已提交。',{exact:true}).count(),0);
 await page.evaluate(()=>window.feedbackFailure=false);await page.getByRole('button',{name:'提交识别反馈',exact:true}).click();await page.getByText('此份反馈已提交。',{exact:true}).waitFor();
 assert.equal(await page.evaluate(()=>window.calls.filter(c=>c.method==='prepareFeedback').length),1);
 assert.deepEqual(await page.evaluate(()=>window.calls.filter(c=>c.method==='submitFeedback').map(c=>c.args.feedbackId)),['frozen-1','frozen-1']);
 assert(await page.getByRole('button',{name:'已提交反馈',exact:true}).isDisabled());
 const out='verification/experience-20261017';await mkdir(out,{recursive:true});await page.screenshot({path:`${out}/feedback-browser.png`});
 assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));assert.deepEqual(errors,[]);
 await writeFile(`${out}/switch-feedback-ui.json`,JSON.stringify({passed:true,environment:'Chromium mock bridge',checks:['取消终态覆盖旧 stream','待识别输入不展示结果反馈','无接收地址不假报成功','图片和 JSON 附件预览','草稿与包编号重开保留','失败重试使用同一反馈包','收到回执后禁用重复提交'],errors},null,2));
 console.log('PASS 取消终态、识别反馈草稿与附件、无地址分享、失败重试和提交回执');
} finally {await browser?.close();server.kill();}
