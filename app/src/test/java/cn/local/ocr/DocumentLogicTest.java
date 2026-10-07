package cn.local.ocr;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

public class DocumentLogicTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 static class Result implements OfficeParser.Sink{List<String> content=new ArrayList<>();int images;public void text(String type,String text){content.add(type+":"+text);}public void image(String name,byte[] data){images++;}}
 File zip(String...pairs)throws Exception{File f=temp.newFile();try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(f))){for(int i=0;i<pairs.length;i+=2){z.putNextEntry(new ZipEntry(pairs[i]));z.write(pairs[i+1].getBytes(StandardCharsets.UTF_8));z.closeEntry();}}return f;}
 @Test public void rejectsZipTraversal()throws Exception{File f=zip("../escape.txt","bad"),dest=temp.newFolder();assertThrows(IOException.class,()->FilesUtil.unzip(f,dest));assertFalse(new File(dest.getParentFile(),"escape.txt").exists());}
 @Test public void shaAndAtomicSaveRoundTrip()throws Exception{File f=new File(temp.newFolder(),"state.json");FilesUtil.write(f,"abc");assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",FilesUtil.sha(f));FilesUtil.write(f,"你好");assertEquals("你好",FilesUtil.read(f));assertFalse(new File(f+".tmp").exists());}
 @Test public void refusesOversizeStreams()throws Exception{assertThrows(IOException.class,()->FilesUtil.bytes(new ByteArrayInputStream(new byte[8]),7));}
 @Test public void docxTextTableAndImages()throws Exception{
 String xml="<w:document xmlns:w='urn:w' xmlns:a='urn:a' xmlns:r='urn:r'><w:body><w:p><w:pPr><w:pStyle w:val='Heading1'/></w:pPr><w:r><w:t>标题</w:t></w:r><a:blip r:embed='rId1'/></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>A &amp; B</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>";
 File f=zip("word/document.xml",xml,"word/_rels/document.xml.rels","<Relationships xmlns='urn:r'><Relationship Id='rId1' Target='media/p.png'/></Relationships>","word/media/p.png","test-image");Result r=new Result();OfficeParser.parse(f,"docx",r);assertEquals("text:## 标题",r.content.get(0));assertTrue(r.content.get(1).contains("A &amp; B"));assertEquals(1,r.images);
 }
 @Test public void xlsxSparseCellsSharedStringsAndCachedFormula()throws Exception{
 File f=zip("xl/workbook.xml","<workbook xmlns='urn:x' xmlns:r='urn:r'><sheets><sheet name='表一' r:id='s1'/></sheets></workbook>","xl/_rels/workbook.xml.rels","<Relationships xmlns='urn:r'><Relationship Id='s1' Target='worksheets/sheet1.xml'/></Relationships>","xl/sharedStrings.xml","<sst xmlns='urn:x'><si><t>中文</t></si></sst>","xl/worksheets/sheet1.xml","<worksheet xmlns='urn:x'><sheetData><row r='1'><c r='A1' t='s'><v>0</v></c><c r='C1'><f>1+2</f><v>3</v></c></row></sheetData></worksheet>");Result r=new Result();OfficeParser.parse(f,"xlsx",r);assertEquals("text:## 表一",r.content.get(0));assertTrue(r.content.get(1).contains("<td>中文</td><td></td><td>3</td>"));
 }
 @Test public void oldXlsExtractsActualCells()throws Exception{File f=temp.newFile("sheet.xls");try(HSSFWorkbook w=new HSSFWorkbook()){var row=w.createSheet("数据").createRow(0);row.createCell(0).setCellValue("项目");row.createCell(1).setCellValue(42);try(OutputStream out=new FileOutputStream(f)){w.write(out);}}Result r=new Result();OfficeParser.parse(f,"xls",r);assertTrue(r.content.get(1).contains("<td>项目</td><td>42</td>"));}
 @Test public void rejectsExternalEntitiesUtf8AndUtf16()throws Exception{String xml="<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><a>&x;</a>";for(String encoding:new String[]{"UTF-8","UTF-16"})assertThrows(IOException.class,()->OfficeParser.xml(new ByteArrayInputStream(xml.getBytes(encoding))));}
 @Test public void modelSourcesAreExplicitAndFileUrlsPinned()throws Exception{ModelHub.checkRepo("dr3334/PP-DocLayoutV3-mnn");ModelHub.checkRepo("dr3334/ovrics-ocrv2_mnn");ModelHub.checkRepo("another/public-model");assertThrows(IOException.class,()->ModelHub.checkRepo("../escape"));String url=ModelHub.url(FilesUtil.obj("repo",ModelHub.REPOS[0],"revision","abc123","path","dir/model file.mnn"));assertTrue(url.contains("Revision=abc123"));assertTrue(url.contains("FilePath=dir%2Fmodel+file.mnn"));}
 @Test public void exportedBundleRestoresEditsAndRebasesImageResources()throws Exception{
  File files=temp.newFolder(),cache=temp.newFolder();android.content.Context context=new android.content.ContextWrapper(null){@Override public File getFilesDir(){return files;}@Override public File getCacheDir(){return cache;}};
  DocumentStore store=new DocumentStore(context);String id=store.create();File image=FilesUtil.child(store.dir(id),"assets/test.png");image.getParentFile().mkdirs();Files.write(image.toPath(),new byte[]{1,2,3});
  org.json.JSONObject ir=FilesUtil.obj("pages",new org.json.JSONArray().put(FilesUtil.obj("page_id","p1","reading_order",new org.json.JSONArray().put("b1").put("b2"),"blocks",new org.json.JSONArray().put(FilesUtil.obj("id","b1","type","text","content",FilesUtil.obj("text","原始文字"))).put(FilesUtil.obj("id","b2","type","image","content",FilesUtil.obj("resource","assets/test.png"))))),"resources",new org.json.JSONArray().put(FilesUtil.obj("path","assets/test.png","width",50,"height",30)));
  org.json.JSONObject doc=store.normalize(id,"测试",ir);doc.put("progress",2);doc.put("edits",FilesUtil.obj("p1-b1","修订文字"));store.save(doc);FilesUtil.write(new File(store.dir(id),"document.json"),ir.toString());File exported=store.export(id,"zip");
  String newId=store.create();FilesUtil.unzip(exported,store.dir(newId));org.json.JSONObject restored=store.restoreExport(newId,new org.json.JSONObject(FilesUtil.read(new File(store.dir(newId),"app-state.json"))));
  assertEquals("修订文字",restored.getJSONObject("edits").getString("p1-b1"));assertTrue(restored.getJSONObject("assets").getJSONObject("assets/test.png").getString("src").contains(newId));assertFalse(restored.getJSONObject("assets").getJSONObject("assets/test.png").getBoolean("missing"));assertEquals(0,restored.getInt("progress"));assertTrue(FilesUtil.read(new File(store.dir(newId),"document.md")).contains("修订文字"));assertEquals("原始文字",new org.json.JSONObject(FilesUtil.read(new File(store.dir(newId),"document.json"))).getJSONArray("pages").getJSONObject(0).getJSONArray("blocks").getJSONObject(0).getJSONObject("content").getString("text"));
 }
 @Test public void readingOrderOverridesSourceArrayAndHistoryProgressIsBounded()throws Exception{
  File files=temp.newFolder();DocumentStore store=new DocumentStore(new android.content.ContextWrapper(null){@Override public File getFilesDir(){return files;}});String id=store.create();
  org.json.JSONObject ir=FilesUtil.obj("pages",new org.json.JSONArray().put(FilesUtil.obj("page_id","p1","reading_order",new org.json.JSONArray().put("b2").put("b1"),"blocks",new org.json.JSONArray().put(FilesUtil.obj("id","b1","type","text","content",FilesUtil.obj("text","甲"))).put(FilesUtil.obj("id","b2","type","text","content",FilesUtil.obj("text","乙"))))));
  org.json.JSONObject doc=store.normalize(id,"排序",ir);assertEquals("乙",doc.getJSONArray("blocks").getJSONObject(0).getString("markdown"));store.save(doc);assertEquals(2,store.update(FilesUtil.obj("id",id,"progress",999)).getInt("progress"));assertEquals(1,store.list().length());store.delete(id);assertEquals(0,store.list().length());
 }
}
