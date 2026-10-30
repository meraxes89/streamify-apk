import Foundation
import Security

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG1-05 (Swift migration, Engineer 1): AppleKeychainStore — pure Swift
// wrapper around Security.framework's generic-password class, replacing
// Android's EncryptedSharedPreferences (SpotifySecureStore.kt) for secure
// session-token persistence.
//
// MAPPING NOTES (Kotlin → Swift):
//   * The Android store probed hardware-keystore → software AES-GCM →
//     in-memory. On Apple platforms there is ONE lane: the Keychain
//     (Secure Enclave-backed where the hardware provides it) — the probe
//     chain collapses into the single production backend, and a Keychain
//     failure surfaces as a typed error for the caller to degrade.
//   * The editor-transaction surface (putString/putLong/remove/clear +
//     apply) maps onto direct set/remove calls: Keychain items are
//     individually atomic (each SecItemAdd/SecItemUpdate is its own
//     transaction), so there is nothing to batch.
//   * Longs persist as their decimal string image (the same encoding the
//     Android software lane used), so a cross-platform export stays
//     readable.
//
// THREAD SAFETY: SecItem* calls are thread-safe; the store is a value type
// carrying only configuration — usable from any executor without locking.
// MULTIPLATFORM: kSecClassGenericPassword items work identically on iOS
// and macOS (SW-DEC-04 — one unified module, zero platform forks here).
// ─────────────────────────────────────────────────────────────────────────────

/// Typed Keychain failure: the failing operation plus the OSStatus and
/// the OS's own human-readable diagnostic (SecCopyErrorMessageString).
struct KeychainStoreError: Error, Equatable, Sendable {
    enum Operation: String, Sendable {
        case add = "SecItemAdd"
        case update = "SecItemUpdate"
        case copy = "SecItemCopyMatching"
        case delete = "SecItemDelete"
    }

    let operation: Operation
    let status: OSStatus
    let message: String

    init(operation: Operation, status: OSStatus) {
        self.operation = operation
        self.status = status
        let text = SecCopyErrorMessageString(status, nil) as String? ?? ""
        self.message = "\(operation.rawValue) → OSStatus \(status)\(text.isEmpty ? "" : " (\(text))")"
    }
}

/// The minimal persistence surface the auth flows consume (the Swift
/// mirror of SpotifySecureStore's `TokenStore`).
protocol SecureTokenStoring: Sendable {
    /// Reads one secret; nil when absent. Throws on a Keychain failure.
    func string(forKey key: String) throws -> String?
    /// Upserts one secret (a nil value removes the entry, mirroring the
    /// Android editor's putString(key, null) semantics).
    func set(_ value: String?, forKey key: String) throws
    /// Removes one entry (idempotent — a missing key is success).
    func removeValue(forKey key: String) throws
    /// Removes EVERY entry under the store's service (the Android
    /// `clear()` — here a single-class delete, no key rotation needed:
    /// the Keychain item set IS the ciphertext).
    func removeAll() throws
}

/// When a Keychain item becomes readable after a device unlock (the
/// Sendable, Swift-native mirror of the kSecAttrAccessible constants the
/// store consumes).
enum KeychainAccessibility: Sendable {
    /// Tokens usable while the app is foregrounded post-unlock, unreachable
    /// on the lock screen — the EncryptedSharedPreferences parity class.
    case afterFirstUnlock

    var rawValue: CFString { kSecAttrAccessibleAfterFirstUnlock }
}

/// Pure Swift Keychain wrapper (kSecClassGenericPassword) over
/// Security.framework — SW-ENG1-05.
struct AppleKeychainStore: SecureTokenStoring {

    /// The service (Keychain namespace) every item lives under. One
    /// service per concern keeps a `removeAll()` scoped to exactly the
    /// tokens it owns.
    let service: String

    /// When the item becomes readable after a device unlock.
    let accessibility: KeychainAccessibility

    init(
        service: String = "com.streamify.secure.tokens",
        accessibility: KeychainAccessibility = .afterFirstUnlock
    ) {
        self.service = service
        self.accessibility = accessibility
    }

    // MARK: - SecureTokenStoring

    func string(forKey key: String) throws -> String? {
        var query = baseQuery(account: key)
        query[kSecReturnData as String] = kCFBooleanTrue
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data else { return nil }
            return String(data: data, encoding: .utf8)
        case errSecItemNotFound:
            return nil
        default:
            throw KeychainStoreError(operation: .copy, status: status)
        }
    }

    func set(_ value: String?, forKey key: String) throws {
        // nil means "remove" (the Android editor's putString(key, null)).
        guard let value else {
            try removeValue(forKey: key)
            return
        }
        let data = Data(value.utf8)

        // Add-then-update-on-duplicate keeps the call atomic from the
        // caller's perspective and avoids a read-modify-write race.
        var addQuery = baseQuery(account: key)
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = accessibility.rawValue
        let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
        switch addStatus {
        case errSecSuccess:
            return
        case errSecDuplicateItem:
            let updateQuery = baseQuery(account: key)
            let attributes: [String: Any] = [
                kSecValueData as String: data,
                kSecAttrAccessible as String: accessibility.rawValue,
            ]
            let updateStatus = SecItemUpdate(
                updateQuery as CFDictionary,
                attributes as CFDictionary
            )
            guard updateStatus == errSecSuccess else {
                throw KeychainStoreError(operation: .update, status: updateStatus)
            }
        default:
            throw KeychainStoreError(operation: .add, status: addStatus)
        }
    }

    func removeValue(forKey key: String) throws {
        let status = SecItemDelete(baseQuery(account: key) as CFDictionary)
        switch status {
        case errSecSuccess, errSecItemNotFound:
            return // idempotent by contract
        default:
            throw KeychainStoreError(operation: .delete, status: status)
        }
    }

    func removeAll() throws {
        // Service-scoped delete: every generic-password item under this
        // service vanishes in one atomic call — the Keychain equivalent
        // of the Android store's key-rotation wipe, without needing a
        // readable key to rotate.
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
        ]
        let status = SecItemDelete(query as CFDictionary)
        switch status {
        case errSecSuccess, errSecItemNotFound:
            return
        default:
            throw KeychainStoreError(operation: .delete, status: status)
        }
    }

    // MARK: - Numeric helpers (long-as-string parity with the Android lane)

    /// Reads one persisted number; `defaultValue` when absent or unreadable.
    func long(forKey key: String, defaultValue: Int64 = 0) -> Int64 {
        guard let raw = try? string(forKey: key) else { return defaultValue }
        return Int64(raw) ?? defaultValue
    }

    /// Persists one number as its decimal string image.
    func setLong(_ value: Int64, forKey key: String) throws {
        try set(String(value), forKey: key)
    }

    // MARK: - Internals

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
