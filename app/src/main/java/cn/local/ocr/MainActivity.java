package cn.local.ocr;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    WebView web;RecognitionController recognition;DocumentStore store;
    final RecognitionController.Listener recognitionListener=value->event("recognition",value);DocumentImporter importer;CameraController camera;TextureView texture;
    ExecutorService modelRequests=Executors.newFixedThreadPool(2);
    ExecutorService worker=Executors.newSingleThreadExecutor();volatile String pickerRequest,captureRequest,exportRequest;File pendingExport;
    final String origin="https://appassets.androidplatform.net";
    @Override public void onCreate(Bundle b){super.onCreate(b);recognition=RecognitionController.get(this);store=recognition.store();recognition.listen(recognitionListener);importer=new DocumentImporter(this,store);FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.rgb(239,244,237));texture=new TextureView(this);texture.setVisibility(View.GONE);root.addView(texture,new FrameLayout.LayoutParams(1,1));web=new WebView(this);web.setBackgroundColor(Color.TRANSPARENT);root.addView(web,new FrameLayout.LayoutParams(-1,-1));setContentView(root);
        camera=new CameraController(this,texture,new CameraController.Listener(){public void ready(){event("camera",FilesUtil.obj("ready",true));}public void photo(byte[] bytes){String request=captureRequest;captureRequest=null;if(request==null)return;worker.execute(()->{try{String id=store.create();File f=new File(store.dir(id),"photo.jpg");java.nio.file.Files.write(f.toPath(),bytes);JSONObject doc=importer.imported(id,"拍摄照片.jpg",f);reply(request,recognition.autoStart(doc),null);}catch(Exception e){reply(request,null,e);}});}public void error(String error){String id=captureRequest;captureRequest=null;if(id!=null)reply(id,null,new IOException(error));event("camera",FilesUtil.obj("error",error));}});
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setMediaPlaybackRequiresUserGesture(true);s.setDefaultTextEncodingName("UTF-8");WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG&&BuildConfig.TEST_FEATURES);web.addJavascriptInterface(new Bridge(),"AndroidHost");
        web.setWebViewClient(new WebViewClient(){public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){try{Uri u=request.getUrl();if(!origin.equals(u.getScheme()+"://"+u.getHost()))return blocked();String path=u.getPath();InputStream in;String mime;
            if(path.startsWith("/web/")){String relative=path.substring(1);if(relative.contains(".."))return blocked();in=getAssets().open(relative);mime=mime(path);}else if(path.startsWith("/documents/")){String rel=path.substring("/documents/".length());File f=FilesUtil.child(store.root,rel);if(!f.isFile())return blocked();mime=mime(f.getName());if(!(mime.startsWith("image/")||mime.startsWith("font/")))return blocked();in=new FileInputStream(f);}else return blocked();Map<String,String> headers=new HashMap<>();headers.put("Access-Control-Allow-Origin",origin);headers.put("Cache-Control","no-cache");headers.put("X-Content-Type-Options","nosniff");return new WebResourceResponse(mime,"UTF-8",200,"OK",headers,in);
        }catch(Exception e){return blocked();}}});
        web.loadUrl(origin+"/web/index.html");
    }
    static String mime(String p){String x=p.substring(p.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);switch(x){case "html":return "text/html";case "js":return "application/javascript";case "css":return "text/css";case "json":return "application/json";case "png":return "image/png";case "jpg":case "jpeg":return "image/jpeg";case "webp":return "image/webp";case "gif":return "image/gif";case "bmp":return "image/bmp";case "woff2":return "font/woff2";default:return "application/octet-stream";}}
    static WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",404,"Not found",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
    void reply(String id,Object value,Exception error){if(id==null)return;JSONObject res=FilesUtil.obj("id",id,"value",value==null?JSONObject.NULL:value,"error",error==null?JSONObject.NULL:String.valueOf(error.getMessage()));runOnUiThread(()->{if(web!=null)web.evaluateJavascript("window.nativeReply("+res+")",null);});}
    void event(String type,JSONObject value){runOnUiThread(()->{if(web!=null)web.evaluateJavascript("window.nativeEvent&&window.nativeEvent("+JSONObject.quote(type)+","+value+")",null);});}
    final class Bridge {
        @JavascriptInterface public void request(String raw){
            try{
                JSONObject quick=new JSONObject(raw);String method=quick.getString("method"),qid=quick.getString("id");
                if(method.equals("models")){reply(qid,FilesUtil.obj("tasks",ModelHub.summary(MainActivity.this).getJSONArray("tasks"),"repos",ModelHub.repos(MainActivity.this),"running",DownloadService.active,"activity",DownloadService.activity,"freeBytes",ModelHub.root(MainActivity.this).getUsableSpace(),"readiness",recognition.readiness()),null);return;}
                if(method.equals("recognitionStatus")){reply(qid,recognition.status(quick.getJSONObject("args").getString("id")),null);return;}
                if(method.equals("cancelRecognition")){JSONObject a=quick.getJSONObject("args");reply(qid,recognition.cancel(a.getString("id"),a.getString("jobId")),null);return;}
                if(method.equals("recognize")){JSONObject a=quick.getJSONObject("args");reply(qid,recognition.start(a.getString("id"),a.optBoolean("again")),null);return;}
                if(method.equals("setOcrThreads")){reply(qid,recognition.configureThreads(quick.getJSONObject("args").getInt("threads")),null);return;}
                if(method.equals("activateModels")){reply(qid,recognition.activate(),null);return;}
                if(method.equals("pauseDownload")){DownloadService.requestPause();reply(qid,FilesUtil.obj("paused",true),null);return;}
                if(method.equals("catalog")){String repo=ModelHub.normalizeRepo(quick.getJSONObject("args").getString("repo"));modelRequests.execute(()->{try{reply(qid,ModelHub.catalog(MainActivity.this,repo),null);}catch(Exception e){reply(qid,null,e);}});return;}
            }catch(Exception e){try{reply(new JSONObject(raw).getString("id"),null,e);}catch(Exception ignored){}return;}
            worker.execute(()->{String id=null;try{JSONObject r=new JSONObject(raw);id=r.getString("id");String method=r.getString("method");JSONObject a=r.optJSONObject("args");if(a==null)a=new JSONObject();Object out=null;String rid=id;
            switch(method){
                case "bootstrap":out=FilesUtil.obj("testBuild",BuildConfig.TEST_FEATURES,"settings",settings(),"history",store.list(),"version",BuildConfig.VERSION_NAME);break;
                case "history":out=store.list();break;
                case "sample":out=store.sample(a.getString("sample"));break;
                case "open":out=store.load(a.getString("id"));break;
                case "save":out=store.update(a);break;
                case "delete":recognition.delete(a.getString("id"));out=store.list();break;
                case "import":pickerRequest=id;runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,12);});return;
                case "export":pendingExport=store.export(a.getString("id"),a.optString("format","zip"));exportRequest=id;boolean share=a.optBoolean("share");runOnUiThread(()->exportUi(share));return;
                case "settings":saveSettings(a);out=settings();break;
                case "models":out=ModelHub.summary(MainActivity.this);break;
                case "catalog":out=ModelHub.catalog(a.getString("repo"));break;
                case "addRepo":out=ModelHub.addRepo(MainActivity.this,a.getString("repo"));break;
                case "forgetRepo":out=ModelHub.forgetRepo(MainActivity.this,a.getString("repo"));break;
                case "download":String repo=ModelHub.normalizeRepo(a.getString("repo"));if(DownloadService.active)throw new IOException("已有下载任务，请先暂停");String paths=a.getJSONArray("paths").toString();if(paths.length()>200000)throw new IOException("所选文件过多，请分批下载");ModelHub.select(ModelHub.cachedCatalog(MainActivity.this,repo),a.getJSONArray("paths"));runOnUiThread(()->{try{recognition.reserveDownload(repo);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},44);startForegroundService(new Intent(MainActivity.this,DownloadService.class).putExtra("repo",repo).putExtra("paths",paths));reply(rid,FilesUtil.obj("started",true),null);}catch(Exception e){DownloadService.reserved=false;reply(rid,null,e);}});return;
                case "pauseDownload":if(DownloadService.active)startService(new Intent(MainActivity.this,DownloadService.class).setAction("pause"));out=FilesUtil.obj("paused",true);break;
                case "removeModel":out=recognition.removeModel(a.getString("repo"));break;
                case "cameraBounds":JSONObject bounds=a;runOnUiThread(()->{float density=getResources().getDisplayMetrics().density;camera.bounds((int)(bounds.optDouble("x")*density),(int)(bounds.optDouble("y")*density),(int)(bounds.optDouble("w")*density),(int)(bounds.optDouble("h")*density));});out=true;break;
                case "cameraPermission":runOnUiThread(()->{if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},42);else camera.open();});out=true;break;
                case "capture":captureRequest=id;runOnUiThread(()->{camera.capture();new Handler(getMainLooper()).postDelayed(()->{if(rid.equals(captureRequest)){captureRequest=null;reply(rid,null,new IOException("拍摄超时，请重试"));}},12000);});return;
                default:throw new IOException("未知操作："+method);
            }
            reply(id,out,null);
        }catch(Exception e){reply(id,null,e);}});}
    }
    JSONObject settings(){android.content.SharedPreferences p=getSharedPreferences("settings",0);return FilesUtil.obj("wifiOnly",p.getBoolean("wifiOnly",true),"debug",BuildConfig.TEST_FEATURES&&p.getBoolean("debug",false),"chunk",p.getInt("chunk",8),"imageResolution",p.getString("imageResolution","balanced"));}
    void saveSettings(JSONObject a){
        String imagePolicy=a.has("imageResolution")?ImageResolution.validate(a.optString("imageResolution")):null;
        android.content.SharedPreferences.Editor p=getSharedPreferences("settings",0).edit();if(a.has("wifiOnly"))p.putBoolean("wifiOnly",a.optBoolean("wifiOnly"));if(a.has("debug")&&BuildConfig.TEST_FEATURES)p.putBoolean("debug",a.optBoolean("debug"));if(a.has("chunk"))p.putInt("chunk",Math.max(1,Math.min(128,a.optInt("chunk",8))));if(imagePolicy!=null)p.putString("imageResolution",imagePolicy);p.apply();
    }
    void exportUi(boolean share){try{if(share){Uri uri=Uri.parse("content://"+getPackageName()+".files/"+pendingExport.getName());Intent i=new Intent(Intent.ACTION_SEND);i.setType(pendingExport.getName().endsWith("zip")?"application/zip":"text/plain");i.putExtra(Intent.EXTRA_STREAM,uri);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);i.setClipData(ClipData.newRawUri("文档",uri));startActivity(Intent.createChooser(i,"分享文档"));reply(exportRequest,true,null);exportRequest=null;}else{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(pendingExport.getName().endsWith("zip")?"application/zip":"text/plain");i.putExtra(Intent.EXTRA_TITLE,pendingExport.getName());startActivityForResult(i,13);}}catch(Exception e){reply(exportRequest,null,e);exportRequest=null;}}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(req==12){String id=pickerRequest;pickerRequest=null;if(result!=RESULT_OK||data==null){reply(id,null,new IOException("已取消导入"));return;}Uri uri=data.getData();event("importing",new JSONObject());worker.execute(()->{try{reply(id,recognition.autoStart(importer.importUri(uri)),null);}catch(Exception e){reply(id,null,e);}});}if(req==13){String id=exportRequest;exportRequest=null;if(result!=RESULT_OK||data==null){reply(id,null,new IOException("已取消导出"));return;}Uri uri=data.getData();File export=pendingExport;worker.execute(()->{try(InputStream in=new FileInputStream(export);OutputStream out=getContentResolver().openOutputStream(uri)){FilesUtil.copy(in,out,Long.MAX_VALUE);reply(id,true,null);}catch(Exception e){reply(id,null,e);}});}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==42){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)camera.open();else event("camera",FilesUtil.obj("error","相机权限未允许，仍可导入文件"));}}
    @Override public void onBackPressed(){web.evaluateJavascript("window.appBack&&window.appBack()",r->{if("false".equals(r))super.onBackPressed();});}
    @Override protected void onPause(){super.onPause();camera.suspend();web.evaluateJavascript("window.appPause&&window.appPause()",null);}
    @Override protected void onResume(){super.onResume();if(camera!=null)camera.resume();}
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(recognition!=null&&(level==ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW||level==ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL||level>=ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))recognition.onMemoryPressure();}
    @Override protected void onDestroy(){if(isFinishing())recognition.onMemoryPressure();recognition.unlisten(recognitionListener);camera.destroy();web.removeJavascriptInterface("AndroidHost");web.destroy();web=null;worker.shutdown();modelRequests.shutdownNow();super.onDestroy();}
}
