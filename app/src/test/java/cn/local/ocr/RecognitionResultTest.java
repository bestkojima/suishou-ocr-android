package cn.local.ocr;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class RecognitionResultTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    DocumentStore store()throws Exception {
        File root=temp.newFolder();
        android.content.Context c=new android.content.ContextWrapper(null){
            @Override public File getFilesDir(){return root;}
            @Override public File getCacheDir(){return root;}
        };
        return new DocumentStore(c);
    }
    JSONObject input(DocumentStore store)throws Exception {
        String id=store.create();
        FilesUtil.write(new File(store.dir(id),"source.png"),"保留的输入");
        JSONObject doc=FilesUtil.obj("id",id,"title","新图片","input","source.png","original",store.url(id,"source.png"),"mode","pending-ocr","blocks",new JSONArray(),"assets",new JSONObject(),"pages",new JSONArray(),"progress",0);
        store.save(doc);return doc;
    }
    @Test public void realStructuredOutputCanBeCorrectedReorderedExportedAndRecognizedAgain()throws Exception {
        DocumentStore store=store();JSONObject input=input(store);String id=input.getString("id");
        File fixture=new File("../verification/real-ocr/output");assertTrue("需要本轮桌面真实输出",new File(fixture,"document.json").isFile());
        JSONObject doc=store.recognized(id,fixture);
        assertEquals("real-ocr",doc.getString("mode"));assertEquals(10,doc.getJSONArray("blocks").length());
        assertEquals("succeeded",doc.getString("recognitionOutcome"));
        JSONArray bs=doc.getJSONArray("blocks");String a=bs.getJSONObject(0).getString("id"),b=bs.getJSONObject(1).getString("id");
        assertTrue(bs.getJSONObject(5).getString("markdown").contains("\\mathrm{mc}^{2}"));
        assertTrue(bs.getJSONObject(7).getString("markdown").contains("<table>"));
        assertTrue(new File(store.dir(id),bs.getJSONObject(8).getString("resource")).isFile());
        JSONArray order=new JSONArray().put(b).put(a);for(int i=2;i<bs.length();i++)order.put(bs.getJSONObject(i).getString("id"));
        store.update(FilesUtil.obj("id",id,"progress",10,"status","complete","edits",FilesUtil.obj(a,"校对后的标题"),"order",order));
        JSONObject saved=store.load(id);assertTrue(store.markdown(saved,false).contains("校对后的标题"));
        assertEquals("b0002",store.exportIR(saved).getJSONArray("pages").getJSONObject(0).getJSONArray("reading_order").getString(0));
        File zip=store.export(id,"zip");
        try(ZipFile bundle=new ZipFile(zip)) {
            assertNotNull(bundle.getEntry("original-document.json"));assertNotNull(bundle.getEntry("run-manifest.json"));
            assertNotNull(bundle.getEntry("assets/p0001-b0009.png"));assertNotNull(bundle.getEntry("assets/p0001-image.f32"));
        }
        String restoredId=store.create();FilesUtil.unzip(zip,store.dir(restoredId));
        JSONObject restored=store.restoreExport(restoredId,new JSONObject(FilesUtil.read(new File(store.dir(restoredId),"app-state.json"))));
        assertEquals("校对后的标题",restored.getJSONObject("edits").getString(a));assertFalse(restored.has("input"));
        store.save(restored);
        store.update(FilesUtil.obj("id",restoredId,"progress",10,"status","complete"));
        try(ZipFile roundTrip=new ZipFile(store.export(restoredId,"zip"))) {
            byte[] original=Files.readAllBytes(new File(fixture,"document.json").toPath());
            assertArrayEquals("再次导出应保留首次模型输出，不能以排序后的副本覆盖",original,
                FilesUtil.bytes(roundTrip.getInputStream(roundTrip.getEntry("original-document.json")),FilesUtil.IMPORT_LIMIT));
            JSONObject current=new JSONObject(new String(FilesUtil.bytes(roundTrip.getInputStream(roundTrip.getEntry("document.json")),FilesUtil.IMPORT_LIMIT),java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("b0002",current.getJSONArray("pages").getJSONObject(0).getJSONArray("reading_order").getString(0));
        }
        JSONObject another=store.copyInput(saved);assertNotEquals(id,another.getString("id"));assertFalse(another.has("edits"));
        assertEquals("校对后的标题",store.load(id).getJSONObject("edits").getString(a));
        assertArrayEquals(Files.readAllBytes(new File(store.dir(id),"source.png").toPath()),Files.readAllBytes(new File(store.dir(another.getString("id")),"source.png").toPath()));
        JSONObject secondResult=store.recognized(another.getString("id"),new File("../verification/real-ocr/repeated"));
        assertFalse(secondResult.has("edits"));
        String secondImage=secondResult.getJSONArray("blocks").getJSONObject(1).getString("resource");
        assertTrue(secondResult.getJSONObject("assets").getJSONObject(secondImage).getString("src").contains(another.getString("id")));
        assertEquals("校对后的标题",store.load(id).getJSONObject("edits").getString(a));
        assertEquals(b,store.load(id).getJSONArray("blocks").getJSONObject(0).getString("id"));
        assertArrayEquals(Files.readAllBytes(new File(fixture,"assets/p0001-b0009.png").toPath()),Files.readAllBytes(new File(store.dir(id),"assets/p0001-b0009.png").toPath()));
    }
    @Test public void partialAndEmptyResultsPreserveInputAndMissingRegionState()throws Exception {
        DocumentStore store=store();JSONObject input=input(store);File output=temp.newFolder();
        FilesUtil.write(new File(output,"document.json"),"{\"schema_version\":\"1.10\",\"status\":\"partial\",\"pages\":[{\"page_id\":\"p\",\"blocks\":[{\"id\":\"a\",\"status\":\"failed\",\"type\":\"text\"}],\"reading_order\":[\"a\"]}]}");
        JSONObject result=store.recognized(input.getString("id"),output);assertEquals("partial",result.getString("recognitionOutcome"));
        assertEquals("failed",result.getJSONArray("blocks").getJSONObject(0).getString("sourceStatus"));assertTrue(result.getJSONArray("blocks").getJSONObject(0).getString("markdown").contains("未完成"));
        FilesUtil.write(new File(output,"document.json"),"{\"schema_version\":\"1.10\",\"status\":\"blank\",\"pages\":[{\"page_id\":\"p\",\"blocks\":[],\"reading_order\":[]}]}");
        assertEquals("blank",store.recognized(input.getString("id"),output).getString("recognitionOutcome"));
        assertTrue(new File(store.dir(input.getString("id")),"source.png").isFile());
        JSONObject blank=store.recognized(input.getString("id"),new File("../verification/real-ocr/blank"));
        assertEquals("blank",blank.getString("recognitionOutcome"));assertFalse(blank.has("notice"));
    }
    @Test public void importedResourcesOutsideAssetsSurviveExportWithoutDuplicateOriginalImage()throws Exception {
        DocumentStore store=store();JSONObject input=input(store);String id=input.getString("id");
        File photo=new File(store.dir(id),"images/photo.png");FilesUtil.write(photo,"image bytes");
        JSONObject ir=new JSONObject("{\"schema_version\":\"1.0\",\"pages\":[{\"page_id\":\"p\",\"blocks\":[{\"id\":\"picture\",\"type\":\"image\",\"content\":{\"resource\":\"images/photo.png\"}}],\"reading_order\":[\"picture\"]}],\"resources\":[{\"path\":\"images/photo.png\",\"width\":3,\"height\":2}]}");
        FilesUtil.write(new File(store.dir(id),"document.json"),ir.toString());
        JSONObject doc=store.normalize(id,"旧 ZIP",ir).put("progress",1).put("original",store.url(id,"source.png"));
        doc.getJSONObject("assets").put("source.png",FilesUtil.obj("src",store.url(id,"source.png")));store.save(doc);
        try(ZipFile zip=new ZipFile(store.export(id,"zip"))) {
            assertNotNull(zip.getEntry("images/photo.png"));assertNotNull(zip.getEntry("source.png"));
        }
    }
    @Test public void pageLevelPartialPreservesUsableContentAndWarnsAfterHistoryReopen()throws Exception {
        DocumentStore store=store();JSONObject input=input(store);File output=temp.newFolder();
        // 兼容只有页面记录 partial、可用内容块仍为 ok 的 DocumentIR。
        JSONObject ir=new JSONObject("{\"schema_version\":\"1.9\",\"pages\":[{\"page_id\":\"p\",\"status\":\"partial\",\"blocks\":[{\"id\":\"a\",\"status\":\"ok\",\"type\":\"text\",\"content\":{\"text\":\"仍可阅读的正文\"}}],\"reading_order\":[\"a\"]}]}");
        FilesUtil.write(new File(output,"document.json"),ir.toString());
        JSONObject result=store.recognized(input.getString("id"),output);
        assertEquals("partial",result.getString("recognitionOutcome"));
        assertEquals("仍可阅读的正文",result.getJSONArray("blocks").getJSONObject(0).getString("markdown"));
        assertTrue(store.load(input.getString("id")).getString("notice").contains("部分"));
        JSONObject replay=store.normalize(store.create(),"部分结果 JSON",ir);
        assertTrue("结果导入也应提示部分成功",replay.getString("notice").contains("部分"));
    }
}
