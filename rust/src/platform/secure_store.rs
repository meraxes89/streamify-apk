//! Secure storage providers — Phase 5 / MP-CAP04.
//!
//! One thread-safe trait, [`SecureStoreProvider`], four platform
//! backends:
//!
//! | target | backend | mechanism |
//! |---|---|---|
//! | Windows | [`DpapiStore`] | DPAPI `CryptProtectData`/`CryptUnprotectData` (user-scoped) + per-key blob files under `%APPDATA%\StreamifyCore\secrets` |
//! | Linux | [`SecretServiceStore`] | FreeDesktop Secret Service (`org.freedesktop.secrets`) over the pure-Rust zbus connection |
//! | macOS/iOS | [`KeychainStore`] | Keychain Services generic passwords (`SecItemAdd`/`SecItemCopyMatching`/`SecItemDelete` via the `security-framework` crate) |
//! | Android / any | [`ForeignDelegateStore`] | UniFFI callback [`SecureStoreDelegate`] — the Kotlin shell backs it with `EncryptedSharedPreferences`/KeyStore |
//!
//! plus [`InMemorySecureStore`] — the deterministic test double and the
//! last-resort fallback (explicitly NOT at-rest-secure; logged loudly
//! whenever it is selected as a real backend).
//!
//! # Error taxonomy
//!
//! Every failure maps onto the engine's structured taxonomy (MP-C04):
//! provider-unavailable / bus / keychain faults → `Storage { Io }`,
//! access denials → `Storage { Permission }`, integrity failures →
//! `Storage { Corrupt }`, missing items are NOT errors
//! (`retrieve_secret` → `Ok(None)`, `delete_secret` idempotent
//! `Ok(())`) — the trait contract treats absence as data, matching the
//! `Option<Vec<u8>>` retrieval signature.
//!
//! # Threading
//!
//! `SecureStoreProvider: Send + Sync` is part of the contract: the
//! auth/session-token persistence paths call from background threads
//! and the UniFFI scaffolding asserts both bounds. The DPAPI file store
//! serializes on an interior `Mutex`; the Secret Service store blocks
//! on the shared tokio runtime (never from inside a runtime thread —
//! same discipline as `resolver::get_runtime` call sites).
//!
//! # Foreign delegate wiring (the UDL surface)
//!
//! `SecureStoreDelegate` is the UDL callback interface
//! (`src/streamify.udl`); the shell installs one with the
//! `set_secure_store_delegate` namespace function, and every
//! `default_secure_store()` call afterwards prefers it (Android
//! cutover, MP-CAP04 fallback). `secure_store_roundtrip` is the
//! startup self-test the shell uses to verify whichever backend is
//! active end-to-end (store → retrieve → compare → delete).

use crate::contract::{EngineError, StorageErrorKind};
use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};

// ─────────────────────────────────────────────────────────────────────────────
//  The provider trait (MP-CAP04, verbatim contract)
// ─────────────────────────────────────────────────────────────────────────────

/// Unified, thread-safe secure storage contract (Phase 5 / MP-CAP04).
///
/// Semantics (pinned by `rust/tests/test_capability_adapters.rs`):
///
/// * `store_secret` — upsert: an existing entry under `key` is replaced
///   atomically (no window in which the old value survives a failed
///   store);
/// * `retrieve_secret` — `Ok(None)` when no entry exists; missing
///   entries are NOT errors;
/// * `delete_secret` — idempotent: deleting an absent key is `Ok(())`.
pub trait SecureStoreProvider: Send + Sync {
    /// Persist `secret` under `key` (upsert semantics).
    fn store_secret(&self, key: &str, secret: &[u8]) -> Result<(), EngineError>;

    /// Fetch the secret stored under `key`, `Ok(None)` when absent.
    fn retrieve_secret(&self, key: &str) -> Result<Option<Vec<u8>>, EngineError>;

    /// Remove the entry under `key` (idempotent).
    fn delete_secret(&self, key: &str) -> Result<(), EngineError>;
}

// ─────────────────────────────────────────────────────────────────────────────
//  In-memory provider (test double + explicit last-resort fallback)
// ─────────────────────────────────────────────────────────────────────────────

/// Deterministic in-memory provider: the CI test double and the
/// last-resort fallback when no platform backend exists.
///
/// NOT at-rest-secure — plaintext in process memory. Whenever
/// [`default_secure_store`] selects it as a REAL backend (no delegate
/// installed, no platform implementation compiled in) it logs a loud
/// warning so shipping configurations notice.
#[derive(Debug, Default)]
pub struct InMemorySecureStore {
    secrets: Mutex<HashMap<String, Vec<u8>>>,
}

impl InMemorySecureStore {
    /// Empty store.
    pub fn new() -> Self {
        Self::default()
    }
}

impl SecureStoreProvider for InMemorySecureStore {
    fn store_secret(&self, key: &str, secret: &[u8]) -> Result<(), EngineError> {
        let mut secrets = self
            .secrets
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        secrets.insert(key.to_string(), secret.to_vec());
        Ok(())
    }

    fn retrieve_secret(&self, key: &str) -> Result<Option<Vec<u8>>, EngineError> {
        let secrets = self
            .secrets
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        Ok(secrets.get(key).cloned())
    }

