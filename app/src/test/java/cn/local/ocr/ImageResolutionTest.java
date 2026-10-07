package cn.local.ocr;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class ImageResolutionTest {
    @Test public void cameraPhotoIsBoundedBeforeAllocatingRotationPixels()throws Exception {
        ImageResolution.Plan plan=ImageResolution.plan(8000,6000,"balanced");
        assertTrue("4800 万像素不能直接作为规范图片",(long)plan.width*plan.height<=8_000_000);
        assertTrue(Math.max(plan.width,plan.height)<=4096);
        assertEquals(2,plan.sampleSize);
        assertTrue(8000/plan.sampleSize>=plan.width&&6000/plan.sampleSize>=plan.height);
        assertEquals(4.0/3,plan.width/(double)plan.height,0.001);
    }
    @Test public void smallInputIsNotUpscaledAndOriginalModeRetainsDetail()throws Exception {
        ImageResolution.Plan small=ImageResolution.plan(1000,1380,"balanced");
        assertEquals(1000,small.width);assertEquals(1380,small.height);assertEquals(1,small.sampleSize);
        ImageResolution.Plan original=ImageResolution.plan(8000,6000,"original");
        assertEquals(8000,original.width);assertEquals(6000,original.height);assertEquals(1,original.sampleSize);
    }
    @Test public void fastAndLongNarrowInputsRespectBothLimits()throws Exception {
        ImageResolution.Plan fast=ImageResolution.plan(4000,3000,"fast");
        assertTrue((long)fast.width*fast.height<=4_000_000);assertTrue(Math.max(fast.width,fast.height)<=2560);
        ImageResolution.Plan strip=ImageResolution.plan(64000,1,"balanced");
        assertEquals(4096,strip.width);assertEquals(1,strip.height);
        ImageResolution.Plan portrait=ImageResolution.plan(6000,8000,"balanced");
        ImageResolution.Plan landscape=ImageResolution.plan(8000,6000,"balanced");
        assertEquals(landscape.width,portrait.height);assertEquals(landscape.height,portrait.width);
    }
    @Test public void invalidInputIsRejectedBeforeDecode() {
        assertThrows(IOException.class,()->ImageResolution.plan(0,1,"balanced"));
        assertThrows(IOException.class,()->ImageResolution.plan(10000,10000,"balanced"));
        assertThrows(IllegalArgumentException.class,()->ImageResolution.plan(1000,1000,"thumbnail"));
    }
}
