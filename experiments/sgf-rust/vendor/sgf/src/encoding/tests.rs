#![allow(clippy::unwrap_used)]

use encoding_rs::{BIG5, EUC_KR, GBK, SHIFT_JIS};

use super::*;

fn encoded(text: &str, encoding: &'static Encoding) -> Vec<u8> {
    let (bytes, _, errors) = encoding.encode(text);
    assert!(!errors, "test text must be representable");
    bytes.into_owned()
}

fn utf16(text: &str, big_endian: bool) -> Vec<u8> {
    let mut bytes = if big_endian {
        vec![0xfe, 0xff]
    } else {
        vec![0xff, 0xfe]
    };
    for unit in text.encode_utf16() {
        bytes.extend(if big_endian {
            unit.to_be_bytes()
        } else {
            unit.to_le_bytes()
        });
    }
    bytes
}

#[test]
fn bom_is_authoritative_and_removed() {
    let text = "(;CA[GBK]C[围棋🀄])";
    let mut bytes = vec![0xef, 0xbb, 0xbf];
    bytes.extend(text.as_bytes());
    let result = decode(&bytes).unwrap();
    assert_eq!(result.text, text);
    assert_eq!(result.encoding, "UTF-8-BOM");
    assert_eq!(result.warnings.len(), 1);
    assert_eq!(result.warnings[0].position, 2);
    assert_eq!(
        result.warnings[0].kind,
        WarningKind::EncodingMismatch {
            declared: "GBK".to_owned(),
            detected: "UTF-8-BOM".to_owned(),
        }
    );
}

#[test]
fn utf16_boms_decode_both_endiannesses_and_surrogate_pairs() {
    let text = "(;CA[UTF-16]C[围棋🀄])";
    for (big_endian, name) in [(false, "UTF-16LE-BOM"), (true, "UTF-16BE-BOM")] {
        let result = decode(&utf16(text, big_endian)).unwrap();
        assert_eq!(result.text, text);
        assert_eq!(result.encoding, name);
        assert!(result.warnings.is_empty());
    }
}

#[test]
fn malformed_bom_data_returns_the_original_byte_offset() {
    let error = decode(&[0xef, 0xbb, 0xbf, b'(', 0xff]).err().unwrap();
    assert_eq!(error.position, 4);
    assert_eq!(
        error.kind,
        ErrorKind::InvalidEncoding {
            encoding: "UTF-8".to_owned(),
        }
    );
    let error = decode(&[0xff, 0xfe, b'(', 0, 0, 0xd8]).err().unwrap();
    assert_eq!(error.position, 4);
    assert!(decode(&[0xfe, 0xff, 0]).is_err());
}

#[test]
fn unsupported_utf32_bom_is_not_misread_as_utf16() {
    for bytes in [[0xff, 0xfe, 0, 0], [0, 0, 0xfe, 0xff]] {
        let error = decode(&bytes).err().unwrap();
        assert_eq!(error.position, 0);
        assert_eq!(
            error.kind,
            ErrorKind::InvalidEncoding {
                encoding: "UTF-32 (unsupported)".to_owned(),
            }
        );
    }
}

#[test]
fn valid_utf8_precedes_a_conflicting_declaration() {
    let text = "(;CA[Shift_JIS]C[日本囲碁])";
    let result = decode(text.as_bytes()).unwrap();
    assert_eq!(result.text, text);
    assert_eq!(result.encoding, "UTF-8");
    assert!(matches!(
        result.warnings[0].kind,
        WarningKind::EncodingMismatch { .. }
    ));
}

#[test]
fn explicit_legacy_encodings_are_honored() {
    for (encoding, text) in [
        (GBK, "(;CA[GBK]C[围棋])"),
        (GB18030, "(;CA[GB18030]C[围棋𠀀])"),
        (BIG5, "(;CA[Big5]C[圍棋])"),
        (SHIFT_JIS, "(;CA[Shift_JIS]C[日本囲碁])"),
        (EUC_KR, "(;CA[EUC-KR]C[한국 바둑])"),
    ] {
        let result = decode(&encoded(text, encoding)).unwrap();
        assert_eq!(result.text, text);
        assert_eq!(
            result.encoding,
            if encoding == GB18030 {
                "GB18030"
            } else {
                encoding.name()
            }
        );
        assert!(result.warnings.is_empty());
    }
}

