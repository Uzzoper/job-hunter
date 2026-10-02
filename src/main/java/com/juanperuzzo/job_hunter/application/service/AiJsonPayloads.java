package com.juanperuzzo.job_hunter.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Locates the JSON payload inside a raw AI completion.
 *
 * <p>Reasoning models emit deliberation before the answer, and that deliberation may quote a full
 * copy of the requested schema (for example {@code <think>I first drafted {"matchScore": 10, ...}
 * but rejected it</think>}). Slicing from the first {@code {} to the last {@code }} therefore picks
 * the wrong object, so consumers anchor on the field their contract requires and keep the
 * <em>last</em> matching object — deliberation comes first, the answer last.
 */
final class AiJsonPayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiJsonPayloads() {
    }

    /**
     * Returns the last balanced JSON object in {@code raw} that carries {@code requiredField},
     * falling back to the first object found so malformed payloads keep failing with the caller's
     * own error message instead of a generic one. Returns {@code null} when no object exists.
     */
    static String firstObjectWithField(String raw, String requiredField) {
        String firstObject = null;
        String lastWithField = null;
        for (String candidate : objectsIn(raw)) {
            if (firstObject == null) {
                firstObject = candidate;
            }
            try {
                if (MAPPER.readTree(candidate) instanceof ObjectNode node && node.has(requiredField)) {
                    lastWithField = candidate;
                }
            } catch (Exception e) {
                // malformed candidate: keep scanning, the caller reports the failure
            }
        }
        return lastWithField != null ? lastWithField : firstObject;
    }

    /** Returns every balanced {@code {...}} object in {@code raw}, markdown fences removed. */
    private static List<String> objectsIn(String raw) {
        String text = raw == null ? "" : raw.strip().replaceAll("```[a-zA-Z]*\\s*|```\\s*", "").strip();
        var objects = new ArrayList<String>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(text.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }
}