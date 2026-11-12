//! KG-specific raw syntax mode. The normal decoder/parser and writer retain
//! upstream semantics; this mode is only for Java-normalized UTF-8 input.
use crate::{ErrorKind, SgfError, Warning, WarningKind};

const MAX_NESTING: usize = 256;
/// Explicit experiment resource boundary; callers can fall back to Java.
pub const MAX_RAW_EVENTS: usize = 1_000_000;
const MAX_RAW_EVENT_BYTES: usize = 64 * 1024 * 1024;

/// Ordered KG syntax with raw values borrowed from the normalized input.
///
/// This is deliberately separate from `Collection`: its values still contain
/// SGF escapes and physical newlines and must not be sent to `serialize`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawDocument<'a> {
    pub events: Vec<RawEvent<'a>>,
    pub warnings: Vec<Warning>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RawEvent<'a> {
    Open,
    Node,
    Property {
        identifier: String,
        raw_value: &'a str,
    },
    Close,
}

/// Parse input already decoded by `SgfFileReader` and normalized by
/// `SgfDocument.normalizeInput` (including setup ranges/structural newlines).
///
/// Every unescaped `]` ends a value. Stray `]` outside values is ignored and
/// unfinished property names are discarded at `(`, `;`, and `)`, as in KG.
/// ASCII lowercase letters are removed from identifiers; digits and the empty
/// result of an all-lowercase identifier are preserved. Other identifier
/// syntax, malformed tree structure, and missing delimiters return errors so
/// the caller can fall back to Java rather than receive synthesized events.
/// The experiment caps the event count at one million and the encoded event
/// bytes at 64 MiB, including repeated identifier bytes. This bounds both tiny
/// node amplification and repeated-value amplification of a long identifier.
pub fn parse_kg_raw(normalized: &str) -> Result<RawDocument<'_>, SgfError> {
    RawParser {
        text: normalized,
        cursor: 0,
        events: Vec::new(),
        warnings: Vec::new(),
        event_bytes: 0,
    }
    .parse()
}

struct Frame {
    has_node: bool,
    has_variations: bool,
}

struct RawParser<'a> {
    text: &'a str,
    cursor: usize,
    events: Vec<RawEvent<'a>>,
    warnings: Vec<Warning>,
    event_bytes: usize,
}

