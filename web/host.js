import fixtures from '../prototypes/ocr-streaming/fixture-data.json';
import { unzipSync, zipSync, strFromU8, strToU8 } from 'fflate';
export const native = !!window.AndroidHost;
const pending = new Map(); let next = 0;
window.nativeReply = ({id,value,error}) => { const p=pending.get(id);if(!p)return;pending.delete(id);error?p.reject(new Error(error)):p.resolve(value); };
const dbPromise = native ? null : new Promise((resolve,reject)=>{const r=indexedDB.open('suishou-ocr-v1',1);r.onupgradeneeded=()=>r.result.createObjectStore('documents',{keyPath:'id'});r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});
async function database(method,value){const db=await dbPromise;return new Promise((resolve,reject)=>{const tx=db.transaction('documents',method==='get'||method==='getAll'?'readonly':'readwrite');const req=tx.objectStore('documents')[method](value);tx.oncomplete=()=>resolve(req.result);tx.onerror=()=>reject(tx.error);});}
function prefs(){try{return {wifiOnly:true,debug:false,chunk:8,...JSON.parse(localStorage.getItem('ocr-settings')||'{}')};}catch{return {wifiOnly:true,debug:false,chunk:8};}}
const dataURL=blob=>new Promise((ok,no)=>{const r=new FileReader();r.onload=()=>ok(r.result);r.onerror=()=>no(r.error);r.readAsDataURL(blob);});
function dimensions(src){return new Promise((ok,no)=>{const image=new Image();image.onload=()=>ok({width:image.naturalWidth,height:image.naturalHeight});image.onerror=()=>no(Error('无法解码图片'));image.src=src;});}
const safePath=p=>p&&!p.startsWith('/')&&!p.includes('\\')&&!p.split('/').includes('..')&&!p.includes(':');
function normalize(ir,files,title){
 if(!ir.pages?.length)throw Error('JSON 缺少 DocumentIR pages');const assets={};
 for(const r of ir.resources||[]){if(!safePath(r.path))throw Error('无效资源路径');assets[r.path]={src:files[r.path]||'',width:r.width||400,height:r.height||240,missing:!files[r.path]};}
 const blocks=[];for(const [p,page] of ir.pages.entries()){const byId=Object.fromEntries((page.blocks||[]).map(b=>[b.id,b]));for(const id of page.reading_order||Object.keys(byId)){const b=byId[id];if(!b?.content)continue;const c=b.content;blocks.push({id:`${page.page_id||p}-${id}`,type:b.type,page:p+1,markdown:b.type==='image'?`![插图](${c.resource})`:c.format==='latex'?`$$\n${c.text}\n$$`:c.text||'',sourceStatus:b.status||'ok',resource:c.resource,format:c.format});}}
 if(blocks.length>10000)throw Error('区域超过 10000');return {id:crypto.randomUUID(),title,mode:'json-replay',status:'ready',progress:0,blocks,assets,pendingAnalysis:blocks.filter(b=>b.sourceStatus==='pending-layout').length,pendingOcr:blocks.filter(b=>b.sourceStatus==='pending-ocr').length,pages:ir.pages.map((p,i)=>({number:i+1,title:p.title,kind:p.kind,route:'JSON 结果回放'})),updatedAt:Date.now()};
}
async function imported(f){
 if(f.size>256*1024*1024)throw Error('文件超过 256 MB');let doc;
 if(/\.zip$/i.test(f.name)){
  let total=0;const files=unzipSync(new Uint8Array(await f.arrayBuffer()),{filter:e=>{if(!safePath(e.name))throw Error('无效资源路径');total+=e.originalSize;if(total>512*1024*1024)throw Error('解压内容过大');return true;}});
  const key=Object.keys(files).find(k=>/(^|\/)document.json$/.test(k));if(!key)throw Error('ZIP 中没有 document.json');const prefix=key.slice(0,-13),mapped={};
  for(const [path,bytes]of Object.entries(files))if(path.startsWith(prefix)&&/\.(png|jpe?g|webp|gif)$/i.test(path))mapped[path.slice(prefix.length)]=await dataURL(new Blob([bytes],{type:/\.jpe?g$/i.test(path)?'image/jpeg':/\.webp$/i.test(path)?'image/webp':/\.gif$/i.test(path)?'image/gif':'image/png'}));
  doc=normalize(JSON.parse(strFromU8(files[key])),mapped,f.name);
  const snapshot=files[prefix+'app-state.json'];if(snapshot){const state=JSON.parse(strFromU8(snapshot));if(state.appExport!==1)throw Error('不支持的应用文档版本');doc.blocks=state.blocks;doc.edits=state.edits;doc.pendingOcr=state.pendingOcr||0;doc.pendingAnalysis=state.pendingAnalysis||0;doc.pdfClassification=state.pdfClassification;doc.notice=state.notice;doc.title=state.title;doc.pages=(state.pages||[]).map(p=>({...p,source:mapped[p.source]}));doc.original=mapped[state.original];}
  doc.original ||= mapped[Object.keys(mapped).find(k=>k.startsWith('source.'))];
 }else if(/\.json$/i.test(f.name))doc=normalize(JSON.parse(await f.text()),{},f.name);
 else if(f.type.startsWith('image/')){const src=await dataURL(f),size=await dimensions(src);doc={id:crypto.randomUUID(),title:f.name,mode:'pending-ocr',status:'ready',progress:0,blocks:[{id:'image',type:'image',markdown:'![原图](assets/input.png)',resource:'assets/input.png',sourceStatus:'ok'}],assets:{'assets/input.png':{src,...size}},original:src,pages:[],notice:'图片已导入；真实 OCR 尚未接入。',updatedAt:Date.now()};}
 else throw Error('浏览器预览支持图片、JSON 和 ZIP；PDF / Office 请在 APK 中导入。');
 await database('put',doc);return doc;
}
async function pick(){return new Promise((resolve,reject)=>{const input=document.createElement('input');input.type='file';input.accept='.json,.zip,image/*';input.oncancel=()=>reject(Error('已取消导入'));input.onchange=()=>{const f=input.files[0];f?imported(f).then(resolve,reject):reject(Error('未选择文件'));};input.click();});}
function toIR(d){
 const pages=new Map((d.pages||[]).map((p,i)=>[p.number||i+1,{page_id:`p${p.number||i+1}`,title:p.title,kind:p.kind,blocks:[],reading_order:[]}]));
 for(const b of d.blocks){const number=b.page||1;if(!pages.has(number))pages.set(number,{page_id:`p${number}`,blocks:[],reading_order:[]});const page=pages.get(number);page.reading_order.push(b.id);page.blocks.push({id:b.id,type:b.type,status:b.sourceStatus,content:{format:b.format||'markdown',text:b.markdown,resource:b.resource||b.markdown.match(/!\[[^\]]*\]\(([^)]+)\)/)?.[1]||''}});}
 return {schema_version:'app-replay-1',pages:[...pages.values()],resources:Object.entries(d.assets).map(([path,a])=>({path,width:a.width,height:a.height}))};
}
async function exportDoc(d,format){
 const md=d.blocks.slice(0,d.progress).map(b=>d.edits?.[b.id]??b.markdown).join('\n\n');let blob;
 if(format==='zip'){
  const portable=structuredClone(d),files={'document.json':strToU8(JSON.stringify(toIR(d),null,2)),'document.md':strToU8(md)};portable.appExport=1;
  for(const [path,a]of Object.entries(portable.assets)){if(!safePath(path))throw Error('无效资源路径');if(a.src){files[path]=new Uint8Array(await(await fetch(a.src)).arrayBuffer());a.src=path;}}
  if(d.original){files['source.png']=new Uint8Array(await(await fetch(d.original)).arrayBuffer());portable.original='source.png';}
  files['app-state.json']=strToU8(JSON.stringify(portable));blob=new Blob([zipSync(files)],{type:'application/zip'});
 }else blob=new Blob([format==='txt'?md.replace(/^#{1,6}\s+/gm,'').replace(/!\[([^\]]*)\]\([^)]+\)/g,'[图片：$1]').replace(/<[^>]*>/g,' '):md],{type:'text/plain;charset=utf-8'});
 const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=d.title+'.'+format;a.click();setTimeout(()=>URL.revokeObjectURL(a.href),2000);return true;
}
async function browser(method,args){switch(method){
case 'bootstrap':return {testBuild:true,preview:true,settings:prefs(),history:await browser('history',{}),version:'0.6.0-edit'};
case 'history':return (await database('getAll')).sort((a,b)=>b.updatedAt-a.updatedAt).map(d=>({id:d.id,title:d.title,mode:d.mode,updatedAt:d.updatedAt,progress:d.progress,count:d.blocks.length,status:d.status}));
case 'sample':{const f=fixtures.find(f=>f.id===args.sample);const doc={...structuredClone(f),id:crypto.randomUUID(),title:f.title,mode:'json-replay',status:'ready',progress:0,pages:[{number:1,route:'JSON 结果回放'}],updatedAt:Date.now()};await database('put',doc);return doc;}
case 'open':return database('get',args.id);
case 'save':{const d=await database('get',args.id);const {order,...fields}=args;if(order){if(order.length!==d.blocks.length||new Set(order).size!==order.length)throw Error('区域列表无效');const map=new Map(d.blocks.map(b=>[b.id,b]));const completed=fields.progress??d.progress;if((fields.status??d.status)==='running')throw Error('请先暂停输出');const blocks=order.map((id,i)=>{const b=map.get(id);if(!b||(b.page||1)!==(d.blocks[i].page||1))throw Error('只能在同一页内移动区域');if(i>=completed&&id!==d.blocks[i].id)throw Error('只能移动已完成区域');return b;});d.blocks=blocks;d.orderEdited=true;}Object.assign(d,fields,{updatedAt:Date.now()});await database('put',d);return d;}
case 'delete':await database('delete',args.id);return browser('history',{});
case 'settings':{const p={...prefs(),...args};localStorage.setItem('ocr-settings',JSON.stringify(p));return p;}
case 'import':return pick();
case 'models':return {tasks:[],repos:JSON.parse(localStorage.getItem('ocr-repos')||'["dr3334/PP-DocLayoutV3-mnn","dr3334/ovrics-ocrv2_mnn"]'),running:false,preview:true};
case 'addRepo':{let repo=args.repo.trim();if(repo.startsWith('https://')){const u=new URL(repo);if(!['modelscope.cn','www.modelscope.cn'].includes(u.hostname)||u.username||u.password||u.port)throw Error('请输入 ModelScope 仓库链接');const p=u.pathname.split('/');if(p[1]!=='models')throw Error('链接应包含 /models/作者/仓库');repo=p.slice(2,4).join('/');}if(!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\/[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$/.test(repo))throw Error('仓库格式应为 作者/仓库名');const state=await browser('models',{});const repos=[...new Set([...state.repos,repo])];localStorage.setItem('ocr-repos',JSON.stringify(repos));return repos;}
case 'forgetRepo':{if(['dr3334/PP-DocLayoutV3-mnn','dr3334/ovrics-ocrv2_mnn'].includes(args.repo))throw Error('默认仓库保留入口');const state=await browser('models',{});const repos=state.repos.filter(r=>r!==args.repo);localStorage.setItem('ocr-repos',JSON.stringify(repos));return repos;}
case 'cameraBounds':case 'cameraPermission':return true;
case 'export':return exportDoc(await database('get',args.id),args.format);
default:throw Error('此功能使用 Android 原生服务，请在 APK 中测试。');}}
export function request(method,args={}){if(!native)return browser(method,args);return new Promise((resolve,reject)=>{const id=String(++next);pending.set(id,{resolve,reject});window.AndroidHost.request(JSON.stringify({id,method,args}));});}
