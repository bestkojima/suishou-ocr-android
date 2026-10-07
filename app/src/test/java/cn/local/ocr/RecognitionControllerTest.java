package cn.local.ocr;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

public class RecognitionControllerTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    void set(RecognitionController controller,String name,Object value)throws Exception {
        Field field=RecognitionController.class.getDeclaredField(name);field.setAccessible(true);field.set(controller,value);
    }
    @Test public void cancellationStopsBeforeCommitAndRestartRecoversCommittedResults()throws Exception {
        File root=temp.getRoot();
        android.content.Context context=new android.content.ContextWrapper(null){
            @Override public android.content.Context getApplicationContext(){return this;}
            @Override public File getFilesDir(){return root;}
            @Override public File getCacheDir(){return root;}
            @Override public android.content.res.AssetManager getAssets(){throw new UnsupportedOperationException("JVM 测试不提供模型资源");}
            @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){
                return (android.content.SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),new Class<?>[]{android.content.SharedPreferences.class},
                    (proxy,method,args)->{if(method.getName().equals("getString"))return args[1];throw new UnsupportedOperationException(method.getName());});
            }
        };
        RecognitionController controller=RecognitionController.get(context);DocumentStore store=controller.store();String id=store.create();
        JSONObject state=FilesUtil.obj("docId",id,"jobId","job","state","saving","resultSaved",true,"sequence",42);
        JSONObject doc=FilesUtil.obj("id",id,"mode","real-ocr","recognitionOutcome","partial","recognition",state);store.save(doc);
        // 固定在 native 清理期间，不运行或伪造模型推理。
        set(controller,"current",state);set(controller,"busy",true);
        assertThrows(IOException.class,()->controller.start(id,false));
        assertThrows(IOException.class,controller::activate);
        assertThrows(IOException.class,()->controller.removeModel(ModelHub.REPOS[0]));
        assertThrows(IOException.class,()->controller.removeModel(ModelHub.REPOS[1]));
        assertThrows(IOException.class,()->controller.delete(id));
        assertThrows(IOException.class,()->controller.cancel(id,"older-job"));
        assertEquals("saving",controller.cancel(id,"job").getString("state"));
        assertEquals("saving",controller.status(id).getString("state"));
        set(controller,"current",null);set(controller,"busy",false);
        JSONObject committed=controller.status(id);
        assertEquals("partial",committed.getString("state"));assertEquals("partial",store.load(id).getJSONObject("recognition").getString("state"));
        assertTrue(committed.getLong("sequence")>42);
        assertEquals(committed.getLong("sequence"),controller.status(id).getLong("sequence"));
        // 提交前仍允许取消，并将状态保存给历史重开。
        state=FilesUtil.obj("docId",id,"jobId","retry","state","saving");doc.put("mode","pending-ocr").put("recognition",state);store.save(doc);
        set(controller,"current",state);set(controller,"busy",true);
        assertEquals("cancelling",controller.cancel(id,"retry").getString("state"));
        assertEquals("cancelling",store.load(id).getJSONObject("recognition").getString("state"));
        assertThrows(IOException.class,()->controller.start(id,false));
        assertThrows(IOException.class,()->controller.removeModel(ModelHub.REPOS[0]));
        assertEquals("cancelling",controller.cancel(id,"retry").getString("state"));
        set(controller,"busy",false);set(controller,"current",null);set(controller,"cancelled",false);
        assertEquals("failed",controller.status(id).getString("state"));
        // 历史重开时，恢复终态必须比落盘的活跃状态更新，前端才能接受。
        String interruptedId=store.create();
        store.save(FilesUtil.obj("id",interruptedId,"mode","pending-ocr","recognition",
            FilesUtil.obj("docId",interruptedId,"jobId","interrupted","state","recognizing","sequence",99)));
        JSONObject recovered=controller.status(interruptedId);
        assertEquals("failed",recovered.getString("state"));
        assertTrue(recovered.getLong("sequence")>99);
        assertEquals(recovered.getLong("sequence"),store.load(interruptedId).getJSONObject("recognition").getLong("sequence"));
        // 从公开启动/查询入口验证模型加载失败后的重试；JVM 无 Android 模型资源，不执行 JNI。
        File input=new File(store.dir(interruptedId),"source.png");
        java.nio.file.Files.write(input.toPath(),java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRz8AAAAASUVORK5CYII="));
        JSONObject retryInput=store.load(interruptedId).put("input","source.png");store.save(retryInput);
        String firstJob=controller.start(interruptedId,false).getJSONObject("recognition").getString("jobId");
        awaitFailure(controller,interruptedId);
        assertEquals("source.png",store.load(interruptedId).getString("input"));
        String secondJob=controller.start(interruptedId,false).getJSONObject("recognition").getString("jobId");
        assertNotEquals(firstJob,secondJob);
        awaitFailure(controller,interruptedId);
        controller.removeModel(ModelHub.REPOS[0]);
        assertFalse(controller.readiness().getBoolean("inUse"));
        assertEquals("unchecked",controller.readiness().getString("state"));
    }
    private void awaitFailure(RecognitionController controller,String id)throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline){
            if(controller.status(id).optString("state").equals("failed"))return;
            Thread.sleep(10);
        }
        fail("模型资源不可用时，应进入可重试失败终态");
    }
}