    fn delete_secret(&self, key: &str) -> Result<(), EngineError> {
        let mut secrets = self
            .secrets
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        secrets.remove(key);
        Ok(())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  UniFFI foreign delegate surface (Android / fallback lane)
// ─────────────────────────────────────────────────────────────────────────────

/// Outcome of one foreign-delegate storage operation. Mirrored in the
/// UDL; the status variants map one-to-one onto [`StorageErrorKind`]
/// (plus `Success`), so a foreign backend can route errors without
/// carrying the full engine taxonomy across the boundary.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SecureStoreOpStatus {
    /// Operation applied.
    Success,
    /// No entry exists under the key (retrievals surface `None`).
    NotFound,
    /// The OS/keystore denied access (locked keyring, missing perms).
    Permission,
    /// Integrity failure (decrypt error, corrupted entry).
    Corrupt,
    /// Generic storage I/O fault.
    Io,
}

impl SecureStoreOpStatus {
    /// Map onto the engine error taxonomy (`None` for `Success`).
    pub fn to_engine_error(self) -> Option<EngineError> {
        let kind = match self {
            SecureStoreOpStatus::Success => return None,
            SecureStoreOpStatus::NotFound => return Some(EngineError::Storage {
                kind: StorageErrorKind::NotFound,
            }),
            SecureStoreOpStatus::Permission => StorageErrorKind::Permission,
            SecureStoreOpStatus::Corrupt => StorageErrorKind::Corrupt,
            SecureStoreOpStatus::Io => StorageErrorKind::Io,
        };
        Some(EngineError::Storage { kind })
    }
}

/// Result payload of a foreign-delegate retrieval: the status plus the
/// secret bytes when found. (The UDL dictionary keeps status and
/// payload in ONE round-trip — a `(Option, status)` pair would need
/// two calls and race with itself.)
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SecureFetchResult {
    /// Operation status.
    pub status: SecureStoreOpStatus,
    /// The secret, present iff `status == Success`.
    pub secret: Option<Vec<u8>>,
}

/// Foreign secure-storage delegate (UDL callback interface).
///
/// Implemented by the Android shell on top of
/// `EncryptedSharedPreferences`/KeyStore and installed via the
/// `set_secure_store_delegate` namespace function. `Send + Sync` is
/// part of the contract (the UniFFI dispatch handle maps are
/// thread-safe; callbacks may arrive from any engine thread).
pub trait SecureStoreDelegate: Send + Sync {
    /// Upsert `secret` under `key`.
    fn store_secret(&self, key: String, secret: Vec<u8>) -> SecureStoreOpStatus;

    /// Fetch the secret under `key` (`NotFound` status when absent).
    fn retrieve_secret(&self, key: String) -> SecureFetchResult;

    /// Remove the entry under `key` (idempotent).
    fn delete_secret(&self, key: String) -> SecureStoreOpStatus;
}

/// [`SecureStoreProvider`] adapter over an installed foreign delegate.
pub struct ForeignDelegateStore {
    delegate: Arc<dyn SecureStoreDelegate>,
}

impl ForeignDelegateStore {
    /// Wrap one delegate.
    pub fn new(delegate: Arc<dyn SecureStoreDelegate>) -> Self {
        Self { delegate }
    }
}

impl SecureStoreProvider for ForeignDelegateStore {
    fn store_secret(&self, key: &str, secret: &[u8]) -> Result<(), EngineError> {
        self.delegate
            .store_secret(key.to_string(), secret.to_vec())
            .to_engine_error()
            .map_or(Ok(()), Err)
    }

    fn retrieve_secret(&self, key: &str) -> Result<Option<Vec<u8>>, EngineError> {
        let result = self.delegate.retrieve_secret(key.to_string());
        match result.status {
            SecureStoreOpStatus::Success => Ok(result.secret),
            SecureStoreOpStatus::NotFound => Ok(None),
            other => Err(other
                .to_engine_error()
                .unwrap_or(EngineError::Storage {
                    kind: StorageErrorKind::Io,
                })),
        }
    }

