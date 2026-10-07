package cn.local.ocr;
import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.FrameLayout;
import java.util.*;

/** Camera state is serialized on one handler; UI changes stay on the activity thread. */
final class CameraController {
 interface Listener {void ready();void photo(byte[] bytes);void error(String message);}
 final Activity activity;final TextureView view;final Listener listener;
 final HandlerThread thread=new HandlerThread("camera");final Handler handler;
 CameraDevice device;CameraCaptureSession session;ImageReader reader;Surface previewSurface;
 int sensor=90;Size previewSize=new Size(1280,720);
 volatile boolean wanted=false,resumed=true;boolean opening=false;
 CameraController(Activity a,TextureView v,Listener l){activity=a;view=v;listener=l;thread.start();handler=new Handler(thread.getLooper());view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
  public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){open();}
  public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){transform();}
  public boolean onSurfaceTextureDestroyed(SurfaceTexture t){close();return true;}
  public void onSurfaceTextureUpdated(SurfaceTexture t){}
 });}
 void bounds(int x,int y,int w,int h){wanted=w>0&&h>0;if(!wanted){view.setVisibility(View.GONE);close();return;}
  FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(w,h);lp.leftMargin=x;lp.topMargin=y;view.setLayoutParams(lp);view.setVisibility(View.VISIBLE);transform();open();
 }
 void transform(){activity.runOnUiThread(()->{int w=view.getWidth(),h=view.getHeight();if(w==0||h==0)return;int degrees=activity.getWindowManager().getDefaultDisplay().getRotation()*90;
  int relative=(sensor-degrees+360)%360;float bw=(relative%180==0?previewSize.getWidth():previewSize.getHeight()),bh=(relative%180==0?previewSize.getHeight():previewSize.getWidth());float scale=Math.max(w/bw,h/bh);Matrix m=new Matrix();
  if(degrees==90||degrees==270){RectF frame=new RectF(0,0,w,h),buffer=new RectF(0,0,previewSize.getHeight(),previewSize.getWidth());buffer.offset(frame.centerX()-buffer.centerX(),frame.centerY()-buffer.centerY());m.setRectToRect(frame,buffer,Matrix.ScaleToFit.FILL);float s=Math.max((float)h/previewSize.getHeight(),(float)w/previewSize.getWidth());m.postScale(s,s,w/2f,h/2f);m.postRotate(degrees==90?-90:90,w/2f,h/2f);}
  else {m.setScale(bw*scale/w,bh*scale/h,w/2f,h/2f);if(degrees==180)m.postRotate(180,w/2f,h/2f);}view.setTransform(m);
 });}
 void open(){handler.post(this::openNow);}
 void openNow(){if(!wanted||!resumed||device!=null||opening||!view.isAvailable())return;
  if(activity.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
  try{CameraManager manager=activity.getSystemService(CameraManager.class);String cameraId=null;
   for(String id:manager.getCameraIdList()){Integer lens=manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);if(lens!=null&&lens==CameraCharacteristics.LENS_FACING_BACK){cameraId=id;break;}}
   if(cameraId==null){listener.error("此设备没有后置相机，可导入图片");return;}
   CameraCharacteristics c=manager.getCameraCharacteristics(cameraId);Integer angle=c.get(CameraCharacteristics.SENSOR_ORIENTATION);sensor=angle==null?90:angle;
   StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);if(map==null)throw new IllegalStateException("相机缺少输出配置");
   Size[] photos=map.getOutputSizes(ImageFormat.JPEG);Size size=photos[0];for(Size s:photos)if(s.getWidth()<=4000&&s.getWidth()>=1600){size=s;break;}
   Size[] previews=map.getOutputSizes(SurfaceTexture.class);previewSize=previews[0];long best=Long.MAX_VALUE;for(Size s:previews){long score=Math.abs((long)s.getWidth()*s.getHeight()-1280L*720);if(score<best){best=score;previewSize=s;}}
   if(reader!=null)reader.close();reader=ImageReader.newInstance(size.getWidth(),size.getHeight(),ImageFormat.JPEG,2);
   reader.setOnImageAvailableListener(r->{try(Image image=r.acquireLatestImage()){if(image==null)return;java.nio.ByteBuffer b=image.getPlanes()[0].getBuffer();byte[] data=new byte[b.remaining()];b.get(data);listener.photo(data);}catch(Exception e){listener.error(e.getMessage());}},handler);
   opening=true;manager.openCamera(cameraId,new CameraDevice.StateCallback(){
    public void onOpened(CameraDevice d){opening=false;if(!wanted||!resumed){d.close();return;}device=d;preview();}
    public void onDisconnected(CameraDevice d){opening=false;d.close();device=null;closeNow();listener.error("相机已断开，可重新启用");}
    public void onError(CameraDevice d,int error){opening=false;d.close();device=null;closeNow();listener.error("相机不可用："+error);}
   },handler);
  }catch(Exception e){opening=false;closeNow();listener.error(e.getMessage());}
 }
 void preview(){try{SurfaceTexture t=view.getSurfaceTexture();if(t==null||reader==null){closeNow();return;}t.setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight());previewSurface=new Surface(t);transform();CameraDevice expected=device;
  device.createCaptureSession(Arrays.asList(previewSurface,reader.getSurface()),new CameraCaptureSession.StateCallback(){
   public void onConfigured(CameraCaptureSession s){if(device!=expected||!wanted||!resumed){s.close();return;}session=s;try{CaptureRequest.Builder b=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);b.addTarget(previewSurface);b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);s.setRepeatingRequest(b.build(),null,handler);listener.ready();}catch(Exception e){listener.error(e.getMessage());}}
   public void onConfigureFailed(CameraCaptureSession s){listener.error("相机预览配置失败");}
  },handler);
 }catch(Exception e){listener.error(e.getMessage());}}
 void capture(){handler.post(()->{if(device==null||session==null||reader==null){listener.error("相机尚未就绪，请允许相机权限或导入图片");return;}try{CaptureRequest.Builder b=device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);b.addTarget(reader.getSurface());b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);int rotation=activity.getWindowManager().getDefaultDisplay().getRotation()*90;b.set(CaptureRequest.JPEG_ORIENTATION,(sensor-rotation+360)%360);session.capture(b.build(),new CameraCaptureSession.CaptureCallback(){@Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure f){listener.error("拍摄失败，请重试");}},handler);}catch(Exception e){listener.error(e.getMessage());}});}
 void closeNow(){if(session!=null){session.close();session=null;}if(device!=null){device.close();device=null;}if(reader!=null){reader.close();reader=null;}if(previewSurface!=null){previewSurface.release();previewSurface=null;}}
 void close(){handler.post(this::closeNow);}
 void suspend(){resumed=false;close();}
 void resume(){resumed=true;open();}
 void destroy(){wanted=false;resumed=false;handler.post(()->{closeNow();thread.quitSafely();});}
}