#[test]
fn gb18030_fallback_includes_gbk_and_four_byte_characters() {
    for (encoding, text) in [
        (GBK, "(;C[没有编码声明的中文])"),
        (GB18030, "(;C[四字节字符𠀀，围棋])"),
    ] {
        let result = decode(&encoded(text, encoding)).unwrap();
        assert_eq!(result.text, text);
        assert_eq!(result.encoding, "GB18030");
        assert!(result.warnings.is_empty());
    }
}

#[test]
fn invalid_utf8_declaration_falls_back_with_a_warning() {
    let text = "(;CA[UTF-8]C[实际为 GB18030：𠀀])";
    let result = decode(&encoded(text, GB18030)).unwrap();
    assert_eq!(result.text, text);
    assert_eq!(result.encoding, "GB18030");
    assert_eq!(
        result.warnings[0].kind,
        WarningKind::EncodingMismatch {
            declared: "UTF-8".to_owned(),
            detected: "GB18030".to_owned(),
        }
    );
}

#[test]
fn unknown_declaration_is_reported_even_when_utf8_succeeds() {
    for (encoding, text) in [
        (UTF_8, "(;CA[not-a-charset]C[围棋])"),
        (GBK, "(;CA[not-a-charset]C[围棋])"),
    ] {
        let result = decode(&encoded(text, encoding)).unwrap();
        assert_eq!(result.text, text);
        assert_eq!(
            result.warnings[0].kind,
            WarningKind::UnknownEncoding {
                declared: "not-a-charset".to_owned(),
            }
        );
    }
}

#[test]
fn declaration_scanner_obeys_first_game_root_and_escapes() {
    for text in [
        r"(;C[CA[Big5\]围棋])",
        r"(;C[围棋];CA[Big5])",
        r"(;C[围棋](;CA[Big5]))",
        r"(;C[围棋])(;CA[Big5])",
        r"(;XX[围棋][CA[Big5\]])",
    ] {
        let result = decode(&encoded(text, GBK)).unwrap();
        assert_eq!(result.text, text);
        assert_eq!(result.encoding, "GB18030");
        assert!(result.warnings.is_empty());
    }
}

#[test]
fn declaration_supports_recovered_identifiers_and_escaped_labels() {
    for declaration in [r"ca[Shift_JIS]", r"ChArset[Shift_JIS]", r"CA[Shift\_JIS]"] {
        let text = format!("(;{declaration}C[日本囲碁])");
        let result = decode(&encoded(&text, SHIFT_JIS)).unwrap();
        assert_eq!(result.text, text);
        assert_eq!(result.encoding, "Shift_JIS");
        assert!(result.warnings.is_empty());
    }
}

#[test]
fn multibyte_trail_punctuation_before_ca_cannot_hide_the_declaration() {
    // Big5 contains ASCII-looking backslashes and brackets as trail bytes.
    for character in ['功', '許', '也'] {
        let text = format!("(;C[{character}圍棋]CA[Big5])");
        let result = decode(&encoded(&text, BIG5)).unwrap();
        assert_eq!(result.text, text);
        assert_eq!(result.encoding, "Big5");
    }
}

#[test]
fn recoverable_bracket_before_ca_does_not_hide_the_declaration() {
    let text = "(;C[a] stray]CA[Shift_JIS]C[日本])";
    let result = decode(&encoded(text, SHIFT_JIS)).unwrap();
    assert_eq!(result.text, text);
    assert_eq!(result.encoding, "Shift_JIS");
    assert!(result.warnings.is_empty());
}

#[test]
fn corrupt_fallback_is_an_error_without_lossy_replacement() {
    let error = decode(b"(;C[abc\xff])").err().unwrap();
    assert_eq!(error.position, 7);
    let error = decode(b"(;C[abc\x81").err().unwrap();
    assert_eq!(error.position, 7);
}

#[test]
fn decoding_across_output_chunks_preserves_text_and_error_offsets() {
    let text = format!("(;CA[GB18030]C[{}𠀀])", "围棋".repeat(3000));
    let mut bytes = encoded(&text, GB18030);
    assert_eq!(decode(&bytes).unwrap().text, text);
    let bad_offset = bytes.len();
    bytes.push(0xff);
    assert_eq!(decode(&bytes).err().unwrap().position, bad_offset);
}
