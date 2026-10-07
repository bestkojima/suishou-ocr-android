package cn.local.ocr;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import org.json.*;
import java.io.*;
public class RegionOrderTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 JSONObject doc()throws Exception{return FilesUtil.obj("progress",2,"status","paused","blocks",new JSONArray().put(FilesUtil.obj("id","p-a","page",1,"markdown","A")).put(FilesUtil.obj("id","p-b","page",1,"markdown","B")).put(FilesUtil.obj("id","p-c","page",1,"markdown","C")));}
 @Test public void completedPrefixCanMoveWithoutChangingPendingContent()throws Exception{JSONObject d=doc();DocumentStore.reorder(d,new JSONArray("[\"p-b\",\"p-a\",\"p-c\"]"));assertEquals("p-b",d.getJSONArray("blocks").getJSONObject(0).getString("id"));assertEquals("p-c",d.getJSONArray("blocks").getJSONObject(2).getString("id"));}
 @Test public void rejectsMissingDuplicateUnfinishedAndCrossPageMoves()throws Exception{
  for(String order:new String[]{"[\"p-a\"]","[\"p-a\",\"p-a\",\"p-c\"]","[\"p-a\",\"p-c\",\"p-b\"]"})assertThrows(IOException.class,()->DocumentStore.reorder(doc(),new JSONArray(order)));
  JSONObject d=doc();d.getJSONArray("blocks").getJSONObject(1).put("page",2);assertThrows(IOException.class,()->DocumentStore.reorder(d,new JSONArray("[\"p-b\",\"p-a\",\"p-c\"]")));
 }
 @Test public void originalIrRetainsGeometryWhileReadingOrderChanges()throws Exception{
  File root=temp.newFolder();android.content.Context c=new android.content.ContextWrapper(null){@Override public File getFilesDir(){return root;}};DocumentStore store=new DocumentStore(c);String id=store.create();
  JSONObject ir=new JSONObject("{\"schema_version\":\"1.0\",\"pages\":[{\"page_id\":\"p\",\"blocks\":[{\"id\":\"a\",\"bbox\":[1,2,3,4]},{\"id\":\"b\"},{\"id\":\"c\"}],\"reading_order\":[\"a\",\"b\",\"c\"]}]}");FilesUtil.write(new File(store.dir(id),"document.json"),ir.toString());JSONObject d=doc().put("id",id);store.save(d);
  store.update(FilesUtil.obj("id",id,"order",new JSONArray("[\"p-b\",\"p-a\",\"p-c\"]")));JSONObject saved=store.load(id),export=store.exportIR(saved);
  assertTrue(store.markdown(saved,false).startsWith("B\n\nA"));assertEquals("b",export.getJSONArray("pages").getJSONObject(0).getJSONArray("reading_order").getString(0));assertEquals(4,export.getJSONArray("pages").getJSONObject(0).getJSONArray("blocks").getJSONObject(0).getJSONArray("bbox").length());
 }
 @Test public void appExportCanBeReorderedAgainWithoutCorruptingIds()throws Exception{
  File root=temp.newFolder();android.content.Context c=new android.content.ContextWrapper(null){@Override public File getFilesDir(){return root;}};DocumentStore store=new DocumentStore(c);String id=store.create();JSONObject d=doc().put("id",id).put("assets",new JSONObject()).put("progress",3);
  FilesUtil.write(new File(store.dir(id),"document.json"),store.toIR(d).toString());DocumentStore.reorder(d,new JSONArray().put("p-c").put("p-a").put("p-b"));JSONObject ir=store.exportIR(d);assertEquals("p-c",ir.getJSONArray("pages").getJSONObject(0).getJSONArray("reading_order").getString(0));
 }
}
