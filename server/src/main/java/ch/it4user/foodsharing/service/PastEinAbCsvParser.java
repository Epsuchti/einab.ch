package ch.it4user.foodsharing.service;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 CSV reader (quoted cells, embedded line breaks, comma / semicolon / tab separated). */
final class PastEinAbCsvParser {

    private PastEinAbCsvParser() {
    }

    static List<List<String>> parse(String text) {
        String input = text == null ? "" : text;
        if (!input.isEmpty() && input.charAt(0) == '﻿') {
            input = input.substring(1);
        }
        char delimiter = detectDelimiter(input);
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                records.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            records.add(row);
        }
        return records;
    }

    /** Counts the candidate separators of the first record (outside quotes) and takes the most frequent one. */
    private static char detectDelimiter(String input) {
        int comma = 0;
        int semicolon = 0;
        int tab = 0;
        boolean quoted = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted) {
                if (c == '\n' || c == '\r') {
                    break;
                }
                if (c == ',') {
                    comma++;
                } else if (c == ';') {
                    semicolon++;
                } else if (c == '\t') {
                    tab++;
                }
            }
        }
        if (tab > comma && tab > semicolon) {
            return '\t';
        }
        return semicolon > comma ? ';' : ',';
    }
}
