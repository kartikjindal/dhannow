package com.navix.auth;

import com.navix.common.exception.BusinessException;
import com.navix.common.notification.DeviceTokenDirectory;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A borrower's devices (PLAN Phase 8, V110 {@code device_token}): written at app sign-in and by
 * {@code PUT /api/devices/me} (FCM token, platform, app version), listed on Settings > Devices, and deleted when a
 * device is signed out. A device shows in the list only while it holds a live refresh token.
 */
@Service
public class DeviceService implements DeviceTokenDirectory {

    /** The app's own install id: a UUID in practice; anything outside this is refused rather than stored. */
    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9._:-]{8,128}");
    private static final String ACTIVE = "exists (select 1 from refresh_token r where r.customer_id = d.customer_id"
            + " and r.device_id = d.device_id and r.revoked_at is null and r.expires_at > now())";

    /** One row of Settings > Devices. {@code current} is the device the request came from. */
    public record DeviceView(String deviceId, String name, String platform, String appVersion, Instant lastSeen,
                             boolean current) {
    }

    private final JdbcTemplate jdbc;

    public DeviceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String requireDeviceId(String deviceId) {
        if (deviceId == null || !DEVICE_ID.matcher(deviceId).matches()) {
            throw new BusinessException("INVALID_DEVICE_ID", "A valid device id is required");
        }
        return deviceId;
    }

    /** Sign-in or refresh: record the device and when it was last seen. Keeps any FCM token already on file. */
    public void touch(long customerId, String deviceId, String name, String platform, String appVersion) {
        jdbc.update("""
                insert into device_token (customer_id, device_id, device_name, platform, app_version)
                values (?, ?, ?, coalesce(?, 'android'), ?)
                on conflict (customer_id, device_id) do update set last_seen = now(),
                    device_name = coalesce(excluded.device_name, device_token.device_name),
                    app_version = coalesce(excluded.app_version, device_token.app_version),
                    platform = coalesce(?, device_token.platform)
                """, customerId, deviceId, trim(name, 128), trim(platform, 16), trim(appVersion, 32), trim(platform, 16));
    }

    /**
     * {@code PUT /api/devices/me}: the device's current FCM token. A token can only belong to one device, so a
     * token now on this device is taken off any other row (another account signed in on the same phone).
     */
    @Transactional
    public void register(long customerId, String deviceId, String fcmToken, String platform, String appVersion,
                         String name) {
        String token = fcmToken == null || fcmToken.isBlank() ? null : trim(fcmToken.trim(), 512);
        touch(customerId, deviceId, name, platform, appVersion);
        if (token != null) {
            jdbc.update("update device_token set fcm_token = null where fcm_token = ?"
                    + " and not (customer_id = ? and device_id = ?)", token, customerId, deviceId);
        }
        jdbc.update("update device_token set fcm_token = ? where customer_id = ? and device_id = ?",
                token, customerId, deviceId);
    }

    public List<DeviceView> list(long customerId, String currentDeviceId) {
        return jdbc.query("select device_id, device_name, platform, app_version, last_seen from device_token d"
                        + " where customer_id = ? and " + ACTIVE + " order by last_seen desc",
                (rs, i) -> new DeviceView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getString(1).equals(currentDeviceId)), customerId);
    }

    /** Forget the device row (and with it its push token). The caller revokes its refresh tokens. */
    public boolean remove(long customerId, String deviceId) {
        return jdbc.update("delete from device_token where customer_id = ? and device_id = ?", customerId, deviceId) > 0;
    }

    public void removeAll(long customerId) {
        jdbc.update("delete from device_token where customer_id = ?", customerId);
    }

    @Override
    public List<String> fcmTokens(long customerId) {
        return jdbc.queryForList("select fcm_token from device_token d where customer_id = ? and fcm_token is not null"
                + " and " + ACTIVE, String.class, customerId);
    }

    @Override
    public void forgetToken(String fcmToken) {
        jdbc.update("update device_token set fcm_token = null where fcm_token = ?", fcmToken);
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
