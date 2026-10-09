package cn.local.ocr;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import org.opencv.imgcodecs.Imgcodecs;
import java.nio.file.*;

/** 使用生产检测/透视代码及真实像素，不执行 OCR，也不模拟 Android Bitmap。 */
public final class DocumentCropProbe {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        nu.pattern.OpenCV.loadLocally();Path output=Path.of(args[1]);Files.createDirectories(output);
        Mat page=Imgcodecs.imread(args[0],Imgcodecs.IMREAD_GRAYSCALE);check(!page.empty(),"需要实际文档图片");
        Mat resized=new Mat();Imgproc.resize(page,resized,new Size(420,580));page.release();
        double[][] expected={{.18,.08},{.78,.13},{.85,.89},{.12,.84}};
        MatOfPoint2f from=new MatOfPoint2f(new Point(0,0),new Point(419,0),new Point(419,579),new Point(0,579));
        MatOfPoint2f to=new MatOfPoint2f(new Point(144,64),new Point(624,104),new Point(680,712),new Point(96,672));
        Mat transform=Imgproc.getPerspectiveTransform(from,to),scene=new Mat();
        Imgproc.warpPerspective(resized,scene,transform,new Size(800,800),Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,new Scalar(45));
        Imgproc.rectangle(scene,new Point(15,50),new Point(65,200),new Scalar(180),-1);
        Imgproc.circle(scene,new Point(745,720),30,new Scalar(220),-1);
        Imgcodecs.imwrite(output.resolve("tilted-with-clutter.png").toString(),scene);
        double[][] detected=DocumentBoundaryDetector.detect(scene);check(detected!=null,"倾斜文档应自动检测到边界");
        for(int i=0;i<4;i++)for(int axis=0;axis<2;axis++)check(Math.abs(detected[i][axis]-expected[i][axis])<.025,"文档边角偏差超过样本容差");
        Mat result=DocumentBoundaryDetector.warp(scene,detected);Imgcodecs.imwrite(output.resolve("cropped.png").toString(),result);
        check(result.rows()>500&&result.cols()>400,"透视校正应保留文档尺寸");
        Mat corner=result.submat(0,15,0,15);check(Core.mean(corner).val[0]>190,"裁剪左上角不能保留深色背景");corner.release();
        Mat blank=new Mat(400,600,CvType.CV_8UC1,new Scalar(170));check(DocumentBoundaryDetector.detect(blank)==null,"无文档边界时应回退手动选框");
        Mat low=new Mat(400,600,CvType.CV_8UC1,new Scalar(145));Imgproc.rectangle(low,new Point(100,50),new Point(500,350),new Scalar(170),-1);
        check(DocumentBoundaryDetector.detect(low)!=null,"低对比度矩形应由阈值轮廓检测补充");
        Mat complete=DocumentBoundaryDetector.warp(resized,CropGeometry.full());
        check(complete.size().equals(resized.size())&&Core.norm(complete,resized,Core.NORM_INF)==0,"全图裁剪必须保持原像素");
        Files.writeString(output.resolve("algorithm.json"),"{\"hostOpenCv\":\""+Core.VERSION+"\",\"checks\":[\"实际文档倾斜与背景杂物\",\"自动四角容差\",\"透视裁剪排除背景\",\"无边界回退\",\"低对比度阈值轮廓\",\"全图像素保持\"]}\n");
        result.release();scene.release();blank.release();low.release();complete.release();resized.release();transform.release();from.release();to.release();
        System.out.println("PASS 文档边界、背景杂物、低对比度、透视校正及全图像素保持；OpenCV "+Core.VERSION);
    }
}
