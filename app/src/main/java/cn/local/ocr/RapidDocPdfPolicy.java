package cn.local.ocr;

import java.util.*;
import java.util.regex.*;

/** Decision thresholds/order from RapidDoc pdf_classify.py at the pinned revision.
 * Android collects the inputs separately; the PDFBox adapter is not PDFium-equivalent.
 */
final class RapidDocPdfPolicy {
    static final String REVISION="60cd038d424e0e839462ba4bd96345e0279290fe";
    static final class Sample {
        int page,total,abnormal,mapErrors,nonGenerated;
        double width,height;
        String text="";
        final Map<String,Integer> fonts=new HashMap<>(),nativeFonts=new HashMap<>(),cjkFonts=new HashMap<>();
        final Set<String> cidFonts=new HashSet<>(),latinFonts=new HashSet<>();
    }
    static final class Decision {
        final boolean ocr;final String reason;final int page;
        Decision(boolean ocr,String reason,int page){this.ocr=ocr;this.reason=reason;this.page=page;}
        String mode(){return ocr?"ocr":"txt";}
        String description(){switch(reason){
            case "empty_document":return "文档没有可分类页面";
            case "extreme_aspect_ratio":return "抽样页长宽比超过 10";
            case "sparse_text":return "抽样页平均有效字符少于 50";
            case "unicode_mapping":return "字符映射错误比例达到 4%";
            case "cid_without_mapping":return "缺少 ToUnicode 的 CID 字体实际使用量异常";
            case "latin_font_cjk":return "Latin 字体被大量解码成中文字符";
            case "abnormal_characters":return "文本异常字符比例达到 3%";
            case "cross_script":return "文本中出现密集的跨文字系统异常混入";
            case "suspicious_cjk":return "文本存在可疑的中文字符映射";
            case "punctuation":return "抽样页存在密集的连续标点乱码";
            case "classification_error":return "分类信息读取失败，转 OCR 路径";
            default:return "抽样文本通过质量检查";
        }}
    }
    static List<Integer> samplePages(int count){
        List<Integer> out=new ArrayList<>();int n=Math.min(10,Math.max(0,count));
        for(int i=0;i<n;i++)out.add(n==1?0:(int)Math.rint((double)i*(count-1)/(n-1)));
        return out;
    }
    static String clean(String s){StringBuilder out=new StringBuilder();s.codePoints().filter(c->!(Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0x85)).forEach(out::appendCodePoint);return out.toString();}
    static int length(String s){return s.codePointCount(0,s.length());}
    static double ratio(long count,long total){return total>0?(double)count/total:0;}
    static String fontName(String s){return s==null?"":s.trim().replaceFirst("^/+","").replaceFirst("^[A-Z]{6}\\+","");}
    static boolean cjk(int c){return c>=0x3400&&c<=0x4dbf||c>=0x4e00&&c<=0x9fff||c>=0xf900&&c<=0xfaff||c>=0x20000&&c<=0x2ebef;}
    static boolean abnormal(int c){return c==0||c==0xfffd||c>=0xe000&&c<=0xf8ff||((c<32||c>=127&&c<=159)&&c!=9&&c!=10&&c!=13);}
    static boolean latinCharset(String charset){
        Matcher matcher=Pattern.compile("/([^/\\s]+)").matcher(charset);Set<String> names=new HashSet<>();while(matcher.find())names.add(matcher.group(1));int latin=0;
        for(String name:names){if(name.matches("[A-Za-z]"))latin++;if(name.matches("(?:uni|u)[0-9a-fA-F]{4,6}")){String hex=name.substring(name.startsWith("uni")?3:1);if(cjk(Integer.parseInt(hex,16)))return false;}}
        return latin>=10&&ratio(latin,names.size())>=.5;
    }
    static Decision classify(List<Sample> samples){
        if(samples.isEmpty())return new Decision(true,"empty_document",-1);
        long cleaned=0,total=0,bad=0,mapErrors=0;
        for(Sample p:samples){if(p.width>0&&p.height>0&&Math.max(p.width/p.height,p.height/p.width)>10)return new Decision(true,"extreme_aspect_ratio",p.page);cleaned+=length(clean(p.text));total+=p.total;bad+=p.abnormal;mapErrors+=p.mapErrors;}
        if((double)cleaned/samples.size()<50)return new Decision(true,"sparse_text",-1);
        if(ratio(mapErrors,total)>=.04)return new Decision(true,"unicode_mapping",-1);
        for(Sample p:samples){long used=0;for(String font:p.cidFonts)used+=p.fonts.getOrDefault(font,0);if(used>=30&&ratio(used,p.total)>=.01)return new Decision(true,"cid_without_mapping",p.page);}
        for(Sample p:samples)for(String font:new TreeSet<>(p.latinFonts)){int used=p.nativeFonts.getOrDefault(font,0),chinese=p.cjkFonts.getOrDefault(font,0);if(used>=30&&ratio(used,p.nonGenerated)>=.01&&ratio(chinese,used)>=.8)return new Decision(true,"latin_font_cjk",p.page);}
        if(total>=300&&ratio(bad,total)>=.03)return new Decision(true,"abnormal_characters",-1);
        long chinese=0,suspicious=0,basicChinese=0,unusual=0;int[] scripts=new int[CROSS_SCRIPT.length];
        for(Sample p:samples)for(int c:clean(p.text).codePoints().toArray()){
            if(cjk(c))chinese++;if(c>=0x4e00&&c<=0x9fff)basicChinese++;
            if(c>=0x7280&&c<=0x72df&&"犀犁犄犊犒犟犬犯状犷犹狂狄狈狐狗狙狞".indexOf(c)<0)unusual++;
            for(int i=0;i<CROSS_SCRIPT.length;i++)if(c>=CROSS_SCRIPT[i][0]&&c<=CROSS_SCRIPT[i][1]){scripts[i]++;suspicious++;break;}
        }
        int dense=0;for(int count:scripts)if(count>=5)dense++;
        if(cleaned>=300&&chinese>=100&&suspicious>=120&&ratio(suspicious,cleaned)>=.18&&dense>=3)return new Decision(true,"cross_script",-1);
        if(unusual>=30&&ratio(unusual,basicChinese)>=.026)return new Decision(true,"suspicious_cjk",-1);
        for(Sample p:samples)if(punctuation(clean(p.text)))return new Decision(true,"punctuation",p.page);
        // RapidDoc logs high image coverage after these checks but does not change txt to ocr.
        return new Decision(false,"text_quality_passed",-1);
    }
    static final int[][] CROSS_SCRIPT={{0x0400,0x052f},{0x0600,0x06ff},{0x0700,0x074f},{0x0750,0x077f},{0x0780,0x07bf},{0x07c0,0x07ff},{0x0800,0x083f},{0x0840,0x085f},{0x0860,0x086f},{0x0870,0x089f},{0x0900,0x097f},{0x0c80,0x0cff},{0x1000,0x109f},{0x1100,0x11ff},{0x1200,0x137f},{0x13a0,0x13ff},{0x1400,0x167f},{0x1800,0x18af},{0x1a20,0x1aaf},{0x2c00,0x2c5f},{0xa000,0xa48f}};
    static boolean punct(int c){return c>=33&&c<=47||c>=58&&c<=64||c>=91&&c<=96||c>=123&&c<=126;}
    static boolean punctuation(String text){
        int size=length(text),punct=0,run=0,dots=0,runChars=0,dotChars=0;
        for(int c:text.codePoints().toArray()){
            if(punct(c)){punct++;run++;}else{if(run>=4)runChars+=run;run=0;}
            if(c=='.')dots++;else{if(dots>=8)dotChars+=dots;dots=0;}
        }
        if(run>=4)runChars+=run;if(dots>=8)dotChars+=dots;
        if(size-punct>=80){punct=Math.max(0,punct-dotChars);runChars=Math.max(0,runChars-dotChars);}
        return size>=100&&ratio(punct,size)>=.25&&ratio(runChars,size)>=.1;
    }
}
