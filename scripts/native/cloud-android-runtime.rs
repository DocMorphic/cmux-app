//! Process-owned Android context for this cdylib's DNS and TLS dependencies.
use jni::{EnvUnowned, Outcome, objects::{Global, JObject}};
use std::ffi::c_void;
use std::sync::{Mutex, OnceLock};

static INIT: Mutex<()> = Mutex::new(());
// DNS keeps this raw reference; retaining the global for process lifetime is intentional.
static APPLICATION: OnceLock<Global<JObject<'static>>> = OnceLock::new();

/// Called only by the Android JNI bridge with a live JNI frame/application context.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cmux_android_initialize(raw_env: *mut c_void, context: *mut c_void) -> bool {
    if raw_env.is_null() || context.is_null() { return false; }
    let Ok(_guard) = INIT.lock() else { return false; };
    if APPLICATION.get().is_some() { return true; }
    let mut unowned = unsafe { EnvUnowned::from_raw(raw_env.cast()) };
    let outcome = unowned.with_env(|env| -> Result<(), jni::errors::Error> {
        let local = unsafe { JObject::from_raw(env, context.cast()) };
        let vm = env.get_java_vm()?;
        let global = env.new_global_ref(&local)?;
        rustls_platform_verifier::android::init_with_env(env, local)?;
        // These pointers remain live until process exit. INIT excludes repeated installation.
        unsafe { iroh_dns::install_android_jni_context(vm.get_raw().cast(), global.as_raw().cast()); }
        let _ = APPLICATION.set(global);
        Ok(())
    }).into_outcome();
    matches!(outcome, Outcome::Ok(()))
}
