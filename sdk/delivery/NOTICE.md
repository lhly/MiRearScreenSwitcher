# ReMRSS Android Platform SDK notices

The ReMRSS Android Platform SDK and its sample are distributed under the GNU General Public License, version 3. The accompanying `LICENSE` file contains the full terms.

The SDK AAR contains ReMRSS client and IPC interface code. It does not bundle the Host app, Miuix UI, libsu, Shizuku, or QuickJS. It uses Android framework and platform-provided `org.json` APIs, plus the Kotlin standard library at runtime. Kotlin consumers receive the standard library through the Kotlin Gradle Plugin; Java-only consumers must provide the Kotlin standard library themselves. No additional AndroidX or third-party runtime library is required.
