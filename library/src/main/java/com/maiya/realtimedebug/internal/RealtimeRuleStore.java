package com.maiya.realtimedebug.internal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory mock / rewrite / throttle config pushed from PC console.
 */
public final class RealtimeRuleStore {

    private static final RealtimeRuleStore INSTANCE = new RealtimeRuleStore();

    private final CopyOnWriteArrayList<MockRule> rules = new CopyOnWriteArrayList<>();
    private final AtomicReference<ThrottleConfig> throttle =
            new AtomicReference<>(ThrottleConfig.none());

    private RealtimeRuleStore() {
    }

    public static RealtimeRuleStore get() {
        return INSTANCE;
    }

    public void setRules(List<MockRule> next) {
        rules.clear();
        if (next != null) {
            List<MockRule> sorted = new ArrayList<>(next);
            Collections.sort(sorted, (a, b) -> Integer.compare(b.priority, a.priority));
            rules.addAll(sorted);
        }
    }

    public void setRulesFromJson(JSONArray arr) {
        List<MockRule> list = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    list.add(MockRule.fromJson(o));
                }
            }
        }
        setRules(list);
    }

    public List<MockRule> snapshotRules() {
        return new ArrayList<>(rules);
    }

    public JSONArray rulesToJson() {
        JSONArray arr = new JSONArray();
        for (MockRule r : rules) {
            arr.put(r.toJson());
        }
        return arr;
    }

    public void setThrottle(ThrottleConfig cfg) {
        throttle.set(cfg != null ? cfg : ThrottleConfig.none());
    }

    public void setThrottleFromJson(JSONObject o) {
        setThrottle(ThrottleConfig.fromJson(o));
    }

    public ThrottleConfig getThrottle() {
        return throttle.get();
    }

    public MockRule match(String method, String url) {
        String m = method == null ? "" : method.toUpperCase();
        String u = url == null ? "" : url;
        for (MockRule rule : rules) {
            if (!rule.enabled) {
                continue;
            }
            if (!rule.matches(m, u)) {
                continue;
            }
            if (!rule.consumeHit()) {
                continue;
            }
            return rule;
        }
        return null;
    }

    public JSONObject configSnapshot() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "config");
            o.put("rules", rulesToJson());
            o.put("throttle", getThrottle().toJson());
        } catch (Exception ignored) {
        }
        return o;
    }

    public static final class ThrottleConfig {
        public final String profile;
        public final int delayMs;
        public final int failPercent;

        public ThrottleConfig(String profile, int delayMs, int failPercent) {
            this.profile = profile == null ? "none" : profile;
            this.delayMs = Math.max(0, delayMs);
            this.failPercent = Math.max(0, Math.min(100, failPercent));
        }

        public static ThrottleConfig none() {
            return new ThrottleConfig("none", 0, 0);
        }

        public static ThrottleConfig fromJson(JSONObject o) {
            if (o == null) {
                return none();
            }
            return new ThrottleConfig(
                    o.optString("profile", "none"),
                    o.optInt("delayMs", 0),
                    o.optInt("failPercent", 0)
            );
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("profile", profile);
                o.put("delayMs", delayMs);
                o.put("failPercent", failPercent);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    public static final class MockRule {
        public final String id;
        public final String group;
        public final boolean enabled;
        public final int priority;
        public final String method;
        public final String urlContains;
        /** mock | abort | rewrite | modify(legacy→mock) */
        public final String action;
        public final int delayMs;
        public final int statusCode;
        public final String responseBody;
        public final String contentType;
        public final AtomicInteger remaining; // -1 = infinite

        /** rewrite: optional new URL (empty = keep) */
        public final String rewriteUrl;
        /** rewrite: optional new method (empty = keep) */
        public final String rewriteMethod;
        /** rewrite: optional new body; null means keep original */
        public final String rewriteBody;
        public final boolean rewriteBodySet;
        /** rewrite: headers to set/overwrite */
        public final Map<String, String> setHeaders;
        /** rewrite: header names to remove */
        public final List<String> removeHeaders;

        public MockRule(String id, String group, boolean enabled, int priority, String method,
                        String urlContains, String action, int delayMs, int statusCode,
                        String responseBody, String contentType, int times,
                        String rewriteUrl, String rewriteMethod, String rewriteBody,
                        boolean rewriteBodySet, Map<String, String> setHeaders,
                        List<String> removeHeaders) {
            this.id = id == null ? "" : id;
            this.group = group == null ? "" : group;
            this.enabled = enabled;
            this.priority = priority;
            this.method = method == null ? "" : method.trim().toUpperCase();
            this.urlContains = urlContains == null ? "" : urlContains;
            String act = action == null ? "mock" : action;
            if ("modify".equalsIgnoreCase(act)) {
                act = "mock";
            }
            this.action = act;
            this.delayMs = Math.max(0, delayMs);
            this.statusCode = statusCode <= 0 ? 200 : statusCode;
            this.responseBody = responseBody == null ? "" : responseBody;
            this.contentType = contentType == null || contentType.isEmpty()
                    ? "application/json; charset=utf-8" : contentType;
            this.remaining = new AtomicInteger(times);
            this.rewriteUrl = rewriteUrl == null ? "" : rewriteUrl;
            this.rewriteMethod = rewriteMethod == null ? "" : rewriteMethod.trim().toUpperCase();
            this.rewriteBody = rewriteBody;
            this.rewriteBodySet = rewriteBodySet;
            this.setHeaders = setHeaders == null
                    ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(setHeaders));
            this.removeHeaders = removeHeaders == null
                    ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(removeHeaders));
        }

        public static MockRule fromJson(JSONObject o) {
            Map<String, String> setHeaders = new LinkedHashMap<>();
            JSONObject headersObj = o.optJSONObject("setHeaders");
            if (headersObj == null) {
                headersObj = o.optJSONObject("rewriteHeaders");
            }
            if (headersObj != null) {
                Iterator<String> keys = headersObj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    setHeaders.put(k, headersObj.optString(k, ""));
                }
            }
            List<String> removeHeaders = new ArrayList<>();
            JSONArray rem = o.optJSONArray("removeHeaders");
            if (rem != null) {
                for (int i = 0; i < rem.length(); i++) {
                    String name = rem.optString(i, "");
                    if (!name.isEmpty()) {
                        removeHeaders.add(name);
                    }
                }
            }
            boolean bodySet = o.has("rewriteBody");
            return new MockRule(
                    o.optString("id", "r" + System.currentTimeMillis()),
                    o.optString("group", ""),
                    o.optBoolean("enabled", true),
                    o.optInt("priority", 0),
                    o.optString("method", ""),
                    o.optString("urlContains", o.optString("url", "")),
                    o.optString("action", "mock"),
                    o.optInt("delayMs", 0),
                    o.optInt("statusCode", 200),
                    o.optString("responseBody", o.optString("body", "")),
                    o.optString("contentType", "application/json; charset=utf-8"),
                    o.optInt("times", -1),
                    o.optString("rewriteUrl", ""),
                    o.optString("rewriteMethod", ""),
                    bodySet ? o.optString("rewriteBody", "") : null,
                    bodySet,
                    setHeaders,
                    removeHeaders
            );
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("group", group);
                o.put("enabled", enabled);
                o.put("priority", priority);
                o.put("method", method);
                o.put("urlContains", urlContains);
                o.put("action", action);
                o.put("delayMs", delayMs);
                o.put("statusCode", statusCode);
                o.put("responseBody", responseBody);
                o.put("contentType", contentType);
                o.put("times", remaining.get());
                o.put("rewriteUrl", rewriteUrl);
                o.put("rewriteMethod", rewriteMethod);
                if (rewriteBodySet) {
                    o.put("rewriteBody", rewriteBody == null ? "" : rewriteBody);
                }
                JSONObject headers = new JSONObject();
                for (Map.Entry<String, String> e : setHeaders.entrySet()) {
                    headers.put(e.getKey(), e.getValue());
                }
                o.put("setHeaders", headers);
                JSONArray rem = new JSONArray();
                for (String name : removeHeaders) {
                    rem.put(name);
                }
                o.put("removeHeaders", rem);
            } catch (Exception ignored) {
            }
            return o;
        }

        boolean matches(String methodUpper, String url) {
            if (!method.isEmpty() && !method.equals(methodUpper)) {
                return false;
            }
            return urlContains.isEmpty() || url.contains(urlContains);
        }

        boolean consumeHit() {
            int left = remaining.get();
            if (left < 0) {
                return true;
            }
            while (true) {
                int cur = remaining.get();
                if (cur == 0) {
                    return false;
                }
                if (cur < 0) {
                    return true;
                }
                if (remaining.compareAndSet(cur, cur - 1)) {
                    return true;
                }
            }
        }
    }
}
