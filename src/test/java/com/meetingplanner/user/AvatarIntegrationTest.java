package com.meetingplanner.user;

import com.meetingplanner.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AvatarIntegrationTest extends IntegrationTestSupport {

    private static final byte[] PNG_BYTES =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 1, 2, 3};

    private String token;

    @BeforeEach
    void setUpUser() throws Exception {
        token = tokenFor("Alice", "alice@example.com");
    }

    @Test
    void uploadValidImageThenDownloadIt() throws Exception {
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.avatarUrl").doesNotExist());

        mockMvc.perform(withToken(multipart("/api/users/me/avatar")
                        .file(new MockMultipartFile("file", "me.png", "image/png", PNG_BYTES)), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").value(org.hamcrest.Matchers.startsWith("/api/users/me/avatar?v=")));

        String stored = userRepository.findByEmail("alice@example.com").orElseThrow().getProfileImagePath();
        assertThat(stored).matches("[0-9a-f]{32}\\.png");

        mockMvc.perform(withToken(get("/api/users/me/avatar"), token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(content().bytes(PNG_BYTES));
    }

    @Test
    void invalidFileTypeIsRejected() throws Exception {
        mockMvc.perform(withToken(multipart("/api/users/me/avatar")
                        .file(new MockMultipartFile("file", "script.html", "text/html", "<script/>".getBytes())), token))
                .andExpect(status().isUnsupportedMediaType());

        mockMvc.perform(withToken(multipart("/api/users/me/avatar")
                        .file(new MockMultipartFile("file", "fake.png", "image/png", "<script/>".getBytes())), token))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(userRepository.findByEmail("alice@example.com").orElseThrow().getProfileImagePath()).isNull();
    }

    @Test
    void oversizedFileIsRejected() throws Exception {
        byte[] big = new byte[2 * 1024 * 1024 + 1];
        System.arraycopy(PNG_BYTES, 0, big, 0, PNG_BYTES.length);

        mockMvc.perform(withToken(multipart("/api/users/me/avatar")
                        .file(new MockMultipartFile("file", "big.png", "image/png", big)), token))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void missingFilePartIsBadRequest() throws Exception {
        mockMvc.perform(withToken(multipart("/api/users/me/avatar"), token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void avatarEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(multipart("/api/users/me/avatar")
                        .file(new MockMultipartFile("file", "me.png", "image/png", PNG_BYTES)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me/avatar")).andExpect(status().isUnauthorized());
    }

    @Test
    void downloadingWithoutUploadIsNotFound() throws Exception {
        mockMvc.perform(withToken(get("/api/users/me/avatar"), token)).andExpect(status().isNotFound());
    }
}
