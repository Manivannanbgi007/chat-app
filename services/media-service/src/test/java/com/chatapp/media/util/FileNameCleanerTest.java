package com.chatapp.media.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FileNameCleanerTest {

    @Test
    void normalNamesStayAsTheyAre() {
        assertThat(FileNameCleaner.clean("holiday photo (1).jpg")).isEqualTo("holiday photo (1).jpg");
        assertThat(FileNameCleaner.clean("\u0928\u092E\u0938\u094D\u0924\u0947.pdf")).isEqualTo("\u0928\u092E\u0938\u094D\u0924\u0947.pdf");
    }

    @Test
    void onlyTheLastPartOfAPathIsKept() {
        assertThat(FileNameCleaner.clean("../../etc/passwd.png")).isEqualTo("passwd.png");
        assertThat(FileNameCleaner.clean("C:\\Users\\me\\secret\\report.docx")).isEqualTo("report.docx");
        assertThat(FileNameCleaner.clean("/var/www/x.txt")).isEqualTo("x.txt");
    }

    @Test
    void controlAndInvisibleCharactersAreRemoved() {
        assertThat(FileNameCleaner.clean("a\u0000b\nc\td.txt")).isEqualTo("abcd.txt");
        // the right-to-left override makes "photo\u202Egpj.exe" LOOK like "photoexe.jpg"
        assertThat(FileNameCleaner.clean("photo\u202Egpj.exe")).isEqualTo("photogpj.exe");
        assertThat(FileNameCleaner.clean("zero\u200Bwidth.txt")).isEqualTo("zerowidth.txt");
    }

    @Test
    void charactersThatAreSpecialInFileSystemsAreRemoved() {
        assertThat(FileNameCleaner.clean("a<b>c:d\"e|f?g*h.txt")).isEqualTo("abcdefgh.txt");
    }

    @Test
    void emptyOrUselessNamesBecomeFile() {
        assertThat(FileNameCleaner.clean(null)).isEqualTo("file");
        assertThat(FileNameCleaner.clean("")).isEqualTo("file");
        assertThat(FileNameCleaner.clean("   ")).isEqualTo("file");
        assertThat(FileNameCleaner.clean("..")).isEqualTo("file");
        assertThat(FileNameCleaner.clean(".")).isEqualTo("file");
        assertThat(FileNameCleaner.clean("a/")).isEqualTo("file");
        assertThat(FileNameCleaner.clean("<>")).isEqualTo("file");
    }

    @Test
    void longNamesAreCutButKeepTheirExtension() {
        String name = "x".repeat(300) + ".jpeg";
        String cleaned = FileNameCleaner.clean(name);
        assertThat(cleaned).hasSize(150).endsWith(".jpeg").startsWith("xxx");
    }

    @Test
    void aLongNameWithoutExtensionIsJustCut() {
        assertThat(FileNameCleaner.clean("y".repeat(400))).hasSize(150);
    }

    @Test
    void cuttingNeverSplitsASurrogatePair() {
        String emoji = "\uD83D\uDE00";
        String cleaned = FileNameCleaner.clean(emoji.repeat(200) + ".png");
        assertThat(cleaned.codePointCount(0, cleaned.length())).isEqualTo(150);
        assertThat(cleaned).endsWith(".png").doesNotContain("\uFFFD");
        assertThat(Character.isHighSurrogate(cleaned.charAt(cleaned.length() - 5))).isFalse();
    }

    @Test
    void extensionIsLowerCaseWithoutDot() {
        assertThat(FileNameCleaner.extension("photo.JPG")).isEqualTo("jpg");
        assertThat(FileNameCleaner.extension("archive.tar.gz")).isEqualTo("gz");
        assertThat(FileNameCleaner.extension("noext")).isEmpty();
        assertThat(FileNameCleaner.extension("trailing.")).isEmpty();
    }
}
