//! Opt-in SGF experiment. No application/runtime hook loads this library.
//!
//! The C ABI uses opaque allocation handles, not shared Rust/C structures.
//! `kg_sgf_parse` consumes Java-decoded and normalized UTF-8 and returns a handle
//! even for a parse error. Read the result while the handle is live, then call
//! `kg_sgf_free` exactly once. Returned data remains borrowed from the handle;
//! it must be copied before freeing. Null result handles are accepted by the
//! accessors/free, but other handles must be live allocations from this exact
//! library. Invalid, forged, double-freed, or concurrently freed handles violate
//! the caller contract. Input memory must remain readable throughout the call.
//! No cancellation or library-unload guarantee is provided while a call runs.
//! Input and response bodies are capped at 64 MiB. Raw syntax also has a one
//! million event/64 MiB event-byte budget and depth 256; exceeding a budget is
//! an explicit parse error. This limits retained data but does not claim to
//! recover from process out-of-memory aborts or invalid caller pointers.
//!
//! Body format (all integers big endian): u64 parse nanoseconds; u8 status;
//! on status 1, u32-byte-length UTF-8 error; on status 0, UTF-8 encoding string,
//! u32 warning count with UTF-8 strings, then `(`, `;`, `)`, or `P` events.
//! A `P` event contains two UTF-8 strings: identifier and exact raw value.
//! Native responses contain no outer frame length. The pipe adds a u32 frame
//! length; its no-argument mode retains upstream decoded-value semantics.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;

pub const MAX_FRAME: usize = 64 * 1024 * 1024;
static LIVE_RESULTS: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Copy)]
pub enum ParseMode {
    Upstream,
    KgRaw,
}

/// An opaque C allocation handle. Its layout is never shared across the ABI.
pub struct KgSgfResult {
    data: Vec<u8>,
}

struct Encoder {
    bytes: Vec<u8>,
    limit: usize,
}

impl Encoder {
    fn new(nanos: u64, status: u8) -> Self {
        let mut bytes = nanos.to_be_bytes().to_vec();
        bytes.push(status);
        Self {
            bytes,
            limit: MAX_FRAME,
        }
    }

    fn append(&mut self, value: &[u8]) -> Result<(), &'static str> {
        if value.len() > self.limit.saturating_sub(self.bytes.len()) {
            return Err("probe response exceeds 64 MiB");
        }
        self.bytes.extend_from_slice(value);
        Ok(())
    }

    fn text(&mut self, value: &str) -> Result<(), &'static str> {
        let length = u32::try_from(value.len()).map_err(|_| "probe text length exceeds u32")?;
        self.append(&length.to_be_bytes())?;
        self.append(value.as_bytes())
    }

    fn property(&mut self, identifier: &str, value: &str) -> Result<(), &'static str> {
        self.append(b"P")?;
        self.text(identifier)?;
        self.text(value)
    }

    fn warnings(&mut self, warnings: &[sgf::Warning]) -> Result<(), &'static str> {
        let count = u32::try_from(warnings.len()).map_err(|_| "too many probe warnings")?;
        self.append(&count.to_be_bytes())?;
        for warning in warnings {
            self.text(&format!("{}: {:?}", warning.position, warning.kind))?;
        }
        Ok(())
    }

    fn tree(&mut self, game: &sgf::GameTree) -> Result<(), &'static str> {
        self.append(b"(")?;
        for node in &game.sequence {
            self.append(b";")?;
            for property in &node.properties {
                for value in &property.values {
                    self.property(&property.identifier, value)?;
                }
            }
        }
        for child in &game.children {
            self.tree(child)?;
        }
        self.append(b")")
    }
}

fn error_response(nanos: u64, message: &str) -> Vec<u8> {
    let mut response = Encoder::new(nanos, 1);
    // Error messages are internal bounded diagnostics, never whole input text.
    if response.text(message).is_err() {
        return error_response(nanos, "probe error message exceeds response limit");
    }
    response.bytes
}

fn elapsed(started: Instant) -> u64 {
    started.elapsed().as_nanos().min(u128::from(u64::MAX)) as u64
}

