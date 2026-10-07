package cn.local.ocr;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.w3c.dom.*;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

/** Read cell structure and cached values; never execute spreadsheet formulas/macros. */
final class SpreadsheetParser {
    static final class Span {int r,c,lastR,lastC;Span(int r,int c,int lastR,int lastC){this.r=r;this.c=c;this.lastR=lastR;this.lastC=lastC;}}
    static final class Grid {
        final Map<Long,String> cells=new HashMap<>();final List<Span> spans=new ArrayList<>();
        int minR=Integer.MAX_VALUE,minC=Integer.MAX_VALUE,maxR=-1,maxC=-1;
        void extent(int r,int c)throws IOException{if(r<0||r>=10000||c<0||c>=256)throw new IOException("工作表内容超出本版 10000 行 / 256 列限制");minR=Math.min(minR,r);minC=Math.min(minC,c);maxR=Math.max(maxR,r);maxC=Math.max(maxC,c);}
        void put(int r,int c,String s)throws IOException{if(s.isEmpty())return;extent(r,c);cells.put(key(r,c),s);}
        void span(Span s)throws IOException{if(s.lastR<s.r||s.lastC<s.c)throw new IOException("无效合并区域");extent(s.r,s.c);extent(s.lastR,s.lastC);spans.add(s);}
        String html()throws IOException{
            if(maxR<0)return "";if((long)(maxR-minR+1)*(maxC-minC+1)>200000)throw new IOException("工作表有效区域过大，请拆分工作表后导入");
            Map<Long,Span> starts=new HashMap<>();Set<Long> covered=new HashSet<>();
            for(Span s:spans){starts.put(key(s.r,s.c),s);for(int r=s.r;r<=s.lastR;r++)for(int c=s.c;c<=s.lastC;c++)if(r!=s.r||c!=s.c)covered.add(key(r,c));}
            StringBuilder out=new StringBuilder("<table>");for(int r=minR;r<=maxR;r++){out.append("<tr>");for(int c=minC;c<=maxC;c++){
                long key=key(r,c);if(covered.contains(key))continue;Span s=starts.get(key);out.append("<td");if(s!=null){if(s.lastR>s.r)out.append(" rowspan=\"").append(s.lastR-s.r+1).append("\"");if(s.lastC>s.c)out.append(" colspan=\"").append(s.lastC-s.c+1).append("\"");}
                out.append(">").append(FilesUtil.escape(cells.getOrDefault(key,"")).replace("\n","<br/>" )).append("</td>");
            }out.append("</tr>");}return out.append("</table>").toString();
        }
    }
    static long key(int r,int c){return (long)r*256+c;}
    static int[] address(String a)throws IOException{if(!a.matches("\\$?[A-Za-z]+\\$?[0-9]+"))throw new IOException("无效单元格地址："+a);a=a.replace("$","").toUpperCase(Locale.ROOT);int col=0,n=0;while(n<a.length()&&Character.isLetter(a.charAt(n))){col=col*26+a.charAt(n++)-'A'+1;if(col>16384)throw new IOException("无效列地址");}return new int[]{Integer.parseInt(a.substring(n))-1,col-1};}
    static Span range(String value)throws IOException{String[] parts=value.split(":");int[] a=address(parts[0]),b=address(parts[parts.length-1]);return new Span(a[0],a[1],b[0],b[1]);}
    static void parse(File file,String ext,OfficeParser.Sink sink)throws Exception{if(ext.equals("xls")){xls(file,sink);return;}
        try(ZipFile zip=new ZipFile(file)){
            Document workbook=OfficeParser.part(zip,"xl/workbook.xml");Map<String,String> relationships=OfficeParser.rels(zip,"xl/_rels/workbook.xml.rels");
            List<String> shared=new ArrayList<>();if(zip.getEntry("xl/sharedStrings.xml")!=null){NodeList strings=OfficeParser.part(zip,"xl/sharedStrings.xml").getElementsByTagNameNS("*","si");for(int i=0;i<strings.getLength();i++)shared.add(OfficeParser.text(strings.item(i),"t"));}
            Map<Integer,String> formats=new HashMap<>();List<Integer> styles=new ArrayList<>();
            if(zip.getEntry("xl/styles.xml")!=null){Document d=OfficeParser.part(zip,"xl/styles.xml");NodeList fs=d.getElementsByTagNameNS("*","numFmt");for(int i=0;i<fs.getLength();i++){Element f=(Element)fs.item(i);formats.put(Integer.parseInt(f.getAttribute("numFmtId")),f.getAttribute("formatCode"));}
                NodeList xfs=d.getElementsByTagNameNS("*","cellXfs");if(xfs.getLength()>0)for(Node xf=xfs.item(0).getFirstChild();xf!=null;xf=xf.getNextSibling())if("xf".equals(xf.getLocalName()))styles.add(integer(((Element)xf).getAttribute("numFmtId"),0));}
            boolean date1904=false;NodeList props=workbook.getElementsByTagNameNS("*","workbookPr");if(props.getLength()>0){String v=((Element)props.item(0)).getAttribute("date1904");date1904=v.equals("1")||v.equals("true");}
            DataFormatter formatter=new DataFormatter(Locale.CHINA);NodeList sheets=workbook.getElementsByTagNameNS("*","sheet");
            if(sheets.getLength()>1000)throw new IOException("工作表超过 1000 个");
            for(int i=0;i<sheets.getLength();i++){
                Element sheet=(Element)sheets.item(i);String title=sheet.getAttribute("name");sink.page(i+1,title,"worksheet");sink.text("text","## "+title);
                String target=relationships.get(OfficeParser.attr(sheet,"id"));if(target==null)throw new IOException("工作表关联缺失");String path=OfficeParser.resolvePart("xl/workbook.xml",target);Document d=OfficeParser.part(zip,path);Grid grid=new Grid();boolean missingCache=false;
                NodeList rows=d.getElementsByTagNameNS("*","row");if(rows.getLength()>10000)throw new IOException("工作表超过 10000 行");
                int previousRow=-1;for(int r=0;r<rows.getLength();r++){
                    Element row=(Element)rows.item(r);int rowIndex=integer(row.getAttribute("r"),previousRow+2)-1;previousRow=rowIndex;int previousCol=-1;
                    for(Node n=row.getFirstChild();n!=null;n=n.getNextSibling())if("c".equals(n.getLocalName())){
                        Element cell=(Element)n;int[] at=cell.hasAttribute("r")?address(cell.getAttribute("r")):new int[]{rowIndex,previousCol+1};previousCol=at[1];String type=cell.getAttribute("t"),v=OfficeParser.text(cell,"v"),formula=OfficeParser.text(cell,"f");
                        if(type.equals("s")&&!v.isEmpty())v=shared.get(Integer.parseInt(v));
                        else if(type.equals("inlineStr"))v=OfficeParser.text(cell,"t");
                        else if(type.equals("b"))v=v.equals("1")?"TRUE":"FALSE";
                        else if(!v.isEmpty()&&(type.isEmpty()||type.equals("n"))){int style=integer(cell.getAttribute("s"),0),fmt=style<styles.size()?styles.get(style):0;String code=formats.getOrDefault(fmt,BuiltinFormats.getBuiltinFormat(fmt));try{v=formatter.formatRawCellContents(Double.parseDouble(v),fmt,code==null?"General":code,date1904);}catch(NumberFormatException ignored){}}
                        if(v.isEmpty()&&!formula.isEmpty()){v="="+formula+"（无缓存结果）";missingCache=true;}
                        grid.put(at[0],at[1],v);
                    }
                }
                NodeList merges=d.getElementsByTagNameNS("*","mergeCell");for(int m=0;m<merges.getLength();m++)grid.span(range(((Element)merges.item(m)).getAttribute("ref")));
                String table=grid.html();if(!table.isEmpty())sink.text("table",table);else sink.warning("此工作表没有可提取的单元格内容。");
                if(missingCache)sink.warning("部分公式没有缓存结果，已保留公式文本；本版不重新计算公式。");
                Map<String,String> sheetRels=OfficeParser.rels(zip,OfficeParser.relsPath(path));NodeList drawings=d.getElementsByTagNameNS("*","drawing");
                for(int a=0;a<drawings.getLength();a++){String drawTarget=sheetRels.get(OfficeParser.attr((Element)drawings.item(a),"id"));if(drawTarget==null){sink.warning("工作表绘图关联缺失。");continue;}
                    String drawing=OfficeParser.resolvePart(path,drawTarget);Document draw=OfficeParser.part(zip,drawing);Map<String,String> drawRels=OfficeParser.rels(zip,OfficeParser.relsPath(drawing));NodeList blips=draw.getElementsByTagNameNS("*","blip");Set<String> emitted=new HashSet<>();
                    for(int b=0;b<blips.getLength();b++){String ref=OfficeParser.attr((Element)blips.item(b),"embed"),image=drawRels.get(ref);if(image==null){sink.warning("链接或缺失的图片未提取。");continue;}if(!emitted.add(image))continue;String resource=OfficeParser.resolvePart(drawing,image);ZipEntry entry=zip.getEntry(resource);if(entry==null){sink.warning("图片资源缺失。");continue;}try(InputStream in=zip.getInputStream(entry)){sink.image(resource,FilesUtil.bytes(in,32L*1024*1024));}}
                    if(draw.getElementsByTagNameNS("*","chart").getLength()>0)sink.warning("工作表图表未还原；已保留可提取的单元格数据。");
                }
            }
        }
    }
    static int integer(String s,int fallback){return s.isEmpty()?fallback:Integer.parseInt(s);}
    static void xls(File file,OfficeParser.Sink sink)throws Exception{
        try(HSSFWorkbook workbook=new HSSFWorkbook(new FileInputStream(file))){DataFormatter formatter=new DataFormatter(Locale.CHINA);int index=0;
            for(Sheet sheet:workbook){sink.page(++index,sheet.getSheetName(),"worksheet");sink.text("text","## "+sheet.getSheetName());Grid grid=new Grid();
                for(Row row:sheet)for(Cell cell:row){String value;
                    if(cell.getCellType()==CellType.FORMULA){switch(cell.getCachedFormulaResultType()){
                        case NUMERIC:CellStyle style=cell.getCellStyle();value=formatter.formatRawCellContents(cell.getNumericCellValue(),style.getDataFormat(),style.getDataFormatString());break;
                        case STRING:value=cell.getStringCellValue();break;case BOOLEAN:value=cell.getBooleanCellValue()?"TRUE":"FALSE";break;default:value="="+cell.getCellFormula();}}
                    else value=formatter.formatCellValue(cell);grid.put(cell.getRowIndex(),cell.getColumnIndex(),value);
                }
                for(CellRangeAddress merge:sheet.getMergedRegions())grid.span(new Span(merge.getFirstRow(),merge.getFirstColumn(),merge.getLastRow(),merge.getLastColumn()));String html=grid.html();if(!html.isEmpty())sink.text("table",html);else sink.warning("此工作表没有单元格内容。");
                HSSFPatriarch drawing=((HSSFSheet)sheet).getDrawingPatriarch();if(drawing!=null)xlsImages(drawing.getChildren(),sink);
            }
        }
    }
    static void xlsImages(List<HSSFShape> shapes,OfficeParser.Sink sink)throws Exception{for(HSSFShape shape:shapes){if(shape instanceof HSSFPicture){HSSFPictureData data=((HSSFPicture)shape).getPictureData();if(data!=null)sink.image("picture."+data.suggestFileExtension(),data.getData());}else if(shape instanceof HSSFShapeGroup)xlsImages(((HSSFShapeGroup)shape).getChildren(),sink);}}
}
