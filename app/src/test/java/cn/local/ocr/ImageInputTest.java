package cn.local.ocr;
import org.junit.Test;
import static org.junit.Assert.*;
public class ImageInputTest {
    @Test public void asymmetricPixelsRotateAndMirrorInAllExifDirections() {
        int[] source={1,2,3,4,5,6}; // 3×2，各位置不同，旋转和镜像不能偶然通过。
        int[][] expected={{1,2,3,4,5,6},{3,2,1,6,5,4},{6,5,4,3,2,1},{4,5,6,1,2,3},
            {1,4,2,5,3,6},{4,1,5,2,6,3},{6,3,5,2,4,1},{3,6,2,5,1,4}};
        for(int exif=1;exif<=8;exif++) assertArrayEquals("EXIF "+exif,expected[exif-1],ImagePixels.orient(source,3,2,exif));
    }
}
