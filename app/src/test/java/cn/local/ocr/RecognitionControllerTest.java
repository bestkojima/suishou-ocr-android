package cn.local.ocr;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.*;
import java.io.File;
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
        };
        RecognitionController controller=RecognitionController.get(context);DocumentStore store=controller.store();String id=store.create();
        JSONObject state=FilesUtil.obj("docId",id,"jobId","job","state","saving","resultSaved",true);
        JSONObject doc=FilesUtil.obj("id",id,"mode","real-ocr","recognitionOutcome","partial","recognition",state);store.save(doc);
        // 固定在 native 清理期间，不运行或伪造模型推理。
        set(controller,"current",state);set(controller,"busy",true);
        assertEquals("saving",controller.cancel(id,"job").getString("state"));
        assertEquals("saving",controller.status(id).getString("state"));
        set(controller,"current",null);set(controller,"busy",false);
        assertEquals("partial",controller.status(id).getString("state"));assertEquals("partial",store.load(id).getJSONObject("recognition").getString("state"));
        // 提交前仍允许取消，并将状态保存给历史重开。
        state=FilesUtil.obj("docId",id,"jobId","retry","state","saving");doc.put("mode","pending-ocr").put("recognition",state);store.save(doc);
        set(controller,"current",state);set(controller,"busy",true);
        assertEquals("cancelling",controller.cancel(id,"retry").getString("state"));
        assertEquals("cancelling",store.load(id).getJSONObject("recognition").getString("state"));
        set(controller,"busy",false);set(controller,"current",null);set(controller,"cancelled",false);
        assertEquals("failed",controller.status(id).getString("state"));
    }
}
