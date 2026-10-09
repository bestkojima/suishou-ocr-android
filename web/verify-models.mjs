import{chromium}from'playwright';
import assert from'node:assert/strict';
import{mkdir,writeFile}from'node:fs/promises';
import{spawn}from'node:child_process';
const port=4194,origin=`http://127.0.0.1:${port}`,server=spawn('python3',['-m','http.server',String(port),'--bind','127.0.0.1','--directory','web/dist'],{stdio:'ignore'});let browser;
const results=[],errors=[];const check=async(name,fn)=>{await fn();results.push(name);console.log('PASS',name);};
try{
 for(let i=0;i<40;i++){try{if((await fetch(origin)).ok)break;}catch{}await new Promise(r=>setTimeout(r,100));}
 browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||'/home/dr/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome',args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:412,height:892}});page.on('pageerror',e=>errors.push(e.message));
 await page.goto(origin);await page.getByRole('button',{name:'设置',exact:true}).click();
 await check('图片分辨率默认均衡，快速与原图设置刷新后保留，窄屏不溢出',async()=>{
  const resolution=page.getByLabel('图片分辨率',{exact:true});assert.equal(await resolution.inputValue(),'balanced');
  await resolution.selectOption('fast');await page.waitForFunction(()=>JSON.parse(localStorage.getItem('ocr-settings')).imageResolution==='fast');
  await page.reload();await page.getByRole('button',{name:'设置',exact:true}).click();assert.equal(await resolution.inputValue(),'fast');
  await resolution.selectOption('original');await page.waitForFunction(()=>JSON.parse(localStorage.getItem('ocr-settings')).imageResolution==='original');
  await page.setViewportSize({width:320,height:740});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  await page.getByText(/小字模糊可选原图/).waitFor();await resolution.selectOption('balanced');await page.setViewportSize({width:412,height:892});
 });
 await page.getByRole('button',{name:/识别模型/}).click();
 await check('自定义仓库链接添加、刷新后保留、移除入口',async()=>{
  await page.getByLabel('添加模型仓库').fill('https://www.modelscope.cn/models/example/my-model');await page.getByRole('button',{name:'添加仓库',exact:true}).click();await page.getByText('example/my-model',{exact:true}).waitFor();await page.reload();await page.getByRole('button',{name:'设置',exact:true}).click();await page.getByRole('button',{name:/识别模型/}).click();await page.getByText('example/my-model',{exact:true}).waitFor();await page.getByRole('button',{name:'移除入口'}).click();await page.getByText('example/my-model',{exact:true}).waitFor({state:'detached'});
 });
 await check('拒绝非 ModelScope 链接并在下载页显示错误',async()=>{await page.getByLabel('添加模型仓库').fill('https://evil.invalid/models/a/b');await page.getByRole('button',{name:'添加仓库',exact:true}).click();await page.getByRole('alert').waitFor();});
 await page.close();
 const native=await browser.newPage({viewport:{width:412,height:892}});native.on('pageerror',e=>errors.push(e.message));
 await native.addInitScript(()=>{
  const repos=['dr3334/PP-DocLayoutV3-mnn','dr3334/ovrics-ocrv2_mnn','MNN/GLM-OCR-MNN'];window.modelCalls=[];window.modelState={tasks:[],repos,running:false,freeBytes:1e10,readiness:{modelChoice:'ovis',cpuThreads:4,inUse:false},modelChoices:[{id:'ovis',name:'OvisOCR2'},{id:'glm',name:'GLM-OCR'}]};window.holdCatalog=false;
  window.AndroidHost={request(raw){const {id,method,args}=JSON.parse(raw);window.modelCalls.push({method,args});let value=true;
   if(method==='bootstrap')value={testBuild:true,settings:{wifiOnly:true,imageResolution:'balanced'},history:[],version:'test'};
   if(method==='settings')value={wifiOnly:true,...args};
   if(method==='models')value=window.modelState;
   if(method==='setOcrModel'){window.modelState.readiness={...window.modelState.readiness,modelChoice:args.model,message:'识别模型已切换'};value=window.modelState.readiness;}
   if(method==='setOcrThreads'){window.modelState.readiness={...window.modelState.readiness,threadChoice:args.threads,cpuThreads:args.threads||4,message:'线程设置已更新'};value=window.modelState.readiness;}
   if(method==='catalog'){if(window.holdCatalog)return;value=[{repo:args.repo,path:'sub/model.mnn',size:1000,sha256:'a'.repeat(64)},{repo:args.repo,path:'config.json',size:100,sha256:'b'.repeat(64)},{repo:args.repo,path:'README.md',size:20,sha256:''}];}
   if(method==='download'){window.modelState={...window.modelState,running:true,tasks:[{repo:args.repo,path:'sub/model.mnn',size:1000,downloaded:0,status:'downloading'}],activity:{repo:args.repo,path:'sub/model.mnn',stage:'downloading',message:'正在下载',bytes:100,total:1000,updatedAt:Date.now()}};value={started:true};}
   if(method==='pauseDownload')window.modelState={...window.modelState,running:false,activity:{...window.modelState.activity,stage:'paused',message:'已暂停，进度已保留'}};
   setTimeout(()=>window.nativeReply({id,value:structuredClone(value),error:null}),5);
  }};
 });
 await native.goto(origin);await native.getByRole('button',{name:'设置',exact:true}).click();
 await check('分辨率选择通过 AndroidHost 发送设置请求（桥模拟）',async()=>{
  await native.getByLabel('图片分辨率',{exact:true}).selectOption('original');
  await native.waitForFunction(()=>window.modelCalls.some(c=>c.method==='settings'&&c.args.imageResolution==='original'));
  await native.waitForFunction(()=>document.querySelector('[aria-label="图片分辨率"]').value==='original');
  assert.equal(await native.getByLabel('图片分辨率',{exact:true}).inputValue(),'original');
 });
 await native.getByRole('button',{name:/识别模型/}).click();
 await check('选择 GLM-OCR 发送模型切换请求并显示独立仓库（桥模拟）',async()=>{
  const select=native.getByLabel('当前识别模型',{exact:true});await select.waitFor();assert.equal(await select.inputValue(),'ovis');
  await select.selectOption('glm');await native.getByText('识别模型已切换',{exact:true}).waitFor();assert.equal(await select.inputValue(),'glm');
  assert(await native.evaluate(()=>window.modelCalls.some(c=>c.method==='setOcrModel'&&c.args.model==='glm')));
  await native.getByRole('region',{name:'MNN/GLM-OCR-MNN',exact:true}).waitFor();
 });
 const card=native.getByRole('region',{name:'dr3334/PP-DocLayoutV3-mnn',exact:true});
 await check('浏览文件、筛选和选择下载，未选文件不会提交',async()=>{
  await card.getByRole('button',{name:'浏览仓库文件'}).click();await card.getByLabel('选择 sub/model.mnn',{exact:true}).waitFor();await card.getByLabel('选择 config.json',{exact:true}).uncheck();await card.getByLabel('筛选仓库文件').fill('.mnn');assert.equal(await card.locator('.selectable').count(),1);await card.getByRole('button',{name:'下载 / 继续所选文件'}).click();assert.deepEqual(await native.evaluate(()=>window.modelCalls.find(c=>c.method==='download').args.paths),['sub/model.mnn']);
 });
 await check('未写入任务快照的新字节数也能实时显示',async()=>{
  await native.waitForFunction(()=>document.querySelector('.model-card progress').value===100);await native.evaluate(()=>{window.modelState.activity.bytes=700;window.modelState.activity.updatedAt=Date.now();});await native.waitForFunction(()=>document.querySelector('.model-card progress').value===700);
 });
 await check('慢清单不阻塞进度刷新，等待连接有明确提示，暂停可用',async()=>{
  await native.evaluate(()=>{window.holdCatalog=true;});await native.getByRole('region',{name:'dr3334/ovrics-ocrv2_mnn',exact:true}).getByRole('button',{name:'浏览仓库文件'}).click();await native.getByText('正在读取仓库清单…').waitFor();await native.evaluate(()=>{window.modelState.activity.bytes=850;window.modelState.activity.updatedAt=Date.now()-10000;});await native.waitForFunction(()=>document.querySelector('.model-card progress').value===850);await native.getByText(/等待服务器响应，可暂停后重试/).waitFor();await card.getByRole('button',{name:'暂停',exact:true}).click();await native.getByText(/已暂停，进度已保留/).waitFor();
 });
 await check('手机宽度不溢出，无页面异常',async()=>{assert(await native.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));assert.deepEqual(errors,[]);});
 await check('推理线程选择发送真实请求，识别占用时禁止切换（桥模拟）',async()=>{
  await native.evaluate(()=>{window.modelState={...window.modelState,running:false,readiness:{modelChoice:'glm',state:'ready',message:'引擎已复用',inUse:false,cpuThreads:4,threadChoice:0}};});
  const select=native.getByLabel('CPU 推理线程',{exact:true});await select.waitFor();await select.selectOption('2');
  await native.getByText('线程设置已更新',{exact:true}).waitFor();assert.equal(await select.inputValue(),'2');
  assert(await native.evaluate(()=>window.modelCalls.some(c=>c.method==='setOcrThreads'&&c.args.threads===2)));
  await native.evaluate(()=>window.modelState.readiness.inUse=true);await native.waitForTimeout(1200);assert(await select.isDisabled());assert(await native.getByLabel('当前识别模型',{exact:true}).isDisabled());
 });
 await mkdir('web/test-results',{recursive:true});await native.screenshot({path:'web/test-results/models-manager.png',fullPage:true});await writeFile('verification/models-ui.json',JSON.stringify({passed:results.length,results,errors},null,2));
}finally{await browser?.close();server.kill();}
