package cn.local.ocr;

import org.json.JSONObject;
import java.io.*;
import java.net.*;

/** 同一反馈编号、同一份文件在主/备用服务重试；收到明确回执才成功。 */
final class FeedbackDelivery {
    interface Transport { JSONObject upload(String endpoint,String id,File archive)throws Exception; }
    static JSONObject send(String primary,String backup,String id,File archive,Transport transport)throws Exception {
        if(primary.isEmpty()&&backup.isEmpty())throw new IOException("反馈接收地址尚未配置，请保存反馈包或分享到飞书");
        for(String endpoint:new String[]{primary,backup}) {
            if(endpoint.isEmpty())continue;
            try {
                JSONObject result=transport.upload(endpoint,id,archive);
                if(!id.equals(result.optString("feedbackId"))||!"received".equals(result.optString("status")))throw new IOException("接收服务未返回有效回执");
                return FilesUtil.obj("feedbackId",id,"submitted",true,"channel",endpoint.equals(primary)?"primary":"feishu","message","反馈已收到");
            } catch(Exception e) {
                // 不把服务地址、鉴权参数或服务端返回的原文暴露到页面。
                if(endpoint.equals(backup)||backup.isEmpty()||backup.equals(primary))break;
            }
        }
        throw new IOException("反馈暂未提交成功，反馈包已保存在本机，可重试或分享到飞书");
    }
    static JSONObject upload(String endpoint,String id,File archive)throws Exception {
        URI uri=new URI(endpoint);
        if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)
            throw new IOException("反馈接收地址必须为 HTTPS");
        HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(20000);
        connection.setRequestMethod("POST");connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/zip");
        connection.setRequestProperty("Idempotency-Key",id);connection.setRequestProperty("X-Feedback-Id",id);
        connection.setFixedLengthStreamingMode(archive.length());
        try {
            try(InputStream input=new FileInputStream(archive);OutputStream output=connection.getOutputStream()){FilesUtil.copy(input,output,FilesUtil.IMPORT_LIMIT);}
            int code=connection.getResponseCode();if(code<200||code>=300)throw new IOException("接收服务暂不可用");
            try(InputStream response=connection.getInputStream()){
                return new JSONObject(new String(FilesUtil.bytes(response,65536),java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally {connection.disconnect();}
    }
}
