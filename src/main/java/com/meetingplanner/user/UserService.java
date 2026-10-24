package com.meetingplanner.user;

import com.meetingplanner.common.ApiException;
import com.meetingplanner.storage.AvatarStorageService;
import com.meetingplanner.storage.AvatarStorageService.StoredImage;
import com.meetingplanner.user.dto.UserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
        deleteUnusedFileAfterTransaction(previous, stored);
        return UserResponse.from(user);
    }

    /**
     * Files aren't transactional, so clean up only once the outcome is known: after a commit the old avatar
     * is no longer referenced; after a rollback the new one never was.
     */
    private void deleteUnusedFileAfterTransaction(String previous, String stored) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                storage.deleteQuietly(status == STATUS_COMMITTED ? previous : stored);
            }
        });
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
