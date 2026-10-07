package cn.local.ocr;
import org.junit.*;
import static org.junit.Assert.*;
import org.json.*;
public class OfficeExportTest {
 @Test public void exportRetainsSheetPagesAndPendingImageState()throws Exception{
  DocumentStore store=new DocumentStore(new android.content.ContextWrapper(null){@Override public java.io.File getFilesDir(){return new java.io.File(System.getProperty("java.io.tmpdir"));}});
  JSONObject doc=FilesUtil.obj("pages",new JSONArray().put(FilesUtil.obj("number",1,"title","销售","kind","worksheet")).put(FilesUtil.obj("number",2,"title","图片","kind","worksheet")),"assets",new JSONObject(),"blocks",new JSONArray().put(FilesUtil.obj("id","p1-b1","page",1,"type","table","markdown","<table></table>","format","html")).put(FilesUtil.obj("id","p2-b2","page",2,"type","image","resource","assets/p.png","sourceStatus","pending-ocr")));
  JSONObject ir=store.toIR(doc);assertEquals(2,ir.getJSONArray("pages").length());JSONObject second=ir.getJSONArray("pages").getJSONObject(1);assertEquals("图片",second.getString("title"));assertEquals("pending-ocr",second.getJSONArray("blocks").getJSONObject(0).getString("status"));assertEquals("assets/p.png",second.getJSONArray("blocks").getJSONObject(0).getJSONObject("content").getString("resource"));
 }
}
