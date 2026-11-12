use super::*;

fn read_text(bytes: &mut &[u8]) -> String {
    let (length, rest) = bytes.split_at(4);
    let length = u32::from_be_bytes(length.try_into().unwrap()) as usize;
    let (text, rest) = rest.split_at(length);
    *bytes = rest;
    std::str::from_utf8(text).unwrap().into()
}

#[test]
fn raw_protocol_carries_raw_values_and_upstream_protocol_stays_decoded() {
    let input = b"(;C[a\\]b\\\\c\\n])";
    for (mode, expected) in [
        (ParseMode::KgRaw, "a\\]b\\\\c\\n"),
        (ParseMode::Upstream, "a]b\\cn"),
    ] {
        let response = response_for_bytes(input, mode);
        assert_eq!(response[8], 0);
        let mut body = &response[9..];
        assert_eq!(read_text(&mut body), "UTF-8");
        assert_eq!(&body[..7], b"\0\0\0\0(;P");
        body = &body[7..];
        assert_eq!(read_text(&mut body), "C");
        assert_eq!(read_text(&mut body), expected);
        assert_eq!(body, b")");
    }
}

#[test]
fn output_limit_and_panic_become_error_responses() {
    let mut encoder = Encoder::new(0, 0);
    encoder.limit = 12;
    assert!(encoder.text("too long").is_err());
    assert!(encoder.bytes.len() <= encoder.limit);
    let response = guarded_response(|| panic!("controlled probe panic"));
    assert_eq!(response[8], 1);
    assert_eq!(read_text(&mut &response[9..]), "Rust SGF probe panicked");
}

#[test]
fn native_valid_invalid_and_repeated_results_are_always_freed() {
    // Only this test allocates FFI handles, so the count checks do not race with
    // another test. Run this alongside the real Java/JNA finally-path tests.
    let before = kg_sgf_live_results();
    unsafe {
        assert!(kg_sgf_result_data(std::ptr::null()).is_null());
        assert_eq!(kg_sgf_result_len(std::ptr::null()), 0);
        kg_sgf_free(std::ptr::null_mut());
        let valid = b"(;LZ2[x]LZOP2[y])";
        for (data, len, status) in [
            (valid.as_ptr(), valid.len() as u64, 0),
            (b"\xff".as_ptr(), 1, 1),
            (std::ptr::null(), 0, 1),
            (std::ptr::null(), 1, 1),
            (std::ptr::dangling::<u8>(), MAX_FRAME as u64 + 1, 1),
            (std::ptr::null(), u64::MAX, 1),
        ] {
            for _ in 0..100 {
                let handle = kg_sgf_parse(data, len);
                assert!(!handle.is_null());
                assert_eq!(kg_sgf_live_results(), before + 1);
                let length = kg_sgf_result_len(handle) as usize;
                assert!((13..=MAX_FRAME).contains(&length));
                let bytes = std::slice::from_raw_parts(kg_sgf_result_data(handle), length);
                assert_eq!(bytes[8], status);
                kg_sgf_free(handle);
                assert_eq!(kg_sgf_live_results(), before);
            }
        }
    }
}