    fn delete_secret(&self, key: &str) -> Result<(), EngineError> {
        self.delegate
            .delete_secret(key.to_string())
            .to_engine_error()
            .map_or(Ok(()), Err)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Global backend selection
// ─────────────────────────────────────────────────────────────────────────────

/// The process-global foreign delegate (installed at shell startup,
/// before any consumer asks for a backend). `OnceLock<Mutex<..>>`:
/// install-once container, replaceable inside (test isolation).
static INSTALLED_DELEGATE: OnceLock<Mutex<Option<Arc<dyn SecureStoreDelegate>>>> = OnceLock::new();

fn delegate_slot(
) -> &'static Mutex<Option<Arc<dyn SecureStoreDelegate>>> {
    INSTALLED_DELEGATE.get_or_init(|| Mutex::new(None))
}

/// Install (or replace) the foreign secure-storage delegate (UDL
/// namespace function `set_secure_store_delegate`). Android installs
/// one at app start; desktop/iOS shells normally leave it unset and
/// use the native platform backend.
pub fn set_secure_store_delegate(delegate: Box<dyn SecureStoreDelegate>) -> Result<(), EngineError> {
    let mut slot = delegate_slot()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    *slot = Some(Arc::from(delegate));
    Ok(())
}

/// Remove the installed delegate (test hygiene; also lets a shell fall
/// back to the native platform backend at runtime).
pub fn clear_secure_store_delegate() {
    let mut slot = delegate_slot()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    *slot = None;
}

/// Whether a foreign delegate is currently installed.
pub fn has_foreign_delegate() -> bool {
    delegate_slot()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .is_some()
}

/// The backend [`secure_store_roundtrip`] and Rust-side consumers use:
///
///   1. an installed foreign delegate, when present (Android cutover /
///      explicit shell override), else
///   2. the native platform backend (DPAPI / Secret Service /
///      Keychain) when compiled in, else
///   3. [`InMemorySecureStore`] with a LOUD warning — never a panic,
///      never an Err from selection itself (a headless CI session must
///      still be able to construct and exercise the contract).
pub fn default_secure_store() -> Arc<dyn SecureStoreProvider> {
    if let Some(delegate) = delegate_slot()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone()
    {
        return Arc::new(ForeignDelegateStore::new(delegate));
    }
    select_native_secure_store()
}

/// Per-target native backend selection. A helper per cfg — each with a
/// proper tail expression — instead of `return`-terminated cfg blocks
/// (whose fall-through shape is invisible to the compiler's control
/// flow and trips needless_return under `-D warnings`).
#[cfg(target_os = "linux")]
fn select_native_secure_store() -> Arc<dyn SecureStoreProvider> {
    use self::secret_service::SecretServiceStore;
    if SecretServiceStore::is_available() {
        return Arc::new(SecretServiceStore::new());
    }
    tracing::warn!(
        target: "streamify::platform",
        "no Secret Service on the session bus — falling back to NOT-at-rest-secure \
         in-memory storage (session tokens will not survive restarts)"
    );
    Arc::new(InMemorySecureStore::new())
}

#[cfg(target_os = "windows")]
fn select_native_secure_store() -> Arc<dyn SecureStoreProvider> {
    Arc::new(DpapiStore::new())
}

#[cfg(any(target_os = "macos", target_os = "ios"))]
fn select_native_secure_store() -> Arc<dyn SecureStoreProvider> {
    Arc::new(KeychainStore::new())
}

#[cfg(not(any(
    target_os = "windows",
    target_os = "linux",
    target_os = "macos",
    target_os = "ios"
)))]
fn select_native_secure_store() -> Arc<dyn SecureStoreProvider> {
    tracing::warn!(
        target: "streamify::platform",
        "no native secure-store backend on this target and no foreign delegate installed — \
         falling back to NOT-at-rest-secure in-memory storage"
    );
    Arc::new(InMemorySecureStore::new())
}

/// Startup self-test (UDL namespace function `secure_store_roundtrip`):
/// store → retrieve → byte-compare → delete through whichever backend
/// [`default_secure_store`] selects, returning whether the full loop
/// held. The probe key is namespaced so a crash mid-test leaves no
/// colliding residue for real keys.
pub fn secure_store_roundtrip(
    probe_key: String,
    probe_value: Vec<u8>,
) -> Result<bool, EngineError> {
    let store = default_secure_store();
    let key = format!("streamify.selftest.{probe_key}");
    store.store_secret(&key, &probe_value)?;
    let fetched = store.retrieve_secret(&key)?;
    let held = fetched.as_deref() == Some(probe_value.as_slice());
    store.delete_secret(&key)?;
    // Post-delete absence is part of the loop (a backend that leaks the
    // probe entry fails the roundtrip honestly).
    let gone = store.retrieve_secret(&key)?.is_none();
    Ok(held && gone)
}

// ─────────────────────────────────────────────────────────────────────────────
//  Windows: DPAPI provider (MP-CAP04)
// ─────────────────────────────────────────────────────────────────────────────

#[cfg(target_os = "windows")]
mod dpapi {
    use super::SecureStoreProvider;
    use crate::contract::{EngineError, StorageErrorKind};
    use sha2::{Digest, Sha256};
    use std::path::PathBuf;
    use std::sync::Mutex;
    use windows::core::PCWSTR;
    use windows::Win32::Foundation::LocalFree;
    use windows::Win32::Security::Cryptography::{
        CryptProtectData, CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    };

    /// DPAPI-backed provider: `CryptProtectData` scopes every blob to
    /// the current Windows user (a copied blob file is useless under
    /// any other account), and the extra entropy binds it to the
    /// Streamify core so no other process on the same account can
    /// unprotect it by calling DPAPI directly.
    ///
    /// Serialized on one mutex: DPAPI is thread-safe, the file upsert
    /// (write-temp + rename) is what needs the guard.
    pub struct DpapiStore {
        base_dir: PathBuf,
        write_lock: Mutex<()>,
    }

    /// Per-app DPAPI entropy. Not a secret (it lives in the binary):
    /// its role is domain separation between applications sharing one
    /// user account, which is exactly DPAPI's own model.
    const APP_ENTROPY: &[u8] = b"streamify-core::secure-store::v1";

