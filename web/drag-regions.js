import{useEffect,useRef,useState}from'react';
// Native touchmove cancellation is enabled only after the hold succeeds; ordinary swipes scroll.
export function useRegionDrag(reader,{enabled,blocks,onStart,onCommit,onError}){
 const latest=useRef();latest.current={enabled,blocks,onStart,onCommit,onError};
 const [hint,setHint]=useState(null);
 useEffect(()=>{
  const root=reader.current;if(!root||!enabled)return;let pending=null,drag=null,timer,frame,suppressClickUntil=0;
  const clearMarks=()=>root.querySelectorAll('.drag-selected,.drop-before,.drop-after').forEach(e=>e.classList.remove('drag-selected','drop-before','drop-after'));
  function reset(){clearTimeout(timer);cancelAnimationFrame(frame);pending=null;drag=null;clearMarks();setHint(null);}
  function target(){
   if(!drag)return;clearMarks();drag.element.classList.add('drag-selected');
   const candidates=[...root.querySelectorAll('.region[data-done="true"]')].filter(el=>{const b=latest.current.blocks.find(b=>b.id===el.dataset.region);return b&&(b.page||1)===drag.page&&b.id!==drag.id;});
   const before=candidates.find(el=>{const r=el.getBoundingClientRect();return drag.y<r.top+r.height/2;});drag.before=before?.dataset.region||null;
   const marker=before||candidates.at(-1);marker?.classList.add(before?'drop-before':'drop-after');
   setHint({x:Math.max(10,Math.min(innerWidth-170,drag.x+12)),y:Math.max(10,Math.min(innerHeight-50,drag.y-45))});
  }
  function scroll(){if(!drag)return;const r=root.getBoundingClientRect();if(drag.y<r.top+56)root.scrollTop-=8;else if(drag.y>r.bottom-56)root.scrollTop+=8;target();frame=requestAnimationFrame(scroll);}
  function begin(x,y,node){
   if(!latest.current.enabled||drag||node.closest('button,a,input,textarea,select'))return;
   const element=node.closest('.region[data-done="true"]');if(!element||!root.contains(element))return;
   const block=latest.current.blocks.find(b=>b.id===element.dataset.region);if(!block)return;
   pending={id:block.id,page:block.page||1,element,x,y};timer=setTimeout(()=>{if(!pending)return;drag=pending;pending=null;suppressClickUntil=Date.now()+1000;window.getSelection()?.removeAllRanges();latest.current.onStart();navigator.vibrate?.(20);target();frame=requestAnimationFrame(scroll);},450);
  }
  function move(x,y,event){if(pending&&Math.hypot(x-pending.x,y-pending.y)>10){reset();return;}if(drag){if(event.cancelable)event.preventDefault();drag.x=x;drag.y=y;target();}}
  function end(cancel=false){
   if(!drag){reset();return;}const {id,page,before,x,y}=drag,r=root.getBoundingClientRect();suppressClickUntil=Date.now()+700;
   const current=latest.current.blocks,group=current.filter(b=>(b.page||1)===page),moved=group.find(b=>b.id===id),others=group.filter(b=>b.id!==id);const index=before?others.findIndex(b=>b.id===before):others.length;
   reset();if(cancel||!moved||index<0||x<r.left||x>r.right||y<r.top||y>r.bottom)return;
   others.splice(index,0,moved);let n=0;const order=current.map(b=>(b.page||1)===page?others[n++]:b);
   if(order.every((b,i)=>b.id===current[i].id))return;
   Promise.resolve(latest.current.onCommit(order)).catch(e=>latest.current.onError(e));
  }
  const touchStart=e=>{if(e.touches.length!==1){end(true);return;}begin(e.touches[0].clientX,e.touches[0].clientY,e.target);};
  const touchMove=e=>{if(e.touches.length!==1){end(true);return;}move(e.touches[0].clientX,e.touches[0].clientY,e);};
  const mouseDown=e=>{if(e.button===0)begin(e.clientX,e.clientY,e.target);};
  const mouseMove=e=>move(e.clientX,e.clientY,e),finish=()=>end(),cancel=()=>end(true),key=e=>{if(e.key==='Escape')cancel();};
  const context=e=>{if(e.target.closest('.region'))e.preventDefault();};
  const click=e=>{if(Date.now()<suppressClickUntil){e.preventDefault();e.stopPropagation();}};
  root.addEventListener('touchstart',touchStart,{passive:true});document.addEventListener('touchmove',touchMove,{passive:false});document.addEventListener('touchend',finish);document.addEventListener('touchcancel',cancel);
  root.addEventListener('mousedown',mouseDown);document.addEventListener('mousemove',mouseMove);document.addEventListener('mouseup',finish);root.addEventListener('contextmenu',context);root.addEventListener('dragstart',context);window.addEventListener('blur',cancel);root.addEventListener('click',click,true);document.addEventListener('keydown',key);window.addEventListener('resize',cancel);
  return()=>{reset();root.removeEventListener('touchstart',touchStart);document.removeEventListener('touchmove',touchMove);document.removeEventListener('touchend',finish);document.removeEventListener('touchcancel',cancel);root.removeEventListener('mousedown',mouseDown);document.removeEventListener('mousemove',mouseMove);document.removeEventListener('mouseup',finish);root.removeEventListener('contextmenu',context);root.removeEventListener('dragstart',context);window.removeEventListener('blur',cancel);root.removeEventListener('click',click,true);document.removeEventListener('keydown',key);window.removeEventListener('resize',cancel);};
 },[reader,enabled]);
 return hint;
}
