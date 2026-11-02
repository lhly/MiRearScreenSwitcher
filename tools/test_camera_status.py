#!/usr/bin/env python3
"""Exercise production weather normalization/freshness and camera arc baselines."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TEST = r'''
package top.zainzh.rearscreen;
public final class CameraStatusChecks {
    static int checks;
    static void require(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    public static void main(String[] args) {
        long now=1793548800000L;
        String timestamp=Long.toString(now);
        RearWeatherSnapshot w=RearWeatherSnapshot.parse("7℃", "0", timestamp, "1");
        require(w!=null && w.temperature.equals("7°") && w.icon==RearWeatherSnapshot.Icon.CLEAR,"Current weather");
        require(w.fresh(now),"Fresh cache rejected");
        require(w.fresh(now+RearWeatherSnapshot.MAX_AGE_MS),"Exact cache age");
        require(!w.fresh(now+RearWeatherSnapshot.MAX_AGE_MS+1),"Old weather accepted");
        require(!w.fresh(now-300001),"Future weather accepted");
        require(RearWeatherSnapshot.parse("−12℃","17",timestamp,"1").temperature.equals("-12°"),"Negative temperature");
        require(RearWeatherSnapshot.parse("68℉","1",timestamp,"0").temperature.equals("68°F"),"Fahrenheit lost");
        require(RearWeatherSnapshot.parse("68°","1",timestamp,"0").temperature.equals("68°F"),"Unit column ignored");
        for(String bad:new String[]{"", "--", "NaN", "温度7", "999℃", "-101℃", "7℃junk"})
            require(RearWeatherSnapshot.parse(bad,"0",timestamp,"1")==null,"Invalid temperature: "+bad);
        require(RearWeatherSnapshot.parse(null,"0",timestamp,"1")==null,"Null accepted");
        require(RearWeatherSnapshot.parse("7℃","0","0","1")==null,"Unknown date accepted");
        require(RearWeatherSnapshot.parse("7℃","0","junk","1")==null,"Invalid date accepted");
        require(RearWeatherSnapshot.iconFor(3)==RearWeatherSnapshot.Icon.HAZE,"Xiaomi code 3 is fog, not showers");
        require(RearWeatherSnapshot.iconFor(7)==RearWeatherSnapshot.Icon.THUNDER,"Thunder mapping");
        require(RearWeatherSnapshot.iconFor(99)==RearWeatherSnapshot.Icon.UNKNOWN,"Unknown icon fabricated");
        require(RearWeatherSnapshot.iconFor(-1)==RearWeatherSnapshot.Icon.UNKNOWN,"Negative code");
        require(RearWeatherSnapshot.iconFor(25)==RearWeatherSnapshot.Icon.SLEET,"Freezing rain");
        RearCameraGapGeometry.Profile p=RearCameraGapGeometry.supported("popsicle");
        require(RearCameraStatusGeometry.arcs(null).length==0,"Unknown device");
        for(String device : new String[]{"pandora", "popsicle", "hongkong", "madrid"}) {
          p = RearCameraGapGeometry.supported(device);
          require(RearCameraStatusGeometry.supported(p),"Known device has no information layout");
          RearCameraStatusGeometry.Arc[] arcs=RearCameraStatusGeometry.arcs(p);
          require(arcs.length==4,"Missing arc");
          for(int i=0;i<arcs.length;i++) {
            RearCameraStatusGeometry.Arc arc=arcs[i];
            require(arc.x==p.x && arc.y==(i%2==0?p.upperY:p.lowerY),"Arc uses another device's lens centers");
            require(arc.radius>p.radius+p.margin,"Baseline too close to lens");
            require(arc.length()>44 && arc.length()<80,"Unexpected arc length");
            require(arc.textSize>=18,"Unexpectedly small nominal text");
            require(arc.clockwise==(arc.y==p.upperY),"Bottom text upside down");
        }
        }
        System.out.println("PASS "+checks+" status checks: weather, units, expiry, Xiaomi codes and arc baselines (actual glyphs checked on Android)");
    }
}
'''
with tempfile.TemporaryDirectory() as directory:
    test = Path(directory) / "CameraStatusChecks.java"
    test.write_text(TEST)
    jdk = Path(os.environ.get("JAVA_HOME", ROOT / ".local/toolchains/jdk21")) / "bin"
    source = ROOT / "android/app/src/main/java/top/zainzh/rearscreen"
    files = ["RearWeatherSnapshot.java", "RearCameraStatusGeometry.java", "RearCameraGapGeometry.java", "RearCastViewport.java"]
    subprocess.run([str(jdk / "javac"), "-d", directory, *[str(source / f) for f in files], str(test)], check=True)
    subprocess.run([str(jdk / "java"), "-cp", directory, "top.zainzh.rearscreen.CameraStatusChecks"], check=True)
