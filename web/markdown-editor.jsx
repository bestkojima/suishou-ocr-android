import React,{useRef,useState}from'react';
import{Region}from'./renderer.jsx';
export function MarkdownEditor({value,onChange,components,block}){
 const input=useRef(),selection=useRef({start:0,end:0}),history=useRef([value]),index=useRef(0);const [preview,setPreview]=useState(false),[revision,setRevision]=useState(0);
 function remember(next){if(next!==history.current[index.current]){history.current=history.current.slice(0,index.current+1);history.current.push(next);if(history.current.length>100)history.current.shift();index.current=history.current.length-1;}onChange(next);setRevision(v=>v+1);}
 function select(){if(input.current)selection.current={start:input.current.selectionStart,end:input.current.selectionEnd};}
 function apply(kind){
  const {start,end}=selection.current,text=value.slice(start,end);let replacement,a=start,b=end,next;
  if(['heading','bullet','number','quote'].includes(kind)){
   a=value.lastIndexOf('\n',start-1)+1;const until=value.indexOf('\n',end>start&&value[end-1]==='\n'?end-1:end);b=until<0?value.length:until;
   const lines=value.slice(a,b).split('\n');replacement=lines.map((line,i)=>{const clean=line.replace(/^\s{0,3}(?:#{1,6}\s+|[-*+]\s+|\d+\.\s+|>\s?)/,'');return(kind==='heading'?'## ':kind==='bullet'?'- ':kind==='number'?`${i+1}. `:'> ')+clean;}).join('\n');
  }else if(kind==='bold')replacement=`**${text||'文字'}**`;
  else if(kind==='italic')replacement=`*${text||'文字'}*`;
  else if(kind==='formula')replacement=`\n\n$$\n${text||'x = y'}\n$$\n\n`;
  else if(kind==='break')replacement='  \n';
  else if(kind==='table')replacement='\n\n| 列一 | 列二 |\n| --- | --- |\n| 内容 | 内容 |\n\n';
  next=value.slice(0,a)+replacement+value.slice(b);remember(next);selection.current={start:a,end:a+replacement.length};requestAnimationFrame(()=>{input.current?.focus();input.current?.setSelectionRange(a,a+replacement.length);});
 }
 function undo(delta){const next=index.current+delta;if(next<0||next>=history.current.length)return;index.current=next;onChange(history.current[next]);selection.current={start:0,end:0};setRevision(v=>v+1);}
 return <div className="md-editor">
  <div className="md-toolbar" role="toolbar" aria-label="Markdown 常用格式">{[['heading','标题'],['bold','加粗'],['italic','斜体'],['bullet','列表'],['number','编号'],['quote','引用'],['formula','公式'],['break','换行'],['table','插入表格']].map(([key,label])=><button key={key} disabled={preview} onMouseDown={e=>e.preventDefault()} onClick={()=>apply(key)}>{label}</button>)}<button disabled={index.current===0} onClick={()=>undo(-1)}>撤销</button><button disabled={index.current>=history.current.length-1} onClick={()=>undo(1)}>重做</button><button aria-pressed={preview} onClick={()=>setPreview(v=>!v)}>{preview?'返回编辑':'预览效果'}</button></div>
  {preview?<div className="md-preview"><Region block={{...block,type:'text',raw:value,done:true,sourceStatus:'ok'}} components={components}/></div>:<textarea ref={input} aria-label="校对 Markdown" value={value} onSelect={select} onKeyUp={select} onClick={select} onChange={e=>{remember(e.target.value);select();}}/>}
  <p className="fine">选中文字后点击格式按钮；标题、列表和引用作用于所在行。支持直接修改 Markdown。{block.format==='html'?' 此区域含 HTML 表格，可直接修改单元格文字。':''}</p>
 </div>;
}
