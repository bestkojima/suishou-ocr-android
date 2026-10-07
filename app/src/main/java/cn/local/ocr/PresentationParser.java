package cn.local.ocr;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.w3c.dom.*;

/** Extract slide content without a desktop rendering engine or model inference. */
final class PresentationParser {
    static void parse(File file, OfficeParser.Sink sink) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            Document presentation = OfficeParser.part(zip, "ppt/presentation.xml");
            Map<String,String> relationships = OfficeParser.rels(zip, "ppt/_rels/presentation.xml.rels");
            NodeList slides = presentation.getElementsByTagNameNS("*", "sldId");
            if (slides.getLength() > 1000) throw new IOException("幻灯片超过 1000 页");
            for (int i = 0; i < slides.getLength(); i++) {
                String id = OfficeParser.attr((Element) slides.item(i), "id");
                // sldId has both an unqualified numeric id and r:id; resolve the relationship attribute.
                NamedNodeMap attrs=slides.item(i).getAttributes();
                for(int a=0;a<attrs.getLength();a++){Node attr=attrs.item(a);if("id".equals(attr.getLocalName())&&attr.getNamespaceURI()!=null)id=attr.getNodeValue();}
                String target = relationships.get(id);
                if (target == null) throw new IOException("幻灯片关联缺失：" + id);
                String path = OfficeParser.resolvePart("ppt/presentation.xml", target);
                sink.page(i + 1, "第 " + (i + 1) + " 张幻灯片", "slide");
                sink.text("text", "## 第 " + (i + 1) + " 张幻灯片");
                Document slide = OfficeParser.part(zip, path);
                NodeList trees=slide.getElementsByTagNameNS("*","spTree");
                if(trees.getLength()==0){sink.warning("本页没有可提取的内容。");continue;}
                Map<String,String> rels=OfficeParser.rels(zip,OfficeParser.relsPath(path));
                shapes((Element)trees.item(0),zip,path,rels,sink);
            }
        }
    }
    static void shapes(Element tree,ZipFile zip,String part,Map<String,String> rels,OfficeParser.Sink sink)throws Exception{
        for(Node child=tree.getFirstChild();child!=null;child=child.getNextSibling()){
            if(!(child instanceof Element))continue;
            Element shape=(Element)child;String type=shape.getLocalName();
            if("grpSp".equals(type)){shapes(shape,zip,part,rels,sink);continue;}
            if("sp".equals(type)){
                NodeList bodies=shape.getElementsByTagNameNS("*","txBody");
                for(int i=0;i<bodies.getLength();i++){String value=paragraphs((Element)bodies.item(i));if(!value.trim().isEmpty())sink.text("text",value);}
                if(bodies.getLength()==0)sink.warning("本页包含无法直接提取文字的绘图对象，尚未转为页面图片。");
            }else if("graphicFrame".equals(type)){
                NodeList tables=shape.getElementsByTagNameNS("*","tbl");
                if(tables.getLength()>0)for(int i=0;i<tables.getLength();i++)sink.text("table",table((Element)tables.item(i)));
                else sink.warning("图表或 SmartArt 未还原；可先导出为 PDF 再导入。");
            }
            NodeList images=shape.getElementsByTagNameNS("*","blip");
            Set<String> emitted=new HashSet<>();
            for(int n=0;n<images.getLength();n++){
                String ref=OfficeParser.attr((Element)images.item(n),"embed");
                if(ref.isEmpty()){sink.warning("外部链接图片未下载。");continue;}
                if(!emitted.add(ref))continue;String target=rels.get(ref);
                if(target==null){sink.warning("图片关联缺失。");continue;}
                String path=OfficeParser.resolvePart(part,target);ZipEntry e=zip.getEntry(path);
                if(e==null){sink.warning("图片文件缺失："+path);continue;}
                try(InputStream in=zip.getInputStream(e)){sink.image(path,FilesUtil.bytes(in,32L*1024*1024));}
            }
        }
    }
    static String paragraphs(Element body){
        StringBuilder out=new StringBuilder();NodeList ps=body.getElementsByTagNameNS("*","p");
        for(int i=0;i<ps.getLength();i++){
            Element p=(Element)ps.item(i);String value=inline(p).trim();if(value.isEmpty())continue;
            if(out.length()>0)out.append("\n\n");
            if(p.getElementsByTagNameNS("*","buChar").getLength()>0||p.getElementsByTagNameNS("*","buAutoNum").getLength()>0)out.append("- ");
            out.append(value);
        }return out.toString();
    }
    static String inline(Node n){if("t".equals(n.getLocalName()))return n.getTextContent();if("br".equals(n.getLocalName()))return "\n";if("tab".equals(n.getLocalName()))return "\t";StringBuilder s=new StringBuilder();for(Node c=n.getFirstChild();c!=null;c=c.getNextSibling())s.append(inline(c));return s.toString();}
    static String table(Element table){
        StringBuilder out=new StringBuilder("<table>");
        for(Node row=table.getFirstChild();row!=null;row=row.getNextSibling())if("tr".equals(row.getLocalName())){
            out.append("<tr>");for(Node cell=row.getFirstChild();cell!=null;cell=cell.getNextSibling())if("tc".equals(cell.getLocalName())){
                Element e=(Element)cell;if("1".equals(e.getAttribute("hMerge"))||"true".equals(e.getAttribute("hMerge"))||"1".equals(e.getAttribute("vMerge"))||"true".equals(e.getAttribute("vMerge")))continue;
                out.append("<td");for(String attr:new String[]{"gridSpan","rowSpan"}){String v=e.getAttribute(attr);if(v.matches("[1-9][0-9]{0,2}"))out.append(attr.equals("gridSpan")?" colspan=\"":" rowspan=\"").append(v).append("\"");}
                out.append(">").append(FilesUtil.escape(paragraphs(e)).replace("\n","<br/>" )).append("</td>");
            }out.append("</tr>");
        }return out.append("</table>").toString();
    }
}
