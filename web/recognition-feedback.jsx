import React,{useEffect,useRef,useState} from 'react';
import {request} from './host.js';

export function RecognitionFeedback({documentId,jobId}) {
 const key=`recognition-feedback:${documentId}:${jobId||''}`;
 const [draft,setDraft]=useState(()=>{try{return JSON.parse(localStorage.getItem(key))||{};}catch{return {};}});
 const value=useRef(draft),[options,setOptions]=useState(null),[busy,setBusy]=useState(false),[message,setMessage]=useState(''),[error,setError]=useState('');
 function remember(patch){const next={...value.current,...patch};value.current=next;localStorage.setItem(key,JSON.stringify(next));setDraft(next);return next;}
 useEffect(()=>{let active=true;request('feedbackOptions',{id:documentId,jobId:jobId||''}).then(o=>{if(active)setOptions(o);}).catch(e=>{if(active)setError(e.message);});return()=>{active=false;};},[documentId,jobId]);
 function edit(patch){remember({...patch,feedbackId:null,submitted:false});setMessage('');setError('');}
 async function pack(){if(value.current.feedbackId)return value.current;const result=await request('prepareFeedback',{id:documentId,jobId:jobId||'',category:value.current.category||'other',description:value.current.description||''});return remember(result);}
 async function act(action){if(busy)return;setBusy(true);setError('');setMessage('');try{const p=await pack();if(action==='submit'){remember(await request('submitFeedback',{feedbackId:p.feedbackId}));setMessage('反馈已收到，谢谢提供识别样本。');}else if(action==='share'){await request('shareFeedback',{feedbackId:p.feedbackId});setMessage('已打开系统分享，可选择飞书；请在接收方确认发送。');}else{await request('saveFeedback',{feedbackId:p.feedbackId});setMessage('反馈包已保存，尚未提交。');}}catch(e){setError(e.message);}finally{setBusy(false);}}
 const ready=!!options&&!!draft.description?.trim()&&!busy;
 return <div className="recognition-feedback">
  <p className="fine">附上本次识别图片和原始 JSON，描述哪里识别错了即可。校对后的修改不会覆盖原始 JSON。</p>
  {options&&<div className="feedback-attachments"><img src={options.image} alt="本次识别图片"/><span>本次识别图片<br/><code>{options.json}</code></span></div>}
  <label>问题分类（选填）<select aria-label="识别问题分类" disabled={busy} value={draft.category||'other'} onChange={e=>edit({category:e.target.value})}>{[['other','其他 / 不确定'],['text','文字识别错误'],['table-formula','表格或公式错误'],['layout','漏识别 / 顺序 / 版面错误'],['repetition','重复输出']].map(([id,label])=><option key={id} value={id}>{label}</option>)}</select></label>
  <label>问题描述<textarea aria-label="识别问题描述" rows={4} maxLength={4000} disabled={busy} value={draft.description||''} placeholder="例如：第二行数字识别错了，应该是 128.50" onChange={e=>edit({description:e.target.value})}/></label>
  <p className="fine">发送时将一起提供图片、原始 JSON、描述，以及应用版本、模型和设备信息。</p>
  {options&&!options.configured&&<p role="status">反馈接收地址尚未配置，可先保存反馈包，或通过系统分享发送到飞书。</p>}
  {draft.submitted&&<p role="status">此份反馈已提交。</p>}
  {draft.feedbackId&&!draft.submitted&&<p className="fine">反馈包已保存在本机，尚未提交。</p>}
  {options?.configured&&<button className="primary full" disabled={!ready||draft.submitted} onClick={()=>act('submit')}>{busy?'正在处理…':draft.submitted?'已提交反馈':'提交识别反馈'}</button>}
  {options?.backupConfigured&&<p className="fine">主渠道连接失败时，自动尝试飞书备用渠道。</p>}
  <div className="actions"><button disabled={!ready} onClick={()=>act('share')}>分享到飞书等应用</button><button disabled={!ready} onClick={()=>act('save')}>保存反馈包</button></div>
  {busy&&<p role="status">正在处理反馈…</p>}{message&&<p role="status">{message}</p>}{error&&<p role="alert">{error}</p>}
 </div>;
}
