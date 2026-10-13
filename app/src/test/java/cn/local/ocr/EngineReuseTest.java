package cn.local.ocr;

import android.content.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class EngineReuseTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    RecognitionController controller;
    Field instance;
    Object previous;
    AtomicInteger preference = new AtomicInteger();
    AtomicReference<String> modelPreference = new AtomicReference<>("ovis");
    @Before public void setup() throws Exception {
        instance=RecognitionController.class.getDeclaredField("instance");instance.setAccessible(true);
        previous=instance.get(null);instance.set(null,null);
        Context context=new ContextWrapper(null) {
            @Override public Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return temp.getRoot();}
            @Override public File getCacheDir(){return temp.getRoot();}
            @Override public android.content.res.AssetManager getAssets(){throw new AssertionError("已有引擎不能重新读取加载工件");}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){
                return (SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                    if(method.getName().equals("getInt"))return preference.get();
                    if(method.getName().equals("edit"))return Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,action,values)->{
                        if(action.getName().equals("putInt")){preference.set((Integer)values[1]);return editor;}
                        if(action.getName().equals("putString")){modelPreference.set((String)values[1]);return editor;}
                        if(action.getName().equals("apply"))return null;
                        throw new UnsupportedOperationException(action.getName());
                    });
                    if(method.getName().equals("getString"))return args[0].equals("ocrModel")?modelPreference.get():args[1];
                    throw new UnsupportedOperationException(method.getName());
                });
            }
        };
        controller=RecognitionController.get(context);
    }
    @After public void restore() throws Exception {instance.set(null,previous);}
    void set(String name,Object value)throws Exception {Field f=RecognitionController.class.getDeclaredField(name);f.setAccessible(true);f.set(controller,value);}
    Object get(String name)throws Exception {Field f=RecognitionController.class.getDeclaredField(name);f.setAccessible(true);return f.get(controller);}
    Object call(String name)throws Exception {
        Method m=RecognitionController.class.getDeclaredMethod(name);m.setAccessible(true);
        try{return m.invoke(controller);}catch(InvocationTargetException e){throw new RuntimeException(e.getCause());}
    }
    static class Cleanup implements RecognitionController.NativeResources {
        int jobs,engines;long lastJob,lastEngine;boolean fail;
        public void jobDestroy(long job)throws IOException {jobs++;lastJob=job;if(fail)throw new IOException("controlled cleanup failure");}
        public void destroy(long engine)throws IOException {engines++;lastEngine=engine;if(fail)throw new IOException("controlled cleanup failure");}
    }
    @Test public void completedJobReleasesResultsButKeepsModelForNextImage()throws Exception {
        Cleanup cleanup=new Cleanup();set("resources",cleanup);set("engine",99L);set("nativeJob",12L);set("busy",true);
        call("cleanupCompletedJob");assertEquals(1,cleanup.jobs);assertEquals(12L,cleanup.lastJob);assertEquals(0,cleanup.engines);
        assertEquals(99L,get("engine"));assertEquals(0L,get("nativeJob"));
    }
    @Test public void memoryPressureReleasesOnlyAnIdleEngine()throws Exception {
        Cleanup cleanup=new Cleanup();set("resources",cleanup);set("engine",99L);set("busy",true);
        call("releaseCachedEngine");assertEquals(0,cleanup.engines);assertEquals(99L,get("engine"));
        set("busy",false);call("releaseCachedEngine");assertEquals(1,cleanup.engines);assertEquals(99L,cleanup.lastEngine);
        assertEquals(0L,get("engine"));assertFalse(controller.readiness().getBoolean("inUse"));
    }
    @Test public void threadChangesAreBlockedDuringInferenceAndInvalidateOnlyAnIdleEngine()throws Exception {
        Cleanup cleanup=new Cleanup();set("resources",cleanup);set("engine",99L);set("engineThreads",4);set("busy",true);
        assertThrows(IOException.class,()->controller.configureThreads(2));assertEquals(0,cleanup.engines);assertEquals(0,preference.get());
        set("busy",false);controller.configureThreads(2);assertEquals(1,cleanup.engines);assertEquals(0L,get("engine"));assertEquals(2,preference.get());
        controller.configureThreads(2);assertEquals(1,cleanup.engines);
    }
    @Test public void failedUnloadKeepsTheEngineBlockedAndDoesNotApplyThreadChanges()throws Exception {
        Cleanup cleanup=new Cleanup();cleanup.fail=true;set("resources",cleanup);set("engine",99L);set("engineThreads",4);
        assertThrows(IOException.class,()->controller.configureThreads(2));assertEquals(99L,get("engine"));assertEquals(0,preference.get());
        assertTrue(controller.readiness().getBoolean("inUse"));assertThrows(IOException.class,controller::activate);
    }
    @Test public void equivalentAutoAndExplicitChoicesKeepTheLoadedEngine()throws Exception {
        Cleanup cleanup=new Cleanup();set("resources",cleanup);set("engine",99L);
        int threads=RecognitionModels.effectiveCpuThreads(0,Runtime.getRuntime().availableProcessors());set("engineThreads",threads);
        controller.configureThreads(threads);assertEquals(0,cleanup.engines);assertEquals(99L,get("engine"));
        assertEquals(threads,controller.readiness().getInt("cpuThreads"));
    }
    @Test public void cpuThreadChoicesRespectAvailableProcessors() {
        assertEquals(4,RecognitionModels.effectiveCpuThreads(0,8));assertEquals(2,RecognitionModels.effectiveCpuThreads(0,3));
        assertEquals(1,RecognitionModels.effectiveCpuThreads(4,1));assertEquals(2,RecognitionModels.effectiveCpuThreads(4,2));
        assertEquals(1,RecognitionModels.effectiveCpuThreads(1,8));
        assertThrows(IllegalArgumentException.class,()->RecognitionModels.effectiveCpuThreads(3,8));
    }
    @Test public void preparedEngineIsReusedWithoutReloadingArtifacts()throws Exception {
        set("engine",99L);assertEquals(true,call("prepare"));assertEquals(99L,get("engine"));
        assertEquals("ready",controller.readiness().getString("state"));
    }
    void cacheProfiles()throws Exception {
        RecognitionModels models=(RecognitionModels)get("models");
        Field profiles=RecognitionModels.class.getDeclaredField("cachedProfiles");profiles.setAccessible(true);
        profiles.set(models,new JSONObject("{\"ovis\":{},\"glm\":{}}"));
    }
    @Test public void switchingModelUnloadsAnIdleEngineAndPersistsTheSelection()throws Exception {
        cacheProfiles();Cleanup cleanup=new Cleanup();set("resources",cleanup);set("engine",99L);
        controller.setModel("glm");assertEquals(1,cleanup.engines);assertEquals(0L,get("engine"));
        assertEquals("glm",modelPreference.get());assertEquals("glm",controller.readiness().getString("modelChoice"));
        controller.setModel("glm");assertEquals(1,cleanup.engines);
        assertThrows(IOException.class,()->controller.setModel("unknown"));assertEquals("glm",modelPreference.get());
    }
    @Test public void modelSwitchIsBlockedWhileBusyWithoutReadingArtifacts()throws Exception {
        set("busy",true);assertThrows(IOException.class,()->controller.setModel("glm"));
        assertEquals("ovis",modelPreference.get());
    }
    @Test public void modelSwitchIsBlockedWhileDownloadIsReserved()throws Exception {
        boolean previous=DownloadService.reserved;
        try {DownloadService.reserved=true;assertThrows(IOException.class,()->controller.setModel("glm"));}
        finally {DownloadService.reserved=previous;}
        assertEquals("ovis",modelPreference.get());
    }
    @Test public void failedUnloadDoesNotApplyAnotherModel()throws Exception {
        cacheProfiles();Cleanup cleanup=new Cleanup();cleanup.fail=true;set("resources",cleanup);set("engine",99L);
        assertThrows(IOException.class,()->controller.setModel("glm"));
        assertEquals("ovis",modelPreference.get());assertEquals(99L,get("engine"));assertTrue(controller.readiness().getBoolean("inUse"));
    }
    String regionDocument()throws Exception {
        DocumentStore store=controller.store();String id=store.create();
        FilesUtil.write(FilesUtil.child(store.dir(id),"assets/crop.png"),"controlled image");
        store.save(FilesUtil.obj("id",id,"mode","real-ocr","input","source.png","status","complete","progress",2,
            "blocks",new org.json.JSONArray().put(FilesUtil.obj("id","b1","type","table","resource","assets/crop.png","markdown","旧表格"))
                .put(FilesUtil.obj("id","b2","type","text","markdown","其他区域")),
            "edits",FilesUtil.obj("b1","原校对内容"),"recognition",FilesUtil.obj("state","succeeded","jobId","original")));
        cacheProfiles();set("engine",99L);set("engineModel","glm");return id;
    }
    class RegionNative extends Cleanup {
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),resume=new java.util.concurrent.CountDownLatch(1);
        boolean cancelled,exported;String path,type;
        public long jobCreate(long engine){assertEquals(99L,engine);return 12;}
        public int runRegion(long job,String path,String type)throws IOException {
            this.path=path;this.type=type;entered.countDown();
            try{if(!resume.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("test timeout");}catch(InterruptedException e){throw new IOException(e);}
            return cancelled?7:0;
        }
        public String status(long job){return "{\"terminal\":false}";}
        public void cancel(long job){cancelled=true;resume.countDown();}
        public void export(long job,String directory)throws IOException {
            exported=true;
            try{FilesUtil.write(new File(directory,"document.json"),"{\"pages\":[{\"blocks\":[{\"status\":\"ok\",\"content\":{\"text\":\"新表格\",\"format\":\"markdown\"}}]}]}");}
            catch(Exception e){throw new IOException(e);}
        }
    }
    void finished()throws Exception {
        ((java.util.concurrent.ExecutorService)get("inference")).submit(()->{}).get(8,java.util.concurrent.TimeUnit.SECONDS);
    }
    @Test public void regionResultReplacesOldOutputWithoutChangingDefaultModelOrOtherBlocks()throws Exception {
        String id=regionDocument();RegionNative api=new RegionNative();set("resources",api);
        JSONObject started=controller.startRegion(id,"b1","glm");
        assertTrue(api.entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
        assertThrows(IOException.class,()->controller.startRegion(id,"b2","ovis"));
        assertEquals("original",controller.status(id).getString("jobId"));
        api.resume.countDown();finished();
        JSONObject state=controller.regionStatus(id);assertEquals("succeeded",state.getString("state"));assertEquals("新表格",state.getJSONObject("result").getString("markdown"));
        JSONObject saved=controller.store().load(id);assertEquals("新表格",saved.getJSONObject("edits").getString("b1"));
        assertEquals("新表格",saved.getJSONArray("blocks").getJSONObject(0).getString("markdown"));
        assertFalse(controller.store().markdown(saved,false).contains("原校对内容"));
        assertTrue(state.getBoolean("resultApplied"));
        assertEquals("其他区域",saved.getJSONArray("blocks").getJSONObject(1).getString("markdown"));
        assertEquals("ovis",modelPreference.get());assertEquals("table",api.type);assertTrue(api.path.endsWith("assets/crop.png"));
        assertEquals(1,api.jobs);assertEquals(0,api.engines);assertFalse(controller.readiness().getBoolean("inUse"));
        assertEquals(started.getString("jobId"),state.getString("jobId"));
    }
    @Test public void staleDraftCannotRestoreOldOutputAndOtherRegionSaveKeepsReplacement()throws Exception {
        String id=regionDocument();RegionNative api=new RegionNative();set("resources",api);
        File original=new File(controller.store().dir(id),"document.json");FilesUtil.write(original,"original evidence");
        controller.startRegion(id,"b1","glm");api.resume.countDown();finished();
        DocumentStore store=controller.store();
        assertThrows(IOException.class,()->store.update(FilesUtil.obj("id",id,"blockEdit",FilesUtil.obj("id","b1","text","旧错误草稿","regionRevision",""))));
        store.update(FilesUtil.obj("id",id,"blockEdit",FilesUtil.obj("id","b2","text","其他区域新校对","regionRevision","")));
        JSONObject saved=store.load(id);assertEquals("新表格",saved.getJSONObject("edits").getString("b1"));
        assertEquals("其他区域新校对",saved.getJSONObject("edits").getString("b2"));
        String revision=saved.getJSONArray("blocks").getJSONObject(0).getString("regionRevision");
        store.update(FilesUtil.obj("id",id,"blockEdit",FilesUtil.obj("id","b1","text","完成后人工修改","regionRevision",revision)));
        assertTrue(store.markdown(store.load(id),false).contains("完成后人工修改"));
        assertEquals("original evidence",FilesUtil.read(original));
    }
    @Test public void cancellingRegionDoesNotExportOrReplaceTheDocument()throws Exception {
        String id=regionDocument();RegionNative api=new RegionNative();set("resources",api);
        JSONObject state=controller.startRegion(id,"b1","glm");assertTrue(api.entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
        controller.cancel(id,state.getString("jobId"));finished();
        assertEquals("cancelled",controller.regionStatus(id).getString("state"));assertFalse(api.exported);
        assertEquals("原校对内容",controller.store().load(id).getJSONObject("edits").getString("b1"));assertEquals(1,api.jobs);
    }
    @Test public void failedRegionLeavesExistingEditsAndReleasesJob()throws Exception {
        String id=regionDocument();RegionNative api=new RegionNative(){
            @Override public int runRegion(long job,String path,String type)throws IOException {throw new IOException("controlled inference failure");}
        };set("resources",api);controller.startRegion(id,"b1","glm");finished();
        JSONObject state=controller.regionStatus(id);assertEquals("failed",state.getString("state"));assertFalse(state.has("result"));
        assertEquals("原校对内容",controller.store().load(id).getJSONObject("edits").getString("b1"));
        assertEquals(1,api.jobs);assertFalse(controller.readiness().getBoolean("inUse"));
    }
    @Test public void perRequestModelSwitchUnloadsOldEngineWithoutChangingPreference()throws Exception {
        cacheProfiles();Cleanup cleanup=new Cleanup();set("resources",cleanup);set("engine",99L);set("engineModel","ovis");
        Method prepare=RecognitionController.class.getDeclaredMethod("prepare",String.class);prepare.setAccessible(true);
        // 测试 profile 故意没有模型工件，准备在卸载旧引擎后失败。
        assertThrows(InvocationTargetException.class,()->prepare.invoke(controller,"glm"));
        assertEquals(1,cleanup.engines);assertEquals(0L,get("engine"));assertEquals("ovis",modelPreference.get());
    }
    @Test public void interruptedRegionIsNotMistakenForSavedWholePageResult()throws Exception {
        String id=regionDocument();JSONObject doc=controller.store().load(id);
        doc.put("regionRecognition",FilesUtil.obj("docId",id,"blockId","b1","jobId","lost","state","recognizing","sequence",100));controller.store().save(doc);
        JSONObject state=controller.regionStatus(id);assertEquals("failed",state.getString("state"));assertTrue(state.getLong("sequence")>100);
        assertEquals("succeeded",controller.status(id).getString("state"));
    }
}
