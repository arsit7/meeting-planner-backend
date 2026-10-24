package com.meetingplanner.user;

import com.meetingplanner.security.AuthUser;
import com.meetingplanner.security.CurrentUser;
import com.meetingplanner.storage.AvatarStorageService.StoredImage;
import com.meetingplanner.user.dto.UserResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/users/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public UserResponse me(@CurrentUser AuthUser user) {
        return userService.getProfile(user.id());
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UserResponse uploadAvatar(@CurrentUser AuthUser user, @RequestParam("file") MultipartFile file) {
        return userService.updateAvatar(user.id(), file);
    }

    @GetMapping("/avatar")
    public ResponseEntity<Resource> avatar(@CurrentUser AuthUser user) {
        StoredImage image = userService.getAvatar(user.id());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.mediaType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .body(new FileSystemResource(image.path()));
    }
}
