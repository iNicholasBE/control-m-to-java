package io.example.nightlyclose.batch;

import java.util.ArrayList;
import java.util.List;

/** Splits one CSV line; handles double-quoted fields (the comments column) and doubled quotes inside them. */
final class Csv {

    private Csv() {
    }

    static List<String> split(String line) {
        List<String> fields = new ArrayList<>(40);
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields;
    }
}
