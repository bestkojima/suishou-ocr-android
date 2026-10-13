package cn.local.ocr;

import java.io.*;
import org.json.*;

/** 校对区域只绑定已保存裁图，候选结果不直接修改原文或用户草稿。 */
final class RegionRetry {
    static JSONObject block(JSONObject doc,String blockId)throws Exception {
        JSONArray blocks=doc.getJSONArray("blocks");
        for(int i=0;i<blocks.length();i++)if(blockId.equals(blocks.getJSONObject(i).getString("id")))return blocks.getJSONObject(i);
        throw new IOException("找不到所选区域，请重新打开文档");
    }
    static File input(DocumentStore store,JSONObject doc,String blockId)throws Exception {
        JSONObject block=block(doc,blockId);String type=block.optString("type");
        if(!type.equals("text")&&!type.equals("table")&&!type.equals("formula"))throw new IOException("此区域不支持重新识别");
        String path=block.optString("resource");
        if(path.isEmpty())throw new IOException("此区域没有保存识别裁图，无法单独重新识别");
        File file=FilesUtil.child(store.dir(doc.getString("id")),path);
        if(!file.isFile())throw new IOException("区域裁图已缺失，请重新导入含图片的完整结果");
        return file;
    }
    static JSONObject candidate(JSONObject ir)throws Exception {
        JSONArray pages=ir.getJSONArray("pages");
        if(pages.length()!=1||pages.getJSONObject(0).getJSONArray("blocks").length()!=1)throw new IOException("区域识别返回的内容不完整");
        JSONObject block=pages.getJSONObject(0).getJSONArray("blocks").getJSONObject(0);
        JSONObject content=block.optJSONObject("content"),provenance=block.optJSONObject("provenance");
        String text=content==null?"":content.optString("text");
        String format=content==null?"markdown":content.optString("format","markdown");
        if(text.isEmpty()&&provenance!=null) {text=provenance.optString("raw_output","");format="markdown";}
        if(text.isEmpty())throw new IOException("此模型未返回可用文字，原内容已保留");
        JSONObject assessment=provenance==null?null:provenance.optJSONObject("assessment");
        return FilesUtil.obj("markdown",format.equals("latex")?"$$\n"+text+"\n$$":text,
            "sourceStatus",block.optString("status","partial"),"stopReason",assessment==null?"":assessment.optString("stop_reason"),
            "error",block.optString("error",""));
    }
    static boolean active(JSONObject state) {
        if(state==null)return false;
        return java.util.Arrays.asList("preparing","recognizing","saving","cancelling").contains(state.optString("state"));
    }
}
