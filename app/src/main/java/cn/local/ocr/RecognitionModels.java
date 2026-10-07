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
    JSONObject verify() throws Exception {
        return ModelHub.verifyRequired(ModelHub.root(context),contract());
    }

    String config() throws Exception {
        JSONObject config;
        try(InputStream in=context.getAssets().open("ocr/config.json")) {
            config=new JSONObject(new String(FilesUtil.bytes(in,65536),StandardCharsets.UTF_8));
        }
        config.getJSONObject("models").getJSONObject("layout").put("root",FilesUtil.child(ModelHub.root(context),ModelHub.REPOS[0]+"/files").getPath());
        config.getJSONObject("models").getJSONObject("recognition").put("root",FilesUtil.child(ModelHub.root(context),ModelHub.REPOS[1]+"/files").getPath());
        return config.toString();
    }
}
