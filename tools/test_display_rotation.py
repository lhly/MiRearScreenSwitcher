#!/usr/bin/env python3
"""Exercise the production rotation transaction without Android; device policies need real-device QA."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
HARNESS = r'''
package top.zainzh.rearscreen;
import java.util.*;
import java.io.IOException;

public class RotationRegressionTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static String dump(int actual) {
        return "Display: mDisplayId=0\n mRotation=0 mDeferredRotationPauseCount=0\n"
            + " mImeWindow=Window{ime} Display: mDisplayId=1 (organized)\n"
            + " overrideConfig={mRotation=ROTATION_270}\n mUserRotation=ROTATION_180\n"
            + " mRotation=" + actual + " mDeferredRotationPauseCount=0\n"
            + "Display: mDisplayId=2\n mRotation=3\n";
    }
    static class Shell implements DisplayRotationController.CommandRunner {
        String user = "lock 2", fixed = "default", failure = "";
        int actual = 0, reads, sleeps;
        boolean changed;
        final List<String> commands = new ArrayList<>(), logs = new ArrayList<>();
        DisplayRotationController controller() { return new DisplayRotationController(this, millis -> {
            sleeps++;
            if (failure.equals("interrupted")) throw new InterruptedException("test cancellation");
        }, logs::add); }
        public DisplayRotationController.Result run(String... args) throws Exception {
            String command = String.join(" ", args); commands.add(command);
            if (args[0].equals("dumpsys")) {
                reads++;
                if (failure.equals("read_exit")) return result(1, "permission denied");
                if (failure.equals("read_exception")) throw new IOException("read failed");
                if (failure.equals("read_malformed")) return result(0, "Display: mDisplayId=1\n mUserRotation=ROTATION_180");
                if (changed && !failure.equals("never_applies") && !failure.equals("interrupted")) {
                    if (!failure.equals("delayed") || reads >= 3) actual = Integer.parseInt(user.substring(5));
                }
                return result(0, dump(actual));
            }
            boolean policy = args[1].equals("fixed-to-user-rotation");
            if (args.length == 4) {
                if (failure.equals("bad_snapshot")) return result(0, "Error: unsupported");
                return result(0, policy ? fixed : user);
            }
            String value = args[4];
            boolean target = policy ? value.equals("enabled") : value.equals("lock") && args[5].equals("1");
            if (target) {
                if (failure.equals(policy ? "policy_exit" : "lock_exit")) return result(1, "permission denied");
                if (failure.equals(policy ? "policy_noop" : "lock_noop")) return result(0, "Error: ignored");
                if (failure.equals(policy ? "policy_exception" : "lock_exception")) throw new IOException("command failed");
            }
            if (policy) fixed = value;
            else { user = value.equals("free") ? "free" : "lock " + args[5]; changed = true; }
            return result(0, "");
        }
        DisplayRotationController.Result result(int exit, String output) { return new DisplayRotationController.Result(exit, output); }
        void restored() {
            check(user.equals("lock 2") && fixed.equals("default"), "not restored: " + user + "/" + fixed);
        }
    }
    public static void main(String[] args) throws Exception {
        String name = args[0]; Shell shell = new Shell(); DisplayRotationController control = shell.controller();
        switch (name) {
            case "read_actual": check(control.getRotation(1)==0,"used request/config instead of actual"); break;
            case "scope": check(DisplayRotationController.parseRotation(dump(1),0)==0,"primary");
                check(DisplayRotationController.parseRotation(dump(1),1)==1,"rear");
                check(DisplayRotationController.parseRotation(dump(1),2)==3,"third display");
                check(DisplayRotationController.parseRotation(dump(1),5)==-1,"missing display"); break;
            case "symbolic":
                for(int angle=0;angle<4;angle++) check(DisplayRotationController.parseRotation("Display: mDisplayId=1\n mRotation=ROTATION_"+(angle*90),1)==angle,"symbolic angle"); break;
            case "no_cross_display":
                check(DisplayRotationController.parseRotation("Display: mDisplayId=1\n mUserRotation=2\nDisplay: mDisplayId=2\n mRotation=1",1)==-1,"leaked next display");
                check(DisplayRotationController.parseRotation("Display: mDisplayId=1\n mRotation=10",1)==-1,"accepted invalid angle"); break;
            case "invalid": check(!control.setRotation(-1,0)&&!control.setRotation(1,-1)&&!control.setRotation(1,4)&&control.getRotation(-1)==-1,"invalid accepted");
                check(shell.commands.isEmpty(),"invalid executed commands"); break;
            case "success": case "delayed":
                shell.failure=name.equals("delayed")?name:"";
                check(control.setRotation(1,1),"rotation rejected"); check(shell.fixed.equals("enabled")&&shell.user.equals("lock 1")&&shell.actual==1,"wrong result");
                check(shell.commands.indexOf("wm fixed-to-user-rotation -d 1 enabled") < shell.commands.indexOf("wm user-rotation -d 1 lock 1"),"lock applied before policy");
                if(name.equals("delayed")) check(shell.sleeps==2,"did not wait for actual rotation"); break;
            case "primary_policy": check(control.setRotation(0,0),"primary failed");
                check(shell.commands.stream().noneMatch(c->c.contains("fixed-to-user")),"changed primary policy"); break;
            case "free_rollback": shell.user="free";shell.fixed="disabled";shell.failure="never_applies";
                check(!control.setRotation(1,1),"false success");check(shell.user.equals("free")&&shell.fixed.equals("disabled"),"free mode not restored");break;
            case "conditional_rollback": shell.fixed="enabled_if_no_auto_rotation";shell.failure="never_applies";
                check(!control.setRotation(1,1),"false success");check(shell.fixed.equals("enabled_if_no_auto_rotation"),"conditional policy not restored");break;
            case "bad_snapshot": shell.failure=name;check(!control.setRotation(1,1),"accepted bad snapshot");
                check(shell.commands.size()==1,"mutated without snapshot");break;
            case "read_exit":case "read_exception":case "read_malformed": shell.failure=name;
                check(control.getRotation(1)==-1,"read failure reported 0");break;
            case "process_streams":
                DisplayRotationController.Result output=DisplayRotationController.runCommand("sh","-c","printf actual; printf error >&2; exit 7");
                check(output.exitCode==7&&output.output.equals("actualerror"),"lost output/exit code");break;
            case "process_timeout":
                long start=System.nanoTime();boolean failed=false;
                try {DisplayRotationController.runCommand("sleep","10");}catch(IOException expected){failed=true;}
                check(failed&&System.nanoTime()-start<6_000_000_000L,"unbounded command");break;
            default:
                shell.failure=name;
                check(!control.setRotation(1,1),"false success for "+name);shell.restored();
                if(name.equals("interrupted")) {check(Thread.interrupted(),"lost interrupt flag");}
                if(name.startsWith("policy_")) check(shell.commands.stream().noneMatch(c->c.endsWith("lock 1")),"locked after failed policy");
        }
        System.out.println("PASS "+name);
    }
}
'''


class RotationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='mrss-rotation-tests-')
        folder = Path(cls.temp.name)
        cls.classes = folder / 'classes'
        cls.classes.mkdir()
        java_home = Path(os.environ.get('JAVA_HOME', ROOT / '.local/toolchains/jdk21'))
        cls.java = java_home / 'bin/java'
        harness = folder / 'RotationRegressionTest.java'
        harness.write_text(HARNESS)
        production = ROOT / 'android/app/src/main/java/top/zainzh/rearscreen/DisplayRotationController.java'
        subprocess.run([str(java_home / 'bin/javac'), '--release', '11', '-d', str(cls.classes), str(production), str(harness)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def scenario(self, name):
        result = subprocess.run([str(self.java), '-cp', str(self.classes), 'top.zainzh.rearscreen.RotationRegressionTest', name], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASS ' + name, result.stdout)


for scenario in ['read_actual', 'scope', 'symbolic', 'no_cross_display', 'invalid', 'success', 'delayed',
                 'primary_policy', 'free_rollback', 'conditional_rollback', 'bad_snapshot',
                 'read_exit', 'read_exception', 'read_malformed', 'policy_exit', 'lock_exit',
                 'policy_noop', 'lock_noop', 'policy_exception', 'lock_exception', 'never_applies',
                 'interrupted', 'process_streams', 'process_timeout']:
    setattr(RotationTests, 'test_' + scenario, lambda self, name=scenario: self.scenario(name))

if __name__ == '__main__':
    unittest.main(verbosity=2)