    impl DpapiStore {
        /// New store rooted at `%APPDATA%\StreamifyCore\secrets`. The
        /// directory is created lazily on first write so merely
        /// constructing a store in a read-only environment (CI probe)
        /// never fails.
        pub fn new() -> Self {
            let base_dir = std::env::var("APPDATA")
                .map(|appdata| {
                    PathBuf::from(appdata)
                        .join("StreamifyCore")
                        .join("secrets")
                })
                .unwrap_or_else(|_| PathBuf::from(".streamify-core-secrets"));
            Self {
                base_dir,
                write_lock: Mutex::new(()),
            }
        }

        fn blob_path(&self, key: &str) -> PathBuf {
            // SHA-256 of the key: opaque, filesystem-safe, no path
            // traversal from hostile key material.
            let digest = Sha256::digest(key.as_bytes());
            self.base_dir.join(format!("{}.bin", hex::encode(digest)))
        }

        fn protect(plaintext: &[u8]) -> Result<Vec<u8>, EngineError> {
            unsafe {
                let mut in_blob = CRYPT_INTEGER_BLOB {
                    cbData: plaintext.len() as u32,
                    pbData: plaintext.as_ptr() as *mut u8,
                };
                let mut entropy_blob = CRYPT_INTEGER_BLOB {
                    cbData: APP_ENTROPY.len() as u32,
                    pbData: APP_ENTROPY.as_ptr() as *mut u8,
                };
                let mut out_blob = CRYPT_INTEGER_BLOB::default();
                CryptProtectData(
                    &mut in_blob,
                    PCWSTR::null(),
                    Some(&mut entropy_blob),
                    None,
                    None,
                    CRYPTPROTECT_UI_FORBIDDEN,
                    &mut out_blob,
                )
                .map_err(|_| EngineError::Storage {
                    kind: StorageErrorKind::Io,
                })?;
                Self::take_blob(out_blob)
            }
        }

        fn unprotect(ciphertext: &[u8]) -> Result<Vec<u8>, EngineError> {
            unsafe {
                let mut in_blob = CRYPT_INTEGER_BLOB {
                    cbData: ciphertext.len() as u32,
                    pbData: ciphertext.as_ptr() as *mut u8,
                };
                let mut entropy_blob = CRYPT_INTEGER_BLOB {
                    cbData: APP_ENTROPY.len() as u32,
                    pbData: APP_ENTROPY.as_ptr() as *mut u8,
                };
                let mut out_blob = CRYPT_INTEGER_BLOB::default();
                // A DPAPI decrypt failure means wrong user, wrong
                // entropy or a corrupted blob — integrity-class.
                CryptUnprotectData(
                    &mut in_blob,
                    None,
                    Some(&mut entropy_blob),
                    None,
                    None,
                    CRYPTPROTECT_UI_FORBIDDEN,
                    &mut out_blob,
                )
                .map_err(|_| EngineError::Storage {
                    kind: StorageErrorKind::Corrupt,
                })?;
                Self::take_blob(out_blob)
            }
        }

        /// Copy a DPAPI out-blob into owned bytes and free the
        /// LocalAlloc'd buffer (every DPAPI call site MUST go through
        /// here or the buffer leaks).
        unsafe fn take_blob(blob: CRYPT_INTEGER_BLOB) -> Result<Vec<u8>, EngineError> {
            if blob.pbData.is_null() {
                return Ok(Vec::new());
            }
            let bytes =
                std::slice::from_raw_parts(blob.pbData, blob.cbData as usize).to_vec();
            LocalFree(Some(windows::Win32::Foundation::HLOCAL(
                blob.pbData as *mut core::ffi::c_void,
            )));
            Ok(bytes)
        }
    }

    impl Default for DpapiStore {
        fn default() -> Self {
            Self::new()
        }
    }

    impl SecureStoreProvider for DpapiStore {
        fn store_secret(&self, key: &str, secret: &[u8]) -> Result<(), EngineError> {
            let _guard = self
                .write_lock
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let protected = Self::protect(secret)?;
            std::fs::create_dir_all(&self.base_dir).map_err(|_| EngineError::Storage {
                kind: StorageErrorKind::Permission,
            })?;
            // Atomic upsert: temp file + rename, so a crash mid-write
            // never leaves a half-written blob that would surface as
            // `Corrupt` on the next retrieve.
            let target = self.blob_path(key);
            let tmp = target.with_extension("tmp");
            std::fs::write(&tmp, &protected).map_err(|_| EngineError::Storage {
                kind: StorageErrorKind::Io,
            })?;
            std::fs::rename(&tmp, &target).map_err(|_| EngineError::Storage {
                kind: StorageErrorKind::Io,
            })?;
            Ok(())
        }

        fn retrieve_secret(&self, key: &str) -> Result<Option<Vec<u8>>, EngineError> {
            match std::fs::read(self.blob_path(key)) {
                Ok(ciphertext) => Self::unprotect(&ciphertext).map(Some),
                Err(err) if err.kind() == std::io::ErrorKind::NotFound => Ok(None),
                Err(_) => Err(EngineError::Storage {
                    kind: StorageErrorKind::Io,
                }),
            }
        }

