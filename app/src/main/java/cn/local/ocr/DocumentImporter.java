package cn.local.ocr;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import org.json.*;
import java.io.*;
import java.util.*;

public final class DocumentImporter {
    final Context context;final DocumentStore store;
    DocumentImporter(Context c,DocumentStore s){context=c;store=s;PDFBoxResourceLoader.init(c);}
    JSONObject importUri(Uri uri)throws Exception{String name="document";try(Cursor c=context.getContentResolver().query(uri,null,null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));}String id=store.create();File dir=store.dir(id);String safe=name.replaceAll("[^\\p{L}\\p{N}._-]","_");File file=new File(dir,"input-"+safe);try(InputStream in=context.getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(file)){FilesUtil.copy(in,out,FilesUtil.IMPORT_LIMIT);}try{return imported(id,name,file);}catch(Exception e){FilesUtil.remove(dir);throw e;}}
    JSONObject imported(String id,String name,File file)throws Exception{
        String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);File dir=store.dir(id);
        if(ext.equals("zip")){File unpack=new File(dir,"unpacked");unpack.mkdirs();FilesUtil.unzip(file,unpack);File json=findJson(unpack);if(json==null)throw new IOException("压缩包没有 document.json");copyTree(json.getParentFile(),dir);FilesUtil.remove(unpack);File snapshot=new File(dir,"app-state.json");if(snapshot.isFile()){JSONObject restored=store.restoreExport(id,new JSONObject(FilesUtil.read(snapshot)));store.save(restored);return restored;}file=new File(dir,"document.json");ext="json";}
        if(ext.equals("json")){JSONObject ir=new JSONObject(FilesUtil.read(file));if(!file.getName().equals("document.json"))java.nio.file.Files.copy(file.toPath(),new File(dir,"document.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);JSONObject doc=store.normalize(id,name,ir);for(File f:dir.listFiles())if(f.getName().startsWith("source."))doc.put("original",store.url(id,f.getName()));store.save(doc);return doc;}
        JSONObject doc=FilesUtil.obj("id",id,"title",name,"mode","native-parse","status","ready","progress",0,"blocks",new JSONArray(),"assets",new JSONObject(),"pages",new JSONArray());
        if(ext.equals("pdf")){parsePdf(file,doc);}else if(Arrays.asList("doc","docx","xls","xlsx","ppt","pptx").contains(ext)){
            OfficeParser.parse(file,ext,new OfficeParser.Sink(){
                int page=1;
                public void page(int number,String title,String kind)throws Exception{page=number;doc.getJSONArray("pages").put(FilesUtil.obj("number",number,"title",title,"kind",kind,"route","原生内容解析"));}
                public void text(String type,String text)throws Exception{add(doc,type,text,page);JSONObject block=doc.getJSONArray("blocks").getJSONObject(doc.getJSONArray("blocks").length()-1);block.put("origin","native");if(type.equals("notice"))block.put("sourceStatus","notice");}
                public void image(String name,byte[] bytes)throws Exception{int before=doc.getJSONArray("blocks").length();imageBytes(doc,bytes,page);if(doc.getJSONArray("blocks").length()>before){JSONObject block=doc.getJSONArray("blocks").getJSONObject(before);if(block.getString("type").equals("image")){block.put("sourceStatus","pending-ocr");block.put("origin","embedded-image");if(!doc.has("ocrCandidates"))doc.put("ocrCandidates",new JSONArray());doc.getJSONArray("ocrCandidates").put(FilesUtil.obj("blockId",block.getString("id"),"page",page,"resource",block.getString("resource"),"state","pending"));}}}
            });
            int candidates=doc.has("ocrCandidates")?doc.getJSONArray("ocrCandidates").length():0;doc.put("pendingOcr",candidates);
            doc.put("notice","已直接提取文档内容"+(candidates>0?"；"+candidates+" 张图片已保留，图片文字待 OCR":"")+"。复杂版式、图表和 SmartArt 不保证还原。");
        }
        else {
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
            if(bounds.outWidth<=0)throw new IOException("无法读取此文件。支持图片、PDF、Word、Excel、PPT、DocumentIR JSON/ZIP。");
            // 原始文件已在私有目录保存；方向转换失败也保留待识别输入。
            doc.put("mode","pending-ocr");doc.put("rawInput",file.getName());doc.put("input","source.png");
            doc.put("recognition",FilesUtil.obj("state","waiting","message","输入已保存，可开始识别"));
            store.save(doc);
            String path="source.png";File dest=FilesUtil.child(dir,path);
            try {
                int[] size=ImageInput.normalize(file,dest);
                doc.put("input",path);doc.put("original",store.url(id,path));resource(doc,path,size[0],size[1]);
                doc.getJSONArray("pages").put(FilesUtil.obj("number",1,"source",store.url(id,path),"width",size[0],"height",size[1],"route","单图本地识别"));
            } catch(Exception e) {
                doc.put("recognition",FilesUtil.obj("state","failed","message",e.getMessage()+"；原始输入已保留"));
                store.save(doc);return doc;
            }
            store.save(doc);return doc;
        }
        if(doc.getJSONArray("blocks").length()==0)throw new IOException("没有提取到内容；此文档需要真实 OCR 或更完整的格式解析器。");store.save(doc);return doc;
    }
    void add(JSONObject doc,String type,String text,int page)throws Exception{JSONArray bs=doc.getJSONArray("blocks");bs.put(FilesUtil.obj("id","p"+page+"-b"+(bs.length()+1),"page",page,"type",type,"markdown",text,"format",type.equals("table")?"html":"markdown","sourceStatus","ok","resource",type.equals("image")?text.substring(text.indexOf("(")+1,text.lastIndexOf(")")):""));}
    void resource(JSONObject doc,String path,int w,int h)throws Exception{doc.getJSONObject("assets").put(path,FilesUtil.obj("src",store.url(doc.getString("id"),path),"width",w,"height",h));}
    void imageBytes(JSONObject doc,byte[] bytes,int page)throws Exception{BitmapFactory.Options b=new BitmapFactory.Options();b.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,b);if(b.outWidth<=0){add(doc,"text","> 该嵌入图像格式暂不支持显示。",page);return;}String path="assets/image-"+UUID.randomUUID()+"."+(b.outMimeType!=null&&b.outMimeType.contains("jpeg")?"jpg":"png");File dest=FilesUtil.child(store.dir(doc.getString("id")),path);dest.getParentFile().mkdirs();java.nio.file.Files.write(dest.toPath(),bytes);resource(doc,path,b.outWidth,b.outHeight);add(doc,"image","![插图]("+path+")",page);}
    void parsePdf(File file,JSONObject doc)throws Exception{
        try(PDDocument pdf=PDDocument.load(file);ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);PdfRenderer renderer=new PdfRenderer(fd)){
            if(pdf.getNumberOfPages()>200)throw new IOException("本版最多导入 200 页 PDF");
            PdfClassification classification=new PdfClassification(pdf);
            doc.put("pdfClassification",classification.json());
            JSONArray candidates=new JSONArray(),layoutCandidates=new JSONArray();
            PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);
            for(int i=0;i<pdf.getNumberOfPages();i++){
                String text="";
                if(!classification.decision.ocr){stripper.setStartPage(i+1);stripper.setEndPage(i+1);text=stripper.getText(pdf).trim();}
                try(PdfRenderer.Page p=renderer.openPage(i)){
                    // RapidDoc uses 200 DPI. Android caps each bitmap at four million pixels.
                    double scale=Math.min(200.0/72,Math.sqrt(4_000_000.0/((double)p.getWidth()*p.getHeight())));
                    Bitmap bitmap=Bitmap.createBitmap(Math.max(1,(int)(p.getWidth()*scale)),Math.max(1,(int)(p.getHeight()*scale)),Bitmap.Config.ARGB_8888);
                    String path="assets/page-"+(i+1)+".png";
                    try{
                        bitmap.eraseColor(Color.WHITE);p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        File out=FilesUtil.child(store.dir(doc.getString("id")),path);out.getParentFile().mkdirs();
                        try(OutputStream os=new FileOutputStream(out)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,os))throw new IOException("页面图片保存失败");}
                        resource(doc,path,bitmap.getWidth(),bitmap.getHeight());
                    }finally{bitmap.recycle();}
                    if(i==0)doc.put("original",store.url(doc.getString("id"),path));
                    boolean needsLayout=!classification.decision.ocr&&RapidDocPdfPolicy.clean(text).isEmpty();
                    String route=classification.decision.ocr?"文档 OCR 路径 → 页面转图 → 待 OCR":needsLayout?"文档文本路径 → 本页待版面分析":"文档文本路径 → 原生文本提取";
                    doc.getJSONArray("pages").put(FilesUtil.obj("number",i+1,"source",store.url(doc.getString("id"),path),"route",route,"renderDpi",scale*72));
                    add(doc,"text","## 第 "+(i+1)+" 页",i+1);
                    if(classification.decision.ocr||needsLayout){
                        add(doc,"image","![页面]("+path+")",i+1);
                        JSONObject block=doc.getJSONArray("blocks").getJSONObject(doc.getJSONArray("blocks").length()-1);
                        block.put("sourceStatus",needsLayout?"pending-layout":"pending-ocr");
                        (needsLayout?layoutCandidates:candidates).put(FilesUtil.obj("blockId",block.getString("id"),"page",i+1,"resource",path,"state","pending","reason",needsLayout?"no_native_text_requires_layout":classification.decision.reason));
                    }else{add(doc,"text",text,i+1);pdfImages(pdf.getPage(i).getResources(),doc,i+1,new HashSet<>());}
                }
            }
            doc.put("ocrCandidates",candidates);doc.put("pendingOcr",candidates.length());doc.put("layoutCandidates",layoutCandidates);doc.put("pendingAnalysis",layoutCandidates.length());
            doc.put("notice","RapidDoc auto 规则："+classification.decision.description()+"。"+(classification.decision.ocr?"整份文档已转图片，真实 OCR 尚未接入。":"已提取原生文本；模型版面分析及区域 OCR 尚未接入，复杂阅读顺序与表格需核对。")+(layoutCandidates.length()>0?"无原生文字页已保留页面图片，待版面分析。":""));
        }
    }
    void pdfImages(PDResources resources,JSONObject doc,int page,Set<Object> visited)throws Exception{
        if(resources==null||!visited.add(resources.getCOSObject()))return;
        for(com.tom_roush.pdfbox.cos.COSName name:resources.getXObjectNames()){
            com.tom_roush.pdfbox.pdmodel.graphics.PDXObject x=resources.getXObject(name);
            if(x instanceof com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject){
                Bitmap image=((com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject)x).getImage();
                if(image!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,out);imageBytes(doc,out.toByteArray(),page);}
            }else if(x instanceof com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject)pdfImages(((com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject)x).getResources(),doc,page,visited);
        }
    }
    static File findJson(File root){File f=new File(root,"document.json");if(f.isFile())return f;File[] files=root.listFiles();if(files!=null)for(File d:files)if(d.isDirectory()){File found=findJson(d);if(found!=null)return found;}return null;}
    static void copyTree(File from,File to)throws Exception{for(File f:from.listFiles()){File dest=FilesUtil.child(to,f.getName());if(f.isDirectory()){dest.mkdirs();copyTree(f,dest);}else java.nio.file.Files.copy(f.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}}
}
