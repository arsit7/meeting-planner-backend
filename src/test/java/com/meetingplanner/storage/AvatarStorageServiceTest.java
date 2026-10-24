package com.meetingplanner.storage;

import com.meetingplanner.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarStorageServiceTest {

    static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 1, 2, 3};
    private static final long MAX = 1024;

    @TempDir
    Path tempDir;

    private AvatarStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new AvatarStorageService(tempDir, MAX);
    }

    @Test
    void storesValidPngUnderGeneratedNameIgnoringClientFileName() throws Exception {
        var file = new MockMultipartFile("file", "../../etc/passwd.png", "image/png", PNG_BYTES);

        String stored = storage.store(file);

        assertThat(stored).matches("[0-9a-f]{32}\\.png");
        assertThat(Files.readAllBytes(tempDir.resolve(stored))).isEqualTo(PNG_BYTES);
        assertThat(storage.load(stored)).hasValueSatisfying(img -> assertThat(img.mediaType()).isEqualTo("image/png"));
    }

    @Test
    void rejectsDisallowedContentType() {
        var file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        assertStatus(file, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void rejectsSpoofedImageWhoseBytesAreNotAnImage() {
        var file = new MockMultipartFile("file", "evil.png", "image/png", "<script>alert(1)</script>".getBytes());

        assertStatus(file, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void rejectsOversizedFile() {
        byte[] big = new byte[(int) MAX + 1];
        System.arraycopy(PNG_BYTES, 0, big, 0, PNG_BYTES.length);
        var file = new MockMultipartFile("file", "big.png", "image/png", big);

        assertStatus(file, HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void rejectsEmptyFile() {
        var file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

        assertStatus(file, HttpStatus.BAD_REQUEST);
    }

    @Test
    void loadRejectsTraversalAndUnexpectedNames() {
        assertThat(storage.load("../secret.png")).isEmpty();
        assertThat(storage.load("..\\secret.png")).isEmpty();
        assertThat(storage.load("abc.exe")).isEmpty();
        assertThat(storage.load(null)).isEmpty();
    }

    private void assertStatus(MockMultipartFile file, HttpStatus expected) {
        assertThatThrownBy(() -> storage.store(file))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(expected));
        assertThat(tempDir.toFile().list()).isNullOrEmpty();
    }
}