        fn delete_secret(&self, key: &str) -> Result<(), EngineError> {
            match std::fs::remove_file(self.blob_path(key)) {
                Ok(()) => Ok(()),
                Err(err) if err.kind() == std::io::ErrorKind::NotFound => Ok(()),
                Err(_) => Err(EngineError::Storage {
                    kind: StorageErrorKind::Io,
                }),
            }
        }
    }
}

#[cfg(target_os = "windows")]
pub use dpapi::DpapiStore;

// ─────────────────────────────────────────────────────────────────────────────
//  Linux: FreeDesktop Secret Service provider (MP-CAP04)
// ─────────────────────────────────────────────────────────────────────────────

#[cfg(target_os = "linux")]
mod secret_service {
    use super::SecureStoreProvider;
    use crate::contract::{EngineError, StorageErrorKind};
    use std::collections::HashMap;
    use zbus::proxy::CacheProperties;
    use zbus::wire::{ObjectPath, OwnedObjectPath, OwnedVariant, Variant};
    use zbus::{proxy, Connection};

    /// The `(session, parameters, value, content_type)` wire tuple of
    /// the Secret Service API. Field order IS the wire signature
    /// `(oayays)`; the `Type` derive computes it structurally from the
    /// field types, so no hand-pinned signature can drift.
    #[derive(Debug, Clone, zbus::wire::Type, serde::Serialize, serde::Deserialize)]
    pub struct SecretWire {
        session: OwnedObjectPath,
        parameters: Vec<u8>,
        value: Vec<u8>,
        content_type: String,
    }

    fn storage_err(kind: StorageErrorKind) -> EngineError {
        EngineError::Storage { kind }
    }

    /// zbus/Secret-Service failures onto the engine taxonomy: service
    /// missing / bus down → environment I/O; portal/keyring denials →
    /// permission-class (so the shell can prompt a unlock flow).
    fn map_bus_error(err: zbus::Error) -> EngineError {
        match &err {
            // No Secret Service on the bus / interface: environment I/O.
            zbus::Error::InterfaceNotFound => storage_err(StorageErrorKind::Io),
            zbus::Error::FDO(fdo) => match &**fdo {
                zbus::fdo::Error::ServiceUnknown(_)
                | zbus::fdo::Error::AccessDenied(_)
                | zbus::fdo::Error::AuthFailed(_) => {
                    storage_err(StorageErrorKind::Permission)
                }
                _ => storage_err(StorageErrorKind::Io),
            },
            _ => storage_err(StorageErrorKind::Io),
        }
    }

