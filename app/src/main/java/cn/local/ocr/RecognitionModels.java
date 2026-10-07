package cn.local.ocr;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** 只绑定生产配置的九个工件，独立于下载页所选文件的完成比例。 */
final class RecognitionModels {
    final Context context;
    RecognitionModels(Context c) { context=c; }
    JSONArray contract() throws Exception {
        try(InputStream in=context.getAssets().open("ocr/models.json")) {
            return new JSONArray(new String(FilesUtil.bytes(in,65536),StandardCharsets.UTF_8));
        }
    }
    JSONObject downloadedReadiness() throws Exception {
        return ModelHub.downloadedReadiness(ModelHub.root(context),contract(),ModelHub.state(context));
    }

    static int effectiveCpuThreads(int choice,int processors) {
        if(choice!=0&&choice!=1&&choice!=2&&choice!=4)throw new IllegalArgumentException("推理线程请选择自动、1、2 或 4");
        int available=processors>=4?4:processors>=2?2:1;
        return choice==0?available:Math.min(choice,available);
    }
    int threadChoice() {return context.getSharedPreferences("settings",0).getInt("ocrThreads",0);}
    int cpuThreads() {return effectiveCpuThreads(threadChoice(),Runtime.getRuntime().availableProcessors());}
    String config() throws Exception {
        JSONObject config;
        try(InputStream in=context.getAssets().open("ocr/config.json")) {
            config=new JSONObject(new String(FilesUtil.bytes(in,65536),StandardCharsets.UTF_8));
        }
        config.getJSONObject("platform").put("threads",cpuThreads());
        config.getJSONObject("models").getJSONObject("layout").put("root",FilesUtil.child(ModelHub.root(context),ModelHub.REPOS[0]+"/files").getPath());
        config.getJSONObject("models").getJSONObject("recognition").put("root",FilesUtil.child(ModelHub.root(context),ModelHub.REPOS[1]+"/files").getPath());
        return config.toString();
    }
}
