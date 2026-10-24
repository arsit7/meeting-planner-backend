package com.meetingplanner.storage;

import java.util.Arrays;
import java.util.Optional;

/**
 * Supported avatar formats, identified by their file signature ("magic bytes") rather than by the
 * client-supplied file name or Content-Type, which are trivially spoofed.
 */
public enum ImageType {

    PNG("image/png", "png", new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}),
    JPEG("image/jpeg", "jpg", new int[]{0xFF, 0xD8, 0xFF}),
    GIF("image/gif", "gif", new int[]{0x47, 0x49, 0x46, 0x38}),
    WEBP("image/webp", "webp", new int[]{0x52, 0x49, 0x46, 0x46, -1, -1, -1, -1, 0x57, 0x45, 0x42, 0x50});

    private final String mediaType;
    private final String extension;
    /** Expected header bytes; -1 matches any byte. */
    private final int[] signature;

    ImageType(String mediaType, String extension, int[] signature) {
        this.mediaType = mediaType;
        this.extension = extension;
        this.signature = signature;
    }

    public String mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }

    public static Optional<ImageType> detect(byte[] header) {
        return Arrays.stream(values()).filter(t -> t.matches(header)).findFirst();
    }

    public static Optional<ImageType> fromExtension(String extension) {
        return Arrays.stream(values()).filter(t -> t.extension.equalsIgnoreCase(extension)).findFirst();
    }

    private boolean matches(byte[] header) {
        if (header.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (signature[i] != -1 && (header[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
