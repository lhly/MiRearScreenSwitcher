#!/usr/bin/env python3
"""Check the real gap geometry against circle collisions and monotonic battery progress."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TEST = r'''
package top.zainzh.rearscreen;
public final class CameraGapChecks {
    static int checks;
    static void require(boolean value, String why) {
        checks++; if (!value) throw new AssertionError(why);
    }
    static void eq(float a, float b) { require(Math.abs(a-b)<0.0001f, a+" != "+b); }
    public static void main(String[] args) {
        String[] devices={"pandora","popsicle","hongkong","madrid"};
        int[][] dimensions={{904,572},{976,596},{912,596},{976,596}};
        for(int n=0;n<devices.length;n++) {
            RearCameraGapGeometry.Profile p=RearCameraGapGeometry.candidate(devices[n]);
            require(RearCameraGapGeometry.supported(devices[n]) != null,"Known device not enabled");
            require(p.verified == "popsicle".equals(devices[n]),"Template support mislabeled as physical validation");
            require(p.width==dimensions[n][0]&&p.height==dimensions[n][1],"Wrong physical baseline");
            eq(p.x-p.left(),p.right()-p.x);
            float[] heights=new float[14];
            float[][] bars=new float[14][];
            for(int i=0;i<14;i++) {
                float pitch=(p.right()-p.left())/14f;
                float left=p.left()+i*pitch+2.7f,right=p.left()+(i+1)*pitch-2.7f;
                float[] gap=p.interval(left,right);
                require(gap[0]<gap[1],"No room between lenses");
                heights[i]=gap[1]-gap[0];
                bars[i]=new float[]{left,gap[0],right,gap[1]};
                require(gap[0]>=p.upperY && gap[1]<=p.lowerY,"Bar exceeds complete camera gap");
                // Check every subpixel column across the finite-width bar, including its nearest approach.
                for(int s=0;s<=100;s++) {
                    float x=left+(right-left)*s/100f;
                    require(Math.hypot(x-p.x,gap[0]-p.upperY)>=p.radius+p.margin-0.001,"Upper lens collision");
                    require(Math.hypot(x-p.x,gap[1]-p.lowerY)>=p.radius+p.margin-0.001,"Lower lens collision");
                }
            }
            for(int i=0;i<7;i++) {
                float[] a=bars[i],b=bars[13-i];
                eq(a[0]+b[2],2*p.x); eq(a[2]+b[0],2*p.x);
                eq(a[1],b[1]); eq(a[3],b[3]);
                // Subtracting two float boundaries accumulates their rounding error.
                require(Math.abs(heights[i]-heights[13-i])<0.001,"Mirrored bar heights differ");
            }
            require(heights[0]>heights[1]+1 && heights[1]>heights[2]+1,
                    "Left bars were flattened instead of following the lens arcs");
            require(heights[13]>heights[12]+1 && heights[12]>heights[11]+1,
                    "Right bars were flattened instead of following the lens arcs");
            RearCastViewport safe=RearCastViewport.safe(p.width,p.height,0,450,296,0,0,0);
            require(p.matches(safe),"Profile rejected matching physical safe area");
            require(!p.matches(safe.full()),"No-cutout screen accepted as an independent gap");
            require(!p.matches(RearCastViewport.safe(p.width+8,p.height,0,450,296,0,0,0)),"Changed panel accepted");
        }
        require(RearCameraGapGeometry.supported(null)==null && RearCameraGapGeometry.supported("unknown")==null,"Unknown device inferred from screen size");
        require(RearCameraGapGeometry.candidate("madrid").lowerY!=RearCameraGapGeometry.candidate("popsicle").lowerY,
                "Identical resolution incorrectly shares lens coordinates");
        float[] previous=new float[14];
        for(int level=0;level<=100;level++) {
            float total=0;
            for(int i=0;i<14;i++) {
                float fill=RearCameraGapGeometry.segmentFill(level,i,14);
                require(fill>=previous[i]&&fill>=0&&fill<=1,"Nonmonotonic charge indication");
                if(i>0&&fill>0) eq(RearCameraGapGeometry.segmentFill(level,i-1,14),1);
                previous[i]=fill; total+=fill;
            }
            eq(total,level*14f/100f);
        }
        require(RearCameraGapGeometry.percentage(-1,100)==-1,"Unknown battery became 0%");
        require(RearCameraGapGeometry.percentage(50,0)==-1,"Invalid scale accepted");
        require(RearCameraGapGeometry.percentage(101,100)==-1,"Overfull input accepted");
        require(RearCameraGapGeometry.percentage(3,4)==75,"Battery scale ignored");
        require(RearCameraGapGeometry.percentage(Integer.MAX_VALUE,Integer.MAX_VALUE)==100,"Battery overflow");
        System.out.println("PASS "+checks+" camera-gap checks: finite bar collision safety, per-device identity, no-cutout rejection, 0–100% mapping");
    }
}
'''
with tempfile.TemporaryDirectory() as directory:
    p = Path(directory) / "CameraGapChecks.java"
    p.write_text(TEST)
    jdk = Path(os.environ.get("JAVA_HOME", ROOT / ".local/toolchains/jdk21")) / "bin"
    source = ROOT / "android/app/src/main/java/top/zainzh/rearscreen"
    subprocess.run([str(jdk / "javac"), "-d", directory, str(source / "RearCastViewport.java"),
                    str(source / "RearCameraGapGeometry.java"), str(p)], check=True)
    subprocess.run([str(jdk / "java"), "-cp", directory, "top.zainzh.rearscreen.CameraGapChecks"], check=True)
