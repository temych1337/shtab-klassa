package ru.shtabklassa.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    private static String body(byte[] file) {
        return new String(Arrays.copyOfRange(file, 3, file.length), StandardCharsets.UTF_8);
    }

    @Test
    void fileStartsWithUtf8BomAndUsesSemicolonAndCrlf() {
        byte[] file = new CsvWriter().row("Родитель", "Ответ").row("Ирина Петрова", "Согласен").toBytes();

        assertThat(Arrays.copyOf(file, 3)).containsExactly(0xEF, 0xBB, 0xBF);
        assertThat(body(file)).isEqualTo("Родитель;Ответ\r\nИрина Петрова;Согласен\r\n");
    }

    @Test
    void separatorQuotesAndLineBreaksAreQuoted() {
        byte[] file = new CsvWriter().row("а;б", "он сказал \"да\"", "две\nстроки", "ок").toBytes();

        assertThat(body(file)).isEqualTo("\"а;б\";\"он сказал \"\"да\"\"\";\"две\nстроки\";ок\r\n");
    }

    @Test
    void formulaLikeValuesAreNeutralised() {
        assertThat(CsvWriter.escape("=HYPERLINK(\"http://evil\",\"жми\")")).isEqualTo("\"'=HYPERLINK(\"\"http://evil\"\",\"\"жми\"\")\"");
        assertThat(CsvWriter.escape("+79001234567")).isEqualTo("'+79001234567");
        assertThat(CsvWriter.escape("-1")).isEqualTo("'-1");
        assertThat(CsvWriter.escape("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(CsvWriter.escape("\t=1")).isEqualTo("'\t=1");
        assertThat(CsvWriter.escape("мама=папа")).as("= не в начале безопасно").isEqualTo("мама=папа");
    }

    @Test
    void nullAndEmptyCellsStayEmpty() {
        assertThat(body(new CsvWriter().row("a", null, "", 5).toBytes())).isEqualTo("a;;;5\r\n");
    }

    @Test
    void emptyReportIsJustBom() {
        assertThat(new CsvWriter().toBytes()).containsExactly(0xEF, 0xBB, 0xBF);
    }
}
