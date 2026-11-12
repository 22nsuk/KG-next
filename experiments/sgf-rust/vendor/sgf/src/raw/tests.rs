#![allow(clippy::unwrap_used)]
use super::*;
use proptest::prelude::*;

fn properties(document: &RawDocument<'_>) -> Vec<(String, String)> {
    document
        .events
        .iter()
        .filter_map(|event| match event {
            RawEvent::Property {
                identifier,
                raw_value,
            } => Some((identifier.clone(), (*raw_value).into())),
            _ => None,
        })
        .collect()
}

#[test]
fn both_analysis_slots_and_duplicate_values_preserve_order() {
    let parsed =
        parse_kg_raw("(;LZ[first]LZ2[second]LZOP[one][two]LZOP2[three]LZ2[again])").unwrap();
    assert_eq!(
        properties(&parsed),
        vec![
            ("LZ".into(), "first".into()),
            ("LZ2".into(), "second".into()),
            ("LZOP".into(), "one".into()),
            ("LZOP".into(), "two".into()),
            ("LZOP2".into(), "three".into()),
            ("LZ2".into(), "again".into()),
        ]
    );
}

#[test]
fn escapes_and_physical_newlines_are_exact_raw_slices() {
    let value = "a\\]b\\\\c\\n한글\r\nline\\\r\nnext\\:tail";
    let input = format!("(;C[{value}]PB[{value}]XX[{value}]LZ2[{value}])");
    let parsed = parse_kg_raw(&input).unwrap();
    assert_eq!(properties(&parsed).len(), 4);
    for (_, raw) in properties(&parsed) {
        assert_eq!(raw, value);
    }
    assert!(
        std::ptr::eq(
            match &parsed.events[2] {
                RawEvent::Property { raw_value, .. } => raw_value.as_ptr(),
                _ => panic!("expected property"),
            },
            input[4..].as_ptr()
        ),
        "raw mode borrows the original UTF-8 value"
    );
}

#[test]
fn stray_brackets_do_not_change_names_or_extend_values() {
    let parsed = parse_kg_raw("(;SZ[19]]]LZ2[x]]LZOP2[y];B[aa]](;W[bb]))").unwrap();
    assert_eq!(
        properties(&parsed),
        vec![
            ("SZ".into(), "19".into()),
            ("LZ2".into(), "x".into()),
            ("LZOP2".into(), "y".into()),
            ("B".into(), "aa".into()),
            ("W".into(), "bb".into()),
        ]
    );
    // The first bracket closes C. No lookahead may turn it into literal text.
    assert!(parse_kg_raw("(;C[one] literal text])").is_ok());
    assert_eq!(
        properties(&parse_kg_raw("(;C[one] literal text])").unwrap()),
        vec![("C".into(), "one".into())]
    );
    assert_eq!(
        properties(&parse_kg_raw("(;C[x]XX]W[bb])").unwrap()),
        vec![("C".into(), "x".into()), ("W".into(), "bb".into())]
    );
}

#[test]
fn names_drop_only_ascii_lowercase_and_reset_at_all_structural_tokens() {
    let parsed = parse_kg_raw(
        "(;Player Black[A]lower[x]LzZ2[y]unfinished;W[bb]pending(;B[cc]tail)(;W[dd]))",
    )
    .unwrap();
    assert_eq!(
        properties(&parsed),
        vec![
            ("PB".into(), "A".into()),
            ("".into(), "x".into()),
            ("LZ2".into(), "y".into()),
            ("W".into(), "bb".into()),
            ("B".into(), "cc".into()),
            ("W".into(), "dd".into()),
        ]
    );
    assert_eq!(
        parsed
            .events
            .iter()
            .filter(|event| matches!(event, RawEvent::Open))
            .count(),
        3
    );
    assert_eq!(
        parsed
            .events
            .iter()
            .filter(|event| matches!(event, RawEvent::Close))
            .count(),
        3
    );
    assert!(
        parse_kg_raw("(;C[x]tail;[y])").is_err(),
        "orphan values require Java fallback"
    );
}

