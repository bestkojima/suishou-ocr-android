package cn.local.ocr;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.apache.poi.hslf.usermodel.*;

public class OfficeFormatsTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 static class Output implements OfficeParser.Sink {
  int page;List<String> pages=new ArrayList<>(),texts=new ArrayList<>(),images=new ArrayList<>();
  public void page(int n,String title,String kind){page=n;pages.add(kind+":"+title);}
  public void text(String type,String value){texts.add(page+":"+type+":"+value);}
  public void image(String name,byte[] data){images.add(page+":"+name);}
 }
 File zip(String...parts)throws Exception{File f=temp.newFile();try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(f))){for(int i=0;i<parts.length;i+=2){z.putNextEntry(new ZipEntry(parts[i]));z.write(parts[i+1].getBytes(StandardCharsets.UTF_8));z.closeEntry();}}return f;}
 String presentation="<p:presentation xmlns:p='urn:p' xmlns:r='urn:r'><p:sldIdLst><p:sldId id='257' r:id='second'/><p:sldId id='256' r:id='first'/></p:sldIdLst></p:presentation>";
 String relationships="<Relationships><Relationship Id='first' Target='slides/slide1.xml'/><Relationship Id='second' Target='slides/slide2.xml'/></Relationships>";
 @Test public void pptxUsesPresentationOrderAndPreservesTextTableAndSlideImage()throws Exception{
  String first="<p:sld xmlns:p='urn:p' xmlns:a='urn:a' xmlns:r='urn:r'><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>第一张的内容</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>";
  String second="<p:sld xmlns:p='urn:p' xmlns:a='urn:a' xmlns:r='urn:r'><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>应先显示</a:t></a:r></a:p><a:p><a:pPr><a:buChar char='•'/></a:pPr><a:r><a:t>要点</a:t></a:r></a:p></p:txBody></p:sp><p:graphicFrame><a:tbl><a:tr><a:tc gridSpan='2'><a:txBody><a:p><a:r><a:t>合并表头</a:t></a:r></a:p></a:txBody></a:tc><a:tc hMerge='1'/></a:tr></a:tbl></p:graphicFrame><p:pic><a:blip r:embed='image1'/></p:pic></p:spTree></p:cSld></p:sld>";
  File f=zip("ppt/presentation.xml",presentation,"ppt/_rels/presentation.xml.rels",relationships,"ppt/slides/slide1.xml",first,"ppt/slides/slide2.xml",second,"ppt/slides/_rels/slide2.xml.rels","<Relationships><Relationship Id='image1' Target='../media/photo.png'/></Relationships>","ppt/media/photo.png","image-data");
  Output out=new Output();OfficeParser.parse(f,"pptx",out);assertEquals(2,out.pages.size());assertTrue(out.texts.get(1).contains("应先显示\n\n- 要点"));assertTrue(out.texts.stream().anyMatch(s->s.contains("colspan=\"2\"")));assertEquals(Arrays.asList("1:ppt/media/photo.png"),out.images);assertTrue(out.texts.get(out.texts.size()-1).startsWith("2:text:"));
 }
 @Test public void legacyPptExtractsTextBySlideWithoutAwtRendering()throws Exception{
  File f=temp.newFile("slides.ppt");try(HSLFSlideShow show=new HSLFSlideShow()){for(String value:new String[]{"原生 PPT 第一页","第二页文字"}){HSLFSlide slide=show.createSlide();HSLFTextBox box=new HSLFTextBox();box.setText(value);slide.addShape(box);}try(OutputStream stream=new FileOutputStream(f)){show.write(stream);}}
  Output out=new Output();OfficeParser.parse(f,"ppt",out);assertEquals(2,out.pages.size());assertTrue(out.texts.stream().anyMatch(s->s.equals("1:text:原生 PPT 第一页")));assertTrue(out.texts.stream().anyMatch(s->s.equals("2:text:第二页文字")));
 }
 @Test public void xlsxPreservesSheetsMergedCellsFormatsFormulasAndImageOwnership()throws Exception{
  File f=zip("xl/workbook.xml","<workbook xmlns:r='urn:r'><sheets><sheet name='销售' r:id='s1'/><sheet name='图页' r:id='s2'/></sheets></workbook>","xl/_rels/workbook.xml.rels","<Relationships><Relationship Id='s1' Target='worksheets/one.xml'/><Relationship Id='s2' Target='worksheets/two.xml'/></Relationships>",
   "xl/styles.xml","<styleSheet><cellXfs count='2'><xf numFmtId='0'/><xf numFmtId='10'/></cellXfs></styleSheet>",
   "xl/worksheets/one.xml","<worksheet><sheetData><row r='1'><c r='A1' t='inlineStr'><is><t>营收</t></is></c></row><row r='2'><c r='A2' s='1'><v>0.25</v></c><c r='B2'><f>2+3</f><v>5</v></c><c r='C2'><f>NOW()</f></c></row></sheetData><mergeCells><mergeCell ref='A1:C1'/></mergeCells></worksheet>",
   "xl/worksheets/two.xml","<worksheet xmlns:r='urn:r'><drawing r:id='draw'/></worksheet>","xl/worksheets/_rels/two.xml.rels","<Relationships><Relationship Id='draw' Target='../drawings/drawing1.xml'/></Relationships>",
   "xl/drawings/drawing1.xml","<drawing xmlns:a='urn:a' xmlns:r='urn:r'><a:blip r:embed='pic'/></drawing>","xl/drawings/_rels/drawing1.xml.rels","<Relationships><Relationship Id='pic' Target='../media/photo.png'/></Relationships>","xl/media/photo.png","test");
  Output out=new Output();OfficeParser.parse(f,"xlsx",out);assertEquals(Arrays.asList("worksheet:销售","worksheet:图页"),out.pages);assertEquals(Arrays.asList("2:xl/media/photo.png"),out.images);
  String table=out.texts.stream().filter(s->s.startsWith("1:table:")).findFirst().get();assertTrue(table.contains("colspan=\"3\""));assertTrue(table.contains("25.00%"));assertTrue(table.contains("<td>5</td>"));assertTrue(table.contains("=NOW()（无缓存结果）"));assertTrue(out.texts.stream().anyMatch(s->s.contains("不重新计算公式")));
 }
 @Test public void resourcePathsAllowPackageParentsButRejectEscapingAndRemote()throws Exception{
  assertEquals("ppt/media/a.png",OfficeParser.resolvePart("ppt/slides/slide1.xml","../media/a.png"));assertThrows(IOException.class,()->OfficeParser.resolvePart("ppt/slides/s.xml","../../../secret"));assertThrows(IOException.class,()->OfficeParser.resolvePart("ppt/slides/s.xml","https://host/image.png"));
 }
 @Test public void mergeRowsRenderOnceAndOversizedGridsFailExplicitly()throws Exception{
  SpreadsheetParser.Grid grid=new SpreadsheetParser.Grid();grid.put(0,0,"标题");grid.put(1,1,"尾部");grid.span(new SpreadsheetParser.Span(0,0,1,0));String html=grid.html();assertTrue(html.contains("rowspan=\"2\""));assertEquals(1,html.split("标题",-1).length-1);assertThrows(IOException.class,()->grid.put(0,256,"不能静默丢弃"));
 }
 @Test public void realOfficePackagesProduceReplayBundlesWithPageImageOwnership()throws Exception{
  for(String name:new String[]{"sales-with-image.xlsx","slides-with-table-and-image.pptx"}){
   File input=temp.newFile(name);try(InputStream in=getClass().getResourceAsStream("/office/"+name);OutputStream out=new FileOutputStream(input)){assertNotNull(in);FilesUtil.copy(in,out,FilesUtil.IMPORT_LIMIT);}
   org.json.JSONArray pages=new org.json.JSONArray(),resources=new org.json.JSONArray();Map<String,byte[]> images=new LinkedHashMap<>();
   OfficeParser.parse(input,name.endsWith("xlsx")?"xlsx":"pptx",new OfficeParser.Sink(){org.json.JSONObject page;int count;
    public void page(int number,String title,String kind)throws Exception{page=FilesUtil.obj("page_id","p"+number,"title",title,"kind",kind,"blocks",new org.json.JSONArray(),"reading_order",new org.json.JSONArray());pages.put(page);}
    public void text(String type,String value)throws Exception{String id="b"+(++count);page.getJSONArray("reading_order").put(id);page.getJSONArray("blocks").put(FilesUtil.obj("id",id,"type",type,"status",type.equals("notice")?"notice":"ok","content",FilesUtil.obj("format",type.equals("table")?"html":"markdown","text",value)));}
    public void image(String name,byte[] bytes)throws Exception{String id="b"+(++count),path="assets/"+id+".png";images.put(path,bytes);assertEquals(0x89504e47,java.nio.ByteBuffer.wrap(bytes).getInt());java.nio.ByteBuffer dimensions=java.nio.ByteBuffer.wrap(bytes,16,8);int width=dimensions.getInt(),height=dimensions.getInt();assertEquals(480,width);assertEquals(160,height);resources.put(FilesUtil.obj("path",path,"width",width,"height",height));page.getJSONArray("reading_order").put(id);page.getJSONArray("blocks").put(FilesUtil.obj("id",id,"type","image","status","pending-ocr","content",FilesUtil.obj("resource",path)));assertEquals("p2",page.getString("page_id"));}
   });
   assertEquals(2,pages.length());assertEquals(1,images.size());assertTrue(pages.getJSONObject(0).toString().contains("colspan"));
   File output=new File(System.getProperty("officeFixtureOutput","../verification/office-fixtures"));output.mkdirs();
   try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(new File(output,name+".zip")))){DocumentStore.put(zip,"document.json",FilesUtil.obj("schema_version","app-replay-1","pages",pages,"resources",resources).toString(2).getBytes(StandardCharsets.UTF_8));for(Map.Entry<String,byte[]> image:images.entrySet())DocumentStore.put(zip,image.getKey(),image.getValue());}
  }
 }
}
