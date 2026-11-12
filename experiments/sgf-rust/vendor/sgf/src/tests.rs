#![allow(clippy::unwrap_used, clippy::expect_used)]

use crate::{Collection, ErrorKind, GameTree, Node, Property, WarningKind, parse, serialize};
use proptest::prelude::*;
use serde_json::Value;
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

const STANDARD_PROPERTIES: &str = "AB AE AN AP AR AW B BL BM BR BT C CA CP CR DD DM DO DT EV FF \
    FG GB GC GM GN GW HA HO IT KM KO LB LN MA MN N OB ON OT OW PB PC PL PM PW RE RO RU SL SO \
    SQ ST SZ TB TE TM TR TW UC US V VW W WL WR WT";

fn fixture_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("fixtures/sgf")
}

fn fixture_paths() -> Vec<PathBuf> {
    let mut paths: Vec<_> = std::fs::read_dir(fixture_dir())
        .unwrap()
        .map(|entry| entry.unwrap().path())
        .filter(|path| path.extension().is_some_and(|ext| ext == "sgf"))
        .collect();
    paths.sort();
    paths
}

fn fixture(name: &str) -> Collection {
    parse(&std::fs::read(fixture_dir().join(name)).unwrap()).unwrap()
}

fn nodes(tree: &GameTree) -> Vec<&Node> {
    let mut result: Vec<_> = tree.sequence.iter().collect();
    for child in &tree.children {
        result.extend(nodes(child));
    }
    result
}

fn values<'a>(tree: &'a GameTree, identifier: &str) -> Vec<&'a str> {
    nodes(tree)
        .into_iter()
        .flat_map(|node| &node.properties)
        .filter(|property| property.identifier == identifier)
        .flat_map(|property| property.values.iter().map(String::as_str))
        .collect()
}

fn property(identifier: &str, values: &[&str]) -> Property {
    Property {
        identifier: identifier.into(),
        values: values.iter().map(|value| (*value).into()).collect(),
    }
}

// CA is transport metadata: writing UTF-8 necessarily changes or inserts it.
// All other properties, their order, values, nodes and branches remain exact.
fn utf8_roots(mut games: Vec<GameTree>) -> Vec<GameTree> {
    for game in &mut games {
        let properties = &mut game.sequence[0].properties;
        let mut written = false;
        properties.retain_mut(|property| {
            if property.identifier != "CA" {
                return true;
            }
            if written {
                return false;
            }
            written = true;
            property.values = vec!["UTF-8".into()];
            true
        });
        if !written {
            properties.push(property("CA", &["UTF-8"]));
        }
    }
    games
}

#[test]
fn all_fixtures_match_independent_golden_counts_encoding_and_unknown_names() {
    let manifest: Value =
        serde_json::from_slice(&std::fs::read(fixture_dir().join("manifest.json")).unwrap())
            .unwrap();
    let listed: BTreeSet<_> = manifest["fixtures"]
        .as_array()
        .unwrap()
        .iter()
        .map(|entry| entry["file"].as_str().unwrap().to_owned())
        .collect();
    let paths = fixture_paths();
    let present: BTreeSet<_> = paths
        .iter()
        .map(|path| path.file_name().unwrap().to_str().unwrap().to_owned())
        .collect();
    assert_eq!(listed, present, "every fixture must be in the manifest");
    assert!(paths.len() >= 42);
    let standard: BTreeSet<_> = STANDARD_PROPERTIES.split_whitespace().collect();
    for path in paths {
        let input = std::fs::read(&path).unwrap();
        let parsed = parse(&input).unwrap_or_else(|error| panic!("{}: {error}", path.display()));
        let expected: Value =
            serde_json::from_slice(&std::fs::read(path.with_extension("expected.json")).unwrap())
                .unwrap();
        let actual_encoding = expected["actual_encoding"].as_str().unwrap();
        // GBK bytes are a subset of GB18030 and cannot identify which encoder
        // authored them. The no-CA fallback correctly reports the superset.
        let detected_encoding = if actual_encoding == "GBK" {
            "GB18030"
        } else {
            actual_encoding
        };
        assert_eq!(parsed.encoding, detected_encoding, "{}", path.display());
        let games = expected["games"].as_array().unwrap();
        assert_eq!(parsed.games.len(), games.len(), "{}", path.display());
        for (game, expected) in parsed.games.iter().zip(games) {
            let all_nodes = nodes(game);
            assert_eq!(
                all_nodes.len() as u64,
                expected["node_count"].as_u64().unwrap(),
                "{} node count",
                path.display()
            );
            let comments = all_nodes
                .iter()
                .filter(|node| {
                    node.properties
                        .iter()
                        .any(|property| property.identifier == "C")
                })
                .count();
            assert_eq!(
                comments as u64,
                expected["comment_count"].as_u64().unwrap(),
                "{} comment count",
                path.display()
            );
            let unknown: BTreeSet<_> = all_nodes
                .iter()
                .flat_map(|node| &node.properties)
                .map(|property| property.identifier.as_str())
                .filter(|identifier| !standard.contains(identifier))
                .collect();
            let expected_unknown: BTreeSet<_> = expected["unknown_property_names"]
                .as_array()
                .unwrap()
                .iter()
                .map(|value| value.as_str().unwrap())
                .collect();
            assert_eq!(unknown, expected_unknown, "{}", path.display());
        }
    }
}

