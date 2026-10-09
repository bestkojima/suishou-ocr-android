import React,{useRef,useState,useEffect}from'react';
import{request}from'./host.js';

const full=[[0,0],[1,0],[1,1],[0,1]],names=['左上','右上','右下','左下'];
function valid(p){
 let area=0;
 for(let i=0;i<4;i++){
  const a=p[i],b=p[(i+1)%4],c=p[(i+2)%4];
  if(!a.every(Number.isFinite)||a.some(n=>n<0||n>1)||(b[0]-a[0])*(c[1]-b[1])-(b[1]-a[1])*(c[0]-b[0])<=1e-6)return false;
  area+=a[0]*b[1]-b[0]*a[1];
 }
 return area/2>=.005;
}
export function DocumentCropEditor({doc,onCancel,onConfirmed,onBusy}){
 const crop=doc.documentCrop,w=crop.width,h=crop.height;
 const[points,setPoints]=useState(crop.points||full),[preview,setPreview]=useState(null),[busy,setBusy]=useState(''),[error,setError]=useState(''),[loaded,setLoaded]=useState(false),[message,setMessage]=useState(crop.message||'拖动四角贴合文档边缘，也可拖动框内移动整个选框');
 const svg=useRef(),drag=useRef(),dialog=useRef();
 useEffect(()=>{const element=dialog.current;element.showModal();return()=>element.close();},[]);
 useEffect(()=>{onBusy(!!busy);return()=>onBusy(false);},[busy,onBusy]);
 function change(next){if(!valid(next)){setError('选框不能交叉或过小，请调整其他边角');return;}setPoints(next);setPreview(null);setError('');}
 function coordinate(e){const matrix=svg.current?.getScreenCTM();if(!matrix)return[0,0];const point=new DOMPoint(e.clientX,e.clientY).matrixTransform(matrix.inverse());return[point.x/w,point.y/h];}
 function start(e,index){if(busy||!loaded)return;e.preventDefault();svg.current.setPointerCapture(e.pointerId);drag.current={id:e.pointerId,index,start:coordinate(e),points:points.map(p=>[...p])};setPreview(null);}
 function move(e){const d=drag.current;if(!d||d.id!==e.pointerId||busy)return;const position=coordinate(e);let next=d.points.map(p=>[...p]);
  if(d.index>=0)next[d.index]=position.map(n=>Math.max(0,Math.min(1,n)));
  else{const dx=Math.max(-Math.min(...next.map(p=>p[0])),Math.min(1-Math.max(...next.map(p=>p[0])),position[0]-d.start[0]));const dy=Math.max(-Math.min(...next.map(p=>p[1])),Math.min(1-Math.max(...next.map(p=>p[1])),position[1]-d.start[1]));next=next.map(p=>[p[0]+dx,p[1]+dy]);}
  change(next);
 }
 function finish(){drag.current=null;}
 function keyboard(e,index){if(busy||!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(e.key))return;e.preventDefault();const next=points.map(p=>[...p]),step=e.shiftKey ? .02 : .005;const axis=e.key==='ArrowLeft'||e.key==='ArrowRight'?0:1;next[index][axis]=Math.max(0,Math.min(1,next[index][axis]+(e.key==='ArrowLeft'||e.key==='ArrowUp'?-step:step)));change(next);}
 async function operate(label,work){if(busy)return;setBusy(label);setError('');try{await work();}catch(e){setError(e.message||String(e));}finally{setBusy('');}}
 async function detect(){await operate('正在检测文档边界…',async()=>{const fresh=await request('beginCrop',{id:doc.id});setPoints(fresh.documentCrop.points);setMessage(fresh.documentCrop.message);setPreview(null);});}
 async function previewCrop(){await operate('正在生成裁剪预览…',async()=>{setPreview(await request('previewCrop',{id:doc.id,points}));});}
 async function confirm(){await operate('正在确认裁剪…',async()=>{onConfirmed(await request('confirmCrop',{id:doc.id,token:preview.token}));});}
 const path=points.map(p=>`${p[0]*w},${p[1]*h}`).join(' '),radius=Math.max(w,h)/35;
 return <dialog ref={dialog} className="document-crop" aria-label="调整文档裁剪" onCancel={e=>{e.preventDefault();if(!busy)onCancel(false);}}>
  <header><button disabled={!!busy} onClick={()=>onCancel(false)}>返回</button><div><h1>调整文档裁剪</h1><small>原始照片已保留，确认后开始识别</small></div></header>
  <p className="crop-help" role="status">{busy||message}</p>
  <div className="crop-workspace">
   {preview?<div className="crop-preview"><img src={preview.src} alt="裁剪后的文档预览" onError={()=>{setPreview(null);setError('裁剪预览加载失败，请重新预览');}}/><p>确认后将识别这张裁剪图片 · {preview.width} × {preview.height}</p></div>:
    <svg ref={svg} className="crop-canvas" viewBox={`0 0 ${w} ${h}`} aria-label="文档边界调整区" onPointerMove={move} onPointerUp={finish} onPointerCancel={finish} onLostPointerCapture={finish}>
     <image href={crop.sourceUrl} width={w} height={h} onLoad={()=>setLoaded(true)} onError={()=>{setLoaded(false);setError('原始照片加载失败，请返回后重试');}}/>
     <path d={`M0 0H${w}V${h}H0Z M${points.map(p=>`${p[0]*w} ${p[1]*h}`).join('L')}Z`} fill="#112a2699" fillRule="evenodd" pointerEvents="none"/>
     <polygon points={path} fill="#ffffff05" stroke="#60edb4" strokeWidth="3" vectorEffect="non-scaling-stroke" onPointerDown={e=>start(e,-1)} aria-label="移动整个裁剪框"/>
     {points.map((p,i)=><g key={i}><circle data-corner={i} cx={p[0]*w} cy={p[1]*h} r={radius*1.85} fill="transparent" stroke="transparent" strokeWidth="3" vectorEffect="non-scaling-stroke" tabIndex={busy?-1:0} role="button" aria-label={`调整${names[i]}角`} onPointerDown={e=>start(e,i)} onKeyDown={e=>keyboard(e,i)}/><circle cx={p[0]*w} cy={p[1]*h} r={radius} fill="#fff" fillOpacity=".9" stroke="#25885e" strokeWidth="3" vectorEffect="non-scaling-stroke" pointerEvents="none" aria-hidden="true"/></g>)}
    </svg>}
  </div>
  {error&&<p className="crop-error" role="alert">{error}</p>}
  <div className="crop-tools"><button disabled={!!busy} onClick={detect}>重新检测</button><button disabled={!!busy} onClick={()=>change(full.map(p=>[...p]))}>使用整张照片</button>{preview&&<button disabled={!!busy} onClick={()=>setPreview(null)}>返回调整</button>}<button disabled={!!busy} onClick={()=>onCancel(true)}>重新拍照</button></div>
  <footer><button disabled={!!busy} onClick={()=>onCancel(false)}>取消裁剪</button><button className="primary" disabled={!!busy||!loaded} onClick={preview?confirm:previewCrop}>{preview?'确认并识别':'预览裁剪'}</button></footer>
 </dialog>;
}
