package cn.local.ocr;

import android.graphics.*;
import org.json.*;
import org.opencv.android.*;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;

/** 照片、裁剪底图与确认后的输入分别保留。所有调用在宿主工作线程执行。 */
final class DocumentCrop {
    private static synchronized void initialize()throws IOException {
        if(!OpenCVLoader.initLocal())throw new IOException("文档裁剪引擎加载失败，请重试");
    }
    static boolean pending(JSONObject doc) {
        JSONObject crop=doc.optJSONObject("documentCrop");return crop!=null&&!crop.optString("state").equals("confirmed");
    }
    static JSONObject begin(DocumentStore store,JSONObject doc)throws Exception {
        String id=doc.getString("id");JSONObject crop=doc.optJSONObject("documentCrop");
        if(crop==null) {
            String source="capture-source.png";
            Files.copy(FilesUtil.child(store.dir(id),doc.getString("input")).toPath(),FilesUtil.child(store.dir(id),source).toPath(),StandardCopyOption.REPLACE_EXISTING);
            JSONObject prepared=doc.getJSONObject("imagePreparation");
            crop=FilesUtil.obj("source",source,"original",doc.optString("rawInput",doc.getString("input")),
                "width",prepared.getInt("width"),"height",prepared.getInt("height"),"points",json(CropGeometry.full()));
            doc.put("documentCrop",crop);
        }
        crop.put("state","pending").put("sourceUrl",store.url(id,crop.getString("source")));
        crop.remove("preview");
        doc.put("recognition",FilesUtil.obj("state","waiting","message","请先确认文档裁剪，再开始识别"));store.save(doc);
        return doc;
    }
    static JSONObject detect(DocumentStore store,JSONObject doc)throws Exception {
        JSONObject crop=doc.getJSONObject("documentCrop");Bitmap bitmap=null;Mat rgba=null,gray=null;
        try {
            initialize();rgba=new Mat();gray=new Mat();BitmapFactory.Options options=new BitmapFactory.Options();
            int edge=Math.max(crop.getInt("width"),crop.getInt("height"));options.inSampleSize=1;
            while(edge/options.inSampleSize>960)options.inSampleSize*=2;
            options.inScaled=false;bitmap=BitmapFactory.decodeFile(FilesUtil.child(store.dir(doc.getString("id")),crop.getString("source")).getPath(),options);
            if(bitmap==null)throw new IOException("裁剪底图读取失败");
            Utils.bitmapToMat(bitmap,rgba);Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY);
            double[][] points=gray.cols()>1&&gray.rows()>1?DocumentBoundaryDetector.detect(gray):null;
            crop.put("points",json(points==null?CropGeometry.full():points)).put("detected",points!=null);
            crop.put("message",points==null?"未检测到清晰文档边界，请拖动四角手动选框":"已检测文档边界，请核对四角后预览");
        } catch(Exception|LinkageError error) {
            crop.put("points",json(CropGeometry.full())).put("detected",false).put("message","自动检测未完成，请手动调整裁剪框");
        } finally {if(bitmap!=null)bitmap.recycle();if(rgba!=null)rgba.release();if(gray!=null)gray.release();}
        store.save(doc);return doc;
    }
    static JSONObject preview(DocumentStore store,JSONObject doc,JSONArray coordinates,String policy)throws Exception {
        initialize();JSONObject crop=doc.getJSONObject("documentCrop");double[][] points=points(coordinates);CropGeometry.validate(points);
        Bitmap source=null,result=null;Mat input=new Mat(),output=null;
        try {
            ImageResolution.Plan plan=ImageResolution.plan(crop.getInt("width"),crop.getInt("height"),policy);
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=plan.sampleSize;options.inScaled=false;
            source=BitmapFactory.decodeFile(FilesUtil.child(store.dir(doc.getString("id")),crop.getString("source")).getPath(),options);
            if(source==null)throw new IOException("裁剪底图读取失败");
            if(source.getWidth()!=plan.width||source.getHeight()!=plan.height){Bitmap scaled=Bitmap.createScaledBitmap(source,plan.width,plan.height,true);if(scaled!=source){source.recycle();source=scaled;}}
            Utils.bitmapToMat(source,input);source.recycle();source=null;
            output=DocumentBoundaryDetector.warp(input,points);input.release();
            result=Bitmap.createBitmap(output.cols(),output.rows(),Bitmap.Config.ARGB_8888);Utils.matToBitmap(output,result);
            String token=UUID.randomUUID().toString(),path="crop-preview-"+token+".png";Bitmap image=result;
            ImageInput.atomicSave(FilesUtil.child(store.dir(doc.getString("id")),path),out->{if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("裁剪预览保存失败");});
            JSONObject preview=FilesUtil.obj("token",token,"path",path,"src",store.url(doc.getString("id"),path),"width",result.getWidth(),"height",result.getHeight(),"policy",policy,"points",json(points));
            JSONObject old=crop.optJSONObject("preview");crop.put("points",json(points)).put("preview",preview);store.save(doc);
            if(old!=null)Files.deleteIfExists(FilesUtil.child(store.dir(doc.getString("id")),old.getString("path")).toPath());
            return preview;
        } catch(OutOfMemoryError error){throw new IOException("裁剪内存不足，请返回重拍或降低拍摄分辨率",error);}
        finally {if(source!=null)source.recycle();if(result!=null)result.recycle();input.release();if(output!=null)output.release();}
    }
    static JSONObject confirm(DocumentStore store,JSONObject doc,String token)throws Exception {
        JSONObject crop=doc.getJSONObject("documentCrop"),preview=crop.optJSONObject("preview");
        if(!pending(doc)||preview==null||!token.equals(preview.optString("token")))throw new IOException("裁剪预览已失效，请重新预览后确认");
        // 同一 PNG 直接成为识别输入：不在确认时再次裁剪或编码。
        String path=preview.getString("path");if(!FilesUtil.child(store.dir(doc.getString("id")),path).isFile())throw new IOException("裁剪预览文件缺失，请重新预览");
        crop.put("state","confirmed");doc.put("rawInput",path).put("input",path).put("original",store.url(doc.getString("id"),path));
        doc.put("imagePreparation",FilesUtil.obj("version",ImageResolution.VERSION,"policy",preview.getString("policy"),"width",preview.getInt("width"),"height",preview.getInt("height"),"cropConfirmed",true));
        doc.getJSONObject("assets").put(path,FilesUtil.obj("src",doc.getString("original"),"width",preview.getInt("width"),"height",preview.getInt("height")));
        JSONArray pages=doc.getJSONArray("pages");if(pages.length()==0)pages.put(FilesUtil.obj("number",1));
        pages.getJSONObject(0).put("source",doc.getString("original")).put("width",preview.getInt("width")).put("height",preview.getInt("height")).put("route","拍照 → 文档裁剪 → 本地识别");
        doc.put("recognition",FilesUtil.obj("state","waiting","message","裁剪已确认，可开始识别"));store.save(doc);return doc;
    }
    static JSONArray json(double[][] p)throws JSONException {JSONArray result=new JSONArray();for(double[] point:p)result.put(new JSONArray().put(point[0]).put(point[1]));return result;}
    static double[][] points(JSONArray coordinates)throws JSONException {
        if(coordinates.length()!=4)throw new IllegalArgumentException("需要四个文档边角");double[][] result=new double[4][2];
        for(int i=0;i<4;i++){JSONArray point=coordinates.getJSONArray(i);if(point.length()!=2)throw new IllegalArgumentException("裁剪边角格式无效");result[i][0]=point.getDouble(0);result[i][1]=point.getDouble(1);}return result;
    }
}
