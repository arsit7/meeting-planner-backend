package com.meetingplanner.user;

import com.meetingplanner.common.ApiException;
import com.meetingplanner.storage.AvatarStorageService;
import com.meetingplanner.storage.AvatarStorageService.StoredImage;
import com.meetingplanner.user.dto.UserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final AvatarStorageService storage;

    public UserService(UserRepository userRepository, AvatarStorageService storage) {
        this.userRepository = userRepository;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile(Long userId) {
        return UserResponse.from(findUser(userId));
    }

    @Transactional
    public UserResponse updateAvatar(Long userId, MultipartFile file) {
        User user = findUser(userId);
        String previous = user.getProfileImagePath();
        String stored = storage.store(file);
        user.setProfileImagePath(stored);
        userRepository.saveAndFlush(user);
        storage.deleteQuietly(previous);
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public StoredImage getAvatar(Long userId) {
        return storage.load(findUser(userId).getProfileImagePath())
                .orElseThrow(() -> ApiException.notFound("No profile image uploaded"));
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> ApiException.notFound("User not found"));
    }
}
