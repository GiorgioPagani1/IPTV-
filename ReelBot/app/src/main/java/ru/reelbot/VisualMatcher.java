package ru.reelbot;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Lightweight visual heuristics. Does not identify or track semantic objects. */
public final class VisualMatcher {
    private VisualMatcher() {}

    public static float[] fingerprint(Bitmap image) {
        if(image == null) return null;
        float[] result = new float[27];
        int width=image.getWidth(), height=image.getHeight();
        for(int y=0;y<3;y++) for(int x=0;x<3;x++) {
            int px=Math.min(width-1,Math.max(0,(2*x+1)*width/6));
            int py=Math.min(height-1,Math.max(0,(2*y+1)*height/6));
            int c=image.getPixel(px,py);
            int offset=(y*3+x)*3;
            result[offset]=Color.red(c)/255f;
            result[offset+1]=Color.green(c)/255f;
            result[offset+2]=Color.blue(c)/255f;
        }
        return result;
    }

    public static double visualContinuity(float[] previousEnd,float[] nextStart) {
        if(previousEnd==null||nextStart==null) return .5;
        int length=Math.min(previousEnd.length,nextStart.length);
        if(length==0)return .5;
        double difference=0;
        for(int i=0;i<length;i++) difference+=Math.abs(previousEnd[i]-nextStart[i]);
        return Math.max(0,1-difference/length);
    }

    /**
     * Small global translation search across two low-resolution frames.
     * Positive x/y denote the estimated translation in the second image.
     */
    public static double[] movement(Bitmap before,Bitmap after) {
        if(before==null||after==null||before.getWidth()<32||after.getWidth()<32)
            return new double[]{0,0};
        int width=Math.min(before.getWidth(),after.getWidth());
        int height=Math.min(before.getHeight(),after.getHeight());
        double best=Double.MAX_VALUE;
        int selectedX=0,selectedY=0;
        for(int dy=-6;dy<=6;dy+=2) for(int dx=-6;dx<=6;dx+=2) {
            double mismatch=0;
            for(int y=10;y<height-10;y+=4) for(int x=10;x<width-10;x+=4) {
                mismatch+=Math.abs(luma(before.getPixel(x,y))-luma(after.getPixel(x+dx,y+dy)));
            }
            if(mismatch<best) {
                best=mismatch;
                selectedX=dx; selectedY=dy;
            }
        }
        return new double[]{selectedX,selectedY};
    }

    public static double directionalContinuity(double x1,double y1,double x2,double y2) {
        double len1=Math.hypot(x1,y1),len2=Math.hypot(x2,y2);
        if(len1<1.5||len2<1.5)return .5;
        return Math.max(0,Math.min(1,.5+.5*(x1*x2+y1*y2)/(len1*len2)));
    }

    private static int luma(int color) {
        return (Color.red(color)*54+Color.green(color)*183+Color.blue(color)*19)>>8;
    }
}
