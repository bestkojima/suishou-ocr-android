import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir} from 'node:fs/promises';
const port=Number(process.env.OCR_TEST_PORT||4198), origin=`http://127.0.0.1:${port}`;
const server=spawn(process.env.PYTHON||(process.platform==='win32'?'python':'python3'),['-m','http.server',String(port),'--bind','127.0.0.1','--directory','web/dist'],{stdio:'ignore'});
let browser;
try {
  for(let i=0;i<50;i++){try{if((await fetch(origin)).ok)break;}catch{}await new Promise(r=>setTimeout(r,100));}
  browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_PATH||undefined,args:['--no-sandbox']});
  const page=await browser.newPage({viewport:{width:375,height:812},acceptDownloads:true});
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(origin);
  await page.evaluate(()=>localStorage.setItem('ocr-settings',JSON.stringify({chunk:512})));
  await page.reload();
  // 与真实引擎的 invalid_table_formula_syntax 终态相同：正文为空，原始输出在 provenance。
  const raw=String.raw`<table><tr><th colspan="2">发票金额</th></tr><tr><td rowspan="2">服务费</td><td>￥100.00</td></tr><tr><td>$\badcommand{税额}$</td></tr></table>`;
  const ir={status:'partial',pages:[{page_id:'p1',reading_order:['invoice'],blocks:[{id:'invoice',type:'table',status:'partial',content:{format:'markdown',text:''},provenance:{raw_output:raw}}]}]};
  const chooser=page.waitForEvent('filechooser');
  await page.getByRole('button',{name:'导入文件',exact:true}).click();
  await(await chooser).setFiles({name:'发票终态.json',mimeType:'application/json',buffer:Buffer.from(JSON.stringify(ir))});
  await page.getByRole('button',{name:'重新回放',exact:true}).waitFor();
  assert.equal(await page.locator('.markdown table').count(),1,'完成后仍须显示原始发票表格');
  assert.equal(await page.locator('.markdown th[colspan="2"]').count(),1);
  assert.equal(await page.locator('.markdown td[rowspan="2"]').count(),1);
  assert.match(await page.locator('.region').innerText(),/￥100.00/);
  await page.locator('.edit-region').click();
  assert.equal(await page.getByRole('textbox',{name:'校对 Markdown'}).inputValue(),raw);
  const corrected=raw.replace(String.raw`\badcommand{税额}`,String.raw`\text{税额}`);
  await page.getByRole('textbox',{name:'校对 Markdown'}).fill(corrected);
  await page.getByRole('button',{name:'保存修改',exact:true}).click();
  assert.equal(await page.locator('.markdown table').count(),1);
  await page.getByRole('button',{name:'返回首页',exact:true}).click();await page.reload();
  await page.getByRole('button',{name:'最近记录',exact:true}).click();await page.locator('.history-row>button').first().click();
  await page.locator('.markdown table').waitFor();
  assert.equal(await page.locator('.markdown table').count(),1);
  await mkdir('verification/table-loop-fix',{recursive:true});
  await page.locator('.region').scrollIntoViewIfNeeded();
  await page.screenshot({path:'verification/table-loop-fix/invoice-portrait.png'});
  for(const viewport of [{width:375,height:812},{width:1000,height:650}]){
    await page.setViewportSize(viewport);
    assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1));
  }
  assert.deepEqual(errors,[]);
  // Android 桥模拟：区域先完成、整页后保存，两次切换都须保持可读表格。
  const live=await browser.newPage({viewport:{width:375,height:812}});
  await live.addInitScript(raw=>{
    window.saved={id:'invoice-live',title:'发票实时识别',input:'source.png',mode:'pending-ocr',blocks:[],assets:{},pages:[],recognition:{docId:'invoice-live',jobId:'j1',sequence:1,state:'recognizing'}};
    window.AndroidHost={request(message){const {id,method}=JSON.parse(message);let value=true;
      if(method==='bootstrap')value={settings:{chunk:64},history:[]};
      if(['capture','open','save'].includes(method))value=window.saved;
      if(method==='recognitionStatus')value=window.saved.recognition;
      setTimeout(()=>window.nativeReply({id,value:structuredClone(value)}),0);
    }};
    window.invoice=raw;
  },raw);
  await live.goto(origin);await live.getByRole('button',{name:'拍照',exact:true}).click();
  await live.getByRole('heading',{name:'发票实时识别',exact:true}).waitFor();
  await live.evaluate(()=>{window.saved.recognition={...window.saved.recognition,sequence:2,stream:{revision:2,regions:[{requestId:'r1',type:'table',raw:window.invoice,done:true,finishReason:'complete'}]}};window.nativeEvent('recognition',window.saved.recognition);});
  await live.locator('.markdown table').waitFor();
  await live.evaluate(()=>{window.saved.mode='real-ocr';window.saved.blocks=[{id:'p1-invoice',type:'table',markdown:window.invoice,sourceStatus:'partial'}];window.saved.recognition={...window.saved.recognition,sequence:3,state:'partial',liveOutput:true};window.nativeEvent('recognition',window.saved.recognition);});
  await live.locator('.edit-region').waitFor();assert.equal(await live.locator('.markdown table').count(),1);
  await live.getByRole('button',{name:'返回首页',exact:true}).click();
  await live.evaluate(()=>{window.saved={...window.saved,mode:'pending-ocr',blocks:[],recognition:{docId:'invoice-live',jobId:'j2',sequence:1,state:'recognizing'}};});
  await live.getByRole('button',{name:'拍照',exact:true}).click();
  await live.getByRole('heading',{name:'发票实时识别',exact:true}).waitFor();
  await live.evaluate(()=>{window.saved.recognition={...window.saved.recognition,sequence:2,stream:{revision:2,regions:[{requestId:'r1',type:'text',raw:'异常重复',done:true,finishReason:'truncated',stopReason:'repetition'}]}};window.nativeEvent('recognition',window.saved.recognition);});
  await live.getByText('检测到重复输出，已停止此区域，请核对原图。',{exact:true}).waitFor();
  assert.equal(await live.locator('.receiving').count(),0);
  console.log('PASS 发票终态恢复原文、合并单元格、异常公式、校对保存及重开、横竖屏');
  console.log('PASS 模拟桥区域完成→整页保存保持表格；循环停止提示');
} finally {await browser?.close();server.kill();}
