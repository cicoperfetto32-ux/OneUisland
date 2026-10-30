package dev.flexy.dynamicisland.xposed;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class GptStream {
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private boolean sse;

    synchronized void feed(byte[] b, int off, int len, boolean decoded) {
        if (!sse && decoded) {
            String head = new String(b, off, Math.min(len, 16), StandardCharsets.ISO_8859_1);
            sse = head.startsWith("event:") || head.startsWith("data:");
        }
        if (!sse) return;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c != '\n') {
                if (pending.size() < 4_000_000) pending.write(c);
                continue;
            }
            String line = new String(pending.toByteArray(), StandardCharsets.UTF_8).trim();
            pending.reset();
            if (line.startsWith("data:")) onData(line.substring(5).trim());
        }
    }

    private void onData(String data) {
        if ("[DONE]".equals(data)) {
            AssistantReporter.finished(true);
            return;
        }
        if (data.isEmpty() || data.charAt(0) != '{') return;
        try {
            JSONObject o = new JSONObject(data);
            String type = o.optString("type");
            if ("message_marker".equals(type)) {
                String marker = o.optString("marker");
                if (!"first".equals(o.optString("event"))) return;
                if ("cot_token".equals(marker)) {
                    AssistantReporter.working();
                    AssistantReporter.stage("think", AssistantReporter.label("Thinking", "Думает"));
                } else if ("final_channel_token".equals(marker) || "user_visible_token".equals(marker)) {
                    AssistantReporter.working();
                    AssistantReporter.stage("text", AssistantReporter.label("Writing the answer", "Пишет ответ"));
                }
                return;
            }
            if ("message_stream_complete".equals(type)) {
                AssistantReporter.finished(true);
                return;
            }
            if ("input_message".equals(type)) {
                AssistantReporter.working();
                AssistantReporter.stage("user", AssistantReporter.label("Reading your message", "Читает ваше сообщение"));
                return;
            }
            Object v = o.opt("v");
            if (v instanceof JSONObject && ((JSONObject) v).has("message")) {
                onMessage(((JSONObject) v).optJSONObject("message"));
            } else if (o.has("message") && o.optJSONObject("message") != null) {
                onMessage(o.optJSONObject("message"));
            }
        } catch (Throwable ignored) {
        }
    }

    private static void onMessage(JSONObject m) {
        if (m == null) return;
        JSONObject author = m.optJSONObject("author");
        String role = author == null ? "" : author.optString("role");
        String name = author == null ? "" : author.optString("name");
        JSONObject content = m.optJSONObject("content");
        String ct = content == null ? "" : content.optString("content_type");
        JSONObject meta = m.optJSONObject("metadata");
        String query = searchQuery(meta);
        if ("tool".equals(role)) {
            AssistantReporter.working();
            if (name.startsWith("web") || name.contains("search") || name.contains("browser")) {
                AssistantReporter.stage("web", query != null ? query : AssistantReporter.label("Searching the web", "Ищет в интернете"));
            } else if (name.contains("python") || name.contains("code")) {
                AssistantReporter.stage("cmd", AssistantReporter.label("Running code", "Запускает код"));
            } else if (!name.isEmpty()) {
                AssistantReporter.stage("tool", AssistantReporter.label("Using ", "Использует ") + name.replace('_', ' ').replace('.', ' '));
            }
            return;
        }
        if (!"assistant".equals(role)) return;
        if ("thoughts".equals(ct)) {
            AssistantReporter.working();
            JSONArray thoughts = content.optJSONArray("thoughts");
            if (thoughts == null || thoughts.length() == 0) {
                AssistantReporter.stage("think", AssistantReporter.label("Thinking", "Думает"));
                return;
            }
            for (int i = 0; i < thoughts.length(); i++) {
                JSONObject t = thoughts.optJSONObject(i);
                if (t != null) AssistantReporter.stage("think", t.optString("summary"));
            }
        } else if ("reasoning_recap".equals(ct)) {
            AssistantReporter.stage("think", content.optString("content"));
        } else if ("code".equals(ct) && query != null) {
            AssistantReporter.working();
            AssistantReporter.stage("web", query);
        }
    }

    private static String searchQuery(JSONObject meta) {
        if (meta == null) return null;
        JSONArray qs = meta.optJSONArray("search_queries");
        if (qs == null || qs.length() == 0) return null;
        JSONObject q = qs.optJSONObject(0);
        String text = q != null ? q.optString("q") : qs.optString(0);
        return text == null || text.isEmpty() ? null : text;
    }

    private static String firstLine(String s) {
        if (s == null) return null;
        String t = s.trim().replaceAll("[*_`#>]+", "");
        int nl = t.indexOf('\n');
        return nl > 0 ? t.substring(0, nl) : t;
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return null;
    }
}
