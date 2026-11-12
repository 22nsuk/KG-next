//! Decode before parsing: legacy multibyte trail bytes can be SGF punctuation.

use encoding_rs::{DecoderResult, Encoding, GB18030, UTF_8, UTF_16BE, UTF_16LE};

use crate::{ErrorKind, SgfError, Warning, WarningKind};

pub(crate) struct Decoded {
    pub text: String,
    pub encoding: String,
    pub warnings: Vec<Warning>,
}

pub(crate) fn decode(bytes: &[u8]) -> Result<Decoded, SgfError> {
    if bytes.starts_with(&[0xff, 0xfe, 0, 0]) || bytes.starts_with(&[0, 0, 0xfe, 0xff]) {
        return Err(SgfError {
            position: 0,
            kind: ErrorKind::InvalidEncoding {
                encoding: "UTF-32 (unsupported)".to_owned(),
            },
        });
    }
    if let Some((encoding, bom_len)) = Encoding::for_bom(bytes) {
        let text = strict_decode(&bytes[bom_len..], encoding).map_err(|position| SgfError {
            position: position + bom_len,
            kind: ErrorKind::InvalidEncoding {
                encoding: encoding.name().to_owned(),
            },
        })?;
        return Ok(decoded(text, encoding, true));
    }
    if let Ok(text) = std::str::from_utf8(bytes) {
        return Ok(decoded(text.to_owned(), UTF_8, false));
    }

    // A raw byte scan cannot reliably distinguish ']' or '\\' from a Big5/GBK
    // trail byte. Treat ASCII labels only as candidates, then validate them in
    // the decoded root. Never let CA-looking comment text select the encoding.
    let mut tried = vec![UTF_8];
    let mut cursor = 0;
    while cursor < bytes.len() {
        if !bytes[cursor].is_ascii_alphabetic() {
            cursor += 1;
            continue;
        }
        let start = cursor;
        while bytes.get(cursor).is_some_and(u8::is_ascii_alphabetic) {
            cursor += 1;
        }
        if normalized_identifier(&bytes[start..cursor]) != "CA" {
            continue;
        }
        skip_space(bytes, &mut cursor);
        let Some(label) = read_value(bytes, &mut cursor, false) else {
            continue;
        };
        let Some(encoding) = Encoding::for_label(&label) else {
            continue;
        };
        if tried.contains(&encoding) || !encoding.is_ascii_compatible() {
            continue;
        }
        tried.push(encoding);
        if let Ok(text) = strict_decode(bytes, encoding)
            && root_declaration(&text).is_some_and(|declaration| {
                Encoding::for_label(declaration.value.as_bytes()) == Some(encoding)
            })
        {
            return Ok(decoded(text, encoding, false));
        }
    }

    strict_decode(bytes, GB18030)
        .map(|text| decoded(text, GB18030, false))
        .map_err(|position| SgfError {
            position,
            kind: ErrorKind::InvalidEncoding {
                encoding: "GB18030".to_owned(),
            },
        })
}

fn strict_decode(bytes: &[u8], encoding: &'static Encoding) -> Result<String, usize> {
    let mut decoder = encoding.new_decoder_without_bom_handling();
    let mut text = String::new();
    let mut offset = 0;
    loop {
        // A fixed growth step avoids multiplication overflow and reserves enough
        // room for every supported decoder to make progress.
        text.reserve(4096);
        let (result, read) =
            decoder.decode_to_string_without_replacement(&bytes[offset..], &mut text, true);
        offset += read;
        match result {
            DecoderResult::InputEmpty => return Ok(text),
            DecoderResult::OutputFull => {}
            DecoderResult::Malformed(length, after) => {
                return Err(offset.saturating_sub(usize::from(length) + usize::from(after)));
            }
        }
    }
}

