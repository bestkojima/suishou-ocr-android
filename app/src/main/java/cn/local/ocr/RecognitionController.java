package cn.local.ocr;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** 应用级单作业控制；页面销毁不拥有引擎，也不取消推理。 */
final class RecognitionController {
    interface Listener { void changed(JSONObject state); }
    private static RecognitionController instance;
    static synchronized RecognitionController get(Context c) {
        if(instance==null) instance=new RecognitionController(c.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final DocumentStore store;
    private final RecognitionModels models;
    private final ExecutorService inference=Executors.newSingleThreadExecutor();
    private final List<Listener> listeners=new CopyOnWriteArrayList<>();
    private JSONObject readiness=FilesUtil.obj("state","unchecked","message","请校验并加载识别模型");
    private JSONObject current;
    private boolean busy, cancelled;
    private long engine, nativeJob, sequence;
    private RecognitionController(Context c) { context=c;store=new DocumentStore(c);models=new RecognitionModels(c); }
    DocumentStore store() { return store; }
    void listen(Listener l) { listeners.add(l); }
    void unlisten(Listener l) { listeners.remove(l); }
    synchronized JSONObject readiness() throws Exception {
        return new JSONObject(readiness.toString()).put("inUse",busy);
    }
    synchronized JSONObject start(String id,boolean again) throws Exception {
        if(busy) throw new IOException("已有识别作业，请返回该文档或等待安全停止");
        JSONObject doc=store.load(id);
        if(doc.optString("input").isEmpty()) throw new IOException("首轮仅支持已保存的单张图片");
        if(DownloadService.active||DownloadService.reserved) throw new IOException("请先完成或暂停模型下载，再开始识别");
        if(doc.optString("mode").equals("real-ocr")) {
            if(!again) throw new IOException("已有识别结果，请使用重新识别另存新记录");
            doc=store.copyInput(doc);
        }
        busy=true;cancelled=false;nativeJob=0;
        current=FilesUtil.obj("docId",doc.getString("id"),"jobId",UUID.randomUUID().toString(),"state","preparing","message","正在校验模型文件","sequence",++sequence);
        try{doc.put("recognition",new JSONObject(current.toString()));store.save(doc);}catch(Exception e){busy=false;current=null;throw e;}
        String documentId=doc.getString("id");
        inference.execute(()->run(documentId));
        return doc;
    }
    JSONObject autoStart(JSONObject doc) throws Exception {
        if(!doc.optString("mode").equals("pending-ocr")||doc.optString("input").isEmpty()||doc.optJSONObject("recognition")!=null&&doc.getJSONObject("recognition").optString("state").equals("failed")) return doc;
        synchronized(this) {
            if(busy||DownloadService.active||DownloadService.reserved) {
                doc.put("recognition",FilesUtil.obj("state","waiting","message",busy?"输入已保存，已有识别作业；稍后可开始识别":"输入已保存，请完成模型下载后开始识别"));
                store.save(doc);return doc;
            }
            return start(doc.getString("id"),false);
        }
    }
    synchronized JSONObject status(String id) throws Exception {
        if(current!=null&&id.equals(current.optString("docId"))) {
            if(nativeJob!=0&&!cancelled&&current.optString("state").equals("recognizing")) {
                JSONObject status=new JSONObject(NativeOcr.status(nativeJob));
                JSONObject event=status.optJSONObject("latestEvent");
                String stage=event!=null?event.optString("kind"):current.optString("stage");
                int completed=status.optInt("region_completed"),total=status.optInt("region_total");
                if(!stage.equals(current.optString("stage"))||completed!=current.optInt("regionCompleted")||total!=current.optInt("regionTotal")) {
                    current.put("stage",stage).put("regionCompleted",completed).put("regionTotal",total).put("sequence",++sequence);
                }
            }
            return new JSONObject(current.toString());
        }
        JSONObject doc=store.load(id),state=doc.optJSONObject("recognition");
        if(state==null) return FilesUtil.obj("state","waiting","message","输入已保存，可开始识别");
        if(Arrays.asList("preparing","recognizing","cancelling","saving").contains(state.optString("state"))) {
            if(doc.optString("mode").equals("real-ocr")) state.put("state",doc.optString("recognitionOutcome","succeeded")).put("resultSaved",true).put("message","识别结果已保存，以下为结果回放");
            else state.put("state","failed").put("message","上次识别随进程退出而停止，输入已保留，可重试");
            sequence=Math.max(sequence,state.optLong("sequence"))+1;
            state.put("sequence",sequence);
            store.save(doc);
        }
        return state;
    }
    synchronized JSONObject cancel(String id,String jobId) throws Exception {
        if(!busy||current==null||!id.equals(current.optString("docId"))||!jobId.equals(current.optString("jobId"))) throw new IOException("该作业已结束或不是当前作业");
        if(current.optBoolean("resultSaved")) return new JSONObject(current.toString());
        cancelled=true;
        if(nativeJob!=0) {
            JSONObject nativeState=new JSONObject(NativeOcr.status(nativeJob));
            if(!nativeState.optBoolean("terminal")) {
                try{NativeOcr.cancel(nativeJob);}catch(IOException e){if(!new JSONObject(NativeOcr.status(nativeJob)).optBoolean("terminal"))throw e;}
            }
        }
        update("cancelling","正在安全停止，当前区域完成及资源清理后结束");
        return new JSONObject(current.toString());
    }
    synchronized JSONObject activate() throws Exception {
        if(busy) throw new IOException("已有识别或加载作业");
        if(DownloadService.active||DownloadService.reserved) throw new IOException("请先完成或暂停下载");
        busy=true;cancelled=false;current=null;readiness=FilesUtil.obj("state","loading","message","正在校验并加载本地模型");
        inference.execute(()->{try{prepare();}catch(Exception|LinkageError e){synchronized(this){readiness=FilesUtil.obj("state","load-failed","message","模型加载失败："+e.getMessage());}}finally{synchronized(this){busy=false;}}});
        return readiness();
    }
    private boolean prepare() throws Exception {
        synchronized(this){readiness=FilesUtil.obj("state","checking","message","正在核验九个必需模型工件");}
        JSONObject verified=models.verify();
        synchronized(this){readiness=verified;}
        if(!verified.optString("state").equals("files-ready")) return false;
        synchronized(this){if(cancelled&&current!=null)return false;readiness=FilesUtil.obj("state","loading","message","文件校验通过，正在加载生产引擎");if(current!=null)update("preparing",readiness.getString("message"));}
        if(engine==0) engine=NativeOcr.create(models.config(),context.getCacheDir().getPath());
        synchronized(this){readiness=FilesUtil.obj("state","ready","message","本地引擎已实际加载，支持离线单图识别");}
        return true;
    }
    private synchronized void update(String state,String message) throws Exception {
        current.put("state",state).put("message",message).put("sequence",++sequence);
        JSONObject doc=store.load(current.getString("docId"));doc.put("recognition",new JSONObject(current.toString()));store.save(doc);
        for(Listener l:listeners) l.changed(new JSONObject(current.toString()));
    }
    private void run(String id) {
        String terminal="failed",message="识别失败，输入已保留";
        try {
            JSONObject savedInput=store.load(id);File image=FilesUtil.child(store.dir(id),savedInput.getString("input"));
            if(!image.isFile()) {
                int[] size=ImageInput.normalize(FilesUtil.child(store.dir(id),savedInput.getString("rawInput")),image);
                synchronized(store) {
                    savedInput=store.load(id);savedInput.put("original",store.url(id,savedInput.getString("input")));
                    savedInput.getJSONObject("assets").put(savedInput.getString("input"),FilesUtil.obj("src",savedInput.getString("original"),"width",size[0],"height",size[1]));
                    store.save(savedInput);
                }
            }
            if(!prepare()) { terminal="missing-models";message=readiness().getString("message");return; }
            synchronized(this) {
                if(cancelled) {terminal="cancelled";message="已安全取消，输入已保留，可重试";return;}
                nativeJob=NativeOcr.jobCreate(engine);update("recognizing","正在本地识别，阶段与区域进度来自引擎");
            }
            JSONObject doc=store.load(id);
            int code=NativeOcr.run(nativeJob,FilesUtil.child(store.dir(id),doc.getString("input")).getPath());
            synchronized(this) {
                if(cancelled||code==7) {terminal="cancelled";message="已安全取消，输入已保留，可重试";return;}
                if(code!=0) throw new IOException("引擎返回 "+code+"："+NativeOcr.status(nativeJob));
                update("saving","识别已返回，正在保存完整结果和资源");
            }
            File output=new File(store.dir(id),"recognized");FilesUtil.remove(output);output.mkdirs();
            NativeOcr.export(nativeJob,output.getPath());
            synchronized(this) {
                if(cancelled){terminal="cancelled";message="已安全取消，输入已保留，可重试";return;}
                JSONObject result=store.recognized(id,output);
                current.put("resultSaved",true);
                terminal=result.optString("recognitionOutcome","succeeded");message=terminal.equals("partial")?"部分识别成功，未完成区域已标示":terminal.equals("blank")?"识别完成，此页没有正文区域":"识别完成，结果已保存；以下为结果回放";
                update("saving","结果已保存，正在释放原生资源");
            }
        } catch(Exception|LinkageError e) {
            message="识别失败："+e.getMessage()+"；输入已保留，可重试";
            synchronized(this){if(!readiness.optString("state").equals("ready")) readiness=FilesUtil.obj("state","load-failed","message",message);}
        } finally {
            long jobToClose,engineToClose;
            synchronized(this){jobToClose=nativeJob;nativeJob=0;engineToClose=engine;}
            try {
                // 不持有控制锁执行 native 清理；状态查询和取消仍能即时响应。
                if(jobToClose!=0) NativeOcr.jobDestroy(jobToClose);
                if(engineToClose!=0) NativeOcr.destroy(engineToClose);
                synchronized(this) {
                    engine=0;
                    if(readiness.optString("state").equals("ready")) readiness=FilesUtil.obj("state","files-ready","message","模型文件齐备；下次识别会重新加载引擎");
                    if(cancelled){terminal="cancelled";message="已安全取消，输入已保留，可重试";}
                    update(terminal,message);busy=false;
                }
            } catch(Exception|LinkageError e) {
                synchronized(this) {
                    try{update("failed","清理失败："+e.getMessage()+"；请重启 App 后重试");}catch(Exception ignored){}
                    // 清理失败时保留占用，不允许第二项销毁或复用仍存活的 native 资源。
                }
            }
        }
    }

    synchronized void checkModelMutation(String repo) throws Exception {
        if(busy&&Arrays.asList(ModelHub.REPOS).contains(repo)) throw new IOException("识别作业正在使用模型，请等待安全停止后再修改");
    }
    synchronized void reserveDownload(String repo) throws Exception {
        checkModelMutation(repo);
        if(DownloadService.active||DownloadService.reserved) throw new IOException("已有下载任务");
        if(Arrays.asList(ModelHub.REPOS).contains(repo)) {
            if(engine!=0){NativeOcr.destroy(engine);engine=0;}
            readiness=FilesUtil.obj("state","unchecked","message","下载完成后请校验并加载识别模型");
        }
        DownloadService.reserved=true;
    }
    synchronized JSONObject removeModel(String repo) throws Exception {
        checkModelMutation(repo);
        if(Arrays.asList(ModelHub.REPOS).contains(repo)) {
            if(engine!=0) {NativeOcr.destroy(engine);engine=0;}
            readiness=FilesUtil.obj("state","unchecked","message","模型已删除，请重新校验");
        }
        ModelHub.remove(context,repo);
        return ModelHub.summary(context);
    }
    synchronized void delete(String id) throws Exception {
        if(busy&&current!=null&&id.equals(current.optString("docId"))) throw new IOException("请先安全停止识别再删除输入");
        store.delete(id);
    }
}
