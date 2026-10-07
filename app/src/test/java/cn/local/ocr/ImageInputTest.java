package cn.local.ocr;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;
public class ImageInputTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void failedWriteKeepsInputAndDoesNotLeaveCanonicalPngBlockingRetry()throws Exception {
        File raw=temp.newFile("original.jpg"),output=new File(temp.getRoot(),"source.png");
        Files.write(raw.toPath(),new byte[]{7,8,9});
        try{ImageInput.atomicSave(output,out->{out.write(new byte[]{1,2});throw new IOException("模拟磁盘写入失败");});fail("应报告写入失败");}catch(IOException expected){}
        assertFalse(output.exists());assertArrayEquals(new byte[]{7,8,9},Files.readAllBytes(raw.toPath()));
        assertEquals(1,temp.getRoot().listFiles().length);
        ImageInput.atomicSave(output,out->out.write(new byte[]{3,4,5}));assertArrayEquals(new byte[]{3,4,5},Files.readAllBytes(output.toPath()));
        try{ImageInput.atomicSave(output,out->{out.write(6);throw new IOException("再次写入失败");});fail("应报告写入失败");}catch(IOException expected){}
        assertArrayEquals(new byte[]{3,4,5},Files.readAllBytes(output.toPath()));
    }
    @Test public void asymmetricPixelsRotateAndMirrorInAllExifDirections() {
        int[] source={1,2,3,4,5,6}; // 3×2，各位置不同，旋转和镜像不能偶然通过。
        int[][] expected={{1,2,3,4,5,6},{3,2,1,6,5,4},{6,5,4,3,2,1},{4,5,6,1,2,3},
            {1,4,2,5,3,6},{4,1,5,2,6,3},{6,3,5,2,4,1},{3,6,2,5,1,4}};
        for(int exif=1;exif<=8;exif++) assertArrayEquals("EXIF "+exif,expected[exif-1],ImagePixels.orient(source,3,2,exif));
    }
}
