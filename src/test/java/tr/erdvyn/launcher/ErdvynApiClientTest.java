package tr.erdvyn.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ErdvynApiClientTest {
    @Test
    void serverTextIsCappedAndStrippedOfControlCharacters() {
        assertEquals("Line one\nLine two", ErdvynApiClient.clip("Line\u0007 one\nLine\r two", 100));
        assertEquals("abc", ErdvynApiClient.clip("abcdef", 3));
        assertEquals("", ErdvynApiClient.clip(null, 10));
    }
}
