// 识别终态优先于最后一帧流输出；不把取消的片段当作已保存结果回放。
export const activeRecognition=s=>['preparing','recognizing','cancelling','saving'].includes(s?.state);
export const stoppedRecognition=s=>['cancelled','failed','missing-models'].includes(s?.state);
export function stopReplay(previous){return {...previous,phase:'interrupted',index:0,blocks:[],events:[]};}
export function recognitionCaption(state){return state?.state==='cancelled'?'已取消':state?.state==='failed'?'识别失败':state?.state==='missing-models'?'模型未就绪':'';}