#[test]
fn every_fixture_round_trips_all_properties_values_and_branches() {
    for path in fixture_paths() {
        let parsed = parse(&std::fs::read(&path).unwrap()).unwrap();
        let serialized = serialize(&parsed);
        let reparsed = parse(serialized.as_bytes()).unwrap();
        assert_eq!(reparsed.encoding, "UTF-8");
        assert_eq!(
            reparsed.games,
            utf8_roots(parsed.games),
            "{}",
            path.display()
        );
        assert_eq!(serialize(&reparsed), serialized, "{}", path.display());
    }
}

#[test]
fn legacy_and_misdeclared_encoding_comments_match_handwritten_text() {
    for (name, text) in [
        ("gbk-no-ca.sgf", "没有编码声明的中文"),
        ("gb18030-no-ca.sgf", "四字节字符𠀀，围棋"),
        ("utf8-bom.sgf", "带 BOM 的中文"),
        ("ca-mismatch-utf8.sgf", "实际为 UTF-8：围棋"),
        ("ca-mismatch-gb18030.sgf", "实际为 GB18030：𠀀"),
    ] {
        let parsed = fixture(name);
        assert_eq!(values(&parsed.games[0], "C"), vec![text], "{name}");
        if name.starts_with("ca-mismatch") {
            assert!(
                parsed
                    .warnings
                    .iter()
                    .any(|warning| matches!(warning.kind, WarningKind::EncodingMismatch { .. }))
            );
        }
    }
}

#[test]
fn java_lz_payload_is_exact_before_and_after_round_trip() {
    let parsed = fixture("java-saved-lz.sgf");
    let expected = b"KataGo-fixture 41.5 100 2.5 3.0 0.0 rootVisits=100 wrn=0.04\n\
        move G3 visits 80 winrate 5850 prior 3000 scoreMean 2.50 order 0 pv G3 G7";
    assert_eq!(values(&parsed.games[0], "LZ")[0].as_bytes(), expected);
    for path in fixture_paths() {
        let parsed = parse(&std::fs::read(&path).unwrap()).unwrap();
        let reparsed = parse(serialize(&parsed).as_bytes()).unwrap();
        for (before, after) in parsed.games.iter().zip(&reparsed.games) {
            let bytes = |tree: &GameTree| {
                values(tree, "LZ")
                    .into_iter()
                    .map(|text| text.as_bytes().to_vec())
                    .collect::<Vec<_>>()
            };
            assert_eq!(bytes(before), bytes(after), "{}", path.display());
        }
    }
}

