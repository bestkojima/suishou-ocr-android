package cn.local.ocr;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.util.*;

/** 只分析缩小后的灰度图；不把检测可靠性等同于 OCR 准确率。 */
final class DocumentBoundaryDetector {
    static double[][] detect(Mat gray) {
        Mat blurred=new Mat(),edges=new Mat(),binary=new Mat(),kernel=Imgproc.getStructuringElement(Imgproc.MORPH_RECT,new Size(3,3));
        List<MatOfPoint> contours=new ArrayList<>();Mat hierarchy=new Mat();
        double[][] best=null;double bestArea=0,canvas=gray.cols()*(double)gray.rows();
        try {
            Imgproc.GaussianBlur(gray,blurred,new Size(5,5),0);
            for(int pass=0;pass<2;pass++) {
                if(pass==0) {
                    Imgproc.Canny(blurred,edges,50,150);
                    Imgproc.morphologyEx(edges,binary,Imgproc.MORPH_CLOSE,kernel);
                } else Imgproc.threshold(blurred,binary,0,255,Imgproc.THRESH_BINARY|Imgproc.THRESH_OTSU);
                Imgproc.findContours(binary,contours,hierarchy,Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE);
                for(MatOfPoint contour:contours) {
                    double contourArea=Math.abs(Imgproc.contourArea(contour));
                    if(contourArea<canvas*.12||contourArea>canvas*.985||contourArea<bestArea)continue;
                    MatOfPoint2f curve=new MatOfPoint2f(contour.toArray()),approx=new MatOfPoint2f();
                    try {
                        Imgproc.approxPolyDP(curve,approx,Imgproc.arcLength(curve,true)*.02,true);
                        Point[] corners=approx.toArray();if(corners.length!=4)continue;
                        double cx=0,cy=0;for(Point corner:corners){cx+=corner.x/4;cy+=corner.y/4;}
                        final double centerX=cx,centerY=cy;
                        Arrays.sort(corners,Comparator.comparingDouble(p->Math.atan2(p.y-centerY,p.x-centerX)));
                        int first=0;for(int i=1;i<4;i++)if(corners[i].x+corners[i].y<corners[first].x+corners[first].y)first=i;
                        double[][] p=new double[4][2];
                        for(int i=0;i<4;i++){Point corner=corners[(first+i)%4];p[i]=new double[]{corner.x/(gray.cols()-1),corner.y/(gray.rows()-1)};}
                        try {CropGeometry.validate(p);}catch(IllegalArgumentException ignored){continue;}
                        double area=CropGeometry.area(p)*canvas;
                        if(area>bestArea){bestArea=area;best=p;}
                    } finally {curve.release();approx.release();}
                }
                for(MatOfPoint contour:contours)contour.release();contours.clear();
            }
            return best;
        } finally {
            for(MatOfPoint contour:contours)contour.release();
            blurred.release();edges.release();binary.release();kernel.release();hierarchy.release();
        }
    }
    static Mat warp(Mat source,double[][] points) {
        int[] size=CropGeometry.dimensions(points,source.cols(),source.rows());
        Point[] p=new Point[4];for(int i=0;i<4;i++)p[i]=new Point(points[i][0]*(source.cols()-1),points[i][1]*(source.rows()-1));
        MatOfPoint2f from=new MatOfPoint2f(p),to=new MatOfPoint2f(new Point(0,0),new Point(size[0]-1,0),new Point(size[0]-1,size[1]-1),new Point(0,size[1]-1));
        Mat transform=null,result=new Mat();
        try {
            transform=Imgproc.getPerspectiveTransform(from,to);
            Imgproc.warpPerspective(source,result,transform,new Size(size[0],size[1]),Imgproc.INTER_LINEAR,Core.BORDER_REPLICATE);
            return result;
        } catch(RuntimeException error){result.release();throw error;}
        finally {from.release();to.release();if(transform!=null)transform.release();}
    }
}
