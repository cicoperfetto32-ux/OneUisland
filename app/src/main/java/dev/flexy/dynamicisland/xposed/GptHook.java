package dev.flexy.dynamicisland.xposed;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class GptHook {
    static final String PACKAGE = "com.openai.chatgpt";
    private static final Map<Object, GptStream> STREAMS = Collections.synchronizedMap(new WeakHashMap<>());

    private GptHook() {
    }

    static void install(ClassLoader cl) {
        AssistantReporter.app = "chatgpt";
        int n = 0;
        for (Method m : java.util.zip.Inflater.class.getDeclaredMethods()) {
            if (!"inflate".equals(m.getName()) || m.getParameterTypes().length != 3
                    || m.getParameterTypes()[0] != byte[].class) continue;
            XposedBridge.hookMethod(m, reader(true));
            n++;
        }
        XLog.i("chatgpt hooks: " + n);
    }

    private static XC_MethodHook reader(boolean decoded) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Object r = p.getResult();
                    if (!(r instanceof Integer) || (Integer) r <= 0 || !(p.args[0] instanceof byte[])) return;
                    GptStream s = STREAMS.get(p.thisObject);
                    if (s == null) {
                        s = new GptStream();
                        STREAMS.put(p.thisObject, s);
                    }
                    s.feed((byte[]) p.args[0], (Integer) p.args[1], (Integer) r, decoded);
                } catch (Throwable ignored) {
                }
            }
        };
    }
}