fn parse_response(bytes: &[u8], mode: ParseMode) -> Vec<u8> {
    if bytes.len() > MAX_FRAME {
        return error_response(0, "probe input exceeds 64 MiB");
    }
    let started = Instant::now();
    match mode {
        ParseMode::Upstream => {
            let collection = sgf::parse(bytes);
            let nanos = elapsed(started);
            let collection = match collection {
                Ok(collection) => collection,
                Err(error) => return error_response(nanos, &error.to_string()),
            };
            let mut response = Encoder::new(nanos, 0);
            let encoded = (|| {
                response.text(&collection.encoding)?;
                response.warnings(&collection.warnings)?;
                for game in &collection.games {
                    response.tree(game)?;
                }
                Ok::<_, &'static str>(())
            })();
            match encoded {
                Ok(()) => response.bytes,
                Err(error) => error_response(nanos, error),
            }
        }
        ParseMode::KgRaw => {
            let text = match std::str::from_utf8(bytes) {
                Ok(text) => text,
                Err(_) => {
                    return error_response(elapsed(started), "raw probe input is not valid UTF-8");
                }
            };
            let parsed = sgf::parse_kg_raw(text);
            let nanos = elapsed(started);
            let document = match parsed {
                Ok(document) => document,
                Err(error) => return error_response(nanos, &error.to_string()),
            };
            let mut response = Encoder::new(nanos, 0);
            let encoded = (|| {
                response.text("UTF-8")?;
                response.warnings(&document.warnings)?;
                for event in &document.events {
                    match event {
                        sgf::RawEvent::Open => response.append(b"(")?,
                        sgf::RawEvent::Node => response.append(b";")?,
                        sgf::RawEvent::Close => response.append(b")")?,
                        sgf::RawEvent::Property {
                            identifier,
                            raw_value,
                        } => response.property(identifier, raw_value)?,
                    }
                }
                Ok::<_, &'static str>(())
            })();
            match encoded {
                Ok(()) => response.bytes,
                Err(error) => error_response(nanos, error),
            }
        }
    }
}

fn guarded_response(parse: impl FnOnce() -> Vec<u8>) -> Vec<u8> {
    match catch_unwind(AssertUnwindSafe(parse)) {
        Ok(response) => response,
        Err(_) => error_response(0, "Rust SGF probe panicked"),
    }
}

/// Shared body encoder for the pipe and native experiment.
pub fn response_for_bytes(bytes: &[u8], mode: ParseMode) -> Vec<u8> {
    guarded_response(|| parse_response(bytes, mode))
}

/// Parse normalized UTF-8. Length/null validation occurs before forming a slice.
///
/// # Safety
/// For nonzero `len`, `data` must point to at least `len` readable bytes for the
/// duration of this call. The returned handle must be freed exactly once using
/// this library's `kg_sgf_free`, after all result reads have finished.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn kg_sgf_parse(data: *const u8, len: u64) -> *mut KgSgfResult {
    let response = guarded_response(|| {
        if len > MAX_FRAME as u64 {
            return error_response(0, "probe input exceeds 64 MiB");
        }
        if data.is_null() && len != 0 {
            return error_response(0, "null probe input with nonzero length");
        }
        let bytes = if len == 0 {
            &[]
        } else {
            // SAFETY: size is bounded and the caller promises readable input.
            unsafe { std::slice::from_raw_parts(data, len as usize) }
        };
        parse_response(bytes, ParseMode::KgRaw)
    });
    let result = Box::new(KgSgfResult { data: response });
    LIVE_RESULTS.fetch_add(1, Ordering::SeqCst);
    Box::into_raw(result)
}

/// # Safety
/// A nonnull handle must be live and allocated by this library. No concurrent
/// free is allowed. The returned byte pointer is valid only until handle free.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn kg_sgf_result_data(result: *const KgSgfResult) -> *const u8 {
    if result.is_null() {
        return std::ptr::null();
    }
    // SAFETY: the caller promises a live handle with no concurrent free.
    unsafe { (*result).data.as_ptr() }
}

/// # Safety
/// A nonnull handle must be live, from this library, and not concurrently freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn kg_sgf_result_len(result: *const KgSgfResult) -> u64 {
    if result.is_null() {
        return 0;
    }
    // SAFETY: the caller promises a live handle with no concurrent free.
    unsafe { (*result).data.len() as u64 }
}

/// # Safety
/// A nonnull handle must be a live allocation from this library, freed exactly
/// once, with no concurrent accesses. Null is a no-op.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn kg_sgf_free(result: *mut KgSgfResult) {
    if result.is_null() {
        return;
    }
    // SAFETY: the caller transfers the unique live allocation back to Rust.
    drop(unsafe { Box::from_raw(result) });
    LIVE_RESULTS.fetch_sub(1, Ordering::SeqCst);
}

/// Diagnostic count of result handles allocated but not yet freed.
#[unsafe(no_mangle)]
pub extern "C" fn kg_sgf_live_results() -> u64 {
    LIVE_RESULTS.load(Ordering::SeqCst)
}

#[cfg(test)]
mod tests;
