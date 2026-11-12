use crate::{Collection, ErrorKind, GameTree, Node, Property, SgfError, Warning, WarningKind};
use std::collections::HashSet;

/// A safety limit on variation nesting, not on the number of moves or siblings.
const MAX_NESTING: usize = 256;

/// Decode and parse a collection. Recoverable deviations are returned as
/// warnings; invalid syntax and unsupported nesting return positioned errors.
pub fn parse(bytes: &[u8]) -> Result<Collection, SgfError> {
    let decoded = crate::encoding::decode(bytes)?;
    let mut parser = Parser {
        text: &decoded.text,
        cursor: 0,
        warnings: decoded.warnings,
    };
    let games = parser.collection()?;
    Ok(Collection {
        games,
        encoding: decoded.encoding,
        warnings: parser.warnings,
    })
}

struct Frame {
    tree: GameTree,
    has_variations: bool,
}

struct Parser<'a> {
    text: &'a str,
    cursor: usize,
    warnings: Vec<Warning>,
}

impl Parser<'_> {
    fn collection(&mut self) -> Result<Vec<GameTree>, SgfError> {
        let mut games = Vec::new();
        let mut stack: Vec<Frame> = Vec::new();
        loop {
            self.skip_space();
            let Some(byte) = self.byte() else { break };
            match byte {
                b'(' => {
                    if stack.len() == MAX_NESTING {
                        return Err(self.error(ErrorKind::NestingLimit { limit: MAX_NESTING }));
                    }
                    if let Some(parent) = stack.last_mut() {
                        if parent.tree.sequence.is_empty() {
                            return Err(self.error(ErrorKind::ExpectedNode));
                        }
                        parent.has_variations = true;
                    }
                    stack.push(Frame {
                        tree: GameTree {
                            sequence: Vec::new(),
                            children: Vec::new(),
                        },
                        has_variations: false,
                    });
                    self.cursor += 1;
                }
                b';' => {
                    let Some(frame) = stack.last_mut() else {
                        return Err(self.error(ErrorKind::ExpectedGameTree));
                    };
                    if frame.has_variations {
                        return Err(self.error(ErrorKind::NodeAfterVariation));
                    }
                    self.cursor += 1;
                    frame.tree.sequence.push(self.node()?);
                }
                b')' => {
                    let Some(frame) = stack.pop() else {
                        return Err(self.error(ErrorKind::UnexpectedCharacter(')')));
                    };
                    if frame.tree.sequence.is_empty() {
                        return Err(self.error(ErrorKind::ExpectedNode));
                    }
                    Self::finish_tree(frame.tree, &mut stack, &mut games);
                    self.cursor += 1;
                }
                _ => {
                    return Err(self.error(if stack.is_empty() {
                        ErrorKind::ExpectedGameTree
                    } else {
                        ErrorKind::UnexpectedCharacter(self.character())
                    }));
                }
            }
        }
        // Only missing tree delimiters at EOF are unambiguous to synthesize.
        while let Some(frame) = stack.pop() {
            if frame.tree.sequence.is_empty() {
                return Err(self.error(ErrorKind::ExpectedNode));
            }
            self.warnings.push(Warning {
                position: self.cursor,
                kind: WarningKind::MissingClosingParenthesis,
            });
            Self::finish_tree(frame.tree, &mut stack, &mut games);
        }
        if games.is_empty() {
            return Err(self.error(ErrorKind::ExpectedGameTree));
        }
        Ok(games)
    }

    fn finish_tree(tree: GameTree, stack: &mut [Frame], games: &mut Vec<GameTree>) {
        if let Some(parent) = stack.last_mut() {
            parent.tree.children.push(tree);
        } else {
            games.push(tree);
        }
    }

    fn node(&mut self) -> Result<Node, SgfError> {
        let mut node = Node::default();
        let mut identifiers = HashSet::new();
        loop {
            self.skip_space();
            if !self.byte().is_some_and(|b| b.is_ascii_alphabetic()) {
                break;
            }
            let position = self.cursor;
            while self.byte().is_some_and(|b| b.is_ascii_alphabetic()) {
                self.cursor += 1;
            }
            let original = &self.text[position..self.cursor];
            let identifier = normalize_identifier(original);
            if original != identifier {
                self.warnings.push(Warning {
                    position,
                    kind: WarningKind::PropertyName {
                        original: original.to_owned(),
                        normalized: identifier.clone(),
                    },
                });
            }
            self.skip_space();
            if self.byte() != Some(b'[') {
                return Err(self.error(ErrorKind::ExpectedPropertyValue));
            }
            let mut values = Vec::new();
            while self.byte() == Some(b'[') {
                values.push(self.value()?);
                self.skip_space();
            }
            if !identifiers.insert(identifier.clone()) {
                self.warnings.push(Warning {
                    position,
                    kind: WarningKind::DuplicateProperty {
                        identifier: identifier.clone(),
                    },
                });
            }
            node.properties.push(Property { identifier, values });
        }
        Ok(node)
    }

    fn value(&mut self) -> Result<String, SgfError> {
        let opening = self.cursor;
        self.cursor += 1;
        let mut value = String::new();
        while let Some(byte) = self.byte() {
            match byte {
                b'\\' => {
                    self.cursor += 1;
                    match self.byte() {
                        Some(b'\r' | b'\n') => self.skip_newline(),
                        Some(_) => {
                            let ch = self.character();
                            value.push(ch);
                            self.cursor += ch.len_utf8();
                        }
                        None => break,
                    }
                }
                b']' => {
                    if literal_closing_bracket(self.text.as_bytes(), self.cursor) {
                        self.warnings.push(Warning {
                            position: self.cursor,
                            kind: WarningKind::UnescapedClosingBracket,
                        });
                        value.push(']');
                        self.cursor += 1;
                    } else {
                        self.cursor += 1;
                        return Ok(value);
                    }
                }
                b'\r' | b'\n' => {
                    self.skip_newline();
                    value.push('\n');
                }
                _ => {
                    let ch = self.character();
                    value.push(ch);
                    self.cursor += ch.len_utf8();
                }
            }
        }
        Err(SgfError {
            position: opening,
            kind: ErrorKind::UnterminatedValue,
        })
    }

    fn skip_space(&mut self) {
        while self.byte().is_some_and(|b| b.is_ascii_whitespace()) {
            self.cursor += 1;
        }
    }

    fn skip_newline(&mut self) {
        let first = self.byte();
        self.cursor += 1;
        if matches!(
            (first, self.byte()),
            (Some(b'\r'), Some(b'\n')) | (Some(b'\n'), Some(b'\r'))
        ) {
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

pub(crate) fn normalize_identifier(original: &str) -> String {
    if original.bytes().all(|b| b.is_ascii_lowercase()) {
        original.to_ascii_uppercase()
    } else {
        original.chars().filter(char::is_ascii_uppercase).collect()
    }
}

/// A legal SGF boundary always wins. Treat an otherwise stray `]` as text
/// only when another unescaped closing bracket precedes any tree boundary.
/// This deliberately avoids guessing across nodes or variations.
pub(crate) fn literal_closing_bracket(bytes: &[u8], position: usize) -> bool {
    let rest = &bytes[position + 1..];
    let mut index = 0;
    while rest.get(index).is_some_and(u8::is_ascii_whitespace) {
        index += 1;
    }
    match rest.get(index) {
        None | Some(b';' | b'(' | b')' | b'[') => return false,
        Some(b) if b.is_ascii_alphabetic() => {
            let mut end = index;
            while rest.get(end).is_some_and(u8::is_ascii_alphabetic) {
                end += 1;
            }
            while rest.get(end).is_some_and(u8::is_ascii_whitespace) {
                end += 1;
            }
            if rest.get(end) == Some(&b'[') {
                return false;
            }
        }
        _ => {}
    }
    while let Some(byte) = rest.get(index) {
        match byte {
            b']' => return true,
            b';' | b'(' | b')' => return false,
            b'\\' => index += 1,
            _ => {}
        }
        index += 1;
    }
    false
}
