package cn.local.ocr;

import java.io.*;
import java.util.*;
import com.tom_roush.pdfbox.cos.*;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.util.Matrix;
import com.tom_roush.pdfbox.util.Vector;
import org.json.*;

/** PDFBox input adapter for the separately tested RapidDoc policy. */
final class PdfClassification {
    final List<Integer> indices;
    final List<RapidDocPdfPolicy.Sample> samples=new ArrayList<>();
    RapidDocPdfPolicy.Decision decision;
    String error="";
    PdfClassification(PDDocument pdf){
        indices=RapidDocPdfPolicy.samplePages(pdf.getNumberOfPages());
        try{for(int index:indices)samples.add(sample(pdf,index));decision=RapidDocPdfPolicy.classify(samples);}
        catch(Exception exception){error=String.valueOf(exception.getMessage());decision=new RapidDocPdfPolicy.Decision(true,"classification_error",-1);}
    }
    static RapidDocPdfPolicy.Sample sample(PDDocument pdf,int index)throws IOException{
        PDPage page=pdf.getPage(index);RapidDocPdfPolicy.Sample sample=new RapidDocPdfPolicy.Sample();sample.page=index;sample.width=page.getCropBox().getWidth();sample.height=page.getCropBox().getHeight();
        fontResources(page.getResources(),sample);
        PDFTextStripper extractor=new PDFTextStripper(){
            @Override protected void showGlyph(Matrix matrix,PDFont font,int code,String unicode,Vector displacement)throws IOException{
                // PDFium's HasUnicodeMapError is unavailable here. Null PDFBox mappings are the adapter signal.
                String decoded=unicode==null||unicode.isEmpty()?"\u0000":unicode;
                int count=RapidDocPdfPolicy.length(decoded);sample.total+=count;sample.nonGenerated+=count;
                if(unicode==null||unicode.isEmpty())sample.mapErrors++;
                String name=RapidDocPdfPolicy.fontName(font.getName());sample.fonts.put(name,sample.fonts.getOrDefault(name,0)+count);sample.nativeFonts.put(name,sample.nativeFonts.getOrDefault(name,0)+count);
                for(int c:decoded.codePoints().toArray()){if(RapidDocPdfPolicy.abnormal(c))sample.abnormal++;if(RapidDocPdfPolicy.cjk(c))sample.cjkFonts.put(name,sample.cjkFonts.getOrDefault(name,0)+1);}
                super.showGlyph(matrix,font,code,unicode,displacement);
            }
        };
        extractor.setSortByPosition(true);extractor.setStartPage(index+1);extractor.setEndPage(index+1);sample.text=extractor.getText(pdf);
        // Keep inserted spaces/newlines in total-character denominators, but out of native font usage.
        sample.total=Math.max(sample.total,RapidDocPdfPolicy.length(sample.text));return sample;
    }
    static void fontResources(PDResources resources,RapidDocPdfPolicy.Sample sample){
        if(resources==null)return;COSBase fontBase=resources.getCOSObject().getDictionaryObject(COSName.FONT);if(!(fontBase instanceof COSDictionary))return;
        COSDictionary fonts=(COSDictionary)fontBase;Map<String,Set<COSDictionary>> candidates=new HashMap<>();Map<COSDictionary,Boolean> latin=new IdentityHashMap<>();
        for(COSName key:fonts.keySet()){
            COSBase value=fonts.getDictionaryObject(key);if(!(value instanceof COSDictionary))continue;COSDictionary font=(COSDictionary)value;
            String name=RapidDocPdfPolicy.fontName(font.getNameAsString(COSName.BASE_FONT,key.getName()));if(name.isEmpty())continue;
            String subtype=font.getNameAsString(COSName.SUBTYPE),encoding=font.getNameAsString(COSName.ENCODING);
            if("Type0".equals(subtype)&&("Identity-H".equals(encoding)||"Identity-V".equals(encoding))&&font.containsKey(COSName.DESCENDANT_FONTS)&&!font.containsKey(COSName.TO_UNICODE))sample.cidFonts.add(name);
            COSBase desc=font.getDictionaryObject(COSName.FONT_DESC);String charset=desc instanceof COSDictionary?((COSDictionary)desc).getString(COSName.getPDFName("CharSet")):null;
            boolean suspect="Type1".equals(subtype)&&font.getDictionaryObject(COSName.TO_UNICODE)!=null&&charset!=null&&RapidDocPdfPolicy.latinCharset(charset);
            candidates.computeIfAbsent(name,k->Collections.newSetFromMap(new IdentityHashMap<>())).add(font);latin.put(font,suspect);
        }
        for(Map.Entry<String,Set<COSDictionary>> entry:candidates.entrySet())if(entry.getValue().size()==1&&Boolean.TRUE.equals(latin.get(entry.getValue().iterator().next())))sample.latinFonts.add(entry.getKey());
    }
    JSONObject json()throws JSONException{
        JSONArray metrics=new JSONArray();for(RapidDocPdfPolicy.Sample p:samples)metrics.put(FilesUtil.obj("page",p.page+1,"cleanedChars",RapidDocPdfPolicy.length(RapidDocPdfPolicy.clean(p.text)),"totalChars",p.total,"abnormalChars",p.abnormal,"unicodeMapErrors",p.mapErrors));
        List<Integer> oneBased=new ArrayList<>();for(int index:indices)oneBased.add(index+1);
        return FilesUtil.obj("policy","RapidDoc auto","revision",RapidDocPdfPolicy.REVISION,"scope","document","mode",decision.mode(),"reason",decision.reason,"description",decision.description(),"triggerPage",decision.page<0?JSONObject.NULL:decision.page+1,"samplePages",new JSONArray(oneBased),"metrics",metrics,"collector","pdfbox-android","adapterDifference","字符计数、字体使用与缺失映射来自 PDFBox，非 PDFium 原始指标；相同指标下决策规则对齐。","error",error);
    }
}
