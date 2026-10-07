package cn.local.ocr;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ModelTransferTest {
 @Rule public TemporaryFolder temp=new TemporaryFolder();
 static class Response extends HttpURLConnection {
  final int status;final String range;final byte[] body;boolean disconnected;
  Response(int status,String range,String data)throws Exception{super(new URL("https://example.invalid/model"));this.status=status;this.range=range;body=data.getBytes("UTF-8");}
  public int getResponseCode(){return status;}public String getHeaderField(String key){return key.equals("Content-Range")?range:null;}public InputStream getInputStream(){return new ByteArrayInputStream(body);}public void disconnect(){disconnected=true;}public boolean usingProxy(){return false;}public void connect(){}
 }
 File part(String text)throws Exception{File f=temp.newFile();Files.write(f.toPath(),text.getBytes("UTF-8"));return f;}
 @Test public void validRangeAppendsExistingBytes()throws Exception{File f=part("abc");Response r=new Response(206,"bytes 3-5/6","def");ModelTransfer.transfer(f,6,()->r,n->{});assertEquals("abcdef",FilesUtil.read(f));assertEquals("bytes=3-",r.getRequestProperty("Range"));assertTrue(r.disconnected);}
 @Test public void ambiguous200ResponseRestartsWithoutRange()throws Exception{File f=part("abc");Response tail=new Response(200,null,"def"),full=new Response(200,null,"abcdef");AtomicInteger calls=new AtomicInteger();ModelTransfer.transfer(f,6,()->calls.getAndIncrement()==0?tail:full,n->{});assertEquals(2,calls.get());assertNull(full.getRequestProperty("Range"));assertEquals("abcdef",FilesUtil.read(f));assertTrue(tail.disconnected&&full.disconnected);}
 @Test public void rejectsWrongRangeWithoutOverwritingPartialFile()throws Exception{File f=part("abc");Response r=new Response(206,"bytes 2-5/6","cdef");assertThrows(IOException.class,()->ModelTransfer.transfer(f,6,()->r,n->{}));assertEquals("abc",FilesUtil.read(f));assertTrue(r.disconnected);}
 @Test public void pausedTransferPreservesPartAndClosesConnection()throws Exception{File f=part("abc");Response r=new Response(206,"bytes 3-5/6","def");assertThrows(IOException.class,()->ModelTransfer.transfer(f,6,()->r,n->{throw new IOException("暂停");}));assertEquals("abc",FilesUtil.read(f));assertTrue(r.disconnected);}
 @Test public void progressIsPublishedBeforeWaitingForNextNetworkChunk()throws Exception{
  File f=part("");java.util.concurrent.atomic.AtomicLong reported=new java.util.concurrent.atomic.AtomicLong(-1);
  Response r=new Response(200,null,""){
   public InputStream getInputStream(){return new InputStream(){int reads;
    public int read(){return -1;}
    public int read(byte[] b,int offset,int length){if(reads++==0){b[offset]='a';b[offset+1]='b';b[offset+2]='c';return 3;}assertEquals("bytes already saved must be visible before a slow next read",3,reported.get());return -1;}
   };}
  };
  ModelTransfer.transfer(f,3,()->r,reported::set);
 }
 @Test public void truncatedResponsePreservesBytesForResume()throws Exception{File f=part("");Response r=new Response(200,null,"abc");assertThrows(IOException.class,()->ModelTransfer.transfer(f,6,()->r,n->{}));assertEquals("abc",FilesUtil.read(f));}
 @Test public void wrongRemoteTotalDoesNotAppend()throws Exception{File f=part("abc");Response r=new Response(206,"bytes 3-5/9","def");assertThrows(IOException.class,()->ModelTransfer.transfer(f,6,()->r,n->{}));assertEquals("abc",FilesUtil.read(f));}
}
