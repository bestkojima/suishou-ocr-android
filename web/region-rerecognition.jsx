import React,{useEffect,useRef,useState} from 'react';
import {native,request} from './host.js';
import {Region} from './renderer.jsx';

const active=s=>['preparing','recognizing','saving','cancelling'].includes(s?.state);
export function RegionRerecognition({documentId,block,components,onResult}) {
  const [options,setOptions]=useState(null),[model,setModel]=useState(''),[state,setState]=useState(null);
  const [error,setError]=useState(''),[starting,setStarting]=useState(false);
  const alive=useRef(true),latest=useRef(null),startingRef=useRef(false);
  function accept(value) {
    if(!alive.current||value?.docId!==documentId||value?.blockId!==block.id)return;
    if(latest.current?.jobId===value.jobId&&(latest.current.sequence||0)>(value.sequence||0))return;
    latest.current=value;setState(value);
  }
  useEffect(()=>{
    alive.current=true;
    if(native)request('regionRecognitionOptions',{id:documentId,blockId:block.id}).then(value=>{
      if(!alive.current)return;
      setOptions(value);accept(value.state);
      const available=value.models.filter(m=>m.ready);
      setModel((available.find(m=>m.id!==value.defaultModel)||available[0]||value.models[0])?.id||'');
    }).catch(e=>{if(alive.current)setError(e.message);});
    return()=>{alive.current=false;};
  },[documentId,block.id]);
  useEffect(()=>{
    if(!active(state))return;
    let disposed=false,timer;
    async function poll(){
      try{const value=await request('regionRecognitionStatus',{id:documentId});if(!disposed&&!startingRef.current)accept(value);}
      catch(e){if(!disposed&&alive.current)setError(e.message);}
      finally{if(!disposed)timer=setTimeout(poll,350);}
    }
    timer=setTimeout(poll,150);return()=>{disposed=true;clearTimeout(timer);};
  },[documentId,state?.jobId,active(state)]);
  useEffect(()=>{
    if(state?.resultApplied)Promise.resolve(onResult(state)).catch(e=>{if(alive.current)setError(e.message);});
  },[state?.jobId,state?.resultApplied]);
  async function start(){
    startingRef.current=true;setStarting(true);setError('');
    try{const value=await request('recognizeRegion',{id:documentId,blockId:block.id,model});accept(value);}
    catch(e){if(alive.current)setError(e.message);}
    finally{startingRef.current=false;if(alive.current)setStarting(false);}
  }
  async function cancel(){
    setError('');
    try{accept(await request('cancelRecognition',{id:documentId,jobId:state.jobId}));}
    catch(e){if(alive.current)setError(e.message);}
  }
  const running=starting||active(state),candidate=state?.result;
  return <details className="region-retry">
    <summary>重新识别此区域</summary>
    {!native?<p className="fine">请在 Android App 中选择模型并执行区域重新识别。</p>:<>
      <p className="fine">只识别当前区域。新结果会自动覆盖此区域的旧输出和草稿，并保存；失败或取消则保留原内容。</p>
      {error&&<p role="alert">{error}</p>}
      {!options&&!error&&<p role="status">正在读取区域和模型…</p>}
      {options?.unavailable?<p className="fine">{options.unavailable}</p>:options&&<>
        <label className="region-retry-model">本次识别模型<select aria-label="区域识别模型" value={model} disabled={running} onChange={e=>setModel(e.target.value)}>
          {options.models.map(m=><option key={m.id} value={m.id} disabled={!m.ready}>{m.name}{m.ready?'':'（需先下载）'}</option>)}
        </select></label>
        {!options.models.some(m=>m.ready)&&<p className="fine">请先在“设置 → 识别模型”完成模型下载，再重新打开校对。</p>}
        {options.source&&<details><summary>查看此区域原图</summary><img className="region-retry-source" src={options.source} alt="当前识别区域原图"/></details>}
        <div className="actions"><button disabled={running||!options.models.some(m=>m.id===model&&m.ready)} onClick={start}>{starting?'正在启动…':'开始区域识别'}</button>
          {active(state)&&<button disabled={state.state==='cancelling'||state.resultSaved} onClick={cancel}>{state.state==='cancelling'?'取消中…':'取消区域识别'}</button>}</div>
      </>}
      {state&&<p role="status">{state.message}</p>}
      {running&&<p className="fine">关闭校对后仍会继续识别并替换此区域，可回到此区域取消。</p>}
      {!running&&candidate&&<div className="region-retry-candidate">
        <strong>新识别结果 · {options?.models.find(m=>m.id===state.modelChoice)?.name||state.modelChoice}</strong>
        {candidate.sourceStatus!=='ok'&&<p className="warning">{candidate.stopReason==='repetition'?'检测到重复输出，已提前停止。':'此结果未完整通过识别校验。'}请对照原图核对。</p>}
        <Region block={{...block,raw:candidate.markdown,done:true,live:false,sourceStatus:candidate.sourceStatus}} components={components}/>
        <p className="fine">{state.resultApplied?'已替换旧输出并保存，可在下方继续校对。':'此结果尚未替换正文，请重新识别。'}</p>
      </div>}
    </>}
  </details>;
}
