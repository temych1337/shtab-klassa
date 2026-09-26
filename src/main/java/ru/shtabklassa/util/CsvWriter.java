package ru.shtabklassa.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/*
 * Под русский эксель: BOM (без него откроет как cp1251 - кракозябры), разделитель ; (с запятой всё в одну колонку), \r\n.
 * Свободные ответы родителей могут начинаться с = + - @ и эксель выполнит это как формулу (=HYPERLINK...),
 * поэтому таким ставим ' впереди.
 */
public final class CsvWriter {

    private static final char SEPARATOR = ';';
    private static final String LINE_END = "\r\n";
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final StringBuilder csv = new StringBuilder();

    public CsvWriter row(Object... cells) {
        return row(Arrays.asList(cells));
    }

    public CsvWriter row(List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                csv.append(SEPARATOR);
            }
            Object cell = cells.get(i);
            csv.append(cell == null ? "" : escape(cell.toString()));
        }
        csv.append(LINE_END);
        return this;
    }

    public byte[] toBytes() {
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] file = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, file, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, file, UTF8_BOM.length, body.length);
        return file;
    }

    static String escape(String value) {
        String safe = value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        boolean needsQuotes = safe.indexOf(SEPARATOR) >= 0 || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0;
        return needsQuotes ? '"' + safe.replace("\"", "\"\"") + '"' : safe;
    }
}
