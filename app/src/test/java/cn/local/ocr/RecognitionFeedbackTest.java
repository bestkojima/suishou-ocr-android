package cn.local.ocr;

import android.content.Context;
import android.content.ContextWrapper;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;
import static org.junit.Assert.*;

public class RecognitionFeedbackTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    DocumentStore store;RecognitionFeedback feedback;File root;String id;
    @Before public void setup()throws Exception {
        root=temp.newFolder();Context context=new ContextWrapper(null){
            @Override public File getFilesDir(){return root;}
            @Override public File getCacheDir(){return root;}
        };
        store=new DocumentStore(context);feedback=new RecognitionFeedback(context,store);id=store.create();
        FilesUtil.write(new File(store.dir(id),"cropped.jpg"),"actual inference input");
        FilesUtil.write(new File(store.dir(id),"raw.jpg"),"original photo before crop");
        FilesUtil.write(new File(store.dir(id),"document.json"),"{ \"original\": \"unmodified output\" }\n");
        store.save(FilesUtil.obj("id",id,"input","cropped.jpg","rawInput","raw.jpg","mode","real-ocr",
            "recognition",FilesUtil.obj("jobId","one","state","succeeded","modelChoice","glm"),
            "edits",FilesUtil.obj("b1","user corrected output"),"blocks",new JSONArray()));
    }
    @Test public void bundleFreezesActualInputRawJsonAndDescriptionAcrossLaterEdits()throws Exception {
        JSONObject prepared=feedback.prepare(id,"one","text","  金额应该为 128.50  ");String fid=prepared.getString("feedbackId");
        FilesUtil.write(new File(store.dir(id),"document.json"),"later output");
        try(ZipFile zip=new ZipFile(feedback.sharingFile(fid))){
            assertEquals(4,zip.size());
            assertEquals("actual inference input",read(zip,"input.jpg"));
            assertEquals("{ \"original\": \"unmodified output\" }\n",read(zip,"document.json"));
            assertEquals("金额应该为 128.50",read(zip,"description.txt"));
            JSONObject manifest=new JSONObject(read(zip,"feedback.json"));assertEquals(fid,manifest.getString("feedbackId"));assertEquals("glm",manifest.getString("model"));
        }
        assertFalse(prepared.getBoolean("submitted"));
        assertThrows(IOException.class,()->feedback.prepare(id,"old-job","text","错误"));
        assertThrows(IOException.class,()->feedback.prepare(id,"one","text","  "));
        assertThrows(IOException.class,()->feedback.sharingFile("../escape"));
    }
    String read(ZipFile zip,String name)throws Exception{try(InputStream in=zip.getInputStream(zip.getEntry(name))){return new String(FilesUtil.bytes(in,100000),StandardCharsets.UTF_8);}}
    @Test public void noReceiptOrWrongReceiptCannotBecomeSuccessAndBackupUsesSamePackage()throws Exception {
        File archive=temp.newFile();List<String> endpoints=new ArrayList<>();
        JSONObject receipt=FeedbackDelivery.send("primary","feishu","stable-id",archive,(endpoint,fid,file)->{
            assertEquals("stable-id",fid);assertSame(archive,file);endpoints.add(endpoint);
            if(endpoint.equals("primary"))throw new IOException("network down");
            return FilesUtil.obj("feedbackId",fid,"status","received");
        });
        assertEquals(Arrays.asList("primary","feishu"),endpoints);assertTrue(receipt.getBoolean("submitted"));assertEquals("feishu",receipt.getString("channel"));
        assertThrows(IOException.class,()->FeedbackDelivery.send("primary","feishu","id",archive,(a,b,c)->FilesUtil.obj("feedbackId","different","status","received")));
        assertThrows(IOException.class,()->FeedbackDelivery.send("","","id",archive,(a,b,c)->{throw new AssertionError("unconfigured must never upload");}));
    }
    @Test public void primaryReceiptStopsFallbackAndMissingInputIsRejected()throws Exception {
        List<String> calls=new ArrayList<>();FeedbackDelivery.send("primary","backup","id",temp.newFile(),(endpoint,id,archive)->{calls.add(endpoint);return FilesUtil.obj("feedbackId",id,"status","received");});
        assertEquals(Collections.singletonList("primary"),calls);
        assertTrue(new File(store.dir(id),"cropped.jpg").delete());assertThrows(IOException.class,()->feedback.prepare(id,"one","other","问题"));
    }
}
