package cn.local.ocr;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class DocumentCropTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    android.content.Context context(){return new android.content.ContextWrapper(null){
        @Override public File getFilesDir(){return temp.getRoot();}
        @Override public File getCacheDir(){return temp.getRoot();}
    };}
    JSONObject input(DocumentStore store)throws Exception {
        String id=store.create();FilesUtil.write(new File(store.dir(id),"source.png"),"完整照片的规范底图");FilesUtil.write(new File(store.dir(id),"photo.jpg"),"原始相机文件");
        JSONObject doc=FilesUtil.obj("id",id,"title","裁剪测试","mode","pending-ocr","input","source.png","rawInput","photo.jpg","original",store.url(id,"source.png"),"blocks",new JSONArray(),"assets",new JSONObject(),"pages",new JSONArray(),"progress",0,
            "imagePreparation",FilesUtil.obj("version",ImageResolution.VERSION,"policy","balanced","width",600,"height",800));
        store.save(doc);return DocumentCrop.begin(store,doc);
    }
    JSONObject preview(DocumentStore store,JSONObject doc)throws Exception {
        String path="crop-preview-token.png";FilesUtil.write(new File(store.dir(doc.getString("id")),path),"真实预览文件的替身，验证文件生命周期");
        JSONObject preview=FilesUtil.obj("token","token","path",path,"width",400,"height",600,"policy","balanced","points",DocumentCrop.json(CropGeometry.full()));
        doc.getJSONObject("documentCrop").put("preview",preview);store.save(doc);return preview;
    }
    @Test public void pendingCropBlocksAutomaticAndExplicitRecognition()throws Exception {
        Constructor<RecognitionController> constructor=RecognitionController.class.getDeclaredConstructor(android.content.Context.class);constructor.setAccessible(true);
        RecognitionController controller=constructor.newInstance(context());JSONObject doc=input(controller.store());
        assertSame(doc,controller.autoStart(doc));
        IOException failure=assertThrows(IOException.class,()->controller.start(doc.getString("id"),false));
        assertTrue(failure.getMessage().contains("确认文档裁剪"));
        assertEquals("waiting",controller.status(doc.getString("id")).getString("state"));
    }
    @Test public void confirmationUsesThePreviewFileAndPreservesOriginals()throws Exception {
        DocumentStore store=new DocumentStore(context());JSONObject doc=input(store);String id=doc.getString("id");preview(store,doc);
        assertThrows(IOException.class,()->DocumentCrop.confirm(store,doc,"stale"));
        assertTrue(DocumentCrop.pending(store.load(id)));
        JSONObject confirmed=DocumentCrop.confirm(store,store.load(id),"token");assertFalse(DocumentCrop.pending(confirmed));
        assertEquals("crop-preview-token.png",confirmed.getString("input"));assertEquals(confirmed.getString("input"),confirmed.getString("rawInput"));
        // 同策略直接复用已确认文件；JVM 没有 Bitmap 解码器，误重新处理会失败。
        assertSame(confirmed,ImageInput.prepare(store,confirmed,"balanced"));
        assertEquals("原始相机文件",FilesUtil.read(new File(store.dir(id),"photo.jpg")));
        assertEquals("完整照片的规范底图",FilesUtil.read(new File(store.dir(id),"capture-source.png")));
        assertThrows(IOException.class,()->DocumentCrop.confirm(store,confirmed,"token"));
    }
    @Test public void reopeningCropClearsThePreviousTerminalRecognitionSnapshot()throws Exception {
        Constructor<RecognitionController> constructor=RecognitionController.class.getDeclaredConstructor(android.content.Context.class);constructor.setAccessible(true);
        RecognitionController controller=constructor.newInstance(context());JSONObject doc=input(controller.store());String id=doc.getString("id");
        Field current=RecognitionController.class.getDeclaredField("current");current.setAccessible(true);
        current.set(controller,FilesUtil.obj("docId",id,"jobId","old","state","failed","sequence",100));
        JSONObject reopened=controller.beginCrop(id);assertTrue(DocumentCrop.pending(reopened));
        assertEquals("waiting",controller.status(id).getString("state"));assertFalse(controller.status(id).has("jobId"));
    }
    @Test public void changingResolutionNeverRestoresTheUncroppedPhoto()throws Exception {
        DocumentStore store=new DocumentStore(context());JSONObject doc=input(store);preview(store,doc);doc=DocumentCrop.confirm(store,doc,"token");
        ImageInput.prepare(store,doc,"fast",(raw,output,policy)->{
            assertEquals("crop-preview-token.png",raw.getName());assertEquals(raw,output);
            return new ImageInput.Result(400,600,FilesUtil.obj("version",ImageResolution.VERSION,"policy",policy,"width",400,"height",600));
        });
        assertEquals("fast",store.load(doc.getString("id")).getJSONObject("imagePreparation").getString("policy"));
    }
    @Test public void recognizedCopyAndZipKeepCropSourceAndOriginalPhoto()throws Exception {
        DocumentStore store=new DocumentStore(context());JSONObject doc=input(store);preview(store,doc);doc=DocumentCrop.confirm(store,doc,"token");String id=doc.getString("id");
        JSONObject result=store.recognized(id,new File("../verification/real-ocr/output"));assertEquals("confirmed",result.getJSONObject("documentCrop").getString("state"));
        JSONObject copy=store.copyInput(result);assertNotEquals(id,copy.getString("id"));
        assertEquals("原始相机文件",FilesUtil.read(new File(store.dir(copy.getString("id")),"photo.jpg")));
        assertTrue(copy.getJSONObject("documentCrop").getString("sourceUrl").contains(copy.getString("id")));
        DocumentCrop.begin(store,copy);assertTrue(DocumentCrop.pending(copy));assertEquals("confirmed",store.load(id).getJSONObject("documentCrop").getString("state"));
        result.put("progress",result.getJSONArray("blocks").length());store.save(result);
        File archive=store.export(id,"zip");try(ZipFile zip=new ZipFile(archive)){assertNotNull(zip.getEntry("photo.jpg"));assertNotNull(zip.getEntry("capture-source.png"));assertNotNull(zip.getEntry("crop-preview-token.png"));}
        String restoredId=store.create();FilesUtil.unzip(archive,store.dir(restoredId));
        JSONObject restored=store.restoreExport(restoredId,new JSONObject(FilesUtil.read(new File(store.dir(restoredId),"app-state.json"))));
        assertTrue(restored.getJSONObject("documentCrop").getString("sourceUrl").contains(restoredId));assertFalse(restored.has("input"));
    }
    @Test public void convexGeometryRejectsCrossingNonFiniteAndTooSmallFrames() {
        CropGeometry.validate(CropGeometry.full());assertArrayEquals(new int[]{600,800},CropGeometry.dimensions(CropGeometry.full(),600,800));
        assertThrows(IllegalArgumentException.class,()->CropGeometry.validate(new double[][]{{0,0},{1,1},{1,0},{0,1}}));
        assertThrows(IllegalArgumentException.class,()->CropGeometry.validate(new double[][]{{Double.NaN,0},{1,0},{1,1},{0,1}}));
        assertThrows(IllegalArgumentException.class,()->CropGeometry.validate(new double[][]{{-.1,0},{1,0},{1,1},{0,1}}));
        assertThrows(IllegalArgumentException.class,()->CropGeometry.validate(new double[][]{{0,0},{.01,0},{.01,.01},{0,.01}}));
        int[] size=CropGeometry.dimensions(new double[][]{{.1,.1},{.9,.2},{.8,.9},{.2,.8}},600,800);
        assertTrue(size[0]*size[1]<=600*800);assertTrue(size[0]>400);assertTrue(size[1]>500);
    }
}
