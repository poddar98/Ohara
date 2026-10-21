package org.example.ohara.organization.application;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 reader: comma-separated, double-quoted fields with "" escapes, CRLF or LF, optional UTF-8 BOM. */
final class CsvParser {

    static final class MalformedCsvException extends RuntimeException {
        MalformedCsvException(String message) {
            super(message);
        }
    }

    private CsvParser() {
    }

    /** Records in file order; blank lines are skipped. */
    static List<List<String>> parse(String content) {
        String text = content.startsWith("﻿") ? content.substring(1) : content;
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;
        int line = 1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
            } else if (c == '"') {
                if (fieldStarted && !field.isEmpty()) {
                    throw new MalformedCsvException("Unexpected quote on line " + line);
                }
                quoted = true;
                fieldStarted = true;
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
                fieldStarted = false;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                endRecord(records, record, field);
                record = new ArrayList<>();
                fieldStarted = false;
                line++;
            } else {
                field.append(c);
                fieldStarted = true;
            }
        }
        if (quoted) {
            throw new MalformedCsvException("Unterminated quoted field starting before line " + line);
        }
        endRecord(records, record, field);
        return records;
    }

    private static void endRecord(List<List<String>> records, List<String> record, StringBuilder field) {
        record.add(field.toString());
        field.setLength(0);
        boolean blank = record.stream().allMatch(String::isBlank);
        if (!blank) {
            records.add(record);
        }
    }
}
