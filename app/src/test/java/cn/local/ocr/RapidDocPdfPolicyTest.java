package cn.local.ocr;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
public class RapidDocPdfPolicyTest {
 List<Object> list(JSONArray a)throws Exception{List<Object> out=new ArrayList<>();for(int i=0;i<a.length();i++)out.add(a.get(i));return out;}
 JSONObject fixtures()throws Exception{try(java.io.InputStream in=getClass().getResourceAsStream("/pdf/rapiddoc-policy-cases.json")){return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}}
 void map(JSONObject row,String key,Map<String,Integer> out)throws Exception{JSONObject values=row.getJSONObject(key);for(Iterator<String> it=values.keys();it.hasNext();){String k=it.next();out.put(k,values.getInt(k));}}
 @Test public void decisionsMatchPinnedOriginalRapidDocForIdenticalMetrics()throws Exception{
  JSONObject fixture=fixtures();assertEquals(RapidDocPdfPolicy.REVISION,fixture.getString("revision"));
  for(Object value:list(fixture.getJSONArray("cases"))){
   JSONObject test=(JSONObject)value;List<RapidDocPdfPolicy.Sample> samples=new ArrayList<>();
   for(Object item:list(test.getJSONArray("samples"))){
    JSONObject p=(JSONObject)item;RapidDocPdfPolicy.Sample s=new RapidDocPdfPolicy.Sample();s.page=p.getInt("page");s.text=p.getString("text");s.total=p.getInt("total");s.abnormal=p.getInt("abnormal");s.mapErrors=p.getInt("mapErrors");s.nonGenerated=p.getInt("nonGenerated");s.width=p.getDouble("width");s.height=p.getDouble("height");
    map(p,"fonts",s.fonts);map(p,"nativeFonts",s.nativeFonts);map(p,"cjkFonts",s.cjkFonts);
    for(Object f:list(p.getJSONArray("cidFonts")))s.cidFonts.add((String)f);for(Object f:list(p.getJSONArray("latinFonts")))s.latinFonts.add((String)f);samples.add(s);
   }
   assertEquals(test.getString("name"),test.getString("expected"),RapidDocPdfPolicy.classify(samples).mode());
  }
 }
 @Test public void samplingMatchesOriginalIncludingBankersRounding()throws Exception{for(Object value:list(fixtures().getJSONArray("sampling"))){JSONObject row=(JSONObject)value;assertEquals(list(row.getJSONArray("indices")),new ArrayList<>(RapidDocPdfPolicy.samplePages(row.getInt("count"))));}}
 @Test public void fontNamesAndCharsetGuards(){assertEquals("Font",RapidDocPdfPolicy.fontName("/ABCDEF+Font"));assertTrue(RapidDocPdfPolicy.latinCharset("/a/b/c/d/e/f/g/h/i/j"));assertFalse(RapidDocPdfPolicy.latinCharset("/a/b/c/d/e/f/g/h/i/j/uni4E00"));}
}
