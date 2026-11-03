#!/usr/bin/env python3
"""Validate exact official resource identities, per-device colors and saved-choice fallback."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
TEST = r'''
package top.zainzh.rearscreen;
import java.util.*;
public final class ShellCatalogChecks {
    static int checks;
    static void require(boolean value, String why) { checks++; if(!value)throw new AssertionError(why); }
    public static void main(String[] args) {
        String[] devices={"pandora","popsicle","hongkong","madrid"};
        String[][] colors={{"BK","WH","PU","LG"},{"BK","WH","PU","LG"},
                {"BK","WH","PK","DB","RD","CY"},{"BK","WH","PK","DB","RD","CY"}};
        long[][] ids={{24994890488217856L,25005738663542848L,25005736619999360L,25005735444545792L},
                {25000238622966048L,25000242116493312L,25000241377443904L,25000240442638464L},
                {26699815782645856L,26699819854201056L,26699817807446336L,26699816735604896L,26699818826727680L,26738564433576160L},
                {26703179066638656L,26703184823255264L,26703181217726464L,26703180422316160L,26703183772712960L,26738831115288896L}};
        Set<Long> unique=new HashSet<>();
        for(int d=0;d<devices.length;d++) {
            String device=devices[d];
            require(Arrays.equals(colors[d],RearShellCatalog.colors(device)),"Wrong device palette: "+device);
            for(int c=0;c<colors[d].length;c++) {
                long id=RearShellCatalog.resourceId(device,colors[d][c]);
                require(id==ids[d][c],"Wrong official resource: "+device+"/"+colors[d][c]);
                require(unique.add(id),"Resource shared between devices/colors");
                require(colors[d][c].equals(RearShellCatalog.colorForDevice(device,colors[d][c])),"Valid choice discarded");
            }
            for(String invalid:new String[]{null,"","bk","../BK","unknown"}) {
                require(RearShellCatalog.resourceId(device,invalid)==0,"Invalid resource selected");
                require("BK".equals(RearShellCatalog.colorForDevice(device,invalid)),"Invalid saved choice did not recover");
            }
            String[] copy=RearShellCatalog.colors(device);copy[0]="broken";
            require("BK".equals(RearShellCatalog.colors(device)[0]),"Caller mutated catalog");
        }
        require(unique.size()==20,"Incomplete official catalog");
        for(String device:new String[]{"pandora","popsicle"}) for(String color:new String[]{"PK","DB","RD","CY"}) {
            require(RearShellCatalog.resourceId(device,color)==0,"18 palette offered to 17");
            require("BK".equals(RearShellCatalog.colorForDevice(device,color)),"Restored 18 choice not normalized on 17");
        }
        for(String device:new String[]{"hongkong","madrid"}) for(String color:new String[]{"PU","LG"}) {
            require(RearShellCatalog.resourceId(device,color)==0,"17 palette offered to 18");
            require("BK".equals(RearShellCatalog.colorForDevice(device,color)),"Restored 17 choice not normalized on 18");
        }
        for(String device:new String[]{null,"unknown","popsicle_global"}) {
            require(RearShellCatalog.colors(device).length==0,"Unknown device guessed from name");
            require(RearShellCatalog.resourceId(device,"BK")==0,"Unknown device got another frame");
            require("LG".equals(RearShellCatalog.colorForDevice(device,"LG")),"Unsupported device cleared saved preference");
        }
        System.out.println("PASS "+checks+" shell catalog checks: 20 exact official IDs, per-model palettes, isolated choices and safe fallback");
    }
}
'''
with tempfile.TemporaryDirectory() as directory:
    test = Path(directory) / "ShellCatalogChecks.java"
    test.write_text(TEST)
    jdk = Path(os.environ.get("JAVA_HOME", ROOT / ".local/toolchains/jdk21")) / "bin"
    source = ROOT / "android/app/src/main/java/top/zainzh/rearscreen/RearShellCatalog.java"
    subprocess.run([str(jdk / "javac"), "-d", directory, str(source), str(test)], check=True)
    subprocess.run([str(jdk / "java"), "-cp", directory, "top.zainzh.rearscreen.ShellCatalogChecks"], check=True)