    #[proxy(
        interface = "org.freedesktop.secrets",
        default_service = "org.freedesktop.secrets",
        default_path = "/org/freedesktop/secrets"
    )]
    trait SecretService {
        /// Plain (non-DH) session: the transport is the user session
        /// bus, already authenticated by the OS.
        fn open_session(
            &self,
            algorithm: &str,
            input: &Variant<'_>,
        ) -> zbus::Result<(OwnedVariant, OwnedObjectPath)>;

        /// Resolve the `default` collection alias (`/` when unset).
        fn read_alias(&self, name: &str) -> zbus::Result<OwnedObjectPath>;

        /// Unlock collections; a non-empty prompt list means the
        /// keyring needs interactive unlocking we cannot drive.
        fn unlock(
            &self,
            objects: &[ObjectPath<'_>],
        ) -> zbus::Result<(Vec<OwnedObjectPath>, Vec<OwnedObjectPath>)>;

        /// Search every unlocked collection for the attribute set.
        fn search_items(
            &self,
            attributes: &HashMap<String, String>,
        ) -> zbus::Result<(Vec<OwnedObjectPath>, Vec<OwnedObjectPath>)>;
    }

    #[proxy(
        interface = "org.freedesktop.Secret.Collection",
        default_service = "org.freedesktop.secrets"
    )]
    trait SecretCollection {
        /// Create (or replace, when `replace` is set) an item carrying
        /// the attributes. Returns `(item, prompt)`; a `/` prompt means
        /// no interaction was needed.
        fn create_item(
            &self,
            properties: HashMap<String, OwnedVariant>,
            secret: SecretWire,
            replace: bool,
        ) -> zbus::Result<(OwnedObjectPath, OwnedObjectPath)>;
    }

    #[proxy(
        interface = "org.freedesktop.Secret.Item",
        default_service = "org.freedesktop.secrets"
    )]
    trait SecretItem {
        fn get_secret(&self, session: &ObjectPath<'_>) -> zbus::Result<SecretWire>;
        fn delete(&self) -> zbus::Result<OwnedObjectPath>;
    }

    /// Attribute pair scoping every Streamify item inside the keyring
    /// (the Secret Service's own namespacing mechanism).
    fn item_attributes(key: &str) -> HashMap<String, String> {
        HashMap::from([
            ("service".to_string(), "streamify-core".to_string()),
            ("key".to_string(), key.to_string()),
        ])
    }

    /// Resolve the default (login) collection, `None` when no alias is
    /// configured or the keyring needs an interactive prompt.
    async fn default_collection(
        service: &SecretServiceProxy<'_>,
    ) -> zbus::Result<Option<OwnedObjectPath>> {
        let collection = service.read_alias("default").await?;
        if collection.as_str() == "/" {
            return Ok(None);
        }
        let (unlocked, prompts) = service.unlock(&[collection.clone().into()]).await?;
        if !prompts.is_empty() || unlocked.is_empty() {
            // A prompt is required (keyring locked): non-interactive
            // callers must not hang on a GUI prompt they never see.
            return Ok(None);
        }
        Ok(Some(collection))
    }

    /// Find the (first) unlocked item carrying `key`'s attributes.
    async fn find_item(
        service: &SecretServiceProxy<'_>,
        key: &str,
    ) -> zbus::Result<Option<OwnedObjectPath>> {
        let (unlocked, _locked) = service.search_items(&item_attributes(key)).await?;
        Ok(unlocked.into_iter().next())
    }

    /// Secret-Service-backed provider.
    ///
    /// Every operation opens a fresh plain session on a fresh
    /// connection: sessions are cheap handles over the same user bus,
    /// and per-operation connections make the store stateless (no
    /// background task to keep alive, no stale-session failure modes
    /// when the daemon restarts mid-process). The async work rides the
    /// engine's shared tokio runtime (`resolver::get_runtime`,
    /// rt-multi-thread), exactly like every other block_on call site
    /// in the crate — callers therefore must not invoke the store from
    /// INSIDE an async context on that same runtime (the same
    /// documented constraint as the resolver's own blocking helpers).
    pub struct SecretServiceStore;

    /// Cached availability probe: a live session bus + a service name
    /// is NOT proof the service can serve — CI runners often carry
    /// D-Bus activation but a locked or policy-denied keyring, where
    /// proxy construction succeeds and every actual secret operation
    /// is denied (measured: `Storage { kind: Permission }`). The only
    /// honest probe is a real round-trip of a throwaway secret through
    /// the same path [`store_secret`] uses; anything less falls back
    /// to [`InMemorySecureStore`] with a loud warning.
    static SERVICE_AVAILABLE: std::sync::OnceLock<bool> = std::sync::OnceLock::new();

    impl SecretServiceStore {
        pub fn new() -> Self {
            Self
        }

        /// Whether a Secret Service can actually serve secrets on this
        /// host (cached after the first probe).
        pub fn is_available() -> bool {
            *SERVICE_AVAILABLE.get_or_init(|| {
                if !crate::platform::linux::mpris::session_bus_available() {
                    return false;
                }
                let probe = SecretServiceStore;
                let probe_key = "streamify.availability.probe";
                match SecureStoreProvider::store_secret(&probe, probe_key, b"probe") {
                    Ok(()) => {
                        // Best-effort cleanup: a lingering probe entry is
                        // inert (namespaced, non-secret payload), so a
                        // failed delete must not fail the availability
                        // verdict.
                        let _ = SecureStoreProvider::delete_secret(&probe, probe_key);
                        true
                    }
                    Err(_) => false,
                }
            })
        }
    }

    impl Default for SecretServiceStore {
        fn default() -> Self {
            Self::new()
        }
    }

    impl SecureStoreProvider for SecretServiceStore {
        fn store_secret(&self, key: &str, secret: &[u8]) -> Result<(), EngineError> {
            crate::resolver::get_runtime()
                .block_on(async {
                    let conn = Connection::session().await.map_err(map_bus_error)?;
                    let service = SecretServiceProxy::new(&conn)
                        .await
                        .map_err(map_bus_error)?;
                    let (_output, session) = service
                        .open_session("plain", &Variant::from(""))
                        .await
                        .map_err(map_bus_error)?;

                    let collection =
                        default_collection(&service).await.map_err(map_bus_error)?;
                    let collection = match collection {
                        Some(path) => path,
                        // No default keyring / locked keyring: storing
                        // would silently land nowhere (or hang a prompt
                        // the caller cannot see). Permission-class so
                        // the shell can route to a setup/unlock flow.
                        None => return Err(storage_err(StorageErrorKind::Permission)),
                    };

                    let collection_proxy = SecretCollectionProxy::builder(&conn)
                        .cache_properties(CacheProperties::No)
                        .path(collection)
                        .destination("org.freedesktop.secrets")
                        .build()
                        .await
                        .map_err(map_bus_error)?;

                    let mut properties: HashMap<String, OwnedVariant> = HashMap::new();
                    properties.insert(
                        "org.freedesktop.Secret.Item.Label".to_string(),
                        OwnedVariant::try_from(Variant::from(format!("Streamify: {key}")))
                            .map_err(map_bus_error)?,
                    );
                    properties.insert(
                        "org.freedesktop.Secret.Item.Attributes".to_string(),
                        OwnedVariant::try_from(Variant::from(item_attributes(key)))
                            .map_err(map_bus_error)?,
                    );
                    let wire = SecretWire {
                        session: session.clone(),
                        parameters: Vec::new(),
                        value: secret.to_vec(),
                        content_type: "application/octet-stream".to_string(),
                    };
                    let (_item, prompt) = collection_proxy
                        .create_item(properties, wire, true)
                        .await
                        .map_err(map_bus_error)?;
                    if prompt.as_str() == "/" {
                        Ok(())
                    } else {
                        Err(storage_err(StorageErrorKind::Permission))
                    }
                })
        }

        fn retrieve_secret(&self, key: &str) -> Result<Option<Vec<u8>>, EngineError> {
            crate::resolver::get_runtime()
                .block_on(async {
                    let conn = Connection::session().await.map_err(map_bus_error)?;
                    let service = SecretServiceProxy::new(&conn)
                        .await
                        .map_err(map_bus_error)?;
                    let (_output, session) = service
                        .open_session("plain", &Variant::from(""))
                        .await
                        .map_err(map_bus_error)?;

                    let item = match find_item(&service, key).await.map_err(map_bus_error)? {
                        Some(item) => item,
                        None => return Ok(None),
                    };
                    let item_proxy = SecretItemProxy::builder(&conn)
                        .cache_properties(CacheProperties::No)
                        .path(item)
                        .destination("org.freedesktop.secrets")
                        .build()
                        .await
                        .map_err(map_bus_error)?;
                    let secret = item_proxy
                        .get_secret(&session)
                        .await
                        .map_err(map_bus_error)?;
                    Ok(Some(secret.value))
                })
        }

        fn delete_secret(&self, key: &str) -> Result<(), EngineError> {
            crate::resolver::get_runtime()
                .block_on(async {
                    let conn = Connection::session().await.map_err(map_bus_error)?;
                    let service = SecretServiceProxy::new(&conn)
                        .await
                        .map_err(map_bus_error)?;
                    service
                        .open_session("plain", &Variant::from(""))
                        .await
                        .map_err(map_bus_error)?;

                    match find_item(&service, key).await.map_err(map_bus_error)? {
                        Some(item) => {
                            let item_proxy = SecretItemProxy::builder(&conn)
                                .cache_properties(CacheProperties::No)
                                .path(item)
                                .destination("org.freedesktop.secrets")
                                .build()
                                .await
                                .map_err(map_bus_error)?;
                            let prompt =
                                item_proxy.delete().await.map_err(map_bus_error)?;
                            if prompt.as_str() == "/" {
                                Ok(())
                            } else {
                                Err(storage_err(StorageErrorKind::Permission))
                            }
                        }
                        // Idempotent delete: absent is success.
                        None => Ok(()),
                    }
                })
        }
    }
}

