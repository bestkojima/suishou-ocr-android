package cn.local.ocr;
import android.graphics.*;
import android.media.ExifInterface;
import java.io.*;

final class ImageInput {
    static int[] normalize(File input,File output) throws Exception {
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(input.getPath(),bounds);
        if(bounds.outWidth<=0||(long)bounds.outWidth*bounds.outHeight>64_000_000L) throw new IOException("图片不可读取或超过 6400 万像素");
        int orientation=1;
        try { orientation=new ExifInterface(input.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1); } catch(IOException ignored) {}
        Bitmap source=BitmapFactory.decodeFile(input.getPath());
        if(source==null) throw new IOException("图片解码失败，原始输入已保留");
        Bitmap normalized=null;
        try {
            int w=source.getWidth(),h=source.getHeight();int[] pixels=new int[w*h];source.getPixels(pixels,0,w,0,0,w,h);
            int[] oriented=ImagePixels.orient(pixels,w,h,orientation);source.recycle();
            int ow=orientation>=5&&orientation<=8?h:w,oh=orientation>=5&&orientation<=8?w:h;
            normalized=Bitmap.createBitmap(oriented,ow,oh,Bitmap.Config.ARGB_8888);output.getParentFile().mkdirs();
            Bitmap image=normalized;
            atomicSave(output,out->{if(!image.compress(Bitmap.CompressFormat.PNG,100,out)) throw new IOException("保存规范图片失败");});
            return new int[]{ow,oh};
        } finally { if(!source.isRecycled()) source.recycle();if(normalized!=null) normalized.recycle(); }
    }
    interface ImageWriter { void write(OutputStream output) throws Exception; }
    static void atomicSave(File output,ImageWriter writer) throws Exception {
        output.getParentFile().mkdirs();
        File temporary=File.createTempFile("normalized-",".tmp",output.getParentFile());
        try {
            try(FileOutputStream out=new FileOutputStream(temporary)){writer.write(out);out.getFD().sync();}
            java.nio.file.Files.move(temporary.toPath(),output.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { temporary.delete(); }
    }
}
