package com.wecv.viewer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Pure-Java WhatsApp export parser (no Android imports, so it can be unit
// tested on a JVM). Handles the Android format
//   8/20/23, 5:56 PM - Sender: body
// and the iOS format
//   [20/08/23, 5:56:54 PM] Sender: body
// in both day-first and month-first date orders (decided per file by
// majority vote), 12h and 24h times, continuation lines, media markers
// ("(file attached)", "<attached: ...>", "<Media omitted>") and the
// empty-body lines WhatsApp writes for calls.
public class ChatParser {
    public static class PMsg {
        public String iso, dateLabel, time;
        public String sender; // null = system row
        public String kind;   // text photo video audio sticker doc docmiss call sys
        public String file;
        public String text = "";
        public boolean omitted, waDeleted;
    }

    public static class Result {
        public List<PMsg> msgs = new ArrayList<>();
        public LinkedHashMap<String, Integer> senders = new LinkedHashMap<>();
    }

    static final Pattern P_ANDROID = Pattern.compile(
            "^(\\d{1,2})/(\\d{1,2})/(\\d{2,4}), (\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*([AaPp]\\.?[Mm]\\.?)? - (.*)$");
    static final Pattern P_IOS = Pattern.compile(
            "^\\[(\\d{1,2})/(\\d{1,2})/(\\d{2,4}), (\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*([AaPp]\\.?[Mm]\\.?)?\\] (.*)$");
    static final Pattern P_ATTACHED = Pattern.compile("^<attached: (.+)>$");
    static final Pattern P_FILE_ATTACHED = Pattern.compile("^([^\\s]+\\.[A-Za-z0-9]{2,5}) \\(file attached\\)\\s*(.*)$");
    static final Pattern P_FILENAME = Pattern.compile("^[^\\s]+\\.(jpg|jpeg|png|gif|webp|mp4|mov|3gp|opus|aac|mp3|m4a|ogg|vcf|pdf|docx?|zip)$", Pattern.CASE_INSENSITIVE);

    static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    public static String kindForExt(String name) {
        String f = name.toLowerCase(Locale.US);
        if (f.endsWith(".jpg") || f.endsWith(".jpeg") || f.endsWith(".png") || f.endsWith(".gif")) return "photo";
        if (f.endsWith(".webp")) return "sticker";
        if (f.endsWith(".mp4") || f.endsWith(".mov") || f.endsWith(".3gp")) return "video";
        if (f.endsWith(".opus") || f.endsWith(".aac") || f.endsWith(".mp3") || f.endsWith(".m4a") || f.endsWith(".ogg")) return "audio";
        return "doc";
    }

    static String clean(String s) {
        return s.replace('\u202F', ' ').replace('\u00A0', ' ').replace("\u200E", "").replace("\u200F", "");
    }

    public static Result parse(List<String> rawLines, Set<String> mediaFiles) {
        // first pass: decide date order by majority vote
        int voteDMY = 0, voteMDY = 0;
        boolean anyAmPm = false;
        for (String raw : rawLines) {
            String line = clean(raw);
            Matcher m = P_ANDROID.matcher(line);
            if (!m.matches()) { m = P_IOS.matcher(line); if (!m.matches()) continue; }
            int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
            if (a > 12) voteDMY++;
            if (b > 12) voteMDY++;
            if (m.group(7) != null) anyAmPm = true;
        }
        boolean dayFirst = voteDMY > voteMDY || (voteDMY == voteMDY && !anyAmPm);

        Result res = new Result();
        PMsg cur = null;
        for (String raw : rawLines) {
            String line = clean(raw);
            Matcher m = P_ANDROID.matcher(line);
            boolean matched = m.matches();
            if (!matched) { m = P_IOS.matcher(line); matched = m.matches(); }
            if (!matched) {
                // continuation line: belongs to the previous message
                if (cur != null && cur.sender != null) {
                    String extra = line.trim();
                    // a bare media filename right after a "<Media omitted>" is its caption
                    if (cur.omitted && cur.text.length() == 0 && P_FILENAME.matcher(extra).matches()
                            && !mediaFiles.contains(extra)) {
                        cur.text = extra;
                    } else {
                        cur.text = cur.text.length() == 0 ? line : cur.text + "\n" + line;
                    }
                }
                continue;
            }
            int a = Integer.parseInt(m.group(1)), b = Integer.parseInt(m.group(2));
            int day = dayFirst ? a : b;
            int mon = dayFirst ? b : a;
            int year = Integer.parseInt(m.group(3));
            if (year < 100) year += 2000;
            int hh = Integer.parseInt(m.group(4));
            int mm = Integer.parseInt(m.group(5));
            String ampm = m.group(7);
            String rest = m.group(8);

            PMsg msg = new PMsg();
            msg.iso = String.format(Locale.US, "%04d-%02d-%02d", year, mon, day);
            msg.dateLabel = day + " " + MONTHS[mon - 1] + " " + year;
            if (ampm != null) {
                String ap = ampm.toUpperCase(Locale.US).startsWith("P") ? "PM" : "AM";
                msg.time = hh + ":" + String.format(Locale.US, "%02d", mm) + " " + ap;
            } else {
                int h12 = hh % 12 == 0 ? 12 : hh % 12;
                msg.time = h12 + ":" + String.format(Locale.US, "%02d", mm) + " " + (hh < 12 ? "AM" : "PM");
            }

            int colon = rest.indexOf(": ");
            String sender = null, body = rest;
            if (colon > 0 && colon <= 60) {
                String cand = rest.substring(0, colon).trim();
                if (cand.length() > 0 && !cand.contains("http")) {
                    sender = cand;
                    body = rest.substring(colon + 2);
                }
            }
            if (sender == null) {
                msg.kind = "sys";
                msg.text = rest.trim();
                res.msgs.add(msg);
                cur = msg;
                continue;
            }
            msg.sender = sender;
            Integer cnt = res.senders.get(sender);
            res.senders.put(sender, cnt == null ? 1 : cnt + 1);
            body = body.trim();

            if (body.length() == 0) {
                msg.kind = "call"; // WhatsApp writes call entries with an empty body
            } else if (body.equals("<Media omitted>")) {
                msg.kind = "text";
                msg.omitted = true;
            } else if (body.equals("This message was deleted") || body.equals("You deleted this message")) {
                msg.kind = "text";
                msg.waDeleted = true;
            } else {
                Matcher ma = P_ATTACHED.matcher(body);
                Matcher mf = P_FILE_ATTACHED.matcher(body);
                if (ma.matches()) {
                    msg.file = ma.group(1).trim();
                    msg.kind = kindForExt(msg.file);
                    if (msg.kind.equals("doc") && !mediaFiles.contains(msg.file)) msg.kind = "docmiss";
                } else if (mf.matches()) {
                    msg.file = mf.group(1);
                    msg.kind = kindForExt(msg.file);
                    if (msg.kind.equals("doc") && !mediaFiles.contains(msg.file)) msg.kind = "docmiss";
                    String cap = mf.group(2).trim();
                    if (cap.length() > 0) msg.text = cap;
                } else if (mediaFiles.contains(body)) {
                    msg.file = body;
                    msg.kind = kindForExt(body);
                } else if (P_FILENAME.matcher(body).matches() && cur != null && cur.omitted && cur.sender != null) {
                    // caption filename under a "<Media omitted>" entry: fold it
                    // into the previous message instead of adding a new one
                    cur.text = body;
                    int c = res.senders.get(sender) - 1;
                    if (c == 0) res.senders.remove(sender); else res.senders.put(sender, c);
                    continue;
                } else {
                    msg.kind = "text";
                    msg.text = body;
                }
            }
            res.msgs.add(msg);
            cur = msg;
        }
        return res;
    }
}
