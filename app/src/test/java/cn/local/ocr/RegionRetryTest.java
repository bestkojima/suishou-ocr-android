package cn.local.ocr;

import java.io.*;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RegionRetryTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 @Test public void usesSavedCropAndRejectsMissingOrEscapingResources()throws Exception {
  DocumentStore store=new DocumentStore(new android.content.ContextWrapper(null){@Override public File getFilesDir(){return temp.getRoot();}});
  String id=store.create();File crop=FilesUtil.child(store.dir(id),"assets/table.png");FilesUtil.write(crop,"crop");
  JSONObject block=FilesUtil.obj("id","b1","type","table","resource","assets/table.png");
  JSONObject doc=FilesUtil.obj("id",id,"blocks",new JSONArray().put(block));
  assertEquals(crop,RegionRetry.input(store,doc,"b1"));
  block.put("resource","../../other.png");assertThrows(IOException.class,()->RegionRetry.input(store,doc,"b1"));
  block.put("resource","assets/missing.png");assertThrows(IOException.class,()->RegionRetry.input(store,doc,"b1"));
  block.put("resource","assets/table.png").put("type","image");assertThrows(IOException.class,()->RegionRetry.input(store,doc,"b1"));
  assertThrows(IOException.class,()->RegionRetry.input(store,doc,"unknown"));
 }
 @Test public void candidatePreservesRawPartialTableAndDoesNotMutateSource()throws Exception {
  String raw="<table><tr><td>原始表格</td></tr></table>";
  JSONObject block=FilesUtil.obj("status","partial","content",FilesUtil.obj("text","","format","markdown"),"provenance",FilesUtil.obj("raw_output",raw,"assessment",FilesUtil.obj("stop_reason","repetition")));
  JSONObject ir=FilesUtil.obj("pages",new JSONArray().put(FilesUtil.obj("blocks",new JSONArray().put(block))));
  JSONObject candidate=RegionRetry.candidate(ir);assertEquals(raw,candidate.getString("markdown"));
  assertEquals("repetition",candidate.getString("stopReason"));assertEquals("",block.getJSONObject("content").getString("text"));
  block.getJSONObject("content").put("text","x^2").put("format","latex");assertEquals("$$\nx^2\n$$",RegionRetry.candidate(ir).getString("markdown"));
  block.getJSONObject("content").put("text","");block.remove("provenance");assertThrows(IOException.class,()->RegionRetry.candidate(ir));
 }
 @Test public void emptyOrFailedNewResultDoesNotReplaceOldContent()throws Exception {
  JSONObject block=FilesUtil.obj("status","ok","content",FilesUtil.obj("text","  "),"provenance",FilesUtil.obj("raw_output",JSONObject.NULL));
  JSONObject ir=FilesUtil.obj("pages",new JSONArray().put(FilesUtil.obj("blocks",new JSONArray().put(block))));
  assertThrows(IOException.class,()->RegionRetry.candidate(ir));
  block.getJSONObject("content").put("text",JSONObject.NULL);assertThrows(IOException.class,()->RegionRetry.candidate(ir));
  block.put("status","failed").getJSONObject("content").put("text","失败输出");assertThrows(IOException.class,()->RegionRetry.candidate(ir));
 }

}
