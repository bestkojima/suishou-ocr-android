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
}
