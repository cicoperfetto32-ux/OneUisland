package dev.flexy.dynamicisland.xposed;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

final class ClaudeStream {
    private final java.io.ByteArrayOutputStream pending = new java.io.ByteArrayOutputStream();
    private final StringBuilder toolInput = new StringBuilder();
    private String toolName;
    private String question;

    ClaudeStream() {
        AssistantReporter.working();
        AssistantReporter.stage("think", AssistantReporter.label("Thinking", "Думает"));
    }

    static boolean interesting(String url) {
        return url.toLowerCase(Locale.ROOT).contains("/completion");
    }

    synchronized void feed(byte[] chunk) {
        for (byte b : chunk) {
            if (b != '\n') {
                pending.write(b);
                continue;
            }
            String line = new String(pending.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim();
            pending.reset();
            if (line.startsWith("data:")) onData(line.substring(5).trim());
        }
    }

    synchronized void finish(boolean failed) {
        if (question != null) AssistantReporter.pendingQuestion(question);
        AssistantReporter.finished(!failed);
    }

    private void onData(String data) {
        if (data.isEmpty() || data.charAt(0) != '{') return;
        try {
            JSONObject o = new JSONObject(data);
            switch (o.optString("type")) {
                case "content_block_start":
                    onBlockStart(o.optJSONObject("content_block"));
                    break;
                case "content_block_delta":
                    onDelta(o.optJSONObject("delta"));
                    break;
                case "content_block_stop":
                    onBlockStop();
                    break;
                default:
                    break;
            }
        } catch (Throwable ignored) {
        }
    }

    private void onBlockStart(JSONObject block) {
        if (block == null) return;
        String type = block.optString("type");
        if ("tool_use".equals(type) || "server_tool_use".equals(type)) {
            toolName = block.optString("name", null);
            toolInput.setLength(0);
            String label = first(block, "message", "display_name");
            AssistantReporter.stage(toolKind(toolName), label != null ? label : toolLabel(toolName));
        } else if ("text".equals(type)) {
            AssistantReporter.stage("text", AssistantReporter.label("Writing the answer", "Пишет ответ"));
        } else if ("thinking".equals(type)) {
            AssistantReporter.stage("think", AssistantReporter.label("Thinking", "Думает"));
            JSONArray summaries = block.optJSONArray("summaries");
            if (summaries == null) return;
            for (int i = 0; i < summaries.length(); i++) {
                JSONObject s = summaries.optJSONObject(i);
                if (s != null) AssistantReporter.stage("think", s.optString("summary"));
            }
        }
    }

    private void onDelta(JSONObject delta) {
        if (delta == null) return;
        String type = delta.optString("type");
        if ("thinking_summary_delta".equals(type)) {
            JSONObject s = delta.optJSONObject("summary");
            AssistantReporter.stage("think", s != null ? s.optString("summary") : delta.optString("summary"));
        } else if ("input_json_delta".equals(type) && toolName != null && toolInput.length() < 20000) {
            toolInput.append(delta.optString("partial_json"));
        }
    }

    private void onBlockStop() {
        if (toolName == null) return;
        String n = toolName.toLowerCase(Locale.ROOT);
        if (n.contains("ask_user") || n.contains("askuser")) {
            try {
                JSONObject in = new JSONObject(toolInput.toString());
                String q = first(in, "question", "prompt");
                JSONArray qs = in.optJSONArray("questions");
                if (q == null && qs != null && qs.length() > 0) {
                    JSONObject f = qs.optJSONObject(0);
                    q = f != null ? first(f, "question") : qs.optString(0);
                }
                if (q != null) question = q;
            } catch (Throwable ignored) {
            }
        }
        toolName = null;
        toolInput.setLength(0);
    }

    private static String toolKind(String name) {
        if (name == null) return "tool";
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("search") || n.contains("fetch")) return "web";
        if (n.contains("artifact")) return "artifact";
        if (n.contains("repl") || n.contains("analysis") || n.contains("bash")) return "cmd";
        return "tool";
    }

    private static String toolLabel(String name) {
        if (name == null) return AssistantReporter.label("Using a tool", "Использует инструмент");
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("search")) return AssistantReporter.label("Searching the web", "Ищет в интернете");
        if (n.contains("fetch")) return AssistantReporter.label("Reading a page", "Читает страницу");
        if (n.contains("artifact") || n.contains("create") || n.contains("edit")) return AssistantReporter.label("Writing", "Пишет");
        if (n.contains("repl") || n.contains("analysis") || n.contains("bash")) return AssistantReporter.label("Running code", "Запускает код");
        return AssistantReporter.label("Using ", "Использует ") + name.replace('_', ' ');
    }

    private static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return null;
    }
}