#[test]
fn collections_branches_and_empty_nodes_keep_exact_event_order() {
    let parsed = parse_kg_raw("(;;B[aa](;W[bb])(;W[cc]))(;C[second])").unwrap();
    let kinds: String = parsed
        .events
        .iter()
        .map(|event| match event {
            RawEvent::Open => '(',
            RawEvent::Node => ';',
            RawEvent::Property { .. } => 'P',
            RawEvent::Close => ')',
        })
        .collect();
    assert_eq!(kinds, "(;;P(;P)(;P))(;P)");
}

#[test]
fn error_boundaries_reject_instead_of_synthesizing_events() {
    for input in [
        "",
        "()",
        "((;B[aa]))",
        "(;C[x]",
        "(;C[unfinished",
        "(;C[unfinished\\",
        "(;C[x](;B[aa]);W[bb])",
        "(;X_[a])",
        "(;C[x])trailing",
    ] {
        let error = parse_kg_raw(input).unwrap_err();
        assert!(error.position <= input.len(), "{input:?}: {error:?}");
    }
    assert_eq!(
        parse_kg_raw("(;C[x]").unwrap_err().kind,
        ErrorKind::MissingClosingParenthesis
    );
    assert_eq!(parse_kg_raw("(;C[unfinished").unwrap_err().position, 3);
    let at_limit = format!("{}{}", "(;".repeat(256), ")".repeat(256));
    assert!(parse_kg_raw(&at_limit).is_ok());
    let over_limit = format!("{}{}", "(;".repeat(257), ")".repeat(257));
    assert_eq!(
        parse_kg_raw(&over_limit).unwrap_err().kind,
        ErrorKind::NestingLimit { limit: 256 }
    );
}

#[test]
fn event_and_repeated_identifier_amplification_are_bounded() {
    let at_limit = format!("({})", ";".repeat(MAX_RAW_EVENTS - 2));
    assert_eq!(
        parse_kg_raw(&at_limit).unwrap().events.len(),
        MAX_RAW_EVENTS
    );
    let over_limit = format!("({})", ";".repeat(MAX_RAW_EVENTS - 1));
    assert_eq!(
        parse_kg_raw(&over_limit).unwrap_err().kind,
        ErrorKind::RawEventLimit {
            limit: MAX_RAW_EVENTS
        }
    );
    let long_identifier = "X".repeat(65_536);
    let ordinary = format!("(;{long_identifier}{})", "[]".repeat(10));
    assert!(parse_kg_raw(&ordinary).is_ok());
    // A small input must not clone its long identifier into unbounded memory.
    let amplified = format!("(;{long_identifier}{})", "[]".repeat(1024));
    assert_eq!(
        parse_kg_raw(&amplified).unwrap_err().kind,
        ErrorKind::RawSizeLimit {
            limit: MAX_RAW_EVENT_BYTES
        }
    );
}

proptest! {
    #![proptest_config(ProptestConfig { cases: 1000, failure_persistence: None, ..ProptestConfig::default() })]

    #[test]
    fn arbitrary_raw_values_are_never_decoded(chars in prop::collection::vec(any::<char>(), 0..100)) {
        let plain: String = chars.into_iter().collect();
        let raw = plain.replace('\\', "\\\\").replace(']', "\\]");
        let input = format!("(;LZ2[{raw}]LZOP2[{raw}])");
        let parsed = parse_kg_raw(&input).unwrap();
        prop_assert_eq!(properties(&parsed), vec![("LZ2".into(), raw.clone()), ("LZOP2".into(), raw)]);
    }

    #[test]
    fn arbitrary_utf8_syntax_never_panics(input in ".{0,2048}") {
        let _ = parse_kg_raw(&input);
    }
}
