package com.navix.auth;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.web.ApiResponse;
import com.navix.config.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The borrower's devices (PLAN Phase 8, app board D7): register this device's push token, list the devices signed
 * in to the account, and sign one out (its refresh tokens are revoked and its push token forgotten).
 */
@RestController
@RequestMapping("/api/devices/me")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService devices;
    private final RefreshTokenService refreshTokens;

    /** {@code deviceId} defaults to the one in the access token; {@code fcmToken} null clears it (push denied, CC-55). */
    public record RegisterDeviceRequest(String deviceId, String fcmToken, String platform, String appVersion,
                                        String deviceName) {
    }

    @PutMapping
    public ApiResponse<Void> register(@RequestBody RegisterDeviceRequest req, HttpServletRequest http) {
        long customerId = borrower();
        String own = (String) http.getAttribute(JwtAuthFilter.DEVICE_ID_ATTR);
        if (own != null && req.deviceId() != null && !own.equals(req.deviceId())) {
            throw new BusinessException("DEVICE_MISMATCH", "You can only register the device you are signed in on");
        }
        String deviceId = DeviceService.requireDeviceId(own != null ? own : req.deviceId());
        devices.register(customerId, deviceId, req.fcmToken(), req.platform(), req.appVersion(), req.deviceName());
        return ApiResponse.ok(null);
    }

    @GetMapping
    public ApiResponse<List<DeviceService.DeviceView>> list(HttpServletRequest http) {
        return ApiResponse.ok(devices.list(borrower(), (String) http.getAttribute(JwtAuthFilter.DEVICE_ID_ATTR)));
    }

    /** Sign {@code deviceId} out. Idempotent; signing out the current device is allowed (the app then signs in again). */
    @DeleteMapping("/{deviceId}")
    public ApiResponse<Void> signOut(@PathVariable String deviceId) {
        long customerId = borrower();
        refreshTokens.revokeDevice(customerId, DeviceService.requireDeviceId(deviceId), "DEVICE_SIGNED_OUT");
        devices.remove(customerId, deviceId);
        return ApiResponse.ok(null);
    }

    private static long borrower() {
        var actor = ActorContext.get();
        if (!"BORROWER".equals(actor.role()) || actor.id() == null) {
            throw new BusinessException("FORBIDDEN_ROLE", "Borrower action required");
        }
        return Long.parseLong(actor.id());
    }
}
