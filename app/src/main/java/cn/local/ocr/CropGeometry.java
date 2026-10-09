package cn.local.ocr;

/** 归一化四角，依次为左上、右上、右下、左下；独立于 Android 位图 API。 */
final class CropGeometry {
    static double[][] full() { return new double[][]{{0,0},{1,0},{1,1},{0,1}}; }
    static double area(double[][] p) {
        double sum=0;
        for(int i=0;i<4;i++)sum+=p[i][0]*p[(i+1)%4][1]-p[(i+1)%4][0]*p[i][1];
        return sum/2;
    }
    static void validate(double[][] p) {
        if(p==null||p.length!=4)throw new IllegalArgumentException("需要四个文档边角");
        for(double[] point:p)if(point==null||point.length!=2||!Double.isFinite(point[0])||!Double.isFinite(point[1])||point[0]<0||point[0]>1||point[1]<0||point[1]>1)
            throw new IllegalArgumentException("裁剪边角必须位于图片内");
        for(int i=0;i<4;i++) {
            double[] a=p[i],b=p[(i+1)%4],c=p[(i+2)%4];
            if((b[0]-a[0])*(c[1]-b[1])-(b[1]-a[1])*(c[0]-b[0])<=1e-6)
                throw new IllegalArgumentException("裁剪框不能交叉或折叠");
        }
        if(area(p)<.005)throw new IllegalArgumentException("裁剪区域过小，请扩大选框");
    }
    static int[] dimensions(double[][] p,int width,int height) {
        validate(p);
        if(width<2||height<2)throw new IllegalArgumentException("图片尺寸无效");
        double top=distance(p[0],p[1],width,height),bottom=distance(p[3],p[2],width,height);
        double left=distance(p[0],p[3],width,height),right=distance(p[1],p[2],width,height);
        int w=(int)Math.round(Math.max(top,bottom))+1,h=(int)Math.round(Math.max(left,right))+1;
        if(w<16||h<16)throw new IllegalArgumentException("裁剪区域过窄，请扩大选框");
        double scale=Math.min(1,Math.sqrt(((double)width*height)/((double)w*h)));
        return new int[]{Math.max(16,(int)Math.round(w*scale)),Math.max(16,(int)Math.round(h*scale))};
    }
    private static double distance(double[] a,double[] b,int w,int h) {
        return Math.hypot((a[0]-b[0])*(w-1),(a[1]-b[1])*(h-1));
    }
}