#[test]
fn ff3_mixed_case_names_use_uppercase_letters_only() {
    let parsed = fixture("ff3-mixed-case-properties.sgf");
    let game = &parsed.games[0];
    assert_eq!(
        game.sequence[0].properties,
        vec![
            property("FF", &["3"]),
            property("GM", &["1"]),
            property("SZ", &["9"]),
            property("AB", &["aa", "bb"]),
            property("AW", &["cc"]),
            property("C", &["旧格式：保留文本"]),
            property("XP", &["opaque"]),
        ]
    );
    assert_eq!(game.sequence[1].properties[0], property("B", &["dd"]));
    assert_eq!(
        game.children[0].sequence[0].properties,
        vec![property("W", &["ee"])]
    );
    assert_eq!(
        game.children[1].sequence[0].properties,
        vec![property("W", &["ff"])]
    );
    assert!(parsed.warnings.iter().any(|warning| matches!(
        &warning.kind,
        WarningKind::PropertyName { original, normalized }
            if original == "AddBlack" && normalized == "AB"
    )));
}

#[test]
fn all_lowercase_names_are_uppercased_and_warn() {
    let parsed = parse(b"(;ff[4]sz[9];b[aa];w[bb])").unwrap();
    assert_eq!(
        parsed.games[0].sequence[0].properties,
        vec![property("FF", &["4"]), property("SZ", &["9"])]
    );
    assert_eq!(
        parsed.games[0].sequence[1].properties,
        vec![property("B", &["aa"])]
    );
    assert_eq!(
        parsed.games[0].sequence[2].properties,
        vec![property("W", &["bb"])]
    );
    assert_eq!(parsed.warnings.len(), 4);
}

#[test]
fn missing_parenthesis_recovers_without_losing_variations() {
    let complete = parse(b"(;C[root](;B[aa])(;B[bb](;W[cc])(;W[dd])))").unwrap();
    let recovered = parse(b"(;C[root](;B[aa])(;B[bb](;W[cc])(;W[dd]").unwrap();
    assert_eq!(complete.games, recovered.games);
    assert_eq!(
        recovered
            .warnings
            .iter()
            .filter(|warning| warning.kind == WarningKind::MissingClosingParenthesis)
            .count(),
        3
    );
}

#[test]
fn unescaped_closing_bracket_recovers_literal_text_and_warns() {
    let parsed = fixture("unescaped-closing-bracket.sgf");
    assert_eq!(values(&parsed.games[0], "C"), vec!["右括号 ] 仍是注释"]);
    assert_eq!(
        parsed
            .warnings
            .iter()
            .filter(|warning| warning.kind == WarningKind::UnescapedClosingBracket)
            .count(),
        1
    );
    let reparsed = parse(serialize(&parsed).as_bytes()).unwrap();
    assert_eq!(reparsed.games, utf8_roots(parsed.games));
    assert!(
        !reparsed
            .warnings
            .iter()
            .any(|warning| warning.kind == WarningKind::UnescapedClosingBracket)
    );
}

#[test]
fn duplicate_properties_preserve_order_and_all_raw_values() {
    let parsed = parse(b"(;C[first]XX[one][two]C[second]XX[three])").unwrap();
    assert_eq!(
        parsed.games[0].sequence[0].properties,
        vec![
            property("C", &["first"]),
            property("XX", &["one", "two"]),
            property("C", &["second"]),
            property("XX", &["three"]),
        ]
    );
    assert_eq!(
        parsed
            .warnings
            .iter()
            .filter(|warning| matches!(warning.kind, WarningKind::DuplicateProperty { .. }))
            .count(),
        2
    );
    assert_eq!(
        parse(serialize(&parsed).as_bytes()).unwrap().games,
        utf8_roots(parsed.games)
    );
}

#[test]
fn escapes_and_line_endings_are_decoded_without_text_semantics() {
    let parsed =
        parse(b"(;C[a\rb\r\nc\n\rd\ne\\\r\nf\\\ng\\\rh\\]i\\\\j\\:k]PB[  A\tB  ]LZ[x\ny])")
            .unwrap();
    assert_eq!(
        values(&parsed.games[0], "C"),
        vec!["a\nb\nc\nd\nefgh]i\\j:k"]
    );
    assert_eq!(values(&parsed.games[0], "PB"), vec!["  A\tB  "]);
    assert_eq!(values(&parsed.games[0], "LZ"), vec!["x\ny"]);
    let serialized = serialize(&parsed);
    assert!(!serialized.contains('\r'));
    assert_eq!(
        parse(serialized.as_bytes()).unwrap().games,
        utf8_roots(parsed.games)
    );
}