fn decoded(text: String, encoding: &'static Encoding, bom: bool) -> Decoded {
    let name = if encoding == GB18030 {
        "GB18030".to_owned()
    } else if bom {
        format!("{}-BOM", encoding.name())
    } else {
        encoding.name().to_owned()
    };
    let mut warnings = Vec::new();
    if let Some(declaration) = root_declaration(&text) {
        let declared_encoding = Encoding::for_label(declaration.value.as_bytes());
        // The generic UTF-16 label leaves endianness to the BOM.
        let generic_utf16 = bom
            && declaration.value.trim().eq_ignore_ascii_case("UTF-16")
            && (encoding == UTF_16LE || encoding == UTF_16BE);
        let kind = match declared_encoding {
            None => Some(WarningKind::UnknownEncoding {
                declared: declaration.value,
            }),
            Some(declared) if declared != encoding && !generic_utf16 => {
                Some(WarningKind::EncodingMismatch {
                    declared: declaration.value,
                    detected: name.clone(),
                })
            }
            Some(_) => None,
        };
        if let Some(kind) = kind {
            warnings.push(Warning {
                position: declaration.position,
                kind,
            });
        }
    }
    Decoded {
        text,
        encoding: name,
        warnings,
    }
}

struct Declaration {
    value: String,
    position: usize,
}

/// Read only the first game's first node, after decoding to UTF-8.
fn root_declaration(text: &str) -> Option<Declaration> {
    let bytes = text.as_bytes();
    let mut cursor = 0;
    skip_space(bytes, &mut cursor);
    if bytes.get(cursor) != Some(&b'(') {
        return None;
    }
    cursor += 1;
    skip_space(bytes, &mut cursor);
    if bytes.get(cursor) != Some(&b';') {
        return None;
    }
    cursor += 1;
    loop {
        skip_space(bytes, &mut cursor);
        let start = cursor;
        while bytes.get(cursor).is_some_and(u8::is_ascii_alphabetic) {
            cursor += 1;
        }
        if start == cursor {
            return None;
        }
        let identifier = normalized_identifier(&bytes[start..cursor]);
        skip_space(bytes, &mut cursor);
        let value = read_value(bytes, &mut cursor, true)?;
        if identifier == "CA" {
            return String::from_utf8(value).ok().map(|value| Declaration {
                value,
                position: start,
            });
        }
        skip_space(bytes, &mut cursor);
        while bytes.get(cursor) == Some(&b'[') {
            read_value(bytes, &mut cursor, true)?;
            skip_space(bytes, &mut cursor);
        }
    }
}

fn normalized_identifier(bytes: &[u8]) -> String {
    let uppercase_only = bytes.iter().any(u8::is_ascii_uppercase);
    bytes
        .iter()
        .filter(|byte| !uppercase_only || byte.is_ascii_uppercase())
        .map(|byte| char::from(byte.to_ascii_uppercase()))
        .collect()
}

fn skip_space(bytes: &[u8], cursor: &mut usize) {
    while bytes.get(*cursor).is_some_and(u8::is_ascii_whitespace) {
        *cursor += 1;
    }
}

fn read_value(bytes: &[u8], cursor: &mut usize, recover: bool) -> Option<Vec<u8>> {
    if bytes.get(*cursor) != Some(&b'[') {
        return None;
    }
    *cursor += 1;
    let mut value = Vec::new();
    while let Some(&byte) = bytes.get(*cursor) {
        *cursor += 1;
        match byte {
            b']' => {
                if recover && crate::parser::literal_closing_bracket(bytes, *cursor - 1) {
                    value.push(byte);
                } else {
                    return Some(value);
                }
            }
            b'\\' => {
                let escaped = *bytes.get(*cursor)?;
                *cursor += 1;
                if escaped == b'\r' || escaped == b'\n' {
                    let other = if escaped == b'\r' { b'\n' } else { b'\r' };
                    if bytes.get(*cursor) == Some(&other) {
                        *cursor += 1;
                    }
                } else {
                    value.push(escaped);
                }
            }
            _ => value.push(byte),
        }
    }
    None
}

#[cfg(test)]
mod tests;
