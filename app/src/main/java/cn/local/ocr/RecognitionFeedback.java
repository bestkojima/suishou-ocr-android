package cn.local.ocr;

import android.content.Context;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.*;

/** 识别样本反馈：冻结输入、原始 JSON 和描述，渠道重试不重新读取当前文档。 */
final class RecognitionFeedback {
    private final Context context;
    private final DocumentStore store;
    private final File root;
    static final Set<String> CATEGORIES=new HashSet<>(Arrays.asList("text","table-formula","layout","repetition","other"));
    RecognitionFeedback(Context context,DocumentStore store){this.context=context;this.store=store;root=new File(context.getFilesDir(),"feedback");root.mkdirs();}
    private JSONObject document(String id,String jobId)throws Exception {
        JSONObject doc=store.load(id),state=doc.optJSONObject("recognition");
        if(!"real-ocr".equals(doc.optString("mode"))||state==null||!jobId.equals(state.optString("jobId"))||RegionRetry.active(state))
            throw new IOException("识别结果已变化或尚未完成，请重新打开结果后反馈");
        if(!new File(store.dir(id),"document.json").isFile()||doc.optString("input").isEmpty()||!FilesUtil.child(store.dir(id),doc.getString("input")).isFile())
            throw new IOException("本次识别图片或原始 JSON 已缺失，无法生成完整反馈");
        return doc;
    }
    JSONObject options(String id,String jobId)throws Exception {
        JSONObject doc=document(id,jobId);
        return FilesUtil.obj("image",store.url(id,doc.getString("input")),"json","document.json",
            "configured",!BuildConfig.FEEDBACK_ENDPOINT.isEmpty()||!BuildConfig.FEISHU_FEEDBACK_ENDPOINT.isEmpty(),
            "backupConfigured",!BuildConfig.FEISHU_FEEDBACK_ENDPOINT.isEmpty());
    }
    synchronized JSONObject prepare(String id,String jobId,String category,String description)throws Exception {
        description=description.trim();
        if(description.isEmpty()||description.length()>4000)throw new IOException("请填写 1～4000 字的问题描述");
        if(!CATEGORIES.contains(category))throw new IOException("未知反馈分类");
        String feedbackId=UUID.randomUUID().toString();
        File archive=FilesUtil.child(root,feedbackId+".zip"),temporary=new File(archive+".tmp");
        JSONObject manifest;
        synchronized(store) {
            JSONObject doc=document(id,jobId);
            File input=FilesUtil.child(store.dir(id),doc.getString("input")),json=new File(store.dir(id),"document.json");
            String extension=input.getName().replaceFirst("^.*\\.","").toLowerCase(Locale.ROOT);
            if(!Arrays.asList("png","jpg","jpeg","webp","bmp","gif").contains(extension))throw new IOException("识别图片格式不支持反馈");
            if(input.length()+json.length()>FilesUtil.IMPORT_LIMIT)throw new IOException("反馈文件超过 256 MB");
            manifest=FilesUtil.obj("schemaVersion",1,"feedbackId",feedbackId,"docId",id,"jobId",jobId,
                "createdAt",System.currentTimeMillis(),"category",category,"description",description,
                "appVersion",BuildConfig.VERSION_NAME,"versionCode",BuildConfig.VERSION_CODE,
                "model",doc.getJSONObject("recognition").optString("modelChoice","unknown"),
                "android",Build.VERSION.RELEASE,"device",Build.MODEL,
                "image","input."+extension,"imageSha256",FilesUtil.sha(input),"document","document.json","documentSha256",FilesUtil.sha(json));
            try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(temporary))) {
                file(zip,"input."+extension,input);file(zip,"document.json",json);
                zip.putNextEntry(new ZipEntry("feedback.json"));zip.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
                zip.putNextEntry(new ZipEntry("description.txt"));zip.write(description.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            } catch(Exception e){temporary.delete();throw e;}
        }
        if(!temporary.renameTo(archive))throw new IOException("反馈包保存失败");
        manifest.put("archiveSha256",FilesUtil.sha(archive)).put("submitted",false);
        FilesUtil.write(FilesUtil.child(root,feedbackId+".json"),manifest.toString());
        return FilesUtil.obj("feedbackId",feedbackId,"submitted",false,"bytes",archive.length(),"message","反馈包已保存在本机");
    }
    private static void file(ZipOutputStream zip,String name,File file)throws Exception {
        zip.putNextEntry(new ZipEntry(name));try(InputStream input=new FileInputStream(file)){FilesUtil.copy(input,zip,FilesUtil.IMPORT_LIMIT);}zip.closeEntry();
    }
    private File archive(String id)throws Exception {
        if(!id.matches("[a-f0-9-]{36}"))throw new IOException("无效反馈编号");
        File archive=FilesUtil.child(root,id+".zip");
        JSONObject record=new JSONObject(FilesUtil.read(FilesUtil.child(root,id+".json")));
        if(!archive.isFile()||!FilesUtil.sha(archive).equals(record.getString("archiveSha256")))throw new IOException("反馈包已缺失或损坏，请重新生成");
        return archive;
    }
    synchronized JSONObject submit(String id)throws Exception {
        File archive=archive(id),recordFile=FilesUtil.child(root,id+".json");
        JSONObject record=new JSONObject(FilesUtil.read(recordFile));
        if(record.optBoolean("submitted"))return record.getJSONObject("receipt");
        JSONObject receipt=FeedbackDelivery.send(BuildConfig.FEEDBACK_ENDPOINT,BuildConfig.FEISHU_FEEDBACK_ENDPOINT,id,archive,FeedbackDelivery::upload);
        record.put("submitted",true).put("receipt",receipt);FilesUtil.write(recordFile,record.toString());return receipt;
    }
    File sharingFile(String id)throws Exception {
        File archive=archive(id),exports=new File(context.getCacheDir(),"exports");exports.mkdirs();
        File shared=new File(exports,"feedback-"+id+".zip");Files.copy(archive.toPath(),shared.toPath(),StandardCopyOption.REPLACE_EXISTING);return shared;
    }
}
