package cn.local.ocr;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;

/** Text/table extraction. Does not claim pixel-faithful Office pagination. */
public final class OfficeParser {
    public interface Sink {default void page(int number,String title,String kind)throws Exception{}default void warning(String message)throws Exception{text("notice","> "+message);}void text(String type,String content)throws Exception;void image(String name,byte[] data)throws Exception;}
    static Document xml(InputStream in)throws Exception{
        byte[] bytes=FilesUtil.bytes(in,32L*1024*1024);
        // Android's XML factory does not support all desktop Xerces features.
        String declaration=new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1).replace("\u0000","");
        if(declaration.toUpperCase(Locale.ROOT).contains("<!DOCTYPE"))throw new IOException("Office XML 不允许外部实体声明");
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
        DocumentBuilder builder=f.newDocumentBuilder();
        builder.setEntityResolver((publicId,systemId)->{throw new org.xml.sax.SAXException("禁止外部实体");});
        return builder.parse(new ByteArrayInputStream(bytes));
    }
    static String text(Node n,String local){StringBuilder b=new StringBuilder();if(n.getNodeType()==Node.ELEMENT_NODE&&local.equals(n.getLocalName()))b.append(n.getTextContent());else for(Node c=n.getFirstChild();c!=null;c=c.getNextSibling())b.append(text(c,local));return b.toString();}
    static String attr(Element e,String name){for(int i=0;i<e.getAttributes().getLength();i++){Node n=e.getAttributes().item(i);if(name.equals(n.getLocalName())||name.equals(n.getNodeName()))return n.getNodeValue();}return "";}
    static Document part(ZipFile z,String name)throws Exception{ZipEntry e=z.getEntry(name);if(e==null)throw new IOException("缺少 Office 内容："+name);try(InputStream in=z.getInputStream(e)){return xml(new ByteArrayInputStream(FilesUtil.bytes(in,32L*1024*1024)));}}
    static Map<String,String> rels(ZipFile zip,String name)throws Exception{Map<String,String> m=new HashMap<>();if(zip.getEntry(name)==null)return m;NodeList rs=part(zip,name).getElementsByTagNameNS("*","Relationship");for(int i=0;i<rs.getLength();i++){Element e=(Element)rs.item(i);if(!e.getAttribute("TargetMode").equals("External"))m.put(e.getAttribute("Id"),e.getAttribute("Target"));}return m;}
    static String resolvePart(String source,String target)throws IOException{
        if(target.contains(":")||target.contains("\\"))throw new IOException("无效 Office 关联路径");
        java.nio.file.Path path=target.startsWith("/")?java.nio.file.Paths.get(target.substring(1)):java.nio.file.Paths.get(source).getParent().resolve(target);
        path=path.normalize();if(path.isAbsolute()||path.startsWith(".."))throw new IOException("Office 关联越界");return path.toString();
    }
    static String relsPath(String part){int slash=part.lastIndexOf('/');return part.substring(0,slash+1)+"_rels/"+part.substring(slash+1)+".rels";}
    public static void parse(File file,String ext,Sink sink)throws Exception{
        try{parseContent(file,ext,sink);}catch(LinkageError error){throw new IOException("当前设备不支持此旧 Office 格式的依赖，请另存为 DOCX / XLSX / PPTX 或 PDF 后导入",error);}
    }
    private static void parseContent(File file,String ext,Sink sink)throws Exception{
        if(ext.equals("pptx")){PresentationParser.parse(file,sink);return;}
        if(ext.equals("ppt")){LegacyPresentationParser.parse(file,sink);return;}
        if(ext.equals("xls")||ext.equals("xlsx")){SpreadsheetParser.parse(file,ext,sink);return;}
        sink.page(1,"正文","document");
        if(ext.equals("doc")){try(HWPFDocument d=new HWPFDocument(new FileInputStream(file));WordExtractor e=new WordExtractor(d)){for(String p:e.getParagraphText())if(!p.trim().isEmpty())sink.text("text",p.trim());for(var pic:d.getPicturesTable().getAllPictures())sink.image(pic.suggestFullFileName(),pic.getContent());}return;}
        try(ZipFile z=new ZipFile(file)){
            if(ext.equals("docx")){Document doc=part(z,"word/document.xml");Map<String,String> rel=rels(z,"word/_rels/document.xml.rels");Node body=doc.getElementsByTagNameNS("*","body").item(0);if(body==null)throw new IOException("Word 正文为空");Set<String> emitted=new HashSet<>();for(Node n=body.getFirstChild();n!=null;n=n.getNextSibling()){
                if("p".equals(n.getLocalName())){String value=text(n,"t");if(!value.trim().isEmpty()){NodeList styles=((Element)n).getElementsByTagNameNS("*","pStyle");String prefix="";if(styles.getLength()>0&&attr((Element)styles.item(0),"val").toLowerCase(java.util.Locale.ROOT).startsWith("heading"))prefix="## ";sink.text("text",prefix+value);}}
                if("tbl".equals(n.getLocalName())){StringBuilder t=new StringBuilder("<table>");NodeList rows=((Element)n).getElementsByTagNameNS("*","tr");for(int r=0;r<rows.getLength();r++){t.append("<tr>");for(Node cell=rows.item(r).getFirstChild();cell!=null;cell=cell.getNextSibling())if("tc".equals(cell.getLocalName())){NodeList spans=((Element)cell).getElementsByTagNameNS("*","gridSpan");String span=spans.getLength()>0?attr((Element)spans.item(0),"val"):"1";if(!span.matches("[0-9]{1,3}"))span="1";t.append("<td colspan=\"").append(span).append("\">").append(FilesUtil.escape(text(cell,"t"))).append("</td>");}t.append("</tr>");}sink.text("table",t.append("</table>").toString());}
                if(n instanceof Element){NodeList blips=((Element)n).getElementsByTagNameNS("*","blip");for(int i=0;i<blips.getLength();i++){String id=attr((Element)blips.item(i),"embed"),target=rel.get(id);if(target==null||!emitted.add(id))continue;String path=resolvePart("word/document.xml",target);ZipEntry entry=z.getEntry(path);if(entry!=null)try(InputStream in=z.getInputStream(entry)){sink.image(new File(path).getName(),FilesUtil.bytes(in,32L*1024*1024));}}}
            }}else throw new IOException("不支持的 Office 格式");
        }
    }
}
