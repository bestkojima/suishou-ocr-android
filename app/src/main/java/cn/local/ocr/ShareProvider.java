package cn.local.ocr;
import android.content.*;import android.database.*;import android.net.Uri;import android.os.*;import android.provider.OpenableColumns;import java.io.*;
public final class ShareProvider extends ContentProvider {
    public boolean onCreate(){return true;}
    File file(Uri u)throws Exception{return FilesUtil.child(new File(getContext().getCacheDir(),"exports"),u.getLastPathSegment());}
    public ParcelFileDescriptor openFile(Uri u,String mode)throws FileNotFoundException{if(!mode.equals("r"))throw new FileNotFoundException();try{return ParcelFileDescriptor.open(file(u),ParcelFileDescriptor.MODE_READ_ONLY);}catch(Exception e){throw new FileNotFoundException(e.getMessage());}}
    public String getType(Uri u){String p=u.getLastPathSegment();return p.endsWith("zip")?"application/zip":p.endsWith("json")?"application/json":"text/plain";}
    public Cursor query(Uri u,String[] projection,String sel,String[] args,String sort){try{File f=file(u);MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{f.getName(),f.length()});return c;}catch(Exception e){return null;}}
    public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
