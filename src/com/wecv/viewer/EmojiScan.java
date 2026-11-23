package com.wecv.viewer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Finds emojis in a message text using the bundled Samsung-style PNG set:
// at each position it greedily builds the longest codepoint id (joined with
// "-", e.g. "2764-fe0f" or ZWJ sequences) that exists in the set. Anything
// not in the set is left as plain text and the phone font renders it.
public class EmojiScan {
    public int[] starts, ends;
    public String[] ids;

    public static EmojiScan scan(String text, Set<String> emojiIds) {
        List<Integer> st = new ArrayList<>();
        List<Integer> en = new ArrayList<>();
        List<String> id = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            int cp = text.codePointAt(i);
            // only try where an emoji can start (not ASCII text/digits/space)
            if (cp > 0x2000 || cp == 0x2764 || cp == 0x2B50 || cp == 0x203C || cp == 0x2049) {
                StringBuilder cand = new StringBuilder();
                int j = i;
                String best = null;
                int bestEnd = -1;
                int steps = 0;
                while (j < n && steps < 12) {
                    int c2 = text.codePointAt(j);
                    if (cand.length() > 0) cand.append('-');
                    cand.append(Integer.toHexString(c2));
                    j += Character.charCount(c2);
                    steps++;
                    if (emojiIds.contains(cand.toString())) {
                        best = cand.toString();
                        bestEnd = j;
                    }
                    // continue only across joiners / variation selectors / more emoji
                    if (j < n) {
                        int next = text.codePointAt(j);
                        boolean joiner = next == 0x200D || next == 0xFE0F;
                        boolean emojiish = next > 0x2000 || next == 0x2764 || next == 0x2B50;
                        if (!joiner && !emojiish) break;
                    }
                }
                if (best != null) {
                    st.add(i);
                    en.add(bestEnd);
                    id.add(best);
                    i = bestEnd;
                    continue;
                }
            }
            i += Character.charCount(cp);
        }
        EmojiScan r = new EmojiScan();
        r.starts = new int[st.size()];
        r.ends = new int[en.size()];
        r.ids = id.toArray(new String[0]);
        for (int k = 0; k < st.size(); k++) { r.starts[k] = st.get(k); r.ends[k] = en.get(k); }
        return r;
    }

    // Emoji-only message (WhatsApp shows these big): nothing but emojis and
    // whitespace, at most 4 emojis.
    public static boolean isJumbo(String text, EmojiScan s) {
        if (s.ids.length == 0 || s.ids.length > 4) return false;
        boolean[] covered = new boolean[text.length()];
        for (int k = 0; k < s.ids.length; k++)
            for (int p = s.starts[k]; p < s.ends[k]; p++) covered[p] = true;
        for (int p = 0; p < text.length(); p++) {
            if (!covered[p] && !Character.isWhitespace(text.charAt(p))) return false;
        }
        return true;
    }
}
