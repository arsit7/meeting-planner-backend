package com.meetingplanner.storage;

import com.meetingplanner.common.ApiException;
import com.meetingplanner.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Stores avatar images on the local file system under a configurable directory.
 * File names are always server-generated UUIDs; client file names are ignored entirely.
 */
@Service
public class AvatarStorageService {

    private static final Logger log = LoggerFactory.getLogger(AvatarStorageService.class);
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp");
    private static final Pattern STORED_NAME = Pattern.compile("^[0-9a-f]{32}\\.(png|jpg|gif|webp)$");
    private static final int HEADER_BYTES = 12;

    private final Path root;
    private final long maxBytes;

    @Autowired
    public AvatarStorageService(AppProperties properties) {
        this(Path.of(properties.storage().avatarDir()), properties.storage().maxAvatarBytes());
    }

    AvatarStorageService(Path root, long maxBytes) {
        this.root = root.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    /**
     * Validates and stores the upload.
     *
     * @return the generated file name to persist on the user record
     */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Please choose an image file to upload");
        }
        if (file.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Image must be at most " + (maxBytes / (1024 * 1024)) + " MB");
        }
        String declaredType = file.getContentType();
        if (declaredType == null || !ALLOWED_CONTENT_TYPES.contains(declaredType.toLowerCase(Locale.ROOT))) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Only PNG, JPEG, GIF or WebP images are allowed");
        }
        ImageType type = detectType(file)
                .orElseThrow(() -> new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "File content is not a supported image (PNG, JPEG, GIF or WebP)"));

        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + type.extension();
        Path target = resolveSafely(fileName);
        try {
            Files.createDirectories(root);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not store avatar", ex);
        }
        return fileName;
    }

    public Optional<StoredImage> load(String fileName) {
        if (fileName == null || !STORED_NAME.matcher(fileName).matches()) {
            return Optional.empty();
        }
        Path path = resolveSafely(fileName);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
        return ImageType.fromExtension(extension).map(type -> new StoredImage(path, type.mediaType()));
    }

    public void deleteQuietly(String fileName) {
        if (fileName == null || !STORED_NAME.matcher(fileName).matches()) {
            return;
        }
        try {
            Files.deleteIfExists(resolveSafely(fileName));
        } catch (IOException ex) {
            log.warn("Could not delete old avatar file");
        }
    }

    private Optional<ImageType> detectType(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return ImageType.detect(in.readNBytes(HEADER_BYTES));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    /** Defence in depth against path traversal: the resolved path must stay inside the root directory. */
    private Path resolveSafely(String fileName) {
        Path resolved = root.resolve(fileName).normalize();
        if (!resolved.getParent().equals(root)) {
            throw ApiException.badRequest("Invalid file name");
        }
        return resolved;
    }

    public record StoredImage(Path path, String mediaType) {
    }
}
