import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir,writeFile,readFile} from 'node:fs/promises';
const server=spawn('python3',['-m','http.server','4196','--bind','127.0.0.1','--directory','web/dist'],{stdio:'ignore'});
let browser; const results=[],errors=[];
try {
 for(let i=0;i<50;i++){try{if((await fetch('http://127.0.0.1:4196')).ok)break;}catch{}await new Promise(r=>setTimeout(r,100));}
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||'/home/dr/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome',args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:412,height:892}}); page.on('pageerror',e=>errors.push(e.message));
 const modelContract=JSON.parse(await readFile('app/src/main/assets/ocr/models.json','utf8'));
 await page.addInitScript(modelContract=>{
  window.calls=[];window.saved={id:'new-photo',title:'新拍摄输入',mode:'pending-ocr',blocks:[],assets:{},pages:[],progress:0,status:'ready',input:'source.png',recognition:{state:'missing-models',message:'缺少识别模型：PP-DocLayoutV3.mnn'}};
  window.documents={};window.modelTasks=[];
  window.AndroidHost={request(raw){const {id,method,args}=JSON.parse(raw);window.calls.push({method,args});let value=true;
   if(method==='bootstrap')value={settings:{chunk:128},history:[],testBuild:true};
   if(method==='capture')value=window.saved;
   if(method==='open'){window.saved=window.documents[args.id]||window.saved;value=window.saved;}
   if(method==='save'){Object.assign(window.documents[args.id]||window.saved,args);value=window.documents[args.id]||window.saved;}
   if(method==='history'){window.documents[window.saved.id]=window.saved;value=Object.values(window.documents).map(d=>({...d,count:d.blocks.length}));}
   if(method==='recognitionStatus')value=window.saved.recognition;
   if(method==='models')value={tasks:window.modelTasks,repos:['dr3334/PP-DocLayoutV3-mnn','dr3334/ovrics-ocrv2_mnn'],readiness:window.modelReadiness||{state:'missing-models',message:'缺少识别模型：PP-DocLayoutV3.mnn'}};
   if(method==='catalog')value=modelContract.filter(f=>f.repo===args.repo);
   if(method==='download')window.modelTasks.push(...modelContract.filter(f=>f.repo===args.repo&&args.paths.includes(f.path)).map(f=>({...f,downloaded:f.size,status:'verified'})));
   if(method==='activateModels'){window.modelReadiness={state:'ready',message:'模拟桥接：九个工件已就绪，引擎已加载'};value=window.modelReadiness;}
   if(method==='recognize'){
    if(args.again){window.documents[window.saved.id]=structuredClone(window.saved);window.saved={...window.saved,id:'recognize-again',title:'另存的新识别',mode:'pending-ocr',blocks:[],edits:{},progress:0};}
    window.saved.recognition={docId:window.saved.id,jobId:'integrated-job',sequence:1,state:'recognizing',message:'模拟桥接：正在识别'};value=window.saved;
   }
   setTimeout(()=>window.nativeReply({id,value:structuredClone(value),error:null}),5);
  }};
 },modelContract);
 await page.goto('http://127.0.0.1:4196');await page.getByRole('button',{name:'拍照',exact:true}).click();
 await page.getByText('缺少识别模型：PP-DocLayoutV3.mnn',{exact:true}).waitFor({timeout:4000});
 assert(await page.getByRole('button',{name:'下载识别模型',exact:true}).isVisible());
 assert(await page.getByRole('button',{name:'开始识别',exact:true}).isVisible());
 await page.getByRole('button',{name:'返回首页',exact:true}).click();await page.getByRole('button',{name:'最近记录',exact:true}).click();await page.getByRole('button',{name:/新拍摄输入/}).first().click();
 await page.getByText('缺少识别模型：PP-DocLayoutV3.mnn',{exact:true}).waitFor();results.push('待识别输入保存与历史重开，缺模型提供下载和稍后识别');

 const check=async(name,fn)=>{await fn();results.push(name);console.log('PASS',name);};
 await check('模型缺失经下载准备、加载、识别保存、另存重识别，旧校对仍可导出（桥接模拟）',async()=>{
  await page.getByRole('button',{name:'下载识别模型',exact:true}).click();
  for(const repo of ['dr3334/PP-DocLayoutV3-mnn','dr3334/ovrics-ocrv2_mnn']){
   const card=page.getByRole('region',{name:repo,exact:true});
   await card.getByRole('button',{name:'浏览仓库文件',exact:true}).click();
   await card.getByRole('button',{name:'下载 / 继续所选文件',exact:true}).click();
   await card.locator('summary').click();
   await card.getByText('SHA-256 校验通过',{exact:false}).first().waitFor();
  }
  assert.equal(await page.getByText('缺少识别模型：PP-DocLayoutV3.mnn',{exact:true}).count(),2,'文件下载完成仍需实际加载');
  await page.getByRole('button',{name:'校验并加载识别模型',exact:true}).click();
  await page.getByText('模拟桥接：九个工件已就绪，引擎已加载',{exact:true}).waitFor();
  await page.getByRole('dialog').getByRole('button',{name:'关闭',exact:true}).click();
  await page.getByRole('button',{name:'开始识别',exact:true}).click();
  await page.getByText('模拟桥接：正在识别',{exact:true}).waitFor();
  await page.evaluate(()=>{
   window.saved.mode='real-ocr';window.saved.blocks=[{id:'integrated-text',type:'text',sourceStatus:'ok',markdown:'集成桥接模拟正文'}];
   window.saved.recognition={...window.saved.recognition,sequence:2,state:'succeeded',message:'模拟桥接：结果已保存'};
   window.nativeEvent('recognition',window.saved.recognition);
  });
  await page.getByRole('button',{name:'重新回放',exact:true}).waitFor();
  await page.locator('.edit-region').first().click();
  await page.getByRole('textbox',{name:'校对 Markdown'}).fill('旧记录的校对内容');
  await page.getByRole('button',{name:'保存修改',exact:true}).click();
  await page.getByRole('button',{name:'重新识别（另存）',exact:true}).click();
  await page.locator('.result h1').filter({hasText:'另存的新识别'}).waitFor();
  assert.equal(await page.getByText('旧记录的校对内容',{exact:true}).count(),0);
  await page.getByRole('button',{name:'返回首页',exact:true}).click();
  await page.getByRole('button',{name:'最近记录',exact:true}).click();
  await page.getByRole('button',{name:/新拍摄输入/}).first().click();
  await page.getByText('旧记录的校对内容',{exact:true}).waitFor();
  await page.getByRole('button',{name:'导出 / 分享',exact:true}).click();
  await page.getByRole('button',{name:'Markdown 单文件',exact:true}).click();
  await page.getByText('已交给系统保存或分享',{exact:true}).waitFor();
  assert(await page.evaluate(()=>window.calls.some(c=>c.method==='export'&&c.args.id==='new-photo'&&c.args.format==='md')));
 });
 await check('识别阶段来自桥接，返回首页继续，回放暂停不调用取消',async()=>{
  await page.evaluate(()=>{window.saved.mode='pending-ocr';window.saved.blocks=[];window.saved.edits={};window.saved.progress=0;window.saved.recognition={docId:window.saved.id,jobId:'job-1',sequence:10,state:'recognizing',message:'正在本地识别',stage:'region_started',regionCompleted:2,regionTotal:5};});
  await page.getByRole('button',{name:'返回首页'}).click();await page.getByRole('button',{name:'最近记录'}).click();await page.getByRole('button',{name:/新拍摄输入/}).first().click();
  await page.getByText('正在本地识别',{exact:true}).waitFor();assert((await page.locator('.recognition-panel').innerText()).includes('2/5'));
  assert.equal(await page.evaluate(()=>window.calls.filter(c=>c.method==='cancelRecognition').length),0);
 });
 await check('显式取消保持取消中，终态后提供重试；迟到事件无法改写状态',async()=>{
  await page.evaluate(()=>{const old=window.AndroidHost.request;window.AndroidHost.request=function(raw){const r=JSON.parse(raw);if(r.method==='cancelRecognition'){window.calls.push(r);window.saved.recognition={...window.saved.recognition,state:'cancelling',sequence:11,message:'正在安全停止'};return window.nativeReply({id:r.id,value:structuredClone(window.saved.recognition)});}return old(raw);};});
  await page.getByRole('button',{name:'取消识别',exact:true}).click();await page.getByRole('button',{name:'取消中',exact:true}).waitFor();assert(await page.getByRole('button',{name:'取消中',exact:true}).isDisabled());
  await page.evaluate(()=>{window.saved.recognition={...window.saved.recognition,state:'cancelled',sequence:12,message:'已安全取消，输入已保留'};window.nativeEvent('recognition',window.saved.recognition);});
  await page.getByRole('button',{name:'重试识别',exact:true}).waitFor();
  await page.evaluate(()=>window.nativeEvent('recognition',{docId:'new-photo',jobId:'job-1',state:'recognizing',sequence:10,message:'旧作业错误提示'}));await page.waitForTimeout(100);assert.equal(await page.getByText('旧作业错误提示').count(),0);
 });
 await check('重复序号事件不能将终态改回识别中',async()=>{
  await page.evaluate(()=>window.nativeEvent('recognition',{docId:'new-photo',jobId:'job-1',state:'recognizing',sequence:12,message:'重复事件错误提示'}));
  await page.waitForTimeout(150);
  assert.equal(await page.getByText('重复事件错误提示',{exact:true}).count(),0);
  assert(await page.getByRole('button',{name:'重试识别',exact:true}).isVisible());
 });
 await check('重试使用公开请求，新作业忽略旧 jobId 事件',async()=>{
  await page.evaluate(()=>{const old=window.AndroidHost.request;window.AndroidHost.request=function(raw){const r=JSON.parse(raw);if(r.method==='recognize'){window.calls.push(r);window.saved.recognition={docId:window.saved.id,jobId:'job-2',sequence:13,state:'recognizing',message:'重试正在识别'};return window.nativeReply({id:r.id,value:structuredClone(window.saved)});}return old(raw);};});
  await page.getByRole('button',{name:'重试识别',exact:true}).click();await page.getByText('重试正在识别',{exact:true}).waitFor();
  await page.evaluate(()=>window.nativeEvent('recognition',{docId:'new-photo',jobId:'job-1',state:'succeeded',sequence:99,message:'过期结果'}));await page.waitForTimeout(150);assert.equal(await page.getByText('过期结果').count(),0);
 });
 await check('连续到达的乱序进度不能覆盖较新阶段',async()=>{
  await page.evaluate(()=>{
   const state={...window.saved.recognition,sequence:15,stage:'region_completed',regionCompleted:3,regionTotal:5,message:'已完成三个区域'};
   window.nativeEvent('recognition',state);
   window.nativeEvent('recognition',{...state,sequence:14,stage:'region_started',regionCompleted:2,message:'迟到的第二个区域'});
  });
  await page.waitForTimeout(150);
  assert.equal(await page.getByText('迟到的第二个区域',{exact:true}).count(),0);
  assert(await page.getByText('已完成三个区域',{exact:true}).isVisible());
  assert((await page.locator('.recognition-panel').innerText()).includes('3/5'));
  await page.evaluate(()=>{window.saved.recognition={...window.saved.recognition,sequence:15,stage:'region_completed',regionCompleted:3,regionTotal:5,message:'已完成三个区域'};});
 });
 await check('第二项启动明确报告冲突，其他文档事件不影响当前输入',async()=>{
  await page.evaluate(()=>{const old=window.AndroidHost.request;window.AndroidHost.request=function(raw){const r=JSON.parse(raw);if(r.method==='capture'){window.saved={id:'second-photo',title:'第二个输入',input:'source.png',mode:'pending-ocr',progress:0,blocks:[],pages:[],assets:{},recognition:{state:'waiting',message:'输入已保存，已有识别作业；稍后可开始识别'}};return window.nativeReply({id:r.id,value:structuredClone(window.saved)});}if(r.method==='recognize')return window.nativeReply({id:r.id,error:'已有识别作业，请返回该文档或等待安全停止'});return old(raw);};});
  await page.getByRole('button',{name:'返回首页'}).click();await page.getByRole('button',{name:'拍照',exact:true}).click();await page.getByRole('button',{name:'开始识别',exact:true}).click();await page.getByText('已有识别作业，请返回该文档或等待安全停止',{exact:true}).waitFor();
  await page.evaluate(()=>window.nativeEvent('recognition',{docId:'new-photo',jobId:'job-2',state:'failed',sequence:30,message:'别的文档错误'}));await page.waitForTimeout(100);assert.equal(await page.getByText('别的文档错误').count(),0);
 });
 await check('完整落盘后进入结果回放，暂停输出与识别取消独立',async()=>{
  await page.getByRole('button',{name:'返回首页'}).click();
  await page.evaluate(()=>{window.saved={id:'real-result',title:'真实输出适配',input:'source.png',mode:'pending-ocr',progress:0,blocks:[],assets:{},pages:[],recognition:{docId:'real-result',jobId:'job-3',sequence:31,state:'recognizing',message:'正在识别新页'}};});
  await page.getByRole('button',{name:'最近记录'}).click();await page.getByRole('button',{name:/真实输出适配/}).first().click();
  await page.locator('.result h1').filter({hasText:'真实输出适配'}).waitFor();
  await page.getByText('正在识别新页',{exact:true}).waitFor();
  await page.evaluate(()=>{window.saved.recognition={...window.saved.recognition,state:'saving',resultSaved:true,sequence:32,message:'结果已保存，正在释放原生资源'};window.nativeEvent('recognition',window.saved.recognition);});
  await page.getByRole('button',{name:'正在结束',exact:true}).waitFor({timeout:2000});assert(await page.getByRole('button',{name:'正在结束',exact:true}).isDisabled());
  await page.evaluate(()=>{window.saved.mode='real-ocr';window.saved.blocks=[{id:'p-a',type:'text',sourceStatus:'ok',markdown:'新的真实识别正文。'.repeat(50)}];window.saved.recognition={...window.saved.recognition,state:'succeeded',sequence:33,message:'识别完成，结果已保存；以下为结果回放'};window.nativeEvent('recognition',window.saved.recognition);});
  await page.getByRole('button',{name:'暂停输出',exact:true}).waitFor({timeout:4000}).catch(async e=>{console.log(await page.locator('body').innerText());console.log(await page.evaluate(()=>({saved:window.saved,calls:window.calls.slice(-15)})));throw e;});await page.getByRole('button',{name:'暂停输出',exact:true}).click();
  const cancels=await page.evaluate(()=>window.calls.filter(c=>c.method==='cancelRecognition').length);await page.waitForTimeout(900);assert.equal(await page.evaluate(()=>window.calls.filter(c=>c.method==='cancelRecognition').length),cancels);assert(await page.getByRole('button',{name:'继续输出',exact:true}).isVisible());
 });
 await check('进程退出后从历史重开，更新序号的恢复终态提供重试',async()=>{
  await page.getByRole('button',{name:'返回首页',exact:true}).click();
  await page.evaluate(()=>{
   window.saved={id:'interrupted-input',title:'进程中断的输入',mode:'pending-ocr',input:'source.png',blocks:[],assets:{},pages:[],progress:0,
    recognition:{docId:'interrupted-input',jobId:'interrupted-job',state:'recognizing',sequence:99,message:'上次正在识别'}};
   const old=window.AndroidHost.request;
   window.AndroidHost.request=function(raw){
    const r=JSON.parse(raw);
    if(r.method==='recognitionStatus'&&r.args.id==='interrupted-input'){
     window.saved.recognition={...window.saved.recognition,state:'failed',sequence:100,message:'上次识别随进程退出而停止，输入已保留，可重试'};
     return window.nativeReply({id:r.id,value:structuredClone(window.saved.recognition)});
    }
    return old(raw);
   };
  });
  await page.getByRole('button',{name:'最近记录',exact:true}).click();
  await page.getByRole('button',{name:/进程中断的输入/}).first().click();
  await page.getByText('上次识别随进程退出而停止，输入已保留，可重试',{exact:true}).waitFor();
  assert(await page.getByRole('button',{name:'重试识别',exact:true}).isVisible());
 });
 await check('失败保留输入并提供重试，模型使用中禁用删除和替换',async()=>{
  await page.getByRole('button',{name:'返回首页',exact:true}).click();
  await page.evaluate(()=>{window.saved={id:'failed-input',title:'加载失败的输入',mode:'pending-ocr',input:'source.png',blocks:[],assets:{},pages:[],progress:0,recognition:{docId:'failed-input',jobId:'job-4',state:'failed',sequence:33,message:'模型加载失败，输入已保留，可重试'}};});
  await page.getByRole('button',{name:'最近记录',exact:true}).click();await page.getByRole('button',{name:/加载失败的输入/}).first().click();
  await page.getByText('模型加载失败，输入已保留，可重试',{exact:true}).waitFor();assert(await page.getByRole('button',{name:'重试识别',exact:true}).isVisible());
  await page.evaluate(()=>{window.modelReadiness={state:'ready',message:'本地引擎已加载',inUse:true};});
  await page.getByRole('button',{name:'设置',exact:true}).click();await page.getByRole('dialog').getByRole('button',{name:/识别模型/}).click();await page.getByText('本地引擎已加载',{exact:true}).waitFor();
  for(const button of await page.getByRole('button',{name:'删除文件',exact:true}).all())assert(await button.isDisabled());
  assert(await page.getByRole('button',{name:'校验并加载识别模型',exact:true}).isDisabled());
  await page.evaluate(()=>{window.modelReadiness={state:'load-failed',message:'模型加载失败：不兼容工件',inUse:false};});await page.getByText('模型加载失败：不兼容工件',{exact:true}).waitFor();assert(await page.getByRole('button',{name:'校验并加载识别模型',exact:true}).isEnabled());
 });
 assert.deepEqual(errors,[]);await mkdir('verification',{recursive:true});await writeFile('verification/recognition-ui.json',JSON.stringify({boundary:'AndroidHost 模拟，非模型推理',results,errors},null,2));
} finally {await browser?.close();server.kill();}