#[test]
fn serializer_normalizes_programmatically_constructed_values_and_escapes_delimiters() {
    let collection = Collection {
        games: vec![GameTree {
            sequence: vec![Node {
                properties: vec![property("XX", &["a\rb\r\nc\n\rd\ne]\\f"])],
            }],
            children: vec![],
        }],
        encoding: "UTF-8".into(),
        warnings: vec![],
    };
    let serialized = serialize(&collection);
    assert_eq!(serialized, "(;XX[a\nb\nc\nd\ne\\]\\\\f]CA[UTF-8])\n");
    let reparsed = parse(serialized.as_bytes()).unwrap();
    assert_eq!(values(&reparsed.games[0], "XX"), vec!["a\nb\nc\nd\ne]\\f"]);
}

#[test]
fn serializer_sets_ca_on_each_root_without_changing_nonroot_ca() {
    let parsed = parse(b"(;CA[GBK]C[a]CA[Big5];CA[private])(;C[b](;CA[variation]))").unwrap();
    let before = parsed.clone();
    let serialized = serialize(&parsed);
    assert_eq!(parsed, before, "serialization must not mutate caller data");
    let reparsed = parse(serialized.as_bytes()).unwrap();
    assert_eq!(reparsed.games, utf8_roots(parsed.games));
    assert_eq!(values(&reparsed.games[0], "CA"), vec!["UTF-8", "private"]);
    assert_eq!(values(&reparsed.games[1], "CA"), vec!["UTF-8", "variation"]);
}

#[test]
fn malformed_inputs_report_positions_instead_of_panicking() {
    for input in [
        b"".as_slice(),
        b"garbage",
        b"()",
        b"(;C)",
        b"(;C[unterminated",
        b"(;C[unfinished\\",
        b"(;B[aa])garbage",
        b"((;B[aa]))",
    ] {
        let error = parse(input).unwrap_err();
        assert!(error.position <= input.len(), "{input:?}: {error:?}");
    }
    let input = "(;C[中文]X)";
    let error = parse(input.as_bytes()).unwrap_err();
    assert!(error.position >= "(;C[中文]".len());
    assert!(error.position <= input.len());
}

#[test]
fn errors_and_recovery_warnings_identify_decoded_byte_offsets() {
    let error = parse(b"(;C[unterminated").unwrap_err();
    assert_eq!(error.kind, ErrorKind::UnterminatedValue);
    assert_eq!(error.position, 3);
    let error = parse(b"(;X)").unwrap_err();
    assert_eq!(error.kind, ErrorKind::ExpectedPropertyValue);
    assert_eq!(error.position, 3);
    let input = "(;C[中文]b[aa])";
    let parsed = parse(input.as_bytes()).unwrap();
    assert_eq!(parsed.warnings[0].position, "(;C[中文]".len());
    let input = "(;C[中 ] 文])";
    let parsed = parse(input.as_bytes()).unwrap();
    assert_eq!(parsed.warnings[0].position, "(;C[中 ".len());
}

#[test]
fn bracket_recovery_keeps_valid_boundaries_and_refuses_to_guess_across_nodes() {
    let parsed = parse(b"(;C[one]C[two][three];B[aa](;W[bb])(;W[cc]))").unwrap();
    assert_eq!(values(&parsed.games[0], "C"), vec!["one", "two", "three"]);
    assert!(
        !parsed
            .warnings
            .iter()
            .any(|warning| warning.kind == WarningKind::UnescapedClosingBracket)
    );
    assert!(parse(b"(;C[one] stray;B[aa])").is_err());
    assert!(parse(b"(;C[one] stray(;B[aa]))").is_err());
    assert!(parse(b"(;C[root](;B[aa]);W[bb])").is_err());
}

