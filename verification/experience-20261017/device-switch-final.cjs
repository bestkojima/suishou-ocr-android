// 仅对已准备好模型/样本的 cn.local.ocr.test 执行；不会操作普通版数据。
const {chromium}=require('playwright');
const {execFileSync}=require('node:child_process');
const fs=require('node:fs');const assert=require('node:assert/strict');
const out='verification/experience-20261017';
const adb=(...args)=>execFileSync('adb',args,{encoding:'utf8',windowsHide:true}).trim();
const pid=()=>{try{return adb('shell','pidof','cn.local.ocr.test:ocr');}catch{return '';}};
const workerExited=async()=>{for(let i=0;i<50;i++){if(!pid())return;await new Promise(r=>setTimeout(r,100));}assert.equal(pid(),'');};
(async()=>{
 const browser=await chromium.connectOverCDP('http://127.0.0.1:19229');
 try {
  const page=browser.contexts()[0].pages()[0];page.setDefaultTimeout(10000);const errors=[];page.on('pageerror',e=>errors.push(e.message));
  const main=adb('shell','pidof','cn.local.ocr.test');
  await page.getByRole('button',{name:'最近记录',exact:true}).click();
  await page.getByRole('button',{name:/^jc02.jpg 真实识别/}).click();
  await page.getByRole('button',{name:'重新识别（另存）',exact:true}).click();
  await page.locator('.recognition-panel').getByText('正在本地识别，阶段与区域进度来自引擎',{exact:true}).waitFor({timeout:30000});
  const first=pid();assert(first);
  await page.getByRole('button',{name:'返回首页',exact:true}).click();await page.getByRole('button',{name:'最近记录',exact:true}).click();
  await page.getByRole('button',{name:/^jc06~4.jpg 真实识别/}).click();
  const started=Date.now();await page.getByRole('button',{name:'重新识别（另存）',exact:true}).click();
  await page.locator('.result h1').filter({hasText:'jc06~4.jpg · 重新识别'}).waitFor();const entered=Date.now()-started;
  await page.locator('.recognition-panel').getByText('正在本地识别，阶段与区域进度来自引擎',{exact:true}).waitFor({timeout:30000});
  const second=pid();assert(second);assert.notEqual(first,second);assert.equal(adb('shell','pidof','cn.local.ocr.test'),main);
  await page.getByRole('button',{name:'取消识别',exact:true}).click();await page.getByRole('button',{name:'重试识别',exact:true}).waitFor();
  await workerExited();assert(!(await page.locator('.result').innerText()).includes('正在生成'));
  await page.screenshot({path:`${out}/cancel-final-device.png`});
  // 立即重试并在模型加载期取消，避免将旧绑定迟到当成新进程。
  await page.getByRole('button',{name:'重试识别',exact:true}).click();await page.getByRole('button',{name:'取消识别',exact:true}).click();
  await page.getByRole('button',{name:'重试识别',exact:true}).waitFor({timeout:20000});await workerExited();
  assert.deepEqual(errors,[]);assert.equal(adb('shell','pidof','cn.local.ocr.test'),main);
  fs.writeFileSync(`${out}/device-final.json`,JSON.stringify({passed:true,mainPid:main,oldWorker:first,newWorker:second,enterDocumentMs:entered,cancelledWorkerExited:true,loadingCancellationPassed:true,errors},null,2));
  await page.getByRole('button',{name:'返回首页',exact:true}).click();console.log('PASS final APK: worker replacement, cancellation, loading cancellation, main process survives');
 } finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
