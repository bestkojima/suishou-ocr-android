import React,{useEffect,useState}from'react';
import{request}from'./host.js';
export const defaultRepos=['dr3334/PP-DocLayoutV3-mnn','dr3334/ovrics-ocrv2_mnn','MNN/GLM-OCR-MNN'];
const bytes=n=>n>=1024**3?(n/1024**3).toFixed(2)+' GB':n>=1024**2?(n/1024**2).toFixed(1)+' MB':n>=1024?(n/1024).toFixed(1)+' KB':`${n||0} B`;
const labels={queued:'等待下载',downloading:'下载中',verifying:'校验中',verified:'SHA-256 校验通过',downloaded:'已下载 · 仅校验大小',paused:'已暂停',error:'下载失败'};
export function ModelManager({preview=false}){
 const [model,setModel]=useState({tasks:[],repos:defaultRepos}),[input,setInput]=useState(''),[opened,setOpened]=useState(''),[catalog,setCatalog]=useState({}),[selected,setSelected]=useState({}),[filter,setFilter]=useState(''),[loading,setLoading]=useState(''),[busy,setBusy]=useState(''),[error,setError]=useState('');
 useEffect(()=>{let alive=true,timer;async function poll(){try{const state=await request('models');if(alive)setModel(state);}catch(e){if(alive)setError(e.message);}finally{if(alive)timer=setTimeout(poll,1000);}}poll();return()=>{alive=false;clearTimeout(timer);};},[]);
 async function act(fn){setError('');try{await fn();}catch(e){setError(e.message);}}
 async function files(repo){setOpened(repo);setFilter('');setLoading(repo);await act(async()=>{const list=await request('catalog',{repo});setCatalog(c=>({...c,[repo]:list}));setSelected(c=>({...c,[repo]:list.filter(f=>!/(^|\/)(\.|README|LICENSE)/i.test(f.path)&&f.sha256).map(f=>f.path)}));});setLoading('');}
 async function start(repo){setBusy(repo);await act(async()=>{await request('download',{repo,paths:selected[repo]||[]});setModel(await request('models'));});setTimeout(()=>setBusy(''),1200);}
 const activity=model.activity||{};
 return <>
  <p className="fine">公开 ModelScope 仓库可自由添加、浏览和选择文件。下载成功不代表此模型已适配 OCR 引擎。</p>
  {model.readiness&&<div className="recognition-panel" role="status"><strong>{model.readiness.message}</strong><button disabled={model.readiness.inUse||model.running||preview} onClick={()=>act(async()=>{await request('activateModels');setModel(await request('models'));})}>加载识别模型</button></div>}
  {!!model.modelChoices?.length&&<label className="setting-row"><span>当前识别模型<small>先选择模型，再下载对应仓库文件并加载</small></span><select aria-label="当前识别模型" value={model.readiness?.modelChoice||'ovis'} disabled={model.readiness?.inUse||model.running||preview} onChange={e=>{const name=e.target.value;act(async()=>{const readiness=await request('setOcrModel',{model:name});setModel(m=>({...m,readiness}));});}}>{model.modelChoices.map(m=><option value={m.id} key={m.id}>{m.name}</option>)}</select></label>}
  {model.readiness?.cpuThreads&&<label className="setting-row"><span>CPU 推理线程<small>实际使用 {model.readiness.cpuThreads} 线程，连续识别复用已加载模型</small></span><select aria-label="CPU 推理线程" value={model.readiness.threadChoice??0} disabled={model.readiness.inUse||model.running||preview} onChange={e=>{const threads=+e.target.value;act(async()=>{const readiness=await request('setOcrThreads',{threads});setModel(m=>({...m,readiness}));});}}><option value={0}>自动（最多 4）</option>{[1,2,4].map(n=><option key={n} value={n}>{n}</option>)}</select></label>}
  {preview&&<p className="document-notice">可预览仓库管理；文件清单和实际下载请使用 APK。</p>}
  <form className="repo-add" onSubmit={e=>{e.preventDefault();act(async()=>{const repos=await request('addRepo',{repo:input});setModel(m=>({...m,repos}));setInput('');});}}>
   <label htmlFor="repo-input">添加模型仓库</label><input id="repo-input" value={input} onChange={e=>setInput(e.target.value)} placeholder="作者/仓库名，或 ModelScope 链接" autoCapitalize="none" spellCheck={false}/><button type="submit" disabled={!input.trim()}>添加仓库</button>
  </form>
  {error&&<p role="alert" className="document-notice">{error}</p>}
  {(model.repos||defaultRepos).map(repo=>{
   const active=activity.repo===repo,live=active&&model.running,tasks=model.tasks.filter(t=>t.repo===repo);
   const values=tasks.map(t=>({...t,downloaded:live&&activity.path===t.path&&activity.stage==='downloading'?activity.bytes:t.downloaded||0}));
   const total=values.reduce((s,t)=>s+(t.size||0),0),done=values.reduce((s,t)=>s+t.downloaded,0),complete=values.filter(t=>['verified','downloaded'].includes(t.status)).length;
   const list=catalog[repo],chosen=selected[repo]||[],visible=list?.filter(f=>f.path.toLowerCase().includes(filter.toLowerCase()));
   const selectedSize=list?.filter(f=>chosen.includes(f.path)).reduce((sum,f)=>sum+f.size,0)||0;
   const stale=live&&['connecting','downloading'].includes(activity.stage)&&Date.now()-(activity.updatedAt||0)>6000;
   return <section className="model-card" key={repo} aria-label={repo}>
    <strong>{repo===defaultRepos[0]?'版面分析':repo===defaultRepos[1]?'OvisOCR2 · 文字 / 表格 / 公式':repo===defaultRepos[2]?'GLM-OCR · 文字 / 表格 / 公式':'自定义仓库'}</strong><small>{repo}</small>
    {active&&<p className="download-stage" role="status">{activity.message}{activity.path&&` · ${activity.path}`}{stale&&' · 等待服务器响应，可暂停后重试'}{live&&activity.stage==='verifying'&&activity.total>0&&` · ${(activity.bytes*100/activity.total).toFixed(0)}%`}</p>}
    <p>{total?`${bytes(done)} / ${bytes(total)} · 文件完成 ${complete}/${tasks.filter(t=>t.size!==undefined).length}`:'尚未下载'}</p>
    {live&&['preparing','connecting'].includes(activity.stage)?<progress aria-label="正在连接"/>:<progress aria-label="下载进度" max={Math.max(total,1)} value={done}/>}
    <div className="actions">
     <button disabled={!!loading||preview} onClick={()=>opened===repo?setOpened(''):files(repo)}>{opened===repo?'收起文件':'浏览仓库文件'}</button>
     <button disabled={!live} onClick={()=>act(()=>request('pauseDownload'))}>暂停</button>
     <button disabled={model.running||model.readiness?.inUse||preview||!!busy} onClick={()=>act(async()=>{if(confirm('删除此仓库的下载文件和断点记录？')){setModel(await request('removeModel',{repo}));setCatalog(c=>{const next={...c};delete next[repo];return next;});setOpened('');}})}>删除文件</button>
     {!defaultRepos.includes(repo)&&<button disabled={model.running} onClick={()=>act(async()=>{const repos=await request('forgetRepo',{repo});setModel(m=>({...m,repos}));})}>移除入口</button>}
    </div>
    {opened===repo&&<div className="repo-files">
     {loading===repo?<p role="status">正在读取仓库清单…</p>:list&&<>
      <input aria-label="筛选仓库文件" placeholder="筛选文件名，例如 .mnn" value={filter} onChange={e=>setFilter(e.target.value)}/>
      <div className="actions"><button disabled={!!loading||model.running} onClick={()=>setSelected(s=>({...s,[repo]:[...new Set([...chosen,...visible.map(f=>f.path)])]}))}>选中筛选结果</button><button disabled={model.running} onClick={()=>setSelected(s=>({...s,[repo]:[]}))}>清空选择</button><button disabled={!!loading||model.running} onClick={()=>files(repo)}>刷新清单</button></div>
      <p className="fine">已选 {chosen.length} / {list.length} 个文件 · {bytes(selectedSize)}。配置、词表与权重通常需配套下载。</p>
      <div className="repo-file-list">{visible.map(f=><label className="model-file selectable" key={f.path}><input type="checkbox" aria-label={`选择 ${f.path}`} checked={chosen.includes(f.path)} disabled={model.running} onChange={e=>setSelected(s=>({...s,[repo]:e.target.checked?[...chosen,f.path]:chosen.filter(p=>p!==f.path)}))}/><span>{f.path}<small>{bytes(f.size)} · {f.sha256?'提供 SHA-256':'未提供 SHA-256，仅校验大小'}</small></span></label>)}</div>
      <button className="primary" disabled={!chosen.length||model.running||model.readiness?.inUse||!!busy} onClick={()=>start(repo)}>{busy===repo?'正在启动…':'下载 / 继续所选文件'}</button>
     </>}
    </div>}
    {!!tasks.length&&<details><summary>下载记录 · {tasks.length} 个文件</summary>{values.map((t,i)=><div className="model-file" key={i}><span>{t.path}</span><small>{t.error||labels[t.status]||t.status} · {bytes(t.downloaded)}{t.size!==undefined&&` / ${bytes(t.size)}`}</small></div>)}</details>}
   </section>;
  })}
  <p className="fine">{model.freeBytes?'可用空间 '+bytes(model.freeBytes):''} 默认仅在非计费网络下载，可在设置中调整。</p>
 </>;
}
