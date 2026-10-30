#!/usr/bin/env python3
"""Optional virtual-display policy: actual support, denied API and old-ROM fallback."""
from pathlib import Path
import os,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
JAVA=r'''package top.zainzh.rearscreen;
public class ResizePolicyTest {
 public static class Config { boolean enabled; public boolean isIgnoreActivitySizeRestrictions(){return enabled;} }
 public static class Builder {Config config=new Config(); public Builder setIgnoreActivitySizeRestrictions(boolean v){config.enabled=v;return this;} public Config build(){return config;} }
 public static class OldBuilder {public Object build(){return new Object();} }
 public static class GetterlessBuilder {public GetterlessBuilder setIgnoreActivitySizeRestrictions(boolean v){return this;} public Object build(){return new Object();} }
 public static class DeniedBuilder extends Builder {public Builder setIgnoreActivitySizeRestrictions(boolean v){throw new SecurityException("denied");} }
 public static class FlagOffBuilder extends Builder {public Config build(){config.enabled=false;return config;} }
 static void mode(Object builder,boolean enabled,String mode)throws Exception{RearCastResizePolicy.Result r=RearCastResizePolicy.build(builder,enabled);if(r.config==null||!r.mode.equals(mode))throw new AssertionError(r.mode+" != "+mode);}
 public static void main(String[] args)throws Exception{
  mode(new Builder(),true,"unrestricted");mode(new Builder(),false,"disabled");mode(new OldBuilder(),true,"unsupported");mode(new DeniedBuilder(),true,"unsupported");mode(new FlagOffBuilder(),true,"system_disabled");mode(new GetterlessBuilder(),true,"unverified");
  System.out.println("PASS optional resize API: actual enabled state, opt-out, missing API, permission denial and disabled ROM flag");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
 test=Path(tmp)/'ResizePolicyTest.java';test.write_text(JAVA)
 jdk=Path(os.environ.get('JAVA_HOME',ROOT/'.local/toolchains/jdk21'))/'bin'
 subprocess.run([str(jdk/'javac'),'-d',tmp,str(ROOT/'android/app/src/main/java/top/zainzh/rearscreen/RearCastResizePolicy.java'),str(test)],check=True)
 subprocess.run([str(jdk/'java'),'-cp',tmp,'top.zainzh.rearscreen.ResizePolicyTest'],check=True)