#[cfg(target_os = "linux")]
pub use secret_service::SecretServiceStore;

// ─────────────────────────────────────────────────────────────────────────────
//  Apple: Keychain Services provider (MP-CAP04)
// ─────────────────────────────────────────────────────────────────────────────

#[cfg(any(target_os = "macos", target_os = "ios"))]
mod keychain {
    use super::SecureStoreProvider;
    use crate::contract::{EngineError, StorageErrorKind};
    use security_framework::base::Error as KeychainError;
    use security_framework::passwords::{
        delete_generic_password, get_generic_password, set_generic_password,
    };

    /// Keychain service label every Streamify secret is filed under
    /// (`kSecAttrService`; the account field carries the caller's key).
    const SERVICE: &str = "com.streamify.core";

    /// `errSecItemNotFound` — the Security framework's "no such item"
    /// status (kept as a local constant: security-framework-sys does
    /// not re-export it uniformly).
    const ERR_SEC_ITEM_NOT_FOUND: i32 = -25300;

    /// `errSecAuthFailed` / `errSecMissingEntitlement` — access denied
    /// by the keychain (locked keychain, or missing keychain-access
    /// group entitlement on iOS).
    const ERR_SEC_AUTH_FAILED: i32 = -25293;
    const ERR_SEC_MISSING_ENTITLEMENT: i32 = -34018;

    /// Keychain-backed provider: generic passwords
    /// (`kSecClassGenericPassword`) via the pure-Rust Security.framework
    /// bindings — the same API surface the master plan names
    /// (SecItemAdd / SecItemCopyMatching / SecItemDelete, wrapped by
    /// the crate's upsert-safe `set_generic_password`).
    pub struct KeychainStore;

    impl KeychainStore {
        pub fn new() -> Self {
            Self
        }
    }

    impl Default for KeychainStore {
        fn default() -> Self {
            Self::new()
        }
    }

    fn map_keychain_error(err: KeychainError) -> EngineError {
        match err.code() {
            ERR_SEC_AUTH_FAILED | ERR_SEC_MISSING_ENTITLEMENT => EngineError::Storage {
                kind: StorageErrorKind::Permission,
            },
            _ => EngineError::Storage {
                kind: StorageErrorKind::Io,
            },
        }
    }

    impl SecureStoreProvider for KeychainStore {
        fn store_secret(&self, key: &str, secret: &[u8]) -> Result<(), EngineError> {
            // set_generic_password is upsert (SecItemUpdate-or-add),
            // matching the trait's replace semantics.
            set_generic_password(SERVICE, key, secret).map_err(map_keychain_error)
        }