impl<'a> RawParser<'a> {
    fn parse(mut self) -> Result<RawDocument<'a>, SgfError> {
        let mut stack: Vec<Frame> = Vec::new();
        let mut games = 0;
        loop {
            self.skip_space();
            let Some(byte) = self.byte() else { break };
            match byte {
                b'(' => {
                    if stack.len() == MAX_NESTING {
                        return Err(self.error(ErrorKind::NestingLimit { limit: MAX_NESTING }));
                    }
                    if let Some(parent) = stack.last_mut() {
                        if !parent.has_node {
                            return Err(self.error(ErrorKind::ExpectedNode));
                        }
                        parent.has_variations = true;
                    }
                    stack.push(Frame {
                        has_node: false,
                        has_variations: false,
                    });
                    self.event(RawEvent::Open)?;
                }
                b';' => {
                    let Some(frame) = stack.last_mut() else {
                        return Err(self.error(ErrorKind::ExpectedGameTree));
                    };
                    if frame.has_variations {
                        return Err(self.error(ErrorKind::NodeAfterVariation));
                    }
                    frame.has_node = true;
                    self.event(RawEvent::Node)?;
                    self.cursor += 1;
                    self.node()?;
                    continue;
                }
                b')' => {
                    let Some(frame) = stack.pop() else {
                        return Err(self.error(ErrorKind::UnexpectedCharacter(')')));
                    };
                    if !frame.has_node {
                        return Err(self.error(ErrorKind::ExpectedNode));
                    }
                    self.event(RawEvent::Close)?;
                    if stack.is_empty() {
                        games += 1;
                    }
                }
                b']' if !stack.is_empty() => {}
                _ => {
                    return Err(self.error(if stack.is_empty() {
                        ErrorKind::ExpectedGameTree
                    } else {
                        ErrorKind::UnexpectedCharacter(self.character())
                    }));
                }
            }
            self.cursor += 1;
        }
        if !stack.is_empty() {
            return Err(self.error(ErrorKind::MissingClosingParenthesis));
        }
        if games == 0 {
            return Err(self.error(ErrorKind::ExpectedGameTree));
        }
        Ok(RawDocument {
            events: self.events,
            warnings: self.warnings,
        })
    }

    fn node(&mut self) -> Result<(), SgfError> {
        let mut pending = String::new();
        let mut identifier = String::new();
        let mut has_identifier = false;
        let mut position = self.cursor;
        while let Some(byte) = self.byte() {
            match byte {
                b'(' | b';' | b')' => return Ok(()),
                b']' => {
                    pending.clear();
                    self.cursor += 1;
                }
                b'[' => {
                    if !pending.is_empty() {
                        has_identifier = true;
                        identifier = pending
                            .chars()
                            .filter(|ch| !ch.is_ascii_lowercase())
                            .collect();
                        if pending != identifier {
                            self.warnings.push(Warning {
                                position,
                                kind: WarningKind::PropertyName {
                                    original: std::mem::take(&mut pending),
                                    normalized: identifier.clone(),
                                },
                            });
                        } else {
                            pending.clear();
                        }
                    }
                    if !has_identifier {
                        return Err(self.error(ErrorKind::ExpectedPropertyValue));
                    }
                    let raw_value = self.value()?;
                    self.event(RawEvent::Property {
                        identifier: identifier.clone(),
                        raw_value,
                    })?;
                }
                b' ' | b'\t' | b'\r' | b'\n' => self.cursor += 1,
                byte if byte.is_ascii_alphanumeric() => {
                    if pending.is_empty() {
                        position = self.cursor;
                    }
                    pending.push(char::from(byte));
                    self.cursor += 1;
                }
                _ => return Err(self.error(ErrorKind::UnexpectedCharacter(self.character()))),
            }
        }
        Ok(())
    }

    fn value(&mut self) -> Result<&'a str, SgfError> {
        let opening = self.cursor;
        self.cursor += 1;
        let start = self.cursor;
        let mut escaped = false;
        while let Some(byte) = self.byte() {
            if !escaped && byte == b']' {
                let value = &self.text[start..self.cursor];
                self.cursor += 1;
                return Ok(value);
            }
            if escaped {
                escaped = false;
            } else if byte == b'\\' {
                escaped = true;
            }
            self.cursor += self.character().len_utf8();
        }
        Err(SgfError {
            position: opening,
            kind: ErrorKind::UnterminatedValue,
        })
    }

    fn event(&mut self, event: RawEvent<'a>) -> Result<(), SgfError> {
        if self.events.len() == MAX_RAW_EVENTS {
            return Err(self.error(ErrorKind::RawEventLimit {
                limit: MAX_RAW_EVENTS,
            }));
        }
        let size = match &event {
            RawEvent::Property {
                identifier,
                raw_value,
            } => 9usize
                .checked_add(identifier.len())
                .and_then(|size| size.checked_add(raw_value.len())),
            _ => Some(1),
        };
        let Some(size) =
            size.filter(|size| *size <= MAX_RAW_EVENT_BYTES.saturating_sub(self.event_bytes))
        else {
            return Err(self.error(ErrorKind::RawSizeLimit {
                limit: MAX_RAW_EVENT_BYTES,
            }));
        };
        self.event_bytes += size;
        self.events.push(event);
        Ok(())
    }

    fn skip_space(&mut self) {
        while matches!(self.byte(), Some(b' ' | b'\t' | b'\r' | b'\n')) {
            self.cursor += 1;
        }
    }

    fn byte(&self) -> Option<u8> {
        self.text.as_bytes().get(self.cursor).copied()
    }
    fn character(&self) -> char {
        self.text[self.cursor..].chars().next().unwrap_or('\0')
    }
    fn error(&self, kind: ErrorKind) -> SgfError {
        SgfError {
            position: self.cursor,
            kind,
        }
    }
}

#[cfg(test)]
mod tests;
