#!/usr/bin/env python3
"""Check in-process notification ownership, bounded content and monotonic expiry without Android."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = r'''
package top.zainzh.rearscreen;
import java.util.*;
class CastNotificationTest {
 static int checks;
 static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
 public static void main(String[] args) {
  check(!RearCastNotification.offer("app", "title", "body", 1000, 10, false));
  List<RearCastNotification.Message> first = new ArrayList<>(), next = new ArrayList<>();
  RearCastNotification.Target a = first::add, b = next::add;
  RearCastNotification.attach(a);
  check(RearCastNotification.offer("app", " title\nline ", " body\rline ", 1000, 10, false));
  var message = first.get(0);
  check(message.title.equals("title line") && message.text.equals("body line"));
  check(message.remaining(1000) == 10000 && message.remaining(10999) == 1 && message.remaining(11000) == 0);
  check(message.remaining(999999) == 0);
  RearCastNotification.attach(b); RearCastNotification.detach(a);
  check(RearCastNotification.offer("app", "new", "body", 12000, 3, true));
  check(first.size() == 1 && next.size() == 1 && next.get(0).oled);
  RearCastNotification.detach(b);
  check(!RearCastNotification.offer("app", "old callback", "body", 12000, 10, false));
  var invalid = new RearCastNotification.Message("app", null, null, 0, Integer.MIN_VALUE, false);
  check(invalid.title.isEmpty() && invalid.text.isEmpty() && invalid.remaining(0) == 1000);
  check(new RearCastNotification.Message("app", "", "", 0, Integer.MAX_VALUE, false).remaining(0) == 120000);
  var bounded = new RearCastNotification.Message("app", "x".repeat(511) + "\ud83d\ude00" + "y".repeat(10000), "body", 0, 10, false);
  check(bounded.title.length() == 511 && !Character.isHighSurrogate(bounded.title.charAt(510)));
  List<RearCastNotification.Message> charging = new ArrayList<>(); int[] clears={0}, available={0}; long[] clearedToken={0}; String[] clearReason={null};
  RearCastNotification.Target charger=new RearCastNotification.Target(){
   public void show(RearCastNotification.Message m){charging.add(m);}
   public void clearNotification(long token,String reason){clears[0]++;clearedToken[0]=token;clearReason[0]=reason;}
   public void presentationAvailable(){available[0]++;}
  };
  RearCastNotification.attach(charger);check(RearCastNotification.hasTarget());
  long chargingToken=RearCastNotification.offerCharging("own", "Charging", "Battery 87%", 2000, false);
  check(chargingToken>0);
  check(charging.get(0).charging && charging.get(0).remaining(2000)==8000);
  check(!message.charging);
  long nextChargingToken=RearCastNotification.offerCharging("own", "Charging", "Battery 87%", 3000, false);
  check(nextChargingToken>0&&nextChargingToken!=chargingToken);
  RearCastNotification.clearCharging(chargingToken);
  check(clears[0]==0&&RearCastNotification.captureNotificationToken()==nextChargingToken);
  RearCastNotification.clearCharging(nextChargingToken);
  check(clears[0]==1&&clearedToken[0]==nextChargingToken&&"CANCELLED".equals(clearReason[0])&&available[0]==1);
  RearCastNotification.detach(charger);RearCastNotification.clearCharging(nextChargingToken);check(clears[0]==1);
  check(RearCastNotification.offerCharging("own", "Charging", "Battery 87%", 2000, false)==0);
  check(!RearCastNotification.hasTarget());
  System.out.println("PASS " + checks + " cast notification ownership/content/expiry checks");
 }
}'''
with tempfile.TemporaryDirectory() as tmp:
    test = Path(tmp) / 'CastNotificationTest.java'
    system_clock = Path(tmp) / 'android/os/SystemClock.java'
    system_clock.parent.mkdir(parents=True)
    system_clock.write_text('package android.os; public final class SystemClock { public static long elapsedRealtime() { return 0L; } }')
    test.write_text(JAVA)
    jdk = Path(os.environ.get('JAVA_HOME', ROOT / '.local/toolchains/jdk21')) / 'bin'
    source = ROOT / 'android/app/src/main/java/top/zainzh/rearscreen/RearCastNotification.java'
    subprocess.run([str(jdk / 'javac'), '-d', tmp, str(system_clock), str(source), str(test)], check=True)
    subprocess.run([str(jdk / 'java'), '-cp', tmp, 'top.zainzh.rearscreen.CastNotificationTest'], check=True)