        fn retrieve_secret(&self, key: &str) -> Result<Option<Vec<u8>>, EngineError> {
            match get_generic_password(SERVICE, key) {
                Ok(secret) => Ok(Some(secret)),
                Err(err) if err.code() == ERR_SEC_ITEM_NOT_FOUND => Ok(None),
                Err(err) => Err(map_keychain_error(err)),
            }
        }

        fn delete_secret(&self, key: &str) -> Result<(), EngineError> {
            match delete_generic_password(SERVICE, key) {
                Ok(()) => Ok(()),
                // Idempotent delete: absent is success.
                Err(err) if err.code() == ERR_SEC_ITEM_NOT_FOUND => Ok(()),
                Err(err) => Err(map_keychain_error(err)),
            }
        }
    }
}

#[cfg(any(target_os = "macos", target_os = "ios"))]
pub use keychain::KeychainStore;

// ─────────────────────────────────────────────────────────────────────────────
//  Host-lane unit tests: the universal contract halves (platform
//  backends are exercised on their own targets / by the roundtrip).
// ─────────────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn in_memory_contract_roundtrip_and_semantics() {
        let store = InMemorySecureStore::new();
        // Absent → None, not an error.
        assert_eq!(store.retrieve_secret("k").unwrap(), None);
        // Upsert.
        store.store_secret("k", b"v1").unwrap();
        assert_eq!(store.retrieve_secret("k").unwrap(), Some(b"v1".to_vec()));
        store.store_secret("k", b"v2").unwrap();
        assert_eq!(store.retrieve_secret("k").unwrap(), Some(b"v2".to_vec()));
        // Idempotent delete.
        store.delete_secret("k").unwrap();
        store.delete_secret("k").unwrap();
        assert_eq!(store.retrieve_secret("k").unwrap(), None);
    }

    #[test]
    fn foreign_delegate_maps_statuses_onto_the_taxonomy() {
        use super::SecureStoreOpStatus as St;

        // Success → not an error.
        assert_eq!(St::Success.to_engine_error(), None);
        // Missing item stays data, not an error, through the provider.
        assert_eq!(
            St::NotFound.to_engine_error(),
            Some(EngineError::Storage {
                kind: StorageErrorKind::NotFound
            })
        );
        for (status, kind) in [
            (St::Permission, StorageErrorKind::Permission),
            (St::Corrupt, StorageErrorKind::Corrupt),
            (St::Io, StorageErrorKind::Io),
        ] {
            assert_eq!(
                status.to_engine_error(),
                Some(EngineError::Storage { kind })
            );
        }
    }

    #[test]
    fn foreign_delegate_store_full_contract() {
        use super::{SecureFetchResult, SecureStoreOpStatus as St};

        /// Recording mock: the Rust-side twin of what Beta's Kotlin
        /// EncryptedSharedPreferences delegate does.
        struct MockDelegate {
            entries: Mutex<HashMap<String, Vec<u8>>>,
            fail_store: Mutex<bool>,
        }

        impl SecureStoreDelegate for MockDelegate {
            fn store_secret(&self, key: String, secret: Vec<u8>) -> St {
                if *self.fail_store.lock().unwrap() {
                    return St::Io;
                }
                self.entries.lock().unwrap().insert(key, secret);
                St::Success
            }

            fn retrieve_secret(&self, key: String) -> SecureFetchResult {
                match self.entries.lock().unwrap().get(&key) {
                    Some(secret) => SecureFetchResult {
                        status: St::Success,
                        secret: Some(secret.clone()),
                    },
                    None => SecureFetchResult {
                        status: St::NotFound,
                        secret: None,
                    },
                }
            }

            fn delete_secret(&self, key: String) -> St {
                self.entries.lock().unwrap().remove(&key);
                St::Success
            }
        }

        let mock = Arc::new(MockDelegate {
            entries: Mutex::new(HashMap::new()),
            fail_store: Mutex::new(false),
        });
        let store = ForeignDelegateStore::new(mock.clone());

        store.store_secret("token", b"sekrit").unwrap();
        assert_eq!(
            store.retrieve_secret("token").unwrap(),
            Some(b"sekrit".to_vec())
        );
        assert_eq!(store.retrieve_secret("absent").unwrap(), None);
        store.delete_secret("token").unwrap();
        assert_eq!(store.retrieve_secret("token").unwrap(), None);

        // Failure mapping: a delegate-side Io surfaces as the typed
        // Storage error, not a panic and not a swallow.
        *mock.fail_store.lock().unwrap() = true;
        assert_eq!(
            store.store_secret("token", b"x"),
            Err(EngineError::Storage {
                kind: StorageErrorKind::Io
            })
        );
    }

    #[test]
    fn roundtrip_selftest_holds_on_the_active_backend() {
        // On CI hosts without a platform backend this exercises the
        // in-memory fallback (delegate untouched by parallel tests);
        // on Windows/Linux/macOS it exercises the real native backend
        // when its OS service is reachable — the same self-test the
        // shells run at startup through the UDL surface.
        let ok = secure_store_roundtrip(
            "unit-probe".to_string(),
            b"probe-bytes-0123456789".to_vec(),
        )
        .unwrap();
        assert!(ok, "active secure-store backend must round-trip");
    }
}
