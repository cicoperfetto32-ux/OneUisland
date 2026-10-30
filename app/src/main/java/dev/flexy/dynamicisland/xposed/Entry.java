package dev.flexy.dynamicisland.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class Entry implements IXposedHookLoadPackage {
    private static boolean systemUiInstalled;
    private static boolean voiceInstalled;
    private static boolean claudeInstalled;
    private static boolean gptInstalled;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.android.systemui".equals(lpparam.packageName)
                && "com.android.systemui".equals(lpparam.processName)
                && !systemUiInstalled) {
            systemUiInstalled = true;
            try {
                SystemUiHooks.install(lpparam.classLoader);
            } catch (Throwable t) {
                XLog.e("install failed", t);
            }
        }
        if (VoiceRecorderHook.PACKAGE.equals(lpparam.packageName)
                && VoiceRecorderHook.PACKAGE.equals(lpparam.processName)
                && !voiceInstalled) {
            voiceInstalled = true;
            try {
                VoiceRecorderHook.install(lpparam.classLoader);
            } catch (Throwable t) {
                XLog.e("voice recorder install failed", t);
            }
        }
        if (ClaudeHook.PACKAGE.equals(lpparam.packageName)
                && ClaudeHook.PACKAGE.equals(lpparam.processName)
                && !claudeInstalled) {
            claudeInstalled = true;
            try {
                ClaudeHook.install(lpparam.classLoader);
            } catch (Throwable t) {
                XLog.e("claude install failed", t);
            }
        }
        if (GptHook.PACKAGE.equals(lpparam.packageName)
                && GptHook.PACKAGE.equals(lpparam.processName)
                && !gptInstalled) {
            gptInstalled = true;
            try {
                GptHook.install(lpparam.classLoader);
            } catch (Throwable t) {
                XLog.e("chatgpt install failed", t);
            }
        }

    }
}