#[test]
fn parser_preserves_values_without_coordinate_number_or_rule_interpretation() {
    let parsed =
        parse(b"(;SZ[unknown]KM[not-a-number]RU[custom]AB[zz:??]B[invalid]LZ[opaque])").unwrap();
    assert_eq!(
        parsed.games[0].sequence[0].properties,
        vec![
            property("SZ", &["unknown"]),
            property("KM", &["not-a-number"]),
            property("RU", &["custom"]),
            property("AB", &["zz:??"]),
            property("B", &["invalid"]),
            property("LZ", &["opaque"]),
        ]
    );
    assert!(parsed.warnings.is_empty());
}

#[test]
fn deep_input_is_bounded_and_long_sequences_do_not_use_recursion() {
    let nested = format!("{}{}", "(;".repeat(257), ")".repeat(257));
    let error = parse(nested.as_bytes()).unwrap_err();
    assert!(error.position <= nested.len());
    let at_limit = format!("{}{}", "(;".repeat(256), ")".repeat(256));
    let parsed = parse(at_limit.as_bytes()).unwrap();
    assert_eq!(nodes(&parsed.games[0]).len(), 256);
    let linear = format!("({})", ";B[]".repeat(20_000));
    let parsed = parse(linear.as_bytes()).unwrap();
    assert_eq!(parsed.games[0].sequence.len(), 20_000);
    assert_eq!(
        parse(serialize(&parsed).as_bytes()).unwrap().games,
        utf8_roots(parsed.games)
    );
}

fn arbitrary_property() -> impl Strategy<Value = Property> {
    // Include syntax delimiters, newlines and non-ASCII text in unknown values.
    let text = prop::collection::vec(
        prop_oneof![4 => any::<char>().prop_filter("canonical newline", |ch| *ch != '\r'),
            3 => prop::sample::select(vec![']', '[', '\\', '(', ')', ';', '\n', '\t', '中', '𠀀'])],
        0..32,
    )
    .prop_map(|chars| chars.into_iter().collect::<String>());
    ("[A-Z]{1,4}", prop::collection::vec(text, 1..4))
        .prop_map(|(identifier, values)| Property { identifier, values })
}

fn arbitrary_sequence() -> impl Strategy<Value = Vec<Node>> {
    prop::collection::vec(
        prop::collection::vec(arbitrary_property(), 0..5)
            .prop_map(|properties| Node { properties }),
        1..5,
    )
}

fn arbitrary_tree() -> impl Strategy<Value = GameTree> {
    arbitrary_sequence()
        .prop_map(|sequence| GameTree {
            sequence,
            children: vec![],
        })
        .prop_recursive(4, 64, 3, |inner| {
            (arbitrary_sequence(), prop::collection::vec(inner, 1..4))
                .prop_map(|(sequence, children)| GameTree { sequence, children })
        })
}

fn fuzz_config() -> ProptestConfig {
    let cases = std::env::var("PROPTEST_CASES")
        .ok()
        .and_then(|value| value.parse().ok())
        .unwrap_or(1_000);
    ProptestConfig {
        cases,
        failure_persistence: None,
        ..ProptestConfig::default()
    }
}

proptest! {
    #![proptest_config(fuzz_config())]

    #[test]
    fn random_trees_round_trip(games in prop::collection::vec(arbitrary_tree(), 1..4)) {
        let collection = Collection { games, encoding: "UTF-8".into(), warnings: vec![] };
        let serialized = serialize(&collection);
        let reparsed = parse(serialized.as_bytes()).unwrap();
        prop_assert_eq!(&reparsed.games, &utf8_roots(collection.games));
        prop_assert_eq!(serialize(&reparsed), serialized);
    }

    #[test]
    fn random_bytes_never_panic(bytes in prop::collection::vec(any::<u8>(), 0..2048)) {
        let _ = parse(&bytes);
    }

    #[test]
    fn random_sgf_shaped_bytes_never_panic(
        bytes in prop::collection::vec(prop::sample::select(b"();[]\\ABCabc \r\n\t".to_vec()), 0..2048)
    ) {
        let _ = parse(&bytes);
    }
}
